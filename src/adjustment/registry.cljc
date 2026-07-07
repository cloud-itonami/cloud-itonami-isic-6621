(ns adjustment.registry
  "Pure-function valuation-report record construction -- an append-only
  independent loss-adjustment/valuation draft.

  Like `cloud-itonami-isic-6511`'s `underwriting.registry` / `cloud-
  itonami-isic-6512`'s `casualty.registry`, there is no single
  international check-digit standard for an independent valuation-report
  number -- every adjusting firm/jurisdiction assigns its own reference
  format. This namespace does NOT invent one; it builds a jurisdiction-
  scoped sequence number and validates the record's required fields, the
  same honest, non-fabricating discipline `adjustment.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network call
  to any insurer's core-administration or claims system. It builds the
  RECORD an independent adjuster would keep, not the act of finalizing
  the valuation itself (that is `adjustment.operation`'s `:valuation/
  finalize`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed independent adjuster's act, not this actor's. See README
  `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-valuation
  "Validate + construct the VALUATION-REPORT registration DRAFT -- the
  independent adjuster's own act of finalizing and issuing a damage/risk
  valuation, referencing the case it was performed for. Pure function --
  does not touch any real claims-payment/settlement system; it builds
  the RECORD an independent adjuster would keep and actually send to the
  requesting party. `adjustment.governor` independently re-verifies the
  evidence checklist and the assigned adjuster's conflict-of-interest
  status before this is ever allowed to commit."
  [case-reference valuation-amount supporting-evidence jurisdiction sequence]
  (when-not (and case-reference (not= case-reference ""))
    (throw (ex-info "valuation: case-reference required" {})))
  (when (< valuation-amount 0)
    (throw (ex-info "valuation: valuation-amount must be >= 0" {})))
  (when-not (seq supporting-evidence)
    (throw (ex-info "valuation: at least one supporting-evidence item required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "valuation: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "valuation: sequence must be >= 0" {})))
  (let [valuation-number (str (str/upper-case jurisdiction) "-VAL-" (zero-pad sequence 6))
        record {"record_id" valuation-number
                "kind" "valuation-draft"
                "case_reference" case-reference
                "valuation_amount" valuation-amount
                "supporting_evidence" (vec supporting-evidence)
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "valuation_number" valuation-number
     "certificate" (unsigned-certificate "ValuationReportCertificate" valuation-number valuation-number)}))

(defn append
  "Append a valuation record, returning a NEW list (never mutate history
  in place)."
  [history result]
  (conj (vec history) (get result "record")))
