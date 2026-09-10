(ns finadvisory.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [finadvisory.store :as store]
            [finadvisory.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Advisory"})
    (store/register-account! st {:account-id "A-1" :client-id "client-1"
                                 :name "account-042"
                                 :max-allocation-pct 25})
    st))

(defn- recommendation-op [pct disclosed?]
  {:op :approve-recommendation :effect :propose :account-id "A-1"
   :allocation-pct pct :risk-disclosure-attached? disclosed?
   :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-within-ceiling-and-disclosed
  (let [st (fresh-store)
        v (governor/check req {} (recommendation-op 15 true) st)]
    (is (:ok? v))))

(deftest ok-at-exact-ceiling-boundary
  (testing "the suitability ceiling is inclusive"
    (let [st (fresh-store)
          v (governor/check req {} (recommendation-op 25 true) st)]
      (is (:ok? v)))))

(deftest hard-on-allocation-exceeds-suitability-ceiling
  (testing "recommending beyond the client's registered risk tolerance is unsuitable advice, not aggressive strategy"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (recommendation-op 60 true) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :allocation-exceeds-suitability-ceiling (:rule %)) (:violations v))))))

(deftest hard-on-missing-risk-disclosure
  (testing "a recommendation without an attached risk disclosure is undisclosed advice, not efficient service"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (recommendation-op 15 false) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :missing-risk-disclosure (:rule %)) (:violations v))))))

(deftest hard-on-unknown-account
  (let [st (fresh-store)
        v (governor/check req {} (assoc (recommendation-op 15 true) :account-id "A-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-account (:rule %)) (:violations v)))))

(deftest hard-on-foreign-account
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (recommendation-op 15 true) st)]
      (is (:hard? v))
      (is (some #(= :account-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (recommendation-op 15 true) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (recommendation-op 15 true) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest always-escalates-trade-execution-even-at-high-confidence
  (testing "no trade execution without the governor gate"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-trade-execution :effect :propose
                                    :account-id "A-1" :confidence 0.99 :stake :low} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest always-escalates-fund-transfer-even-at-high-confidence
  (testing "no fund transfer without the governor gate"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-fund-transfer :effect :propose
                                    :account-id "A-1" :confidence 0.99 :stake :low} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (recommendation-op 15 true) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
