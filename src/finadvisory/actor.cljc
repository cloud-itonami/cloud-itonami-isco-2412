(ns finadvisory.actor
  "FinancialAdvisoryActor -- the named entry point for the ISCO-08 2412
  community financial-advisory actor (ADR-2607011000 / CLAUDE.md Actors
  section). One call = one financial-advisory operation request
  (intake -> advise -> govern -> decide -> commit/hold, with a
  human-approval interrupt for escalated proposals).

  ## This namespace no longer compiles a graph

  The graph moved to `finadvisory.operation`, which is the shape the
  営み OS's standard adapter drives (`operation/build` + `phase/read-ops`
  + `phase/write-ops` + `store/seed-db` + a governor). This namespace
  stays because it is the name this repo's tests and
  `finadvisory.render-html` call, and because the run/approve/reject
  helpers belong somewhere -- but it delegates rather than defining a
  second graph. Two graph definitions in one repo drift, and nothing in
  the output says which one ran.

  The unconditional invariant is unchanged: the advisor can never
  directly commit a record the governor refuses -- every
  `commit-record!` call is behind `:decide`, and the rollout phase gate
  (`finadvisory.phase`) can only add caution on top."
  (:require [langgraph.graph :as g]
            [finadvisory.operation :as operation]))

(defn build-graph
  "Build a compiled FinancialAdvisoryActor graph. `store` implements
  `finadvisory.store/Store`. `advisor` implements
  `finadvisory.advisor/Advisor` (defaults to `mock-advisor`).
  `checkpointer` defaults to an in-memory one.

  Keyword-map arity kept for existing callers; `finadvisory.operation/
  build` is the positional form the OS adapter uses."
  [{:keys [store advisor checkpointer]}]
  (operation/build store (cond-> {}
                           advisor      (assoc :advisor advisor)
                           checkpointer (assoc :checkpointer checkpointer))))

(defn run-request!
  "Run one operation request to completion or interrupt. `thread-id`
  scopes checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume with an explicit approval. Resuming is no
  longer implicitly an approval: the `:request-approval` node commits
  only on `{:status :approved}`, so the OS adapter's `-resume`, which
  passes the human's real decision through, can also reject."
  ([graph thread-id] (approve! graph thread-id nil))
  ([graph thread-id by]
   (g/run* graph {:approval (cond-> {:status :approved} by (assoc :by by))}
           {:thread-id thread-id :resume? true})))

(defn reject!
  "Human-in-the-loop resume with a rejection. The interrupted run
  proceeds to `:hold`; the SSoT is not written and the refusal is
  appended to the audit ledger."
  ([graph thread-id] (reject! graph thread-id nil))
  ([graph thread-id by]
   (g/run* graph {:approval (cond-> {:status :rejected} by (assoc :by by))}
           {:thread-id thread-id :resume? true})))
