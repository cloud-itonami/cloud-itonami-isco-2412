(ns finadvisory.governor
  "FinancialAdvisoryGovernor — the independent safety/traceability
  layer named in this repository's README/business-model.md, gating
  every recommendation an advisor may propose. The governor never
  dispatches hardware itself, never executes a trade and never
  transfers funds. Modeled on cloud-itonami-isco-4311's
  bookkeeping.governor. Task twist: a proposed recommendation's
  allocation percentage is an arithmetic ceiling against the
  account's registered suitability ceiling, and a recommendation
  cannot be disclosed to the client until a risk disclosure is
  attached — undisclosed advice is not efficient service.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance    — the organization/individual must be
                              registered.
    2. no-actuation         — proposal :effect must be :propose (the
                              governor never dispatches hardware, never
                              executes a trade, never transfers funds;
                              it only gates what the advisor may
                              recommend).
    3. account basis        — a recommendation proposal must cite a
                              REGISTERED advisory account belonging to
                              this client.
    4. suitability ceiling  — the proposed allocation percentage must
                              not exceed the account's registered
                              `:max-allocation-pct` (recommending
                              beyond the client's registered risk
                              tolerance is unsuitable advice, not
                              aggressive strategy).
    5. risk-disclosure attached — a recommendation must have
                              `:risk-disclosure-attached?` true before
                              it can be committed (a recommendation
                              without an attached risk disclosure is
                              undisclosed advice, not efficient
                              service).
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off per
  business-model.md's Trust Controls — these are :high/
  :safety-critical regardless of confidence):
    6. :op :approve-trade-execution (no trade execution without the
                              governor gate).
    7. :op :approve-fund-transfer (no fund transfer without the
                              governor gate).
    8. low confidence (< `confidence-floor`)."
  (:require [finadvisory.store :as store]))

(def confidence-floor 0.6)

(def ^:private always-escalate-ops #{:approve-trade-execution
                                     :approve-fund-transfer})

(defn- hard-violations [{:keys [request proposal]} client-record acct]
  (let [{:keys [op allocation-pct risk-disclosure-attached?]} proposal
        recommendation? (= :approve-recommendation op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（governor は取引執行/資金移動を直接実行しない）"})

      (and recommendation? (nil? acct))
      (conj {:rule :unknown-account :detail "未登録 account への推奨は不可"})

      (and recommendation? acct (not= (:client-id acct) (:client-id request)))
      (conj {:rule :account-wrong-client :detail "account が別 client のもの"})

      (and recommendation? acct (number? allocation-pct)
           (> allocation-pct (:max-allocation-pct acct)))
      (conj {:rule :allocation-exceeds-suitability-ceiling
             :detail (str "配分比率 " allocation-pct "% > 登録済み適合性上限 "
                          (:max-allocation-pct acct)
                          "%（登録済みリスク許容度を超える推奨は不適合助言であって積極戦略ではない）")})

      (and recommendation? acct (not risk-disclosure-attached?))
      (conj {:rule :missing-risk-disclosure
             :detail "リスク開示が添付されていない推奨は未開示助言であって効率的サービスではない"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `finadvisory.store/Store`. Pure — never mutates
  the store, never executes a trade, never transfers funds."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        acct (some->> (:account-id proposal) (store/account store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record acct)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        always-risky? (contains? always-escalate-ops (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not always-risky?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky?))}))
