# ADR-0001: cloud-itonami-isic-6621 -- Adjuster-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511` ADR-0001 (Underwriter-LLM ⊣
  UnderwritingGovernor, the pattern this ADR ports), `cloud-itonami-isic-
  6512` ADR-0001 (Underwriter-LLM ⊣ Non-Life Insurance Governor, the
  most recent sibling port and the source of the sanctions/conflict-
  check discipline this ADR reuses), ADR-2607032000 (`cloud-itonami`
  insurance (ISIC 65/66) + real-estate (ISIC 68) coverage push -- the
  blueprint scaffold this ADR deepens), langgraph-clj ADR-0001 (Pregel
  superstep + interrupt + Datomic checkpoint)
- Context: `cloud-itonami-isic-6621` published a business/operator-model
  blueprint (ADR-2607032000's insurance coverage push) but stopped at
  `:blueprint` maturity -- no governed actor implementation. This ADR
  deepens it to `:implemented`, the third insurance-adjacent actor in
  the fleet (after `6511` life insurance and `6512` non-life insurance),
  chosen as the next candidate after `6512` for the SAME reason `6512`
  was chosen after the VC-fund system: a close architectural sibling
  already exists, and this blueprint's own README/business-model.md
  already name the exact actor shape (Adjuster-LLM ⊣ Loss Adjustment
  Governor) to build.

## Problem

Independent loss adjustment/risk evaluation needs THREE different kinds
of judgment, one of them genuinely unlike anything `6511`/`6512` check
for:

1. **Jurisdiction valuation-methodology correctness** -- is the required
   evidence/methodology based on an official valuation-standard or
   licensing-authority source?
2. **Independence itself** -- does the adjuster ASSIGNED to a matter
   have an undisclosed conflict of interest with the party requesting
   the evaluation? Unlike `6511`/`6512`'s sanctions/PEP screening (is
   THIS PARTY on a blocklist?), this is a screening of the EVALUATOR
   against the matter they are evaluating -- the entire premise of an
   "independent" adjusting business is that this check exists and is
   un-overridable.
3. **Real actuation** -- actually finalizing and issuing a valuation
   report: an irreversible real-world act (an insurer or insured will
   rely on it for a real settlement).

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run loss adjustment with an LLM" but "seal the
LLM inside a trust boundary and layer methodology-authenticity,
independence, evidence-completeness, audit and human-approval on top of
it, while structurally fixing real actuation as human-only."

## Decision

### 1. Adjuster-LLM is sealed into the bottom node; it never finalizes directly

`adjustment.adjusterllm` returns exactly four kinds of proposal: intake
normalization, jurisdiction valuation-methodology checklist, conflict-
of-interest screening, and valuation-finalization proposal. No proposal
writes the SSoT or issues a real valuation report directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 loss-adjustment operation

`adjustment.operation/build` is the SAME StateGraph shape as
`cloud-itonami-isic-6511`'s `underwriting.operation` / `cloud-itonami-
isic-6512`'s `casualty.operation`, copied verbatim -- this graph shape
is entirely generic across every op this actor performs. One graph run
corresponds to one loss-adjustment operation, with no unbounded inner
loop.

### 3. Loss Adjustment Governor is a separate system from Adjuster-LLM

`adjustment.governor` has four checks: spec-basis · conflict-of-
interest · evidence-incomplete (HARD, un-overridable) + confidence-
floor/actuation-gate (SOFT, human decides).

### 4. Conflict-of-interest screening mirrors sanctions screening's SHAPE, but the check must remain UNCONDITIONAL on op

`conflict-violations` deliberately copies the exact structure `casualty.
governor`'s `sanctions-violations` settled on AFTER a real bug was
caught in that repo's own R0 build (see that repo's ADR): the
`hit-in-proposal?` branch (does THIS proposal itself, e.g. a `:conflict/
screen`, report a hit) is evaluated UNCONDITIONALLY -- it is NOT scoped
to any specific op -- while the SEPARATE `hit-on-file?` branch (a prior
hit already on record, checked at finalization time) IS scoped to
`:valuation/finalize`. Writing this function from scratch with the
LESSON already in hand (rather than re-deriving it) avoided repeating
the same class of bug this ADR's own sibling had to catch via demo
verification.

### 5. Real actuation is structurally always human-only (enforced by two independent layers)

`adjustment.governor`'s actuation gate (`:stake :actuation` always
escalates) and `adjustment.phase`'s phase table (`:valuation/finalize`
is never a member of any phase's `:auto` set) both prevent a real
valuation finalization from ever auto-committing. Neither depends on
the other being implemented correctly.

### 6. No fabricated international valuation-number standard

Same discipline as `cloud-itonami-isic-6511`'s `underwriting.registry` /
`cloud-itonami-isic-6512`'s `casualty.registry`: there is no single
international check-digit standard for an independent valuation-report
number. `adjustment.registry` therefore does not invent one; it
validates required fields and assigns a jurisdiction-scoped sequence
number only.

### 7. Relationship to `kotoba-lang/insurance`

`kotoba-lang/insurance` (the blueprint-tier capability lib backing all 7
insurance ISIC classes) publishes pure policy/premium/claim/underwriting-
decision contracts with no governor or human-approval workflow.
`adjustment.*` is a self-contained governed implementation for this one
class -- the same relationship `cloud-itonami-isic-6511`'s
`underwriting.*` / `cloud-itonami-isic-6512`'s `casualty.*` have to the
same lib.

## Consequences

- (+) Independent loss adjustment gets the same governed, auditable-
  actor treatment as life insurance (`cloud-itonami-isic-6511`) and
  non-life insurance (`cloud-itonami-isic-6512`), without centralizing
  liability in one vendor -- any licensed independent adjuster/firm can
  fork and run their own instance.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/adjustment/phase_test.clj`'s `valuation-
  finalize-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by
  `test/adjustment/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern `underwriting.store`/`casualty.store` use.
- (+) Applying the conflict-of-interest check's lesson from `6512`'s own
  ADR up front meant this build's demo and test suite passed on the
  FIRST run with no HARD-check-scoping bug -- a direct payoff of writing
  down what went wrong in the sibling repo rather than only fixing it
  there.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA-NY, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `adjustment.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) Inspection scheduling/dispatch (a real-world physical-logistics
  act), real banking/tax/regulatory integration, and multi-adjuster
  peer-review/dispute-resolution workflows are all out of scope for this
  OSS actor -- each operator's responsibility (see README's coverage
  table).
- 24 tests / 106 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6621` at `:blueprint` only | ❌ | Leaves independent loss adjustment without an `:implemented` reference actor, unlike its insurance siblings |
| Model conflict-of-interest screening as a variant of KYC/sanctions screening with no changes | ❌ | The semantics differ (is the EVALUATOR compromised, not whether a party is on a blocklist) even though the code shape is identical; naming the checks/fields for what they actually mean (`:conflict-hit?`/`:disclosure-doc` vs `:sanctions-hit?`/`:id-doc`) keeps the domain honest |
| Require `kotoba.insurance` (the capability lib) directly from `adjustment.*` | ❌ | Neither sibling actor requires its capability lib directly; keeping the actor self-contained matches the established pattern |
| Fabricate a global valuation-report check-digit standard for conformance-test rigor | ❌ | No such standard exists for independent loss-adjustment valuation reports; inventing one would be dishonest |
