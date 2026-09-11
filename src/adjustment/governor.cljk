(ns adjustment.governor
  "Loss Adjustment Governor -- the independent compliance layer that earns
  the Adjuster-LLM the right to commit. The LLM has no notion of which
  jurisdiction's valuation-methodology standard is official, no way to
  know whether the assigned adjuster has an undisclosed conflict of
  interest with the requesting party (the ONE thing that makes this
  business 'independent' at all), and no business being the one that
  decides a real valuation report is finalized and issued today, so this
  MUST be a separate system able to *reject* a proposal and fall back to
  HOLD -- the loss-adjustment analog of `cloud-itonami-isic-6511`'s
  UnderwritingGovernor / `cloud-itonami-isic-6512`'s Non-Life Insurance
  Governor.

  Four checks, in priority order. The first three are HARD violations: a
  human approver CANNOT override them (you don't get to approve your way
  past an undisclosed conflict of interest, a fabricated valuation
  methodology, or an unsupported valuation). The last is SOFT: it asks a
  human to look (low confidence / actuation), and the human may approve
  -- but see `adjustment.phase`: for `:stake :actuation` (finalizing and
  issuing a real valuation report) NO phase ever allows auto-commit
  either. Two independent layers agree that actuation is always a human
  call.

    1. Spec-basis            -- did the jurisdiction proposal cite an
                                 OFFICIAL source (`adjustment.facts`), or
                                 invent one?
    2. Conflict of interest   -- does THIS proposal itself report a
                                 conflict-of-interest hit (a `:conflict/
                                 screen` that just found one), or does
                                 the matter's assigned adjuster already
                                 carry one on file? Independence is the
                                 entire premise of this business -- an
                                 adjuster with an undisclosed conflict
                                 cannot finalize a valuation for that
                                 matter.
    3. Evidence incomplete    -- for `:valuation/finalize`, is the
                                 jurisdiction's required supporting
                                 evidence actually satisfied? Do not
                                 trust the advisor's self-reported
                                 confidence alone -- an unsupported
                                 valuation is exactly the failure mode
                                 this repo's own Trust Controls name.
    4. Confidence floor / actuation gate -- LLM confidence below
                                 threshold, OR the op is `:valuation/
                                 finalize` (a REAL act -- see README
                                 `Actuation`) -> escalate."
  (:require [adjustment.facts :as facts]
            [adjustment.kernels.gate :as gate]
            [adjustment.store :as store]))

(def confidence-floor
  "Documented threshold. The DECIDING copy is
  `adjustment.kernels.gate/confidence-floor-x100` (integer x100 in the
  safety kernel); this def is kept for callers/docs and pinned equal by
  `adjustment.kernels.gate-test`."
  0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  :actuation = finalizing and issuing a real valuation report (an
  insurer or insured will rely on it for a real settlement). There is
  exactly one member on purpose: actuation is not a spectrum."
  #{:actuation})

(defn- confidence->x100
  "Host bridge (façade-side, not kernel vocabulary): scale a 0.0..1.0
  advisor confidence to the kernel's integer x100 wire code."
  [c]
  (Math/round (* 100.0 (double c))))

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:valuation/finalize`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's valuation-methodology requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :valuation/finalize} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- conflict-violations
  "A conflict-of-interest hit -- reported by THIS proposal (e.g. a
  `:conflict/screen` that itself just found one), or already on file in
  the store for the matter's assigned adjuster (`:valuation/finalize`)
  -- is a HARD, un-overridable hold. NOTE: `hit-in-proposal?` is
  deliberately UNCONDITIONAL (not scoped to any particular op) -- only
  `:conflict/screen` proposals ever set `:verdict`, so scoping this
  branch to a specific op would silently prevent the screening op itself
  from ever being held on its own finding (a real bug caught in the
  sibling `cloud-itonami-isic-6512`'s R0 build -- see its ADR)."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :hit (get-in proposal [:value :verdict]))
        adjuster-id (when (= op :valuation/finalize)
                      (:adjuster (store/matter st subject)))
        hit-on-file? (and adjuster-id (= :hit (:verdict (store/conflict-of st adjuster-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :conflict-of-interest
        :detail "利益相反(独立性欠如)のある鑑定人による評価は進められない"}])))

(defn- evidence-incomplete-violations
  "For `:valuation/finalize`, the jurisdiction's required supporting
  evidence must actually be satisfied -- do not trust the advisor's
  self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :valuation/finalize)
    (let [m (store/matter st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction m) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要根拠資料が充足していない状態での評価確定提案"}]))))

(defn check
  "Censors an Adjuster-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [spec-v (spec-basis-violations request proposal)
        conflict-v (conflict-violations request proposal st)
        evid-v (evidence-incomplete-violations request st)
        hard (into [] (concat spec-v conflict-v evid-v))
        conf (:confidence proposal 0.0)
        stakes? (boolean (high-stakes (:stake proposal)))
        ;; The decision itself is delegated to the safety kernel
        ;; (adjustment.kernels.gate, integer-coded fail-closed core);
        ;; this façade only gathers evidence (violation lists with
        ;; human-readable details) and maps codes back to keywords.
        ;; Kernel is stricter than the old inline logic on ONE case by
        ;; design: an out-of-range confidence (< 0 or > 1.0) now
        ;; escalates instead of counting as high confidence.
        code (gate/verdict-code (if (seq spec-v) 1 0)
                                (if (seq conflict-v) 1 0)
                                (if (seq evid-v) 1 0)
                                (confidence->x100 conf)
                                (if stakes? 1 0))]
    {:ok?          (= 0 code)
     :violations   hard
     :confidence   conf
     :hard?        (= 2 code)
     :escalate?    (= 1 code)
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
