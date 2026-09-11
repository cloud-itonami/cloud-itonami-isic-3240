# cloud-itonami-isic-3240: Manufacture of games and toys

Open Business Blueprint for **ISIC 3240**: manufacture of games and toys — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **games-and-toys plant operations**: production-batch data logging (product-type/safety-test-pass-percent/weight/defect-rate), injection-molding/assembly-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for games-and-toys-plant
plant operations: run by a qualified operator so a plant keeps its
own operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not molding/assembly-line control

ISIC 3240 covers the **games-and-toys plant** that injection-molds,
assembles, and safety-tests plastic toys, wooden toys, board games,
and puzzles. This actor coordinates the back-office record keeping
around that plant — it never touches the molding/assembly-line
equipment directly, and it is never the toy-safety-certification
authority (e.g. an accredited testing/certification body applying
ASTM F963 / EN 71 or an equivalent national/regional standard) that
certifies a product's compliance with an applicable toy-safety
standard.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — molding/assembly/safety-test batch, output-quality data logging (product-type/safety-test-pass-percent/weight/defect-rate; administrative, not an operational decision)
- `:schedule-maintenance` — molding/assembly-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a choking-hazard/materials-safety (e.g. lead paint, phthalate)/small-parts concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety- and child-consumer-
protection-relevant domain** (molding/assembly-line equipment,
choking-hazard/small-parts/materials-safety compliance, direct
downstream consequence for child users of the finished toys and
games):

- Does NOT control molding or assembly equipment directly
- Does NOT make plant-safety or toy-safety-certification decisions (that's the plant supervisor's / accredited testing-and-certification body's exclusive human/institutional authority)
- Does NOT actuate molding/assembly-line equipment (human plant supervisor decides)
- Does NOT self-issue a toy-safety compliance certification (e.g. ASTM F963 / EN 71 — the accredited testing/certification body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and toy-safety certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`toysmfg.operation/build`, a langgraph-clj StateGraph):
1. **`toysmfg.advisor`** (sealed intelligence node, `ToysGamesAdvisor`): proposes decisions only, never commits
2. **`toysmfg.governor`** (independent, `Toys & Games Plant Operations Governor`): validates against domain rules, re-derived from `toysmfg.registry`'s pure functions and `toysmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct molding/assembly-line-equipment control)
     - Directly actuating molding/assembly-line equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing a toy-safety compliance certification (`:issue-safety-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically/certifiably implausible `:safety-test-pass-percent` value on a production-batch patch
     - No physically implausible `:weight-grams` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`toysmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`toysmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
kbb -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
kbb -M:dev:test

# Run the demo
kbb -M:dev:run

# Lint
kbb -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
