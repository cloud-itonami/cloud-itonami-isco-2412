(ns finadvisory.phase
  "Phase 0->3 staged rollout for the ISCO-08 2412 independent financial
  & investment advisory actor -- the advisory analog of
  `cloud-itonami-isic-6612`'s `brokerage.phase`.

    Phase 0  read-only         -- no writes at all, still governor-gated.
    Phase 1  assisted-advice   -- issuing a recommendation is allowed,
                                  but every write needs human approval.
    Phase 2  assisted-actuation -- adds the two actuation-approval ops
                                  (trade execution, fund transfer) to the
                                  writable set, still human approval.
    Phase 3  supervised-auto   -- governor-clean `:approve-recommendation`
                                  (which moves no capital) may auto-commit.

  ## The invariant this table exists to hold

  `:approve-trade-execution` and `:approve-fund-transfer` are members of
  `write-ops` (so they are governor-gated like any write) but are NEVER
  members of any phase's `:auto` set -- including phase 3. That is a
  permanent structural fact, not a rollout milestone still to come:
  those two ops are the only real-world acts in this domain that move a
  client's capital, and moving a client's capital is always a licensed
  human adviser's call.

  `finadvisory.governor`'s `always-escalate-ops` enforces the same
  invariant independently, from the compliance side rather than the
  rollout side. Two separate layers agree on it, so neither one being
  edited alone can quietly enable auto-actuation --
  `finadvisory.phase-test` pins both.

  ## This domain has no read op

  `read-ops` is empty and the gate below knows only about writes, so an
  op that is not a declared write holds with `:phase-disabled`. That is
  the fail-closed direction: an unrecognised op cannot slip through.
  `finadvisory.phase-test` pins `read-ops` empty precisely so that
  adding one is forced to come with a matching change here, rather than
  silently holding every read.")

(def read-ops
  "Ops that only read. Empty in this domain -- every operation this
  actor performs writes a record or requests an actuation approval."
  #{})

(def write-ops
  "Ops that write the SSoT (or request an actuation). All are
  governor-gated; see the namespace docstring for which may auto-commit."
  #{:approve-recommendation
    :approve-trade-execution
    :approve-fund-transfer})

(def phases
  "phase -> {:label .. :writes <ops allowed to write at all>
             :auto <ops allowed to auto-commit when governor-clean>}.

  NOTE the invariant: neither `:approve-trade-execution` nor
  `:approve-fund-transfer` appears in any `:auto` set. Do not add them."
  {0 {:label "read-only"          :writes #{}                          :auto #{}}
   1 {:label "assisted-advice"    :writes #{:approve-recommendation}   :auto #{}}
   2 {:label "assisted-actuation" :writes write-ops                    :auto #{}}
   3 {:label "supervised-auto"    :writes write-ops
      :auto #{:approve-recommendation}}})

(def default-phase 3)

(defn- phase-of
  "The phase table row for `phase`, falling back to `default-phase` for
  an unknown phase number. The fallback is the *most* permissive row, so
  it is deliberately not used to carry safety -- safety comes from the
  gate below refusing anything outside that row's `:writes`."
  [phase]
  (get phases phase (get phases default-phase)))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition :commit|:escalate|:hold, :reason kw|nil}.

  The phase gate can only add caution, never remove it:

    - a governor HOLD stays HOLD (compliance wins; no phase overrides it).
    - an op not writable in this phase -> HOLD (:phase-disabled). An
      unrecognised op lands here too, which is the fail-closed direction.
    - a governor ESCALATE stays ESCALATE (a human still signs off).
    - a writable, auto-eligible op with a clean governor -> COMMIT.
    - a writable but not auto-eligible op -> ESCALATE (:phase-approval),
      even though the governor was clean. This is the branch that keeps
      `:approve-trade-execution`/`:approve-fund-transfer` in front of a
      human at every phase."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (phase-of phase)]
    (cond
      (= :hold governor-disposition)     {:disposition :hold     :reason nil}
      (not (contains? writes op))        {:disposition :hold     :reason :phase-disabled}
      (= :escalate governor-disposition) {:disposition :escalate :reason nil}
      (contains? auto op)                {:disposition :commit   :reason nil}
      :else                              {:disposition :escalate :reason :phase-approval})))

(defn verdict->disposition
  "Map a `finadvisory.governor/check` verdict to the base disposition the
  phase gate then adjusts."
  [verdict]
  (cond (:hard? verdict)     :hold
        (:escalate? verdict) :escalate
        :else                :commit))
