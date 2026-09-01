(ns finadvisory.operation
  "OperationActor -- one financial-advisory operation = one supervised
  actor run, expressed as a langgraph-clj StateGraph. This is the shape
  the 営み OS's standard adapter
  (`cloud-itonami.os.adapters.standard`) drives: `build` takes a store
  and returns a compiled graph, the OS injects `:request`/`:context`
  and resumes an interrupted run with `{:approval ..}`.

    :intake -> :advise -> :govern -> :decide -+-> :commit
                                              +-> :request-approval  (interrupt-before)
                                              +-> :hold

  ## Where the graph lives

  There is exactly ONE graph in this repo and it is here.
  `finadvisory.actor` is the older, named entry point (`build-graph` /
  `run-request!` / `approve!`); it delegates to this namespace rather
  than compiling a second graph. Keeping two graph definitions in one
  repo is the defect `cloud-itonami/ma`'s ADR 0001 recorded -- the two
  drift, and nothing in the output distinguishes which one ran.

  ## What is injected

  Everything the actor depends on is a swap, not a rewrite:
    - the Store    (MemStore today; kotobase is the next seam) - `store` arg
    - the Advisor  (mock | real LLM)                           - :advisor opt
    - the Phase    (0->3 rollout)                              - :phase in ctx

  ## Human-in-the-loop is a real approval, not a resume

  `interrupt-before #{:request-approval}` pauses the actor and hands the
  decision to a licensed human adviser. The approver resumes with
  `{:approval {:status :approved}}`; ANY other status (including a
  missing one) holds. `:approve-trade-execution` and
  `:approve-fund-transfer` always reach this node -- see
  `finadvisory.phase`."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [finadvisory.advisor :as advisor]
            [finadvisory.governor :as governor]
            [finadvisory.phase :as phase]
            [finadvisory.store :as store]))

(defn- commit-record
  "The SSoT record a committed proposal writes. Built from the proposal,
  not from the request -- the proposal is what the governor and the
  phase gate actually cleared."
  [request proposal]
  {:client-id  (:client-id request)
   :op         (:op proposal)
   :account-id (:account-id proposal)
   :payload    proposal})

(defn build
  "Compile an OperationActor graph bound to `store` (any
  `finadvisory.store/Store`).
  opts:
    :advisor      -- a `finadvisory.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  ([store] (build store {}))
  ([store {:keys [advisor checkpointer]
           :or   {advisor      (advisor/mock-advisor)
                  checkpointer (cp/mem-checkpointer)}}]
   (-> (g/state-graph
        {:channels
         {:request     {:default nil}
          :context     {:default nil}   ; injected actor-id/role/phase
          :proposal    {:default nil}
          :verdict     {:default nil}
          :disposition {:default nil}   ; :commit | :escalate | :hold
          :record      {:default nil}
          :approval    {:default nil}
          :audit       {:reducer into :default []}}})

       (g/add-node :intake (fn [s] s))

       ;; The contained intelligence node -- proposal only, never a write.
       (g/add-node :advise
                   (fn [{:keys [request]}]
                     (let [p (advisor/-advise advisor store request)]
                       {:proposal p
                        :audit [{:node :advise :request request :proposal p}]})))

       ;; The independent censor -- a separate system from the advisor.
       (g/add-node :govern
                   (fn [{:keys [request context proposal]}]
                     (let [v (governor/check request context proposal store)]
                       {:verdict v
                        :audit [{:node :govern :verdict v}]})))

       ;; Governor disposition first, then the rollout phase gate, which
       ;; can only add caution. The op gated is the PROPOSAL's op, not the
       ;; request's: the proposal is what would be committed, and an
       ;; advisor that proposes a different op than was asked for must not
       ;; be gated as if it had obeyed.
       (g/add-node :decide
                   (fn [{:keys [request context proposal verdict]}]
                     (let [base (phase/verdict->disposition verdict)
                           ph   (:phase context phase/default-phase)
                           op   (or (:op proposal) (:op request))
                           {:keys [disposition reason]} (phase/gate ph {:op op} base)]
                       {:disposition disposition
                        :audit [{:node :decide :op op :phase ph
                                 :disposition disposition :phase-reason reason}]})))

       ;; Approval handoff -- paused by interrupt-before. A human resumes
       ;; with :approval. Anything that is not an explicit :approved holds.
       (g/add-node :request-approval
                   (fn [{:keys [approval]}]
                     (if (= :approved (:status approval))
                       {:disposition :commit
                        :audit [{:node :request-approval :t :approval-granted
                                 :by (:by approval)}]}
                       {:disposition :hold
                        :audit [{:node :request-approval :t :approval-rejected
                                 :status (:status approval) :by (:by approval)}]})))

       ;; The ONLY node that writes the SSoT + audit ledger.
       (g/add-node :commit
                   (fn [{:keys [request proposal]}]
                     (let [record (commit-record request proposal)]
                       (store/commit-record! store record)
                       (store/append-ledger! store {:disposition :commit :record record})
                       {:record record
                        :audit [{:node :commit :record record}]})))

       ;; Hold -- the rejection goes to the ledger; no SSoT mutation.
       (g/add-node :hold
                   (fn [{:keys [verdict]}]
                     (store/append-ledger! store {:disposition :hold :verdict verdict})
                     {:audit [{:node :hold :verdict verdict}]}))

       (g/set-entry-point :intake)
       (g/add-edge :intake :advise)
       (g/add-edge :advise :govern)
       (g/add-edge :govern :decide)

       (g/add-conditional-edges
        :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

       (g/add-conditional-edges
        :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

       (g/set-finish-point :commit)
       (g/set-finish-point :hold)

       (g/compile-graph {:checkpointer     checkpointer
                         :interrupt-before #{:request-approval}}))))
