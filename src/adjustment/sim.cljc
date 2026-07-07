(ns adjustment.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean matter through
  intake -> jurisdiction valuation-methodology assessment -> conflict-of-
  interest screening -> valuation-finalization proposal (always
  escalates) -> human approval -> commit, then shows three HARD holds (a
  conflict-of-interest hit, a jurisdiction with no spec-basis, and a
  valuation finalization attempted with no assessment on file) that
  never reach a human at all, and prints the audit ledger + the draft
  valuation-report record."
  (:require [langgraph.graph :as g]
            [adjustment.store :as store]
            [adjustment.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :adjuster :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== matter/intake matter-1 (JPN, clean adjuster) ==")
    (println (exec! actor "t1" {:op :matter/intake :subject "matter-1"
                                :patch {:id "matter-1" :status :ready}} operator))

    (println "== jurisdiction/assess matter-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "matter-1"} operator))
    (println (approve! actor "t2"))

    (println "== conflict/screen party-2 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :conflict/screen :subject "party-2"} operator))
    (println (approve! actor "t3"))

    (println "== valuation/finalize matter-1 (always escalates -- actuation) ==")
    (let [r (exec! actor "t4" {:op :valuation/finalize :subject "matter-1"
                              :valuation-amount 850000 :supporting-evidence
                              ["現場写真 (site photographs)" "修理・交換見積書 (repair/replacement estimate)"
                               "所有権を証する書類 (proof of ownership)" "類似事例・時価資料 (comparable value data)"]}
                    operator)]
      (println r)
      (println "-- human independent adjuster approves --")
      (println (approve! actor "t4")))

    (println "== conflict/screen party-4 (conflict of interest -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t5" {:op :conflict/screen :subject "party-4"} operator))

    (println "== jurisdiction/assess matter-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "matter-2" :no-spec? true} operator))

    (println "== valuation/finalize matter-2 (no assessment on file -> HARD hold) ==")
    (println (exec! actor "t7" {:op :valuation/finalize :subject "matter-2"
                                :valuation-amount 50000 :supporting-evidence []} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft valuation-report records ==")
    (doseq [r (store/valuation-history db)] (println r))))
