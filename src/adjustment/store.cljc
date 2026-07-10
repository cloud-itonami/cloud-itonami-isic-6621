(ns adjustment.store
  "SSoT for the independent loss-adjustment actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  `cloud-itonami-isic-6511`'s `underwriting.store` / `cloud-itonami-isic-
  6512`'s `casualty.store` use:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/adjustment/store_contract_test.clj), which is the whole point:
  the actor, the Loss Adjustment Governor and the audit ledger never
  know which SSoT they run on.

  The ledger stays append-only on every backend: 'which matter was
  assessed on what jurisdictional basis, which adjuster was screened for
  conflict of interest, which valuation was finalized, approved by whom'
  is always a query over an immutable log -- the audit trail an insurer
  or insured trusting an independent adjuster with a valuation needs,
  and the evidence an operator needs if a valuation is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [adjustment.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (matter [s id])
  (all-matters [s])
  (party [s id])
  (conflict-of [s party-id] "committed conflict-of-interest screening verdict for a party, or nil")
  (assessment-of [s matter-id] "committed jurisdiction valuation-methodology assessment, or nil")
  (ledger [s])
  (valuation-history [s] "the append-only valuation-report history (adjustment.registry drafts)")
  (next-sequence [s jurisdiction] "next valuation-number sequence for a jurisdiction")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-matters [s matters] "replace/seed the matter directory (map id->matter)")
  (with-parties [s parties] "replace/seed the party directory (map id->party)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained matter/party set so the actor + tests run
  offline."
  []
  {:matters
   {"matter-1" {:id "matter-1" :case-reference "CLAIM-JPN-001"
                :subject "2019 Honda Civic - rear collision damage"
                :requesting-party "party-1" :adjuster "party-2"
                :jurisdiction "JPN" :status :intake}
    "matter-2" {:id "matter-2" :case-reference "CLAIM-ATL-001"
                :subject "123 Oak St - fire damage"
                :requesting-party "party-1" :adjuster "party-4"
                :jurisdiction "ATL" :status :intake}
    ;; matter-3 pairs party-5 (adjuster) with party-6 (requesting-party)
    ;; purely to exercise `adjustment.corporate-intel`'s cross-reference
    ;; into cloud-itonami-isic-8291's :disclosure/relationship-check --
    ;; see the party-5/party-6 comment below.
    "matter-3" {:id "matter-3" :case-reference "CLAIM-JPN-002"
                :subject "Downtown warehouse - water damage"
                :requesting-party "party-6" :adjuster "party-5"
                :jurisdiction "JPN" :status :intake}}
   :parties
   {"party-1" {:id "party-1" :name "Acme Insurance Co." :role :requesting-party
               :conflict-hit? false :disclosure-doc nil}
    "party-2" {:id "party-2" :name "田中 一郎" :role :adjuster
               :conflict-hit? false :disclosure-doc "form-jp-****1234"}
    "party-4" {:id "party-4" :name "J. Doe" :role :adjuster
               :conflict-hit? true :disclosure-doc nil}
    ;; party-5/party-6 (+ matter-3 above) exist purely to prove the
    ;; cloud-itonami-isic-8291 corporate-intelligence cross-reference
    ;; catches an undisclosed conflict a LOCAL-ONLY screen would miss
    ;; entirely: party-5 is clean on every local field (no
    ;; :conflict-hit?, has a disclosure doc), but per 8291's OWN seeded
    ;; demo relationship graph (dossier.store/demo-data), its official
    ;; "山田 一郎(デモ)" (of-1) carries a direct :business-contact edge
    ;; to "Jane Smith (demo)" (of-2) -- so cross-referencing this
    ;; adjuster's name against matter-3's counterparty's name surfaces a
    ;; relationship a local-only screen can never see. The names below
    ;; must match 8291's demo data EXACTLY for the cross-reference to
    ;; find them (verified via cloud-itonami-isic-8291's `dossier.llm`).
    "party-5" {:id "party-5" :name "山田 一郎(デモ)" :role :adjuster
               :conflict-hit? false :disclosure-doc "form-jp-****5678"}
    "party-6" {:id "party-6" :name "Jane Smith (demo)" :role :requesting-party
               :conflict-hit? false :disclosure-doc nil}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- finalize!
  "Backend-agnostic `:valuation/mark-finalized` -- looks up the matter via
  the protocol, drafts the valuation-report record, and returns
  {:result .. :matter-patch ..} for the caller to persist. Pure w.r.t.
  any particular backend's transaction mechanics."
  [s matter-id valuation-amount supporting-evidence]
  (let [m (matter s matter-id)
        seq-n (next-sequence s (:jurisdiction m))
        result (registry/register-valuation
                (:case-reference m) valuation-amount supporting-evidence (:jurisdiction m) seq-n)]
    {:result result
     :matter-patch {:status :valued
                   :valuation-number (get result "valuation_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (matter [_ id] (get-in @a [:matters id]))
  (all-matters [_] (sort-by :id (vals (:matters @a))))
  (party [_ id] (get-in @a [:parties id]))
  (conflict-of [_ id] (get-in @a [:conflicts id]))
  (assessment-of [_ matter-id] (get-in @a [:assessments matter-id]))
  (ledger [_] (:ledger @a))
  (valuation-history [_] (:valuations @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :matter/upsert
      (swap! a update-in [:matters (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :conflict/set
      (swap! a assoc-in [:conflicts (first path)] payload)

      :valuation/mark-finalized
      (let [matter-id (first path)
            {:keys [valuation-amount supporting-evidence]} payload
            {:keys [result matter-patch]} (finalize! s matter-id valuation-amount supporting-evidence)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences (:jurisdiction (get-in state [:matters matter-id]))] (fnil inc 0))
                       (update-in [:matters matter-id] merge matter-patch)
                       (update :valuations registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-matters [s matters] (when (seq matters) (swap! a assoc :matters matters)) s)
  (with-parties [s parties] (when (seq parties) (swap! a assoc :parties parties)) s))

(defn seed-db
  "A MemStore seeded with the demo matter/party set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :conflicts {} :ledger [] :sequences {} :valuations []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/conflict payloads, ledger facts,
  valuation records) are stored as EDN strings so `langchain.db` doesn't
  expand them into sub-entities -- the same convention `underwriting.
  store`/`casualty.store` use."
  {:matter/id             {:db/unique :db.unique/identity}
   :party/id              {:db/unique :db.unique/identity}
   :conflict/party-id     {:db/unique :db.unique/identity}
   :assessment/matter-id  {:db/unique :db.unique/identity}
   :ledger/seq            {:db/unique :db.unique/identity}
   :valuation/seq         {:db/unique :db.unique/identity}
   :sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- matter->tx [{:keys [id case-reference subject requesting-party adjuster
                          jurisdiction status valuation-number]}]
  (cond-> {:matter/id id}
    case-reference     (assoc :matter/case-reference case-reference)
    subject            (assoc :matter/subject subject)
    requesting-party   (assoc :matter/requesting-party requesting-party)
    adjuster           (assoc :matter/adjuster adjuster)
    jurisdiction       (assoc :matter/jurisdiction jurisdiction)
    status             (assoc :matter/status status)
    valuation-number   (assoc :matter/valuation-number valuation-number)))

(def ^:private matter-pull
  [:matter/id :matter/case-reference :matter/subject :matter/requesting-party
   :matter/adjuster :matter/jurisdiction :matter/status :matter/valuation-number])

(defn- pull->matter [m]
  (when (:matter/id m)
    {:id (:matter/id m) :case-reference (:matter/case-reference m) :subject (:matter/subject m)
     :requesting-party (:matter/requesting-party m) :adjuster (:matter/adjuster m)
     :jurisdiction (:matter/jurisdiction m) :status (:matter/status m)
     :valuation-number (:matter/valuation-number m)}))

(defn- party->tx [{:keys [id name role conflict-hit? disclosure-doc]}]
  (cond-> {:party/id id}
    name (assoc :party/name name)
    role (assoc :party/role role)
    (some? conflict-hit?) (assoc :party/conflict-hit? conflict-hit?)
    disclosure-doc (assoc :party/disclosure-doc disclosure-doc)))

(defn- pull->party [m]
  (when (:party/id m)
    {:id (:party/id m) :name (:party/name m) :role (:party/role m)
     :conflict-hit? (boolean (:party/conflict-hit? m)) :disclosure-doc (:party/disclosure-doc m)}))

(defrecord DatomicStore [conn]
  Store
  (matter [_ id]
    (pull->matter (d/pull (d/db conn) matter-pull [:matter/id id])))
  (all-matters [_]
    (->> (d/q '[:find [?id ...] :where [?e :matter/id ?id]] (d/db conn))
         (map #(pull->matter (d/pull (d/db conn) matter-pull [:matter/id %])))
         (sort-by :id)))
  (party [_ id]
    (pull->party (d/pull (d/db conn)
                         [:party/id :party/name :party/role :party/conflict-hit? :party/disclosure-doc]
                         [:party/id id])))
  (conflict-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?pid
                :where [?k :conflict/party-id ?pid] [?k :conflict/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ matter-id]
    (dec* (d/q '[:find ?p . :in $ ?mid
                :where [?a :assessment/matter-id ?mid] [?a :assessment/payload ?p]]
              (d/db conn) matter-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (valuation-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :valuation/seq ?s] [?e :valuation/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :matter/upsert
      (d/transact! conn [(matter->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/matter-id (first path) :assessment/payload (enc payload)}])

      :conflict/set
      (d/transact! conn [{:conflict/party-id (first path) :conflict/payload (enc payload)}])

      :valuation/mark-finalized
      (let [matter-id (first path)
            {:keys [valuation-amount supporting-evidence]} payload
            {:keys [result matter-patch]} (finalize! s matter-id valuation-amount supporting-evidence)
            jurisdiction (:jurisdiction (matter s matter-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(matter->tx (assoc matter-patch :id matter-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:valuation/seq (count (valuation-history s)) :valuation/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-matters [s matters]
    (when (seq matters) (d/transact! conn (mapv matter->tx (vals matters)))) s)
  (with-parties [s parties]
    (when (seq parties) (d/transact! conn (mapv party->tx (vals parties)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:matters .. :parties ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [matters parties]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-matters matters) (with-parties parties)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo matter/party set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
