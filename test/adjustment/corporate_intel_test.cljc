(ns adjustment.corporate-intel-test
  "Proves the value `adjustment.corporate-intel` actually adds: an
  adjuster (party-5, \"山田 一郎(デモ)\") that is clean on every LOCAL
  field (no `:conflict-hit?`, has a disclosure doc) but per
  cloud-itonami-isic-8291's OWN seeded demo relationship graph carries an
  undisclosed direct `:business-contact` edge to matter-3's
  requesting-party (party-6, \"Jane Smith (demo)\") no longer silently
  clears once `:matter-id` is supplied and the cross-reference is wired
  in -- something the local-only `screen-conflict` (no `:matter-id`)
  would have missed entirely.

  Empirically verified (see the probes this test formalizes): with the
  REAL, unstubbed 8291 actor, this specific pair (a `:business-contact`
  edge between two ordinary officials -- neither government-official
  capacity nor itself sanctions-flagged) does NOT make 8291's OWN
  DisclosureGovernor escalate -- it commits `{:related? true :kind
  :business-contact}` immediately (8291's `dossier.llm/propose-
  relationship-check` only flags `:stake :sanctions-flag` when the
  resolved id itself is a sanctioned company or a government-official
  capacity official, not merely an official who WORKS AT a sanctioned
  company). That real `:related? true` then reaches THIS actor's OWN
  Loss Adjustment Governor, whose `conflict-violations` check is
  unconditional on any `:verdict :hit` -- so it hard-holds immediately,
  un-overridably, exactly like a local `:conflict-hit?` would. The
  `:pending-human-review?`/`:held?` branches (reachable when 8291 itself
  escalates or rejects the query) are proven separately below with a
  stub, since 8291's current demo data has no pairing that reaches them
  through this op."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [adjustment.store :as store]
            [adjustment.operation :as op]
            [adjustment.adjusterllm :as adjusterllm]
            [adjustment.corporate-intel :as ci]))

(def operator {:actor-id "op-1" :actor-role :adjuster :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- wired-actor []
  (let [db (store/seed-db)]
    [db (op/build db {:advisor (adjusterllm/mock-advisor {:corporate-intel-check ci/check-relationship})})]))

(deftest without-matter-id-behavior-is-byte-for-byte-unchanged
  (testing "sanity: existing callers that omit :matter-id (even against the NEW party-5) keep the exact prior clean-adjuster behavior"
    (let [db (store/seed-db)
          actor (op/build db)                          ; default advisor, NO corporate-intel wired in
          res (exec-op actor "sanity" {:op :conflict/screen :subject "party-5"} operator)]
      (is (= :interrupted (:status res)) "conflict/screen always escalates for approval, clean or not")
      (approve! actor "sanity")
      (is (= :clear (:verdict (store/conflict-of db "party-5")))
          "without :matter-id, party-5 screens :clear regardless of any corporate-intel wiring"))))

(deftest corporate-intel-catches-the-relationship-local-checks-miss
  (testing "with :matter-id + the REAL (unmocked) 8291 actor wired in, party-5 no longer silently clears --
            8291's own relationship-check commits {:related? true :kind :business-contact} immediately
            (this specific pairing isn't itself sanctions/gov-official-flagged, so 8291 doesn't escalate),
            and THIS actor's own governor then hard-holds on the resulting :verdict :hit -- settling
            immediately, no interrupt, never overridable by a human approver"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t1" {:op :conflict/screen :subject "party-5" :matter-id "matter-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:conflict-of-interest} (-> (store/ledger db) first :basis)))
      (is (nil? (store/conflict-of db "party-5")) "no conflict clearance written"))))

(deftest corporate-intel-definitive-related-hit-hard-holds
  (testing "screen-conflict's :related? branch itself is a HARD, un-overridable hold -- proven directly
            with a stub, deterministically, isolated from 8291's own timing"
    (let [db (store/seed-db)
          definitive-hit (fn [_adjuster _counterparty] {:found? true :related? true :kind :business-contact})
          actor (op/build db {:advisor (adjusterllm/mock-advisor {:corporate-intel-check definitive-hit})})
          res (exec-op actor "t2" {:op :conflict/screen :subject "party-5" :matter-id "matter-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (some #{:conflict-of-interest} (-> (store/ledger db) first :basis)))
      (is (nil? (store/conflict-of db "party-5")) "no conflict clearance written"))))

(deftest corporate-intel-pending-human-review-degrades-to-incomplete-and-escalates
  (testing "if 8291 itself escalates a potential hit for ITS OWN human reviewer first, this actor must
            treat that as inconclusive (:incomplete, low confidence) -- escalate, never silently clear
            or silently hard-hold on someone else's still-pending review"
    (let [db (store/seed-db)
          pending (fn [_adjuster _counterparty] {:pending-human-review? true :reason :high-stakes})
          actor (op/build db {:advisor (adjusterllm/mock-advisor {:corporate-intel-check pending})})
          res (exec-op actor "t3" {:op :conflict/screen :subject "party-5" :matter-id "matter-3"} operator)]
      (is (= :interrupted (:status res)) "confidence 0.5 < the 0.6 floor -> escalate")
      (is (= :low-confidence (-> res :state :audit last :reason)))
      (let [r2 (approve! actor "t3")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :incomplete (:verdict (store/conflict-of db "party-5"))) "never :clear on an unresolved 8291 review")))))

(deftest corporate-intel-held-screen-degrades-to-incomplete-not-clear
  (testing "if this tenant's own contract with 8291 is missing/misconfigured, 8291 itself holds the
            query -- 6621 must treat that as inconclusive (escalate), never as clear"
    (let [db (store/seed-db)
          broken (fn [_adjuster _counterparty] {:held? true :reason [:licensed-disclosure]})
          actor (op/build db {:advisor (adjusterllm/mock-advisor {:corporate-intel-check broken})})
          res (exec-op actor "t4" {:op :conflict/screen :subject "party-5" :matter-id "matter-3"} operator)]
      (is (= :interrupted (:status res)) "low confidence (:incomplete) -> escalate, same as a missing disclosure doc")
      (is (nil? (store/conflict-of db "party-5"))))))

(deftest corporate-intel-clean-counterparty-still-clears
  (testing "an adjuster with no local signal, screened with :matter-id against a counterparty with NO
            match in 8291's demo data (party-1 \"Acme Insurance Co.\" isn't in 8291's seeded companies),
            still clears -- additive, not stricter-by-default (a confident not-found is not treated as a hit)"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t5" {:op :conflict/screen :subject "party-2" :matter-id "matter-1"} operator)]
      (is (= :interrupted (:status res)) "conflict/screen always escalates for approval in phase 3, clean or not")
      (approve! actor "t5")
      (is (= :clear (:verdict (store/conflict-of db "party-2")))))))
