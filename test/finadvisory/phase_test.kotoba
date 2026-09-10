(ns finadvisory.phase-test
  "The rollout phase gate, and the structural invariants of the phase
  table itself. These assert on `finadvisory.phase` directly rather than
  through a graph run, so a change to the table is caught even if no
  operation happens to exercise that row."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [finadvisory.phase :as phase]))

;; ---------------------------------------------------------------- table

(deftest read-ops-is-empty
  (testing "the gate knows only about writes; a read op would hold"
    (is (= #{} phase/read-ops)
        (str "phase/gate has no read-op branch. If this domain grows a read "
             "op, add it to the gate at the same time -- otherwise every "
             "read holds with :phase-disabled."))))

(deftest read-and-write-ops-are-disjoint
  (is (empty? (set/intersection phase/read-ops phase/write-ops))))

(deftest actuation-ops-are-never-auto-eligible-at-any-phase
  (testing "the permanent invariant: moving a client's capital is always a human call"
    (doseq [[n {:keys [auto label]}] phase/phases]
      (is (not (contains? auto :approve-trade-execution))
          (str "phase " n " (" label ") made :approve-trade-execution auto-eligible"))
      (is (not (contains? auto :approve-fund-transfer))
          (str "phase " n " (" label ") made :approve-fund-transfer auto-eligible")))))

(deftest every-phase-is-a-subset-of-the-declared-op-set
  (doseq [[n {:keys [writes auto]}] phase/phases]
    (is (every? phase/write-ops writes) (str "phase " n " writes an undeclared op"))
    (is (every? writes auto) (str "phase " n " auto-commits an op it cannot write"))))

(deftest default-phase-is-a-real-phase
  (is (contains? phase/phases phase/default-phase)))

;; ----------------------------------------------------------------- gate

(deftest a-governor-hold-survives-every-phase
  (testing "compliance wins; no phase overrides a HARD violation"
    (doseq [n (keys phase/phases)]
      (is (= :hold (:disposition (phase/gate n {:op :approve-recommendation} :hold)))))))

(deftest phase-0-writes-nothing
  (let [r (phase/gate 0 {:op :approve-recommendation} :commit)]
    (is (= :hold (:disposition r)))
    (is (= :phase-disabled (:reason r)))))

(deftest phase-1-allows-a-recommendation-but-only-with-approval
  (let [r (phase/gate 1 {:op :approve-recommendation} :commit)]
    (is (= :escalate (:disposition r)))
    (is (= :phase-approval (:reason r))))
  (testing "and still refuses actuation outright"
    (is (= :phase-disabled
           (:reason (phase/gate 1 {:op :approve-trade-execution} :commit))))))

(deftest phase-3-auto-commits-a-clean-recommendation
  (let [r (phase/gate 3 {:op :approve-recommendation} :commit)]
    (is (= :commit (:disposition r)))
    (is (nil? (:reason r)))))

(deftest phase-3-still-escalates-actuation
  (testing "the same invariant as the table test, but through the gate"
    (doseq [op [:approve-trade-execution :approve-fund-transfer]]
      (let [r (phase/gate 3 {:op op} :commit)]
        (is (= :escalate (:disposition r)) (str op " auto-committed at phase 3"))
        (is (= :phase-approval (:reason r)))))))

(deftest a-governor-escalation-is-never-downgraded
  (doseq [n (keys phase/phases)]
    (let [r (phase/gate n {:op :approve-recommendation} :escalate)]
      (is (contains? #{:escalate :hold} (:disposition r))
          (str "phase " n " turned a governor escalation into " (:disposition r))))))

(deftest an-unrecognised-op-fails-closed
  (testing "an op the table has never heard of holds, at the most permissive phase"
    (let [r (phase/gate 3 {:op :approve-everything-forever} :commit)]
      (is (= :hold (:disposition r)))
      (is (= :phase-disabled (:reason r)))))
  (testing "including a missing op"
    (is (= :hold (:disposition (phase/gate 3 {} :commit))))))

;; ------------------------------------------------------ verdict mapping

(deftest verdict-maps-to-the-base-disposition
  (is (= :hold     (phase/verdict->disposition {:hard? true :escalate? true})))
  (is (= :escalate (phase/verdict->disposition {:escalate? true})))
  (is (= :commit   (phase/verdict->disposition {:ok? true}))))
