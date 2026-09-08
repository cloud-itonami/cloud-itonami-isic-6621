(ns adjustment.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO operator-console sample and no generator at
  all. This namespace drives the REAL actor stack (`adjustment.operation`
  -> `adjustment.governor` -> `adjustment.store`, through langgraph-clj's
  `g/run*`, exactly the way this repo's own `adjustment.sim` demo driver
  does) and renders the resulting store + run state.

  Everything on the page is this run's actual actor/store output:

    - matters / parties / registers        `adjustment.store` reads
    - HARD holds and their rule + detail   the governor's own violation maps
    - rollout-phase holds                  the phase gate's own reason codes
    - the phase table and op gate          `adjustment.phase/phases` itself,
                                           NOT a hand-written description
    - jurisdiction spec-basis + coverage   `adjustment.facts/catalog`/`coverage`
    - approver attribution                 PROBED at render time (see
                                           `approver-key` -- the page reports
                                           what the store actually kept, so
                                           it self-corrects if that changes)

  Nothing is hand-typed. Where a value cannot be obtained from the store,
  the page says so rather than inventing one.

  Deterministic: no timestamps in the page content, every set/map is
  sorted before rendering, and the scenario is a fixed sequence against
  `store/seed-db`. Two consecutive runs are byte-identical -- verify by
  rendering twice into a fresh temp directory and diffing.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [kotoba.lang.text :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [adjustment.adjusterllm :as adjusterllm]
            [adjustment.corporate-intel :as corporate-intel]
            [adjustment.facts :as facts]
            [adjustment.governor :as governor]
            [adjustment.operation :as op]
            [adjustment.phase :as phase]
            [adjustment.store :as store]))

;; ----------------------------- scenario -----------------------------

(def ^:private operator
  "The actor's own identity/role/phase context. `:phase 3` is
  `adjustment.phase/default-phase` (supervised auto)."
  {:actor-id "op-1" :actor-role :adjuster :phase 3})

(def ^:private approver
  "The human independent adjuster who resumes an interrupted run. This is
  a scenario INPUT (the id a real operator would sign the resume with),
  deliberately different from `operator`'s `:actor-id` -- if the two were
  the same string, a record that kept only the actor-id would look like
  it had kept the approver. Keeping them distinct is what makes the
  approver-attribution probe below able to tell the difference at all."
  "adjuster-ito")

(defn- exec!
  "One operation = one supervised graph run."
  ([actor tid request] (exec! actor tid request operator))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(defn- resume!
  "Resume an interrupted run with a human decision (`:approved` /
  `:rejected`) -- the `interrupt-before #{:request-approval}` handoff."
  [actor tid status]
  (g/run* actor {:approval {:status status :by approver}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives a fresh seeded store through a scenario that reaches EVERY
  disposition this actor can produce, and returns
  `{:db <store> :runs [<run> ..]}` where each run records its thread id,
  label, request, final result and (when it was interrupted) the human
  decision that resumed it.

  The run vector matters: the persisted ledger does NOT record who
  approved anything (`adjustment.operation`'s commit fact carries the
  actor-id, not the approver), so the approver disclosure below has to
  read the `:approval-granted` facts out of each run's own audit channel.
  Both halves are real actor output; the page shows the difference.

  Committed paths (matters/parties are `adjustment.store/demo-data`):
    - `:matter/intake matter-1`       auto-commits at phase 3 (clean,
                                      moves no liability -- the only op in
                                      any phase's `:auto` set)
    - `:jurisdiction/assess matter-1` clean but never auto-eligible ->
                                      human approval -> commit
    - `:jurisdiction/assess matter-3` same, second jurisdiction record
    - `:conflict/screen party-2`      clean + disclosure on file -> approval
    - `:conflict/screen party-6`      no disclosure doc -> confidence 0.4,
                                      BELOW `governor/confidence-floor` ->
                                      escalates for a different reason
                                      (`:low-confidence`, not the phase
                                      gate) -> approved as `:incomplete`
    - `:valuation/finalize matter-1`  `:stake :actuation` -> ALWAYS
                                      escalates at every phase -> approved
                                      -> a real valuation-report draft

  HARD holds (governor refusals -- these never reach a human at all):
    - `:conflict/screen party-4`      conflict of interest from the LOCAL
                                      screen (`:conflict-hit?` on file)
    - `:conflict/screen party-5`      conflict of interest found ONLY by
                                      the cloud-itonami-isic-8291
                                      corporate-intelligence cross-
                                      reference against matter-3's
                                      counterparty -- same rule, evidence
                                      a local-only screen cannot see
    - `:jurisdiction/assess matter-2` no official spec-basis for its
                                      jurisdiction -> the advisor must not
                                      invent one
    - `:valuation/finalize matter-2`  TWO violations at once (no spec-basis
                                      AND required evidence unsatisfied)

  Human refusal (reached a human, who said no -- a different thing from a
  governor refusal, so the page keeps it in its own table):
    - `:valuation/finalize matter-3`  escalates clean, approver REJECTS

  Rollout-phase holds (the phase gate, NOT the governor -- these carry an
  EMPTY `:violations` vector, which is exactly why the build-time
  invariant below is two-stage):
    - `:jurisdiction/assess` at phase 1   op not yet write-enabled
    - `:matter/intake` at phase 0         read-only phase
    - an unrecognised op at phase 3       fails closed to HOLD"
  []
  (let [db (store/seed-db)
        ;; The advisor is injected, so wiring the 8291 corporate-intelligence
        ;; cross-reference in is a swap, not a rewrite. It is consulted ONLY
        ;; by `:conflict/screen`, and only when the request supplies
        ;; `:matter-id` -- every other op below behaves exactly as it does
        ;; under the bare `(mock-advisor)` the sim driver uses.
        actor (op/build db {:advisor (adjusterllm/mock-advisor
                                      {:corporate-intel-check
                                       corporate-intel/check-relationship})})
        runs (atom [])
        step! (fn [label tid request context resume]
                (let [r1 (exec! actor tid request context)
                      r2 (when (and resume (= :interrupted (:status r1)))
                           (resume! actor tid resume))]
                  (swap! runs conj
                         {:thread tid :label label :request request
                          :context context
                          :interrupted? (= :interrupted (:status r1))
                          :resume resume
                          :first r1
                          :result (or r2 r1)})))]

    (step! "matter intake (clean, auto-commits at phase 3)"
           "m1-intake" {:op :matter/intake :subject "matter-1"
                        :patch {:id "matter-1" :status :ready}}
           operator nil)

    (step! "jurisdiction valuation-methodology assessment"
           "m1-assess" {:op :jurisdiction/assess :subject "matter-1"}
           operator :approved)

    (step! "adjuster conflict-of-interest screening (local)"
           "p2-screen" {:op :conflict/screen :subject "party-2"}
           operator :approved)

    (step! "counterparty screening, no disclosure doc on file"
           "p6-screen" {:op :conflict/screen :subject "party-6"}
           operator :approved)

    (step! "valuation finalisation (actuation -- always human)"
           "m1-finalize" {:op :valuation/finalize :subject "matter-1"
                          :valuation-amount 850000
                          :supporting-evidence (facts/evidence-checklist "JPN")}
           operator :approved)

    (step! "jurisdiction assessment for the second JPN matter"
           "m3-assess" {:op :jurisdiction/assess :subject "matter-3"}
           operator :approved)

    (step! "conflict screening: hit on the local conflict registry"
           "p4-screen" {:op :conflict/screen :subject "party-4"}
           operator :approved)

    (step! "conflict screening: hit only via corporate-intelligence cross-reference"
           "p5-screen" {:op :conflict/screen :subject "party-5"
                        :matter-id "matter-3"}
           operator :approved)

    (step! "jurisdiction assessment with no official spec-basis"
           "m2-assess" {:op :jurisdiction/assess :subject "matter-2"
                        :no-spec? true}
           operator :approved)

    (step! "valuation finalisation with no assessment on file"
           "m2-finalize" {:op :valuation/finalize :subject "matter-2"
                          :valuation-amount 50000 :supporting-evidence []}
           operator :approved)

    (step! "valuation finalisation the human approver REFUSES"
           "m3-finalize" {:op :valuation/finalize :subject "matter-3"
                          :valuation-amount 2400000
                          :supporting-evidence (facts/evidence-checklist "JPN")}
           operator :rejected)

    (step! "assessment attempted before its phase enables the write"
           "p1-gate" {:op :jurisdiction/assess :subject "matter-1"}
           (assoc operator :phase 1) :approved)

    (step! "intake attempted in the read-only phase"
           "p0-gate" {:op :matter/intake :subject "matter-1"
                      :patch {:id "matter-1" :status :ready}}
           (assoc operator :phase 0) :approved)

    (step! "unrecognised op (fail-closed probe)"
           "unknown-op" {:op :adjustment/not-an-op :subject "matter-1"}
           operator :approved)

    {:db db :runs @runs}))

;; ----------------------------- build-time invariant -----------------------------

(defn governor-holds
  "Every governor/phase HOLD fact in the persisted ledger."
  [ledger]
  (filterv #(= :governor-hold (:t %)) ledger))

(defn hard-holds
  "The governor's own refusals: HOLD facts carrying at least one
  violation. A rollout-phase hold (`:phase-disabled`) is ALSO written as
  `:t :governor-hold` but with an EMPTY `:violations` vector -- counting
  holds alone would let a page full of nothing but phase-gating pass the
  invariant below while showing no compliance refusal at all."
  [ledger]
  (filterv #(and (= :governor-hold (:t %)) (seq (:violations %))) ledger))

(defn phase-gate-holds
  "HOLD facts produced by the rollout phase gate rather than by a
  compliance violation -- empty `:violations`, a `:phase-reason` instead."
  [ledger]
  (filterv #(and (= :governor-hold (:t %)) (empty? (:violations %))) ledger))

(defn assert-hard-holds!
  "Build-time invariant, two-stage on purpose (see `hard-holds`):

    1. the run must produce at least one governor HOLD at all, and
    2. at least one of those holds must carry a non-empty violation.

  A console that shows only clean paths is not evidence this actor can
  refuse anything, and a console that shows only phase-gating is not
  evidence the GOVERNOR can. Throws rather than writing such a page."
  [ledger]
  (let [holds (governor-holds ledger)
        hard  (hard-holds ledger)]
    (when (empty? holds)
      (throw (ex-info (str "render-html refuses to write a console: this run produced "
                           "ZERO governor holds. The page would show only clean paths, "
                           "which is not evidence the Loss Adjustment Governor can refuse "
                           "anything.")
                      {:ledger-facts (count ledger) :holds 0})))
    (when (empty? hard)
      (throw (ex-info (str "render-html refuses to write a console: all " (count holds)
                           " hold(s) in this run were rollout-phase holds with an empty "
                           ":violations vector. A phase gate deferring a write is not a "
                           "compliance refusal -- at least one HARD governor violation "
                           "must be demonstrated.")
                      {:holds (count holds)
                       :phase-reasons (mapv :phase-reason (phase-gate-holds ledger))
                       :hard-holds 0})))
    hard))

;; ----------------------------- approver attribution probe -----------------------------

(defn- key-name [k]
  (cond (keyword? k) (name k) (string? k) k :else (str k)))

(defn approver-key
  "The name of any key on `v` that mentions 'approv', or nil.

  Deliberately a probe over the record's actual keys rather than a
  hard-coded lookup of `:approved-by`: if the store is later changed to
  retain the approver (or the string-keyed valuation record grows an
  `approved_by`), this page reports the fix instead of continuing to
  assert a defect that no longer exists."
  [v]
  (when (map? v)
    (->> v
         (keep (fn [[k _]] (let [n (key-name k)]
                             (when (re-find #"(?i)approv" n) n))))
         sort
         first)))

(defn persisted-record
  "The SSoT record a committed op actually wrote, looked up through the
  `Store` protocol. Returns {:register .. :key .. :value ..}; `:value` nil
  means the register has nothing under that key."
  [db {:keys [op subject]}]
  (case op
    :matter/intake       {:register ":matters"     :key subject
                          :value (store/matter db subject)}
    :jurisdiction/assess {:register ":assessments" :key subject
                          :value (store/assessment-of db subject)}
    :conflict/screen     {:register ":conflicts"   :key subject
                          :value (store/conflict-of db subject)}
    :valuation/finalize  (let [vn (:valuation-number (store/matter db subject))]
                           {:register ":valuations" :key (or vn "—")
                            :value (first (filter #(= vn (get % "record_id"))
                                                  (store/valuation-history db)))})
    {:register "—" :key subject :value nil}))

(defn approval-granted-fact
  "The `:approval-granted` audit fact from a run's OWN audit channel. This
  is where the approver actually lives -- `adjustment.operation`'s commit
  fact does not carry it, so it never reaches the persisted ledger."
  [run]
  (->> (get-in run [:result :state :audit])
       (filter #(= :approval-granted (:t %)))
       last))

(defn approver-disclosures
  "Per approved run: who approved it, and whether the record the commit
  actually wrote retained that approver. Derived entirely from this run.

  Keyed by THREAD id, not by [op subject]: two runs can legitimately share
  an op and a subject, so joining on that pair is not unique and would
  silently mis-attribute. `duplicate-op-subject` below reports it if the
  scenario ever grows such a pair, instead of quietly producing a wrong
  row."
  [db runs]
  (for [run runs
        :let [granted (approval-granted-fact run)]
        :when granted
        :let [{:keys [op subject]} (:request run)
              {:keys [register key value]} (persisted-record db (:request run))
              found (approver-key value)]]
    {:thread (:thread run)
     :op op
     :subject subject
     :approver (:by granted)
     :register register
     :register-key key
     :record-present? (some? value)
     :approver-key found
     :retained? (boolean found)
     :retained-value (when found (get value (if (str/starts-with? found ":")
                                              (keyword (subs found 1))
                                              (or (some #(when (= found (key-name %)) %)
                                                        (keys value))
                                                  found))))}))

(defn duplicate-op-subject
  "[op subject] pairs that appear more than once among the disclosures --
  the join key that would NOT have been unique."
  [disclosures]
  (->> disclosures
       (map (juxt :op :subject))
       frequencies
       (filter (fn [[_ n]] (> n 1)))
       (map first)
       sort
       vec))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (str v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- muted [v] (str "<span class=\"muted\">" (esc v) "</span>"))

(defn- ok [v] (str "<span class=\"ok\">" (esc v) "</span>"))

(defn- warn [v] (str "<span class=\"warn\">" (esc v) "</span>"))

(defn- crit [v] (str "<span class=\"critical\">" (esc v) "</span>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (if (seq rows)
    (str "    <table>\n"
         "      <thead><tr>"
         (str/join (map #(str "<th>" (esc %) "</th>") headers))
         "</tr></thead>\n"
         "      <tbody>\n" (str/join "\n" rows) "\n      </tbody>\n"
         "    </table>\n")
    "    <p class=\"muted\">No rows — this run produced none.</p>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       (when lede (str "    <p class=\"muted\">" lede "</p>\n"))
       body
       "  </section>\n"))

(defn- join-names [coll]
  (if (seq coll) (str/join ", " (map #(if (keyword? %) (name %) (str %)) coll)) "—"))

;; ----------------------------- sections -----------------------------

(defn- party-label
  "`name (id)` from the party directory, or an explicit note when the
  directory has no such party -- never a guessed name."
  [db id]
  (if-let [p (store/party db id)]
    (str (:name p) " (" id ")")
    (str id " — not in the party directory")))

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) (muted "no activity this run")
      (= :committed (:t f)) (ok (str "committed · " (kw (:op f))))
      (= :approval-rejected (:t f)) (warn (str "approver refused · " (kw (:op f))))
      (and (= :governor-hold (:t f)) (seq (:violations f)))
      (crit (str "HARD hold · " (join-names (:basis f))))
      (= :governor-hold (:t f)) (warn (str "phase hold · " (kw (:phase-reason f))))
      :else (muted (kw (:t f))))))

(defn- matters-section [db ledger]
  (section
   "Matters (SSoT after this run)"
   (str "Read back through the " (code "adjustment.store/Store") " protocol after the "
        "scenario ran — <code>:status</code> and <code>:valuation-number</code> are "
        "whatever the actor actually committed, not a fixture.")
   (table ["Matter" "Case ref" "Subject" "Jurisdiction" "Requesting party" "Assigned adjuster"
           "Status" "Valuation no." "Last decision"]
          (for [m (store/all-matters db)]
            (row (code (:id m))
                 (esc (:case-reference m))
                 (esc (:subject m))
                 (code (:jurisdiction m))
                 (esc (party-label db (:requesting-party m)))
                 (esc (party-label db (:adjuster m)))
                 (if (= :valued (:status m)) (ok (kw (:status m))) (muted (kw (:status m))))
                 (if-let [v (:valuation-number m)] (code v) (muted "—"))
                 (status-cell ledger (:id m)))))))

(defn- party-ids
  "Every party id the matter directory actually references, sorted. Derived
  from the store rather than listed by hand, so a seed change shows up
  here instead of silently dropping a row."
  [db]
  (->> (store/all-matters db)
       (mapcat (juxt :requesting-party :adjuster))
       (remove nil?)
       distinct
       sort))

(defn- parties-section [db ledger]
  (section
   "Parties & committed conflict-of-interest screenings"
   (str "Independence is the whole premise of this business. "
        (code ":conflict-hit?") " and " (code ":disclosure-doc") " are directory facts; "
        "the <em>verdict</em> column is the committed screening in the "
        (code ":conflicts") " register — a screening the governor HELD writes nothing, "
        "so those rows correctly show no committed verdict.")
   (table ["Party" "Name" "Role" "Local conflict flag" "Disclosure doc" "Committed verdict" "Last decision"]
          (for [id (party-ids db)
                :let [p (store/party db id)
                      c (store/conflict-of db id)]]
            (row (code id)
                 (esc (:name p))
                 (kw (:role p))
                 (if (:conflict-hit? p) (crit "hit on file") (ok "clear"))
                 (if-let [d (:disclosure-doc p)] (code d) (warn "none on file"))
                 (cond
                   (nil? c) (muted "not committed")
                   (= :clear (:verdict c)) (ok (kw (:verdict c)))
                   (= :hit (:verdict c)) (crit (kw (:verdict c)))
                   :else (warn (kw (:verdict c))))
                 (status-cell ledger id))))))

(defn- hold-evidence
  "Which screen actually produced the finding, read off the proposal's own
  `:cites` — the corporate-intelligence cross-reference cites
  `:corporate-intelligence`, a purely local screen does not."
  [run]
  (let [cites (set (get-in run [:result :state :proposal :cites]))]
    (if (contains? cites :corporate-intelligence)
      "cloud-itonami-isic-8291 corporate-intelligence cross-reference"
      "local screen / adjustment.facts")))

(defn- hard-holds-section [runs]
  (let [by-thread (into {} (for [r runs
                                 :let [f (last (filter #(and (= :governor-hold (:t %))
                                                             (seq (:violations %)))
                                                       (get-in r [:result :state :audit])))]
                                 :when f]
                             [(:thread r) [r f]]))]
    (section
     "HARD governor holds — refusals that never reach a human"
     (str "The Loss Adjustment Governor is a separate system from the Adjuster-LLM and can "
          "<em>reject</em>. These runs settled at " (code ":hold") " without ever passing the "
          (code "interrupt-before #{:request-approval}") " handoff, so no approver was ever "
          "offered the chance to wave them through. Every rule and detail below is the "
          "governor's own violation map.")
     (table ["Thread" "Op" "Subject" "Rule(s)" "Governor detail" "Advisor confidence" "Evidence source" "Reached a human?"]
            (for [r runs
                  :let [entry (get by-thread (:thread r))]
                  :when entry
                  :let [[_ f] entry]]
              (row (code (:thread r))
                   (code (kw (:op (:request r))))
                   (code (:subject (:request r)))
                   (crit (join-names (:basis f)))
                   (esc (str/join " / " (map :detail (:violations f))))
                   (str "<span class=\"num\">" (esc (:confidence f)) "</span>")
                   (esc (hold-evidence r))
                   (ok "no — settled before the approval node"))))
     )))

(defn- governor-verdict-cell
  "Render a governor verdict HONESTLY.

  `:ok?` is `(= 0 code)` -- 'commit-eligible', not 'clean'. It is FALSE
  for a proposal the governor found nothing wrong with but escalated
  anyway (`:actuation`, or advisor confidence under
  `governor/confidence-floor`). Reading `:ok?` as 'no violation' labels
  every escalation a violation, which is exactly the confusion the two
  hold tables above exist to prevent -- so the violation list, not
  `:ok?`, decides this cell."
  [v]
  (cond
    (nil? v) (muted "—")
    (seq (:violations v)) (crit (join-names (mapv :rule (:violations v))))
    (:ok? v) (ok "clean — commit-eligible")
    :else (ok (str "clean — no violation (escalated: "
                   (cond (:high-stakes? v) ":actuation"
                         (:escalate? v) (str "confidence " (:confidence v)
                                             " < " governor/confidence-floor)
                         :else "—")
                   ")"))))

(defn- phase-holds-section [runs]
  (section
   "Rollout-phase holds — a different thing from a governor refusal"
   (str "These are the " (code "adjustment.phase") " gate deferring a write that the current "
        "rollout phase has not enabled yet. They are written to the ledger as "
        (code ":t :governor-hold") " like a compliance refusal, but their "
        (code ":violations") " vector is <strong>empty</strong> and they carry a "
        (code ":phase-reason") " instead. Nothing is wrong with the proposal — the actor is "
        "simply not allowed to do that yet. The build-time invariant in "
        (code "assert-hard-holds!") " counts these separately for exactly this reason.")
   (table ["Thread" "Op" "Phase" "Phase reason" "Violations" "Governor verdict on the proposal"]
          (for [r runs
                :let [f (last (filter #(and (= :governor-hold (:t %))
                                            (empty? (:violations %)))
                                      (get-in r [:result :state :audit])))]
                :when f
                :let [v (get-in r [:result :state :verdict])]]
            (row (code (:thread r))
                 (code (kw (:op (:request r))))
                 (str "<span class=\"num\">" (esc (:phase f)) "</span> · "
                      (esc (:label (get phase/phases (:phase f)) "—")))
                 (warn (kw (:phase-reason f)))
                 (ok "none — empty :violations")
                 (governor-verdict-cell v))))))

(defn- approval-section [runs]
  (section
   "Human approval handoffs (interrupt-before :request-approval)"
   (str "Runs the governor cleared but that no phase may auto-commit. The actor genuinely "
        "pauses here — " (code ":status") " is " (code ":interrupted") " until a human resumes "
        "it. The escalation reason is the actor's own: " (code ":actuation") " means the op "
        "issues a real valuation report an insurer or insured will rely on, "
        (code ":phase-approval") " means the phase has not made it auto-eligible, and "
        (code ":low-confidence") " means the advisor itself came in under "
        (code "governor/confidence-floor") " (" (esc governor/confidence-floor) ").")
   (table ["Thread" "Op" "Subject" "Paused?" "Escalation reason" "Confidence" "High stakes?" "Human decision" "Outcome"]
          (for [r runs
                :let [req (last (filter #(= :approval-requested (:t %))
                                        (get-in r [:result :state :audit])))]
                :when req
                :let [v (get-in r [:first :state :verdict])
                      disp (get-in r [:result :state :disposition])]]
            (row (code (:thread r))
                 (code (kw (:op (:request r))))
                 (code (:subject (:request r)))
                 (if (:interrupted? r) (ok "yes") (crit "no"))
                 (warn (kw (:reason req)))
                 (str "<span class=\"num\">" (esc (:confidence req)) "</span>")
                 (if (:high-stakes? v) (crit "yes — :actuation") (muted "no"))
                 (if (= :rejected (:resume r)) (crit (str "rejected by " approver))
                     (ok (str "approved by " approver)))
                 (if (= :commit disp) (ok "committed") (crit (str "held · " (kw disp)))))))))

(defn- refusal-section [runs ledger]
  (let [rejected (filter #(= :rejected (:resume %)) runs)]
    (section
   "Human refusals — reached a human, who said no"
   (str "Distinct from a governor refusal above: the compliance layer was clean and the "
        "actor did hand the decision over, and the licensed independent adjuster declined. "
        "The ledger records this as " (code ":t :approval-rejected") " with a "
        (code ":approver-rejected") " violation, and nothing is written to the SSoT.")
   (table ["Thread" "Op" "Subject" "Governor verdict" "Ledger fact" "Basis" "SSoT written?"]
          (for [r rejected
                :let [f (last (filter #(= :approval-rejected (:t %)) ledger))
                      v (get-in r [:first :state :verdict])]]
            (row (code (:thread r))
                 (code (kw (:op (:request r))))
                 (code (:subject (:request r)))
                 (governor-verdict-cell v)
                 (code (kw (:t f)))
                 (crit (join-names (:basis f)))
                 (ok "no")))))))

(defn- approver-section [db runs]
  (let [ds (vec (approver-disclosures db runs))
        dupes (duplicate-op-subject ds)
        ledger-has-approver (some approver-key (store/ledger db))]
    (section
     "Approver attribution — measured, not assumed"
     (str "Probed at render time: for every approved run, does the record the commit "
          "actually wrote still contain the approver? The probe looks for <em>any</em> key "
          "mentioning &ldquo;approv&rdquo; on the persisted record rather than a hard-coded "
          (code ":approved-by") ", so if the store is later changed this page reports the "
          "change instead of repeating a stale claim. Rows are keyed by thread id — "
          (code "[op subject]") " is not a unique join key."
          (when (seq dupes)
            (str " <strong class=\"critical\">Note: " (esc (count dupes))
                 " [op subject] pair(s) repeat in this run — "
                 (esc (join-names (map #(str/join " " (map kw %)) dupes)))
                 " — so any join on that pair would have been ambiguous.</strong>")))
     (str
      (table ["Thread" "Op" "Subject" "Approver (resume input)" "Register" "Key" "Record written?" "Approver retained?" "Retained under"]
             (for [d ds]
               (row (code (:thread d))
                    (code (kw (:op d)))
                    (code (:subject d))
                    (code (:approver d))
                    (code (:register d))
                    (code (:register-key d))
                    (if (:record-present? d) (ok "yes") (crit "no record found"))
                    (if (:retained? d) (ok "yes") (crit "NO — dropped by the store"))
                    (if (:retained? d)
                      (str (code (:approver-key d)) " = " (code (:retained-value d)))
                      (muted "—")))))
      "    <p class=\"muted\">"
      (let [kept (filter :retained? ds)
            lost (remove :retained? ds)]
        (str "Measured this run: <strong>" (esc (count kept)) " of " (esc (count ds))
             "</strong> approved commits retained the approver"
             (if (seq lost)
               (str ", and <strong class=\"critical\">" (esc (count lost))
                    "</strong> did not — "
                    (esc (join-names (map #(str (kw (:op %)) " → " (:register %)) lost)))
                    ". Those effects are applied from a value the store rebuilds itself, so the "
                    "<code>:approved-by</code> the operation put on the record&rsquo;s payload "
                    "never lands in the register. This is stated rather than omitted: silently "
                    "leaving the approver out would make &ldquo;nobody approved it&rdquo; and "
                    "&ldquo;the store did not keep it&rdquo; look identical to a reader.")
               ".")))
      "</p>\n"
      "    <p class=\"muted\">The persisted audit ledger "
      (if ledger-has-approver
        (str (ok "does") " carry an approver key.")
        (str (crit "does not") " carry an approver key on any fact — the commit fact records "
             (code ":actor") " (the actor-id that ran the operation, " (code (:actor-id operator))
             "), which is <em>not</em> the human who approved it. The approver above was read "
             "from each run&rsquo;s own " (code ":approval-granted") " audit fact, which is real "
             "output of this run but is not persisted anywhere by the actor."))
      "</p>\n"))))

(defn- valuation-section [db]
  (let [vs (store/valuation-history db)]
    (section
     "Valuation-report drafts issued this run"
     (str "The append-only " (code ":valuations") " history — what "
          (code "adjustment.registry/register-valuation") " actually built. Every certificate "
          "this actor produces is <strong>unsigned</strong>: signature is the licensed "
          "independent adjuster&rsquo;s act, not the actor&rsquo;s.")
     (table ["Record id" "Kind" "Case reference" "Amount" "Jurisdiction" "Supporting evidence" "Immutable"]
            (for [v vs]
              (row (code (get v "record_id"))
                   (esc (get v "kind"))
                   (esc (get v "case_reference"))
                   (str "<span class=\"num\">" (esc (get v "valuation_amount")) "</span>")
                   (code (get v "jurisdiction"))
                   (str "<span class=\"num\">" (esc (count (get v "supporting_evidence")))
                        "</span> item(s): " (esc (str/join " · " (get v "supporting_evidence"))))
                   (if (get v "immutable") (ok "yes") (crit "no"))))))))

(defn- phase-table-section [runs]
  (let [observed (into {} (for [r runs
                                :let [v (get-in r [:first :state :verdict])]
                                :when v]
                            [(:op (:request r)) (:high-stakes? v)]))]
    (section
     "Rollout phase table & op gate — read from the code, not described"
     (str "Both tables are generated from " (code "adjustment.phase/phases") " and "
          (code "adjustment.phase/write-ops") " themselves. "
          (code ":valuation/finalize") " is deliberately absent from <em>every</em> phase&rsquo;s "
          (code ":auto") " set including phase 3 — a permanent structural fact, not a rollout "
          "milestone still to come. " (code "adjustment.governor") "&rsquo;s "
          (code ":actuation") " high-stakes gate enforces the same invariant independently, so "
          "two layers agree rather than one.")
     (str
      (table ["Phase" "Label" "Writes enabled" "May auto-commit when clean"]
             (for [[p {:keys [label writes auto]}] (sort-by key phase/phases)]
               (row (str "<span class=\"num\">" (esc p) "</span>")
                    (esc label)
                    (if (seq writes) (code (join-names (sort (map kw writes)))) (muted "none"))
                    (if (seq auto) (ok (join-names (sort (map kw auto)))) (muted "none")))))
      "    <h3>Per-op gate</h3>\n"
      (table ["Op" "First phase that enables the write" "First phase that may auto-commit" "High stakes observed this run"]
             (for [o (sort-by str phase/write-ops)
                   :let [w (first (sort (keep (fn [[p {:keys [writes]}]] (when (writes o) p)) phase/phases)))
                         a (first (sort (keep (fn [[p {:keys [auto]}]] (when (auto o) p)) phase/phases)))]]
               (row (code (kw o))
                    (if w (str "<span class=\"num\">" (esc w) "</span>") (muted "never"))
                    (if a (str "<span class=\"num\">" (esc a) "</span>")
                        (crit "never — always a human call"))
                    (case (get observed o)
                      true (crit (str "yes — " (join-names (sort (map kw governor/high-stakes)))))
                      false (muted "no")
                      (muted "not exercised this run")))))))))

(defn- jurisdiction-section [db]
  (let [used (->> (store/all-matters db) (map :jurisdiction) (remove nil?) distinct sort)
        cov (facts/coverage used)]
    (section
     "Jurisdiction spec-basis catalog & honest coverage"
     (str "The governor rejects any assessment that cites no official source for its "
          "jurisdiction. Coverage is reported honestly: a jurisdiction absent from "
          (code "adjustment.facts/catalog") " has <strong>no</strong> spec-basis, full stop — "
          "the advisor must not invent one. Of the "
          "<span class=\"num\">" (esc (:requested cov)) "</span> jurisdiction(s) this run&rsquo;s "
          "matters actually use, <span class=\"num\">" (esc (:covered cov)) "</span> "
          (if (seq (:missing-jurisdictions cov))
            (str "are covered and " (crit (join-names (:missing-jurisdictions cov)))
                 " is not — which is exactly why the assessment for that matter HARD-held above.")
            "are covered."))
     (str
      (table ["ISO3" "Jurisdiction" "Owner authority" "Legal basis" "Provenance" "Required evidence" "Used by a matter this run?"]
             (for [k (sort (keys facts/catalog))
                   :let [e (facts/catalog k)]]
               (row (code k)
                    (esc (:name e))
                    (esc (:owner-authority e))
                    (esc (:legal-basis e))
                    (str "<code>" (esc (:provenance e)) "</code>")
                    (str "<span class=\"num\">" (esc (count (:required-evidence e))) "</span>: "
                         (esc (str/join " · " (:required-evidence e))))
                    (if (some #{k} used) (ok "yes") (muted "no")))))
      "    <p class=\"muted\">" (esc (:note cov)) "</p>\n"))))

(defn- ledger-section [ledger]
  (section
   "Append-only audit ledger (persisted this run)"
   (str "Every decision the actor made, commit and refusal alike, as written to the store by "
        "the " (code ":commit") " and " (code ":hold") " nodes. This is the trail an insurer or "
        "insured relies on if a valuation is later disputed. "
        "<span class=\"num\">" (esc (count ledger)) "</span> fact(s).")
   (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis" "Confidence"]
          (map-indexed
           (fn [i f]
             (row (str "<span class=\"num\">" (esc i) "</span>")
                  (cond
                    (= :committed (:t f)) (ok (kw (:t f)))
                    (= :approval-rejected (:t f)) (warn (kw (:t f)))
                    (seq (:violations f)) (crit (kw (:t f)))
                    :else (warn (str (kw (:t f)) " (phase)")))
                  (code (kw (:op f)))
                  (code (:subject f))
                  (code (:actor f))
                  (kw (:disposition f))
                  (if (seq (:basis f))
                    (esc (join-names (:basis f)))
                    (muted (if (:phase-reason f) (str "empty · " (kw (:phase-reason f))) "—")))
                  (if (some? (:confidence f))
                    (str "<span class=\"num\">" (esc (:confidence f)) "</span>")
                    (muted "—"))))
           ledger))))

(defn- provenance-section [db runs ledger]
  (section
   "How this page was produced"
   nil
   (table ["Property" "Value"]
          [(row "Generator" (code "adjustment.render-html") )
           (row "Command" (code "clojure -M:dev:render-html"))
           (row "Actor stack"
                (str (code "adjustment.operation") " → " (code "adjustment.governor")
                     " → " (code "adjustment.store") ", driven through "
                     (code "langgraph.graph/run*")))
           (row "Advisor" (str (code "adjustment.adjusterllm/mock-advisor")
                               " with " (code "adjustment.corporate-intel/check-relationship")
                               " injected (deterministic; no network, no live LLM)"))
           (row "Store" (str (code "adjustment.store/seed-db") " — a MemStore over "
                             (code "adjustment.store/demo-data")))
           (row "Operations run" (str "<span class=\"num\">" (esc (count runs)) "</span>"))
           (row "Ledger facts" (str "<span class=\"num\">" (esc (count ledger)) "</span>"))
           (row "HARD governor holds"
                (str "<span class=\"num\">" (esc (count (hard-holds ledger))) "</span> — "
                     (esc (join-names (sort (distinct (mapcat :basis (hard-holds ledger))))))))
           (row "Rollout-phase holds"
                (str "<span class=\"num\">" (esc (count (phase-gate-holds ledger))) "</span> — "
                     (esc (join-names (sort (distinct (map :phase-reason (phase-gate-holds ledger))))))))
           (row "Valuation drafts"
                (str "<span class=\"num\">" (esc (count (store/valuation-history db))) "</span>"))
           (row "Build-time invariant"
                (str (code "assert-hard-holds!") " — the generator refuses to write this file "
                     "unless the run produced at least one governor hold AND at least one hold "
                     "carrying a non-empty violation. A phase-gating hold alone does not satisfy it."))
           (row "Determinism"
                (str "No timestamps in page content; every set and map is sorted before "
                     "rendering. Two consecutive runs are byte-identical."))])))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a `run-demo!` result."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
     "<title>cloud-itonami-isic-6621 · Risk and damage evaluation — Operator Console</title>\n"
     "<style>" (jp-go-dds.skin/dds+skin) "</style>\n"
     "</head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Risk and damage evaluation (ISIC 6621) — Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\"><span class=\"badge\">Adjuster-LLM ⊣ Loss Adjustment Governor</span> "
     "<span class=\"badge\">generated from a real actor run</span> "
     "<span class=\"badge\">valuation finalisation is always a human call</span></p>\n"
     "<div class=\"banner\">\n"
     "<p>Read-only sample. Every id, name, number, verdict and hold reason below came out of "
     "one deterministic run of this repository&rsquo;s own actor against "
     "<code>adjustment.store/demo-data</code> — nothing on this page is hand-typed. Regenerate "
     "with <code>clojure -M:dev:render-html</code>.</p>\n"
     "<p>This actor does <strong>not</strong> hold a licence to practise as an independent "
     "adjuster or appraiser in any jurisdiction. It drafts and governs the workflow; a licensed "
     "operator supplies the licence, the methodology and the signature.</p>\n"
     "</div>\n"
     "<main>\n"
     (matters-section db ledger)
     (parties-section db ledger)
     (hard-holds-section runs)
     (phase-holds-section runs)
     (approval-section runs)
     (refusal-section runs ledger)
     (approver-section db runs)
     (valuation-section db)
     (phase-table-section runs)
     (jurisdiction-section db)
     (ledger-section ledger)
     (provenance-section db runs ledger)
     "</main>\n"
     "<footer>\n"
     "<p>cloud-itonami-isic-6621 — Open Business Blueprint for ISIC Rev.5 6621 (risk and damage "
     "evaluation). Generated by <code>adjustment.render-html</code> from a real "
     "<code>langgraph-clj</code> actor run. Styling: "
     "<a href=\"https://github.com/kotoba-lang/jp-go-digital-design-system\">jp-go-digital-design-system</a> "
     "(デジタル庁デザインシステム, MIT).</p>\n"
     "</footer>\n"
     "</body>\n</html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as demo} (run-demo!)
        ledger (vec (store/ledger db))
        ;; INVARIANT FIRST: throw before writing anything, so a run that
        ;; cannot demonstrate a governor refusal leaves no page behind.
        hard (assert-hard-holds! ledger)
        html (render demo)]
    (.mkdirs (java.io.File. (or (.getParent (java.io.File. ^String out)) ".")))
    (spit out html)
    (println "wrote" out
             (str "(" (count html) " bytes, " (count runs) " operations, "
                  (count ledger) " ledger facts, "
                  (count hard) " HARD governor holds "
                  (pr-str (vec (sort (distinct (mapcat :basis hard))))) ", "
                  (count (phase-gate-holds ledger)) " rollout-phase holds, "
                  (count (store/valuation-history db)) " valuation draft(s))"))))
