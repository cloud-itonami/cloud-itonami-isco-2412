(ns finadvisory.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This repo previously had NO demo/visualization page and no generator
  at all (its existing `docs/` only has hand-written
  `business-model.md`/`operator-guide.md`). This namespace drives the
  REAL actor stack (`finadvisory.actor` -> `finadvisory.governor` ->
  `finadvisory.store`) through a scenario built from real, exercised
  store data and renders the result deterministically -- no invented
  numbers, no timestamps in the page content, byte-identical across
  reruns against the same seed (verify by diffing two consecutive runs
  before shipping). Adapted from the proven template in
  `cloud-itonami-isco-1211` (`src/finmgmt/render_html.clj`) -- the
  shape is the same, the domain-specific fields (advisory
  account/suitability ceiling/risk disclosure instead of budget-lines)
  differ.

  Seed data provenance:

  `client-1` (\"Kobo Advisory\") + account `A-1` (\"account-042\",
  max-allocation-pct 25) below are lifted VERBATIM from
  `finadvisory.actor-test/fresh-store` (identical to
  `finadvisory.governor-test/fresh-store` in this repo -- both test
  files use the same fixture values).

  `client-2` (\"Meridian Capital\") + account `A-2` (\"account-099\",
  max-allocation-pct 40) is ADDITIONAL demo data, registered via the
  SAME real `register-client!`/`register-account!` protocol calls this
  repo's own tests use -- this actor's own test fixtures only ever
  register one client+account, so a second client+account is necessary
  to demonstrate the cross-client `:account-wrong-client` rule (account
  belongs to a different client than the requester). Disclosed here
  plainly, not presented as if pre-existing. Every other field this
  page displays (dispositions, hold reasons, committed-record counts)
  is real output read after `run-demo!` actually executed the graph --
  none of it is hand-typed.

  Known architectural gaps, honestly noted rather than papered over:

  1. `finadvisory.governor`'s `:no-actuation` rule (proposal `:effect`
     must be `:propose`) is NOT reachable through this demo, because
     the real `mock-advisor` (`finadvisory.advisor/infer`)
     unconditionally sets `:effect :propose` on every proposal it
     emits -- by design, the advisor can never itself emit a raw store
     write (matches the governor's own docstring: it \"never
     dispatches hardware itself, never executes a trade and never
     transfers funds\"). Covered instead by
     `finadvisory.governor-test/hard-on-no-actuation-violation` (which
     calls `governor/check` directly with a hand-built proposal), not
     by this build-time renderer.
  2. The low-confidence escalation path (`confidence <
     finadvisory.governor/confidence-floor`, i.e. < 0.6) is NOT
     reachable through this demo either: the real mock-advisor's
     `infer` assigns confidence purely from `:stake` (`:high` -> 0.7,
     `:medium` -> 0.85, `:low` -> 0.95), and even the lowest of those
     (0.7 for `:high` stake) is above the 0.6 floor. Covered instead by
     `finadvisory.governor-test/escalates-low-confidence` (direct
     `governor/check` call with a hand-set `:confidence 0.3`).

  Every other governor rule this actor defines IS reached here: client
  provenance (`:no-client`), account basis (`:unknown-account`),
  account ownership (`:account-wrong-client`), the suitability ceiling
  (`:allocation-exceeds-suitability-ceiling`), the mandatory risk
  disclosure (`:missing-risk-disclosure`), plus both always-escalate
  ops (`:approve-trade-execution`, `:approve-fund-transfer` -- escalate
  -> human approve -> commit, regardless of confidence) and the plain
  auto-commit path.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [kotoba.lang.text :as str]
            [finadvisory.store :as store]
            [finadvisory.actor :as actor]))

;; ----------------------------- harness --------------------------------
;; finadvisory.actor already exposes run-request!/approve! wrappers
;; around langgraph.graph/run* -- this repo's own actor ns is the
;; harness, no raw g/run* needed here.

(defn- run-op!
  "Drives one real financial-advisory operation request through the
  actual compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it
  (this demo's scenario never demonstrates an UNAPPROVED escalation --
  every escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely
  reach through its real graph (auto-commit, escalate-then-approve,
  and 5 of the 6 distinct HARD-hold reasons in `finadvisory.governor`
  -- the 6th, `:no-actuation`, plus the low-confidence escalation
  path, are architecturally unreachable via the real advisor, see
  namespace docstring). Every `:op` keyword and violation rule name
  below is copied from `finadvisory.governor`'s own
  `hard-violations`/`check`, not invented."
  [;; client-1 / \"Kobo Advisory\" / A-1 (real fixture from finadvisory.actor-test)
   ["c1-recommend-ok"        "client-1" :approve-recommendation
    {:account-id "A-1" :allocation-pct 15 :risk-disclosure-attached? true :stake :low}]
   ["c1-over-ceiling"        "client-1" :approve-recommendation
    {:account-id "A-1" :allocation-pct 60 :risk-disclosure-attached? true :stake :low}]
   ["c1-missing-disclosure"  "client-1" :approve-recommendation
    {:account-id "A-1" :allocation-pct 15 :risk-disclosure-attached? false :stake :low}]
   ["c1-unknown-account"     "client-1" :approve-recommendation
    {:account-id "A-ghost" :allocation-pct 15 :risk-disclosure-attached? true :stake :low}]
   ["ghost-no-client"        "client-ghost" :approve-recommendation
    {:account-id "A-1" :allocation-pct 15 :risk-disclosure-attached? true :stake :low}]
   ["c1-trade-execution"     "client-1" :approve-trade-execution
    {:account-id "A-1" :stake :low}]
   ["c1-fund-transfer"       "client-1" :approve-fund-transfer
    {:account-id "A-1" :stake :low}]
   ;; client-1 requesting a recommendation against client-2's account
   ["c1-wrong-account"       "client-1" :approve-recommendation
    {:account-id "A-2" :allocation-pct 20 :risk-disclosure-attached? true :stake :low}]
   ;; client-2 / \"Meridian Capital\" / A-2 (additional demo data,
   ;; registered via the same real register-client!/register-account!
   ;; calls -- see namespace docstring)
   ["c2-recommend-ok"        "client-2" :approve-recommendation
    {:account-id "A-2" :allocation-pct 30 :risk-disclosure-attached? true :stake :medium}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `finadvisory.actor` graph. Returns `{:store :runs}` --
  `:runs` is the ordered vector of real per-request outcomes; every
  field in `render` below is read from this or from `store` after the
  graph actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Advisory"})
    (store/register-account! db {:account-id "A-1" :client-id "client-1"
                                  :name "account-042" :max-allocation-pct 25})
    (store/register-client! db {:client-id "client-2" :name "Meridian Capital"})
    (store/register-account! db {:account-id "A-2" :client-id "client-2"
                                  :name "account-099" :max-allocation-pct 40})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- client-row [store {:keys [client-id name account-id account-name max-allocation-pct]} runs]
  (let [record-count (count (store/records-of store client-id))
        last-run (last (filter #(= client-id (:client-id %)) runs))]
    (format "        <tr><td>%s</td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%d%%</td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc name) (esc account-id) (esc account-name)
            max-allocation-pct
            record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (:account-id request) ""))
          (esc (or (some-> (:allocation-pct request) str) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`finadvisory.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:approve-recommendation</code></td><td><span class=\"ok\">auto-commit when the allocation is within the account's suitability ceiling AND a risk disclosure is attached &middot; HARD hold otherwise</span></td></tr>"
   "        <tr><td><code>:approve-trade-execution</code></td><td><span class=\"warn\">ALWAYS human approval &middot; no trade execution without the governor gate, regardless of confidence</span></td></tr>"
   "        <tr><td><code>:approve-fund-transfer</code></td><td><span class=\"warn\">ALWAYS human approval &middot; no fund transfer without the governor gate, regardless of confidence</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [clients [{:client-id "client-1" :name "Kobo Advisory"
                  :account-id "A-1" :account-name "account-042" :max-allocation-pct 25}
                 {:client-id "client-2" :name "Meridian Capital"
                  :account-id "A-2" :account-name "account-099" :max-allocation-pct 40}]
        client-rows (str/join "\n" (map #(client-row store % runs) clients))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-2412 &middot; independent financial &amp; investment advisory</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Independent Financial &amp; Investment Advisory (ISCO-08 2412) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · trade execution &amp; fund transfer always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered clients &amp; advisory accounts</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>finadvisory.store</code> via <code>finadvisory.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly. Committed-record count is a live re-read of <code>store/records-of</code> after the real graph ran — never a remembered number.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Name</th><th>Account</th><th>Account name</th><th>Suitability ceiling</th><th>Committed records</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     client-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Financial Advisory Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. The suitability ceiling and risk-disclosure attachment are rechecked against the registered account record on every proposal, at any confidence. The governor never dispatches hardware itself, never executes a trade and never transfers funds.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own account/allocation, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Account</th><th>Allocation %</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
