# cloud-itonami-isic-6621

Open Business Blueprint for **ISIC Rev.5 6621**: Risk and damage
evaluation. This repository publishes an independent loss-adjustment/
valuation execution actor as an OSS business that any qualified,
licensed operator can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
[`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511)
(life insurance) and [`cloud-itonami-isic-6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512)
(non-life insurance). Here it is **Adjuster-LLM ⊣ Loss Adjustment
Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> valuation-methodology checklist, normalizing matter intake, and
> flagging a thin evidence file -- but it has **no notion of which
> jurisdiction's valuation standard is official, no license to appraise,
> and no way to know on its own whether the assigned adjuster has an
> undisclosed conflict of interest with the party requesting the
> evaluation** -- the ONE thing that makes this business "independent"
> at all. Letting it finalize a valuation directly invites fabricated
> methodology citations, laundering a conflicted adjuster's opinion into
> a real settlement input, and silent liability for whoever runs it.
> This project seals the Adjuster-LLM into a single node and wraps it
> with an independent **Loss Adjustment Governor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor drafts and governs an independent loss-adjustment workflow:
matter intake, per-jurisdiction valuation-methodology/evidence
checklisting, adjuster conflict-of-interest screening, and a valuation-
finalization proposal. It does **not**, by itself, hold a license to
practice as an independent adjuster/appraiser in any jurisdiction, and
it does not claim to. Whoever deploys and operates a live instance (a
licensed independent adjuster, a loss-adjusting firm) supplies the
jurisdiction-specific license, the real methodology/certification
program and the real damage-inspection expertise, and bears that
jurisdiction's liability -- the software supplies the governed,
spec-cited, audited execution scaffold so that operator does not have
to build the compliance layer from scratch for every new market.

### Actuation

**Finalizing and issuing a real valuation report is never autonomous,
at any phase, by construction.** Two independent layers enforce this
(`adjustment.governor`'s `:actuation` high-stakes gate and
`adjustment.phase`'s phase table, which never puts `:valuation/finalize`
in any phase's `:auto` set) -- see `adjustment.phase`'s docstring and
`test/adjustment/phase_test.clj`'s `valuation-finalize-never-auto-at-
any-phase`. The actor may draft, check, screen and recommend; a human
operator (a licensed independent adjuster) is always the one who
actually finalizes and issues a valuation.

## The core contract

```
matter intake + jurisdiction facts (adjustment.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Adjuster-LLM │ ─────────────▶ │ Loss Adjustment         │  (independent system)
   │  (sealed)    │  + citations    │ Governor: spec-basis ·  │
   └──────────────┘                 │ conflict-of-interest ·  │
                             commit ◀────┼──────────▶ hold │ evidence-incomplete
                                 │             │           │ (all un-overridable)
                           record + ledger  escalate ─▶ human
                                             (ALWAYS for
                                              :valuation/finalize)
```

**The Adjuster-LLM never finalizes a valuation the Loss Adjustment
Governor would reject, and never finalizes without a human sign-off.**
Hard violations (fabricated jurisdiction methodology / conflict of
interest / unsupported valuation) force **hold** and *cannot* be
approved past; a clean finalization proposal still always routes to a
human.

## Run

```bash
clojure -M:dev:run     # walk one clean matter through intake -> finalization + three HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a damage-inspection drone
captures site imagery and sensor readings for the human adjuster's
evaluation, under the actor, gated by the independent **Loss Adjustment
Governor**. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Loss Adjustment Governor, valuation-report draft record, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6621`). Related capability contracts (policy/premium/claim shapes) are
published as [`kotoba-lang/insurance`](https://github.com/kotoba-lang/insurance);
this actor's `adjustment.*` namespaces are a self-contained governed
implementation -- it does not require the capability lib directly, the
same "self-contained sibling" relationship `cloud-itonami-isic-6511`'s
`underwriting.*` and `cloud-itonami-isic-6512`'s `casualty.*` have
toward the same lib.

## Layout

| File | Role |
|---|---|
| `src/adjustment/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + valuation-report history |
| `src/adjustment/registry.cljc` | Valuation-report draft records (no fabricated international check-digit standard -- see docstring) |
| `src/adjustment/facts.cljc` | Per-jurisdiction valuation-methodology requirement catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/adjustment/adjusterllm.cljc` | **Adjuster-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/conflict-screening/finalization proposals |
| `src/adjustment/governor.cljc` | **Loss Adjustment Governor** -- 3 HARD checks (spec-basis · conflict-of-interest · evidence-incomplete) + 1 soft (confidence/actuation gate) |
| `src/adjustment/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess/screen → supervised (finalization always human; matter intake auto-eligible, no liability risk) |
| `src/adjustment/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/adjustment/sim.cljc` | demo driver |
| `test/adjustment/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers matter intake through valuation finalization -- the
core governed lifecycle this blueprint's own `docs/business-model.md`
names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Matter intake + per-jurisdiction valuation-methodology/evidence checklisting, HARD-gated on an official spec-basis citation (`:matter/intake`/`:jurisdiction/assess`) | Inspection scheduling/dispatch (a real-world physical-logistics act -- see "Robotics premise" -- not modeled as a governed data op) |
| Adjuster conflict-of-interest screening, HARD-gated un-overridable on any hit (`:conflict/screen`) | Real transfer-agent/banking-payment integration, tax/regulatory reporting |
| Valuation-finalization proposal, independently checked against the jurisdiction's own required-evidence checklist (`:valuation/finalize`) | Multi-adjuster peer-review/dispute-resolution workflows |
| Immutable audit ledger for every intake/assessment/screening/finalization decision | |

Extending coverage is additive: add the next gate as its own governed
op with its own HARD checks and tests, following the SAME "an
independent governor re-verifies against the actor's own records before
any real-world act" pattern this repo's one flagship op already
establishes.

## Jurisdiction coverage (honest)

`adjustment.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `adjustment.facts/catalog` --
currently 4 seeded (JPN, USA-NY, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `adjustment.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `Adjuster-LLM` + `Loss Adjustment Governor` run as
real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the sibling
`cloud-itonami-isic-6511`/`6512`'s architecture. See
`docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
