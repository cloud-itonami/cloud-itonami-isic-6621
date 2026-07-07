(ns adjustment.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` / `cloud-itonami-isic-6512`'s
  `casualty.store-contract-test` for the same pattern on the sibling
  actors."
  (:require [clojure.test :refer [deftest is testing]]
            [adjustment.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "CLAIM-JPN-001" (:case-reference (store/matter s "matter-1"))))
      (is (= "JPN" (:jurisdiction (store/matter s "matter-1"))))
      (is (= "party-2" (:adjuster (store/matter s "matter-1"))))
      (is (= "田中 一郎" (:name (store/party s "party-2"))))
      (is (false? (:conflict-hit? (store/party s "party-2"))))
      (is (true? (:conflict-hit? (store/party s "party-4"))))
      (is (= ["matter-1" "matter-2"] (mapv :id (store/all-matters s))))
      (is (nil? (store/conflict-of s "party-2")))
      (is (nil? (store/assessment-of s "matter-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/valuation-history s)))
      (is (zero? (store/next-sequence s "JPN"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :matter/upsert
                                 :value {:id "matter-1" :status :ready}})
        (is (= :ready (:status (store/matter s "matter-1"))))
        (is (= "CLAIM-JPN-001" (:case-reference (store/matter s "matter-1"))) "case-reference preserved"))
      (testing "assessment / conflict payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["matter-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "matter-1")))
        (store/commit-record! s {:effect :conflict/set :path ["party-2"]
                                 :payload {:party-id "party-2" :verdict :clear}})
        (is (= {:party-id "party-2" :verdict :clear} (store/conflict-of s "party-2"))))
      (testing "finalization drafts a valuation record and advances the sequence"
        (store/commit-record! s {:effect :valuation/mark-finalized :path ["matter-1"]
                                 :payload {:valuation-amount 850000 :supporting-evidence ["photo" "estimate"]}})
        ;; valuation-history holds the inner "record" sub-map (registry/append's
        ;; convention), whose valuation-number key is "record_id".
        (is (= "JPN-VAL-000000" (get (first (store/valuation-history s)) "record_id")))
        (is (= "valuation-draft" (get (first (store/valuation-history s)) "kind")))
        (is (= :valued (:status (store/matter s "matter-1"))))
        (is (= 1 (count (store/valuation-history s))))
        (is (= 1 (store/next-sequence s "JPN"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/matter s "nope")))
    (is (= [] (store/all-matters s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/valuation-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-matters s {"x" {:id "x" :case-reference "C-1" :subject "widget"
                               :requesting-party "p" :adjuster "a" :jurisdiction "JPN"
                               :status :intake}})
    (is (= "C-1" (:case-reference (store/matter s "x"))))))
