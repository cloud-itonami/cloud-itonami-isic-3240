# ADR-0001: ToysGamesAdvisor ⊣ Toys & Games Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-3240` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-3240` publishes an OSS blueprint for
games-and-toys-plant **plant operations coordination**
(production-batch product-type/safety-test-pass-percent/weight/
defect-rate data logging, injection-molding/assembly-equipment
maintenance scheduling, safety-concern flagging, and outbound product
shipment coordination). Like every actor in this fleet, the blueprint
alone is not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the same
langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

Identity ({:id "3240" :name "Manufacture of games and toys"}) was
independently verified against a fresh clone of `kotoba-lang/
industry`'s `resources/kotoba/industry/registry.edn` before any work
began, per this fleet's ID/name-mismatch caution (prior agents in this
fleet have mislabeled their assigned ISIC class). The entry's own
`:repo` field pointed at a stale, never-created `gftdcojp/cloud-
itonami-C3240` placeholder; the real `cloud-itonami` org target name
was independently confirmed 404 via `gh api repos/cloud-itonami/
cloud-itonami-isic-3240` before scaffolding began.

The closest domain analog is `cloud-itonami-isic-3230` (Manufacture of
sports goods): both are back-office coordination actors for a fixed
processing PLANT with molding/assembly production equipment and a
real safety/consumer-protection dimension, and both share the same
four-op shape (`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination). This build mirrors
`cloud-itonami-isic-3230`'s architecture closely but adapts the hazard
profile and equipment/product vocabulary to the games-and-toys plant:
this vertical's central physical hazard is molding/assembly-equipment
operation (the same as 3230), and its central consumer-protection
dimension is toy-safety standard compliance (choking-hazard/small-
parts/lead-paint/phthalate testing per ASTM F963 / EN 71) for
plastic/wooden toys, board games, and puzzles (rather than 3230's
impact-protection/product-safety compliance for protective gear and
fitness equipment) -- and this vertical additionally carries a
CHILD-consumer-protection dimension 3230 does not, since the finished
products are used directly by children; its permanent
equipment-actuation block guards the same molding/assembly-line
EQUIPMENT vocabulary (`:actuate-equipment?`) as 3230; its
production-batch record declares a `:product-type` (closed set
spanning plastic-toy/wooden-toy/board-game/puzzle, replacing 3230's
ball/racket/protective-gear/fitness-equipment) and a
`:safety-test-pass-percent` (a compliance-percentage-against-standard-
threshold analog of 3230's impact-rating-percent, plausibility-checked
1-100) and a `:weight-grams` reading (plausibility-checked above 0 up
to the same 1,000,000g/1-tonne per-batch ceiling, reflecting this
vertical's own large-playset product category) in addition to a
`:defect-rate-percent`, the same shape 3230 uses; and its shipment
quantity is tracked in finished-goods-piece UNITS
(`:units`/`:quantity-units`/`:shipped-units`), the same counted-not-
weighed shape 3230 uses.

This vertical has a DOMAIN-SPECIFIC permanent block mirroring 3230's
impact-protection-certification block but for the toy-safety
regulatory/attestation regime: manufacture of games and toys is
subject to toy-safety compliance certification (the compliance mark
an accredited testing/certification body applies to certify a product
meets an applicable toy-safety standard, e.g. ASTM F963 (US) / EN 71
(EU) or equivalent national/regional certification regimes covering
choking-hazard/small-parts/lead-paint/phthalate testing). This actor
is never the toy-safety-certification authority -- any proposal
(regardless of op) that declares `:issue-safety-certification? true`
is a HARD, PERMANENT, unconditional block
(`toysmfg.governor/safety-certification-authority-blocked-violations`),
the same "no phase, no human override" posture as the equipment-
actuation block.

This vertical has NO pre-existing `kotoba-lang/toysmfg`-style
capability library to wrap (verified: no such repo exists, and no
`toy`/`toys`/`game`/`games`-named manufacturing-capability repo exists
in `kotoba-lang` either, via GitHub code/repo search). This build
therefore uses self-contained domain logic -- pure functions in
`toysmfg.registry` (equipment/batch verification, shipment-quantity
recompute, product-type validation, safety-test-pass-percent
plausibility validation, weight-grams plausibility validation,
defect-rate plausibility validation) are re-verified independently by
the governor, the same "ground truth, not self-report" discipline
established across prior actors (most directly `cloud-itonami-isic-
3230`'s `sportsgoodsmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:toys-games-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "toys-games-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external games-and-toys-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
games-and-toys-plant vertical has NO pre-existing capability library
to wrap. The equipment/batch-verification / shipment-quantity /
product-type / safety-test-pass-percent / weight-grams / defect-rate
validation functions live as pure functions in `toysmfg.registry` and
are re-verified independently by `toysmfg.governor` -- the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-3230`'s
`sportsgoodsmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of games-and-toys-
plant plant operations. It does NOT:
- Control molding or assembly equipment directly
- Make plant-safety or toy-safety-certification decisions (exclusive to the human plant supervisor / accredited testing-and-certification body)
- Actuate molding/assembly-line equipment
- Self-issue a toy-safety compliance certification

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY/CHILD-CONSUMER-PROTECTION BOUNDARY**:
games-and-toys manufacturing is a safety- and child-consumer-
protection-relevant domain (molding/assembly-line equipment hazard,
choking-hazard/small-parts/materials-safety -- lead paint, phthalate
-- compliance for plastic/wooden toys, board games, and puzzles used
directly by children). Safety-concern flagging NEVER auto-commits.
All safety concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (choking-hazard, materials-safety -- lead
paint, phthalate -- or small-parts concern) ALWAYS escalates, never
auto-commits. This is not a "low-stakes proposal" — it is a
circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-3230`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether
a batch's own recorded shipped-to-date unit quantity plus the
proposal's own claimed unit quantity would exceed the batch's own
recorded production quantity — never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into thirteen concrete
checks in `toysmfg.governor`, mirroring `cloud-itonami-isic-3230`'s
own elaboration of its HARD invariants into concrete checks, adapted
to this vertical's safety-test-pass-percent/weight plausibility
fields) block proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct molding/assembly-line-equipment control, equipment actuation, or self-issued toy-safety compliance certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Games-and-toys-plant plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no toy-safety compliance certification can
ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into thirteen concrete governor checks) protect against scope creep
into unauthorized equipment operation, equipment actuation, or
toy-safety-certification self-issuance. Safety concerns are a
circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory. This matters more than usual here because
the finished products are used directly by children.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and toy-safety
certification issuance remain human-/institution-controlled via
external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-3240`: `kbb -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `kbb -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, safety-certification-authority-blocked, already-scheduled,
  invalid-product-type, invalid-safety-test-pass-percent,
  invalid-weight, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `kbb -M:test` resolves offline inside the monorepo checkout.
