# Ripple — Development Backlog

The prototype proves the foundation. Three phases follow.

## Phase 2 — Depth of life (make watching richer)

**Simulation**
- [x] Affairs and their discovery (`AFFAIR_DISCOVERED` is already an event
  type); jealousy as a relationship dimension modifier. *Implemented:
  `RelationshipKind.AFFAIR`, vulnerability/vigilance modifiers in
  `InteractionSystem`, natural + `Reveal`-intervention discovery, fallout via
  `ConsequenceEngine`. See `docs/simulation-rules.md#affairs--jealousy`.*
- [x] Rumour system: private events leak along high-familiarity edges with
  accuracy loss; the newspaper can then be *wrong* (public understanding ≠
  facts, as designed). *Implemented: `RumourSystem`, `RUMOUR_SPREAD` events.
  See `docs/simulation-rules.md#rumours`.*
- Education/skill pipeline for children and teens; returning students
  (Kit-style leavers can come back changed).
- Richer crime: motives, suspicion, constable NPC role, false accusations.
- [~] Building lifecycle: repairs, renovation choices by owners, new
  construction on empty lots, demolition. *Repairs implemented
  (`BuildingLifecycleSystem`, `BUILDING_REPAIRED`, condition now affects home
  comfort) — see `docs/simulation-rules.md#building-lifecycle`. Renovation
  choices, new construction and demolition still open; the latter two need
  map/tile placement work, which is riskier while the building-overlap bug
  below is unresolved.*
- Seasonal events: harvest fair, winter market, floods by the river tiles.

**Product**
- Family tree visualisation (proper generational graph) and a relationship
  map canvas on the People screen.
- Cause viewer as a dedicated branching timeline UI (multi-parent display).
- Follow "moments": short vignette cards when the followed resident does
  something notable.
- Real local notifications (opt-in, POST_NOTIFICATIONS permission flow) for
  followed/favourite residents only, delivered on app open or via WorkManager
  summary — still no continuous background work.
- Sprite atlas support in `SpriteProvider` + commissioned pixel art replacing
  the procedural placeholders; walk cycles with 4 frames and directions.
- Sound: gentle ambient loops by time-of-day/weather.
- Benchmarks in CI (macrobenchmark for town rendering, JMH-style micro for
  ticks).

## Phase 3 — A town with a memory (systems that compound)

- Generational play: family reputation, inherited trauma/beliefs from
  memories, heirlooms; the death-of-followed flow grows into an "era summary".
- Local politics: council seats, petitions (noise, rents), policy effects on
  the economy; elections become campaigns influenced by reputation events.
- Economy v2: prices that move, property market (residents actually buy/sell
  homes), business succession and rivalries.
- Multiple towns: `World` already separates from `Town`; add a second map and
  slow migration between towns.
- Counterfactual viewer ("what nearly happened"): replay a checkpoint with
  one intervention removed — the deterministic engine makes this cheap.
  Strictly a *viewer*; never shown as future prediction.
- Cloud save via the existing `CloudSaveRepository` seam.
- Modding-friendly data: consequence rules and goal templates as data files.

## Phase 4 — The outside world (the seams come alive)

- `ExternalWorldEventProvider`: curated, abstracted real-world pressure feed
  (fuel prices rise → delivery costs rise → the chain the prototype already
  models). Strict mapping through `WorldPressureMapper`; no real names,
  no politics-of-the-day.
- `NarrativeTextProvider` / `DialogueProvider`: an LLM narrative layer that
  writes flavour prose and dialogue *from* facts, never creating facts —
  enforced by the existing engine-only-mutates rule and validated against the
  event log.
- National layer: lightweight country context (taxes, trends) as pressures.
- Shareable town chronicles: export a family's saga as text/images.

## Engineering debt to pay alongside

- Split packages into real Gradle modules once a second app target appears.
- Move mirror writes to incremental dirty-tracking instead of full rewrite at
  checkpoint.
- Pathfinding: replace L-paths with cached A* over the road graph when maps
  grow.
- Schema v2 migration + migration tests (baseline harness already in place).
- Baseline profile + R8 tuning for release builds.
- No gradle wrapper was checked in (`./gradlew` from the README didn't
  actually work) — now added. First real local test run surfaced three
  pre-existing failures, confirmed unrelated to Phase 2 simulation work via
  code-path analysis and flagged as background tasks: `WorldGeneratorTest`
  "scenario seeds are planted" (two seeded buildings overlap in the default
  map layout), `GoalAndEconomyTest` "goals form from combined circumstances
  not randomness" (Ash Thistle forms `FIND_JOB` instead of the expected
  `START_BUSINESS`), and `MigrationTest` "schema v1 matches..." (the
  exported Room schema-v1 JSON the test reads was never generated/committed).
