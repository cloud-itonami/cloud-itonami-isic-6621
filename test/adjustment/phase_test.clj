(ns adjustment.phase-test
  "The phase table as executable tests. The single invariant this repo
  cannot regress on: `:valuation/finalize` must NEVER be a member of any
  phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [adjustment.phase :as phase]))

(deftest valuation-finalize-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real valuation"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :valuation/finalize))
          (str "phase " n " must not auto-commit :valuation/finalize")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-intake
  (is (= #{:matter/intake} (:auto (get phase/phases 3)))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :matter/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :valuation/finalize} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :matter/intake} :commit)))))
