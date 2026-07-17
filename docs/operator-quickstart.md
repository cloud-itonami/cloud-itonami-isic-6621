# Operator Quickstart: Risk and Damage Evaluation

Get the Loss Adjustment Governor running locally in minutes, walk through a demo workflow, and understand how to extend it for your jurisdiction.

## Prerequisites

- **Clojure CLI**: `clojure` 1.11.x or later ([install](https://clojure.org/guides/install_clojure))
- **Git**: for cloning and version control
- **Monorepo dependencies** (if running in-workspace): The project references `langgraph-clj` at `../../kotoba-lang/langgraph` and optionally `cloud-itonami-isic-8291` for corporate-intelligence conflict screening. See `deps.edn` for local/root coordinates; when forking standalone outside the monorepo, update those to use git coordinates instead.

## Run the demo

The demo walks a clean matter through intake → assessment → conflict screening → finalization (always escalates for human approval), then shows three HARD holds that the Governor catches before any human ever sees them.

```bash
clojure -M:dev:run
```

**Expected output**: The demo prints:
1. A clean matter intake (JPN jurisdiction, ready status)
2. Jurisdiction assessment (escalates because finalization always requires human sign-off)
3. Adjuster conflict screening (clean result, still escalates)
4. Valuation finalization proposal (routes to human approval queue)
5. Three HARD holds that never reach a human:
   - Conflict-of-interest hit: a screening result flagging an undisclosed relationship
   - No spec-basis: a jurisdiction with no official methodology on file
   - Missing evidence: a finalization attempt with no prior assessment
6. The immutable audit ledger (every intake/assessment/screening/finalization action)
7. Draft valuation-report records (the proposals the Governor approved to send to a human)

The Governor enforces four checks (three HARD, one soft):
1. **Spec-basis** — all jurisdiction methodology citations must cite an official source
2. **Conflict of interest** — undisclosed adjuster conflicts force a hold, not an override
3. **Evidence incomplete** — required supporting evidence for a finalization must be on file
4. **Confidence floor + actuation** — valuations always escalate (`:actuation` stake never auto-commits)

## Run tests

The test suite verifies the Governor's contract, phase invariants, store parity, and jurisdiction coverage.

```bash
clojure -M:dev:test
```

**Coverage**: Tests run in both Clojure JVM and ClojureScript (optional; requires `org.clojure/clojurescript`).

## Lint

Static analysis checks for errors:

```bash
clojure -M:lint
```

## Architecture overview

| Component | Role | File |
|---|---|---|
| **Adjuster-LLM** | Drafts valuation proposals, checklists, conflict assessments | `src/adjustment/adjusterllm.cljc` |
| **Loss Adjustment Governor** | Independently verifies every proposal against spec-basis, conflicts, and evidence | `src/adjustment/governor.cljc` |
| **Phase engine** | Lifecycle: read-only → assisted intake → assisted assess/screen → supervised finalization | `src/adjustment/phase.cljc` |
| **Store** | Immutable audit ledger + draft proposal history (in-memory or Datomic) | `src/adjustment/store.cljc` |
| **Operation actor** | Langgraph-clj StateGraph orchestrating the workflow | `src/adjustment/operation.cljc` |
| **Facts catalog** | Per-jurisdiction valuation methodology requirements + spec-basis citations | `src/adjustment/facts.cljc` |
| **Registry** | Draft valuation-report record schemas (no fabricated check-digit standards) | `src/adjustment/registry.cljc` |
| **Corporate Intel** | Optional conflict screening via `cloud-itonami-isic-8291` relationship graph | `src/adjustment/corporate_intel.cljc` |

The Governor sits at `adjustment.governor/check-proposal` — it re-verifies every LLM proposal against the facts catalog, the matter's history, and the adjuster's background before the operator's approval workflow even sees it.

## Extend for your jurisdiction

1. **Add jurisdiction facts**: Edit `src/adjustment/facts.cljc` and add a new entry to `:catalog` with your jurisdiction's official valuation-methodology requirements and spec-basis citation.
   - Example: `:USA-NY {:standard "New York Property/Casualty Valuation Manual (2024)" :url "..." :required-evidence [...]}`
   - Never invent a standard; cite a real, publicly available source.

2. **Customize conflict screening**: By default, the demo uses `mock-advisor` and a simple adjuster background check. See `adjustment.adjusterllm/screen-conflict` to inject your own conflict database (or cross-reference `corporate_intel` if you have access to `cloud-itonami-isic-8291`).

3. **Configure the phase table**: Edit `src/adjustment/phase.cljc` to control which operations auto-commit at each phase vs. escalate. By default, `:valuation/finalize` never auto-commits (system property, tested at `test/adjustment/phase_test.clj:valuation-finalize-never-auto-at-any-phase`).

4. **Deploy with a real store**: Replace `MemStore` with `DatomicStore` (requires `langchain.db`) for production persistence and live audit export.

## Demo page

The `index.html` in this directory is the landing page for the project. It links to all documentation and deployment options.

```bash
open docs/index.html
```

## Next steps

- Read [`../README.md`](../README.md) for the full architecture and design rationale.
- Review [`business-model.md`](business-model.md) to understand the customer and offer.
- Check [`operator-guide.md`](operator-guide.md) for production deployment and certification requirements.
- See [`adr/0001-architecture.md`](adr/0001-architecture.md) for the full design history.
- Fork and deploy: `git clone https://github.com/YOUR-ORG/cloud-itonami-isic-6621.git`

## License

AGPL-3.0-or-later. See the repository root for full license text.
