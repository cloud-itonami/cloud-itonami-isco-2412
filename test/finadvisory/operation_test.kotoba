(ns finadvisory.operation-test
  "The standard-form entry point, driven the way the 営み OS's standard
  adapter drives it: `operation/build` on a `store/seed-db`, `:context`
  carrying the phase, and an interrupted run resumed with an explicit
  `{:approval ..}` decision.

  `finadvisory.actor-test` covers the same graph through the older named
  entry point; these tests exist because the OS never calls that one."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [finadvisory.operation :as operation]
            [finadvisory.phase :as phase]
            [finadvisory.store :as store]))

(defn- run [graph request context tid]
  (g/run* graph {:request request :context context} {:thread-id tid}))

(defn- resume [graph approval tid]
  (g/run* graph {:approval approval} {:thread-id tid :resume? true}))

(def ^:private recommendation
  {:client-id "client-1" :op :approve-recommendation :stake :low
   :account-id "A-1" :allocation-pct 15 :risk-disclosure-attached? true})

;; ------------------------------------------------------------- seed-db

(deftest seed-db-is-deterministic-and-registers-the-demo-corpus
  (let [a (store/seed-db) b (store/seed-db)]
    (is (= (store/client a "client-1") (store/client b "client-1")))
    (is (some? (store/account a "A-1")))
    (is (= 10 (:max-allocation-pct (store/account a "A-2"))))
    (testing "and starts with nothing committed"
      (is (empty? (store/records-of a "client-1")))
      (is (empty? (store/ledger a))))))

;; ------------------------------------------------------- the happy path

(deftest commits-a-clean-recommendation-at-the-default-phase
  (let [st (store/seed-db)
        r  (run (operation/build st) recommendation {} "op-1")]
    (is (= :done (:status r)))
    (is (= :commit (get-in r [:state :disposition])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-a-recommendation-over-the-registered-ceiling
  (testing "A-2's ceiling is 10%, so 15% is unsuitable advice"
    (let [st (store/seed-db)
          r  (run (operation/build st)
                  (assoc recommendation :client-id "client-2" :account-id "A-2")
                  {} "op-2")]
      (is (= :hold (get-in r [:state :disposition])))
      (is (empty? (store/records-of st "client-2"))))))

;; ------------------------------------------- the approval is a decision

(deftest an-explicit-approval-commits
  (let [st (store/seed-db)
        gr (operation/build st)
        r1 (run gr (assoc recommendation :op :approve-trade-execution) {} "op-3")]
    (is (= :interrupted (:status r1)))
    (is (empty? (store/records-of st "client-1")))
    (let [r2 (resume gr {:status :approved :by "adviser-7"} "op-3")]
      (is (= :done (:status r2)))
      (is (= 1 (count (store/records-of st "client-1")))))))

(deftest a-rejection-holds-and-writes-nothing
  (testing "resuming is not itself an approval -- this is the branch the OS
           adapter's -resume reaches when a human declines"
    (let [st (store/seed-db)
          gr (operation/build st)
          r1 (run gr (assoc recommendation :op :approve-fund-transfer) {} "op-4")]
      (is (= :interrupted (:status r1)))
      (let [r2 (resume gr {:status :rejected :by "adviser-7"} "op-4")]
        (is (= :done (:status r2)))
        (is (= :hold (get-in r2 [:state :disposition])))
        (is (empty? (store/records-of st "client-1")))
        (is (some #(= :approval-rejected (:t %)) (get-in r2 [:state :audit])))))))

(deftest a-missing-approval-holds
  (testing "an empty resume must not be read as consent"
    (let [st (store/seed-db)
          gr (operation/build st)
          _  (run gr (assoc recommendation :op :approve-trade-execution) {} "op-5")
          r2 (resume gr {} "op-5")]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/records-of st "client-1"))))))

;; ------------------------------------------- the OS's phase reaches the gate

(deftest the-injected-phase-changes-the-outcome
  (testing "phase 1 cannot write an actuation at all -- no interrupt, straight to hold"
    (let [st (store/seed-db)
          r  (run (operation/build st)
                  (assoc recommendation :op :approve-trade-execution)
                  {:actor-id "os" :phase 1} "op-6")]
      (is (= :done (:status r)) "phase 1 should refuse before the approval interrupt")
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #(= :phase-disabled (:phase-reason %)) (get-in r [:state :audit])))
      (is (empty? (store/records-of st "client-1")))))
  (testing "and phase 0 refuses even a clean recommendation the default phase commits"
    (let [st (store/seed-db)
          r  (run (operation/build st) recommendation {:phase 0} "op-7")]
      (is (= :hold (get-in r [:state :disposition])))
      (is (empty? (store/records-of st "client-1")))))
  (testing "while the default phase, injected explicitly, commits it"
    (let [st (store/seed-db)
          r  (run (operation/build st) recommendation
                  {:phase phase/default-phase} "op-8")]
      (is (= :commit (get-in r [:state :disposition])))
      (is (= 1 (count (store/records-of st "client-1")))))))
