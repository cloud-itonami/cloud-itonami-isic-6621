(ns adjustment.registry-test
  (:require [clojure.test :refer [deftest is]]
            [adjustment.registry :as r]))

(deftest valuation-is-a-draft-not-a-real-report
  (let [result (r/register-valuation "CLAIM-JPN-001" 850000 ["photo"] "JPN" 1)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest valuation-assigns-valuation-number
  (let [result (r/register-valuation "CLAIM-JPN-001" 850000 ["photo" "estimate"] "JPN" 7)]
    (is (= (get result "valuation_number") "JPN-VAL-000007"))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "valuation-draft"))
    (is (= (get-in result ["record" "case_reference"]) "CLAIM-JPN-001"))
    (is (= 2 (count (get-in result ["record" "supporting_evidence"]))))))

(deftest valuation-validation-rules
  (is (thrown? Exception (r/register-valuation "" 850000 ["photo"] "JPN" 1)))
  (is (thrown? Exception (r/register-valuation "CLAIM-JPN-001" -1 ["photo"] "JPN" 1)))
  (is (thrown? Exception (r/register-valuation "CLAIM-JPN-001" 850000 [] "JPN" 1)))
  (is (thrown? Exception (r/register-valuation "CLAIM-JPN-001" 850000 ["photo"] "" 1)))
  (is (thrown? Exception (r/register-valuation "CLAIM-JPN-001" 850000 ["photo"] "JPN" -1))))

(deftest valuation-history-is-append-only
  (let [v1 (r/register-valuation "CLAIM-JPN-001" 850000 ["photo"] "JPN" 0)
        hist (r/append [] v1)
        v2 (r/register-valuation "CLAIM-JPN-002" 100000 ["photo"] "JPN" 1)
        hist2 (r/append hist v2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-VAL-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-VAL-000001" (get-in hist2 [1 "record_id"])))))
