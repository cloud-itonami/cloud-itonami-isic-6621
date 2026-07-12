(ns adjustment.phase
  "Phase 0->3 staged rollout -- the loss-adjustment analog of
  `cloud-itonami-isic-6511`'s `underwriting.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- matter intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment + conflict
                                 screening writes, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:matter/intake` (no liability risk)
                                 may auto-commit. `:valuation/finalize`
                                 NEVER auto-commits, at any phase.

  `:valuation/finalize` is deliberately ABSENT from every phase's `:auto`
  set, including phase 3 -- a permanent structural fact, not a rollout
  milestone still to come. Finalizing and issuing a real valuation
  report is the one real-world act this actor performs (an insurer or
  insured will rely on it for a real settlement); it is always a human
  independent adjuster's call. `adjustment.governor`'s `:actuation`
  high-stakes gate enforces the same invariant independently -- two
  layers, not one, agree on this. `:matter/intake` moves no capital or
  liability (governed by its own HARD checks in `adjustment.governor`,
  but never `high-stakes`), so it IS auto-eligible at phase 3.

  The decision core is delegated to the safety kernel
  `adjustment.kernels.gate` (integer-coded, fail-closed, safe-kotoba
  subset); this namespace keeps the human-readable phase table (the
  documentation and structural-invariant tests read it) and does the
  keyword<->wire-code mapping at the boundary. The kernel's own battery
  and the parity matrix in `adjustment.kernels.gate-test` pin the two
  representations together."
  (:require [adjustment.kernels.gate :as kernel]))

(def read-ops  #{})
(def write-ops #{:matter/intake :jurisdiction/assess :conflict/screen :valuation/finalize})

;; NOTE the invariant: `:valuation/finalize` is a member of `write-ops`
;; (governor-gated like any write) but is NEVER a member of any phase's
;; `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                     :auto #{}}
   1 {:label "assisted-intake" :writes #{:matter/intake}                                       :auto #{}}
   2 {:label "assisted-assess" :writes #{:matter/intake :jurisdiction/assess :conflict/screen}  :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:matter/intake}}})

(def default-phase 3)

;; ---- kernel wire-code bridges (façade-side, not kernel vocabulary) ----

(defn- op->code
  "Kernel op wire code. This repo's `read-ops` is empty, so 0 (read) is
  never produced here — kept for the fleet-wide wire contract. Unknown
  ops map to 5 (unknown write) — the kernel never write-enables it, so
  an unrecognized op fails closed to HOLD exactly as the old
  set-membership logic did."
  [op]
  (cond
    (contains? read-ops op)      0
    (= op :matter/intake)        1
    (= op :jurisdiction/assess)  2
    (= op :conflict/screen)      3
    (= op :valuation/finalize)   4
    :else                        5))

(defn- disposition->code [d]
  (cond (= d :commit) 0 (= d :escalate) 1 (= d :hold) 2 :else 2))

(defn- code->disposition [c]
  (if (= c 0) :commit (if (= c 1) :escalate :hold)))

(defn- code->reason [c]
  (if (= c 1) :phase-disabled (if (= c 2) :phase-approval nil)))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:valuation/finalize` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        op-code (op->code op)
        gov-code (disposition->code governor-disposition)
        d (kernel/phase-disposition p op-code gov-code)
        r (kernel/phase-reason p op-code gov-code)]
    {:disposition (code->disposition d)
     :reason (code->reason r)}))

(defn verdict->disposition
  "Map a Loss Adjustment Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
