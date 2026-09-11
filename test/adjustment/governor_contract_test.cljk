(ns adjustment.governor-contract-test
  "The governor contract as executable tests -- the loss-adjustment
  analog of `cloud-itonami-isic-6511`'s `underwriting.governor-contract-
  test` / `cloud-itonami-isic-6512`'s `casualty.governor-contract-test`.
  The single invariant under test:

    Adjuster-LLM never finalizes a valuation the Loss Adjustment
    Governor would reject, `:valuation/finalize` NEVER auto-commits at
    any phase, and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [adjustment.store :as store]
            [adjustment.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :adjuster :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :matter/intake :subject "matter-1"
                   :patch {:id "matter-1" :status :ready}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :ready (:status (store/matter db "matter-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "matter-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "matter-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "matter-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "matter-1")) "no assessment written"))))

(deftest conflict-of-interest-is-held-and-unoverridable
  (testing "a conflict-of-interest hit on a party -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :conflict/screen :subject "party-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:conflict-of-interest} (-> (store/ledger db) first :basis)))
      (is (nil? (store/conflict-of db "party-4")) "no conflict clearance written"))))

(deftest finalize-without-assessment-is-held
  (testing "valuation/finalize before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :valuation/finalize :subject "matter-1"
                                   :valuation-amount 850000 :supporting-evidence []} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest valuation-finalize-always-escalates-then-human-decides
  (testing "a clean, fully-assessed finalization still ALWAYS interrupts for human approval -- actuation is never auto"
    (let [[db actor] (fresh)
          _ (exec-op actor "t6a" {:op :jurisdiction/assess :subject "matter-1"} operator)
          _ (approve! actor "t6a")
          _ (exec-op actor "t6b" {:op :conflict/screen :subject "party-2"} operator)
          _ (approve! actor "t6b")
          r1 (exec-op actor "t6" {:op :valuation/finalize :subject "matter-1"
                                  :valuation-amount 850000
                                  :supporting-evidence ["a" "b" "c" "d"]} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, valuation-report record drafted"
        (let [r2 (approve! actor "t6")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :valued (:status (store/matter db "matter-1"))))
          (is (= 1 (count (store/valuation-history db))) "one draft valuation record")))))
  (testing "reject -> hold, nothing finalized"
    (let [[db actor] (fresh)
          _ (exec-op actor "t7a" {:op :jurisdiction/assess :subject "matter-1"} operator)
          _ (approve! actor "t7a")
          _ (exec-op actor "t7" {:op :valuation/finalize :subject "matter-1"
                                 :valuation-amount 850000
                                 :supporting-evidence ["a" "b" "c" "d"]} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/valuation-history db)) "nothing finalized on reject"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :matter/intake :subject "matter-1"
                       :patch {:id "matter-1" :status :ready}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "matter-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
