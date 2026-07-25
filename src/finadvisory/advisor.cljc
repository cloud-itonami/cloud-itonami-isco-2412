(ns finadvisory.advisor
  "Advisory Advisor — the advisor named in this repository's README,
  proposing a financial-advisory operation (issue a recommendation,
  approve trade execution, approve a fund transfer) from a client
  intake, risk profile and investment mandate. Swappable mock/llm; the
  advisor ONLY proposes — `finadvisory.governor` checks the
  suitability ceiling and risk-disclosure attachment independently and
  always escalates trade-execution and fund-transfer decisions.
  Modeled on cloud-itonami-isco-4311's advisor.

  A proposal: {:op :approve-recommendation|:approve-trade-execution|:approve-fund-transfer
               :effect :propose :account-id str :allocation-pct number
               :risk-disclosure-attached? boolean :stake kw
               :confidence n :rationale str}"
  ;; clojure.edn, not clojure.core/read-string: this parses untrusted
  ;; advisor output, and the core reader executes #=(...) at read time.
  (:require [clojure.edn :as edn]))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake account-id allocation-pct risk-disclosure-attached?] :as request}]
  {:op op
   :effect :propose
   :account-id account-id
   :allocation-pct allocation-pct
   :risk-disclosure-attached? (boolean risk-disclosure-attached?)
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a financial-advisory advisor. Given a request, propose an
   :op, the :account-id, :allocation-pct and whether a risk disclosure
   is attached, an honest :confidence and a :stake. Never propose an
   allocation beyond the account's registered suitability ceiling, or
   a recommendation without a risk disclosure attached — the governor
   checks both against the registered account record. Trade execution
   and fund transfer always require human sign-off regardless of
   confidence.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
