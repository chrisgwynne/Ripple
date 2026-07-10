# Ripple

*Watch a world live. Touch it carefully. Live with what follows.*

Ripple is an observation-first life simulation for Android. A small town —
**Ashcombe** by default — keeps living whether you watch or not. You follow one
resident at a time, read the town's weekly paper, trace why things happened,
and very occasionally spend a *nudge* to tilt circumstances. You never control
anyone; the engine decides what follows.

This repository contains the complete first prototype: a living pixel-art town
of 30 fully-simulated residents (plus ~60 lightweight background residents),
homes, businesses, a school, clinic, town hall, park, factory and cemetery,
with autonomous decisions, relationships, an economy, health and death, an
event-sourced history with cause chains, delayed consequences, limited player
interventions, a generated newspaper, and full offline persistence.

## Building

```bash
# Requires JDK 17+ and the Android SDK (compileSdk 35).
# Point local.properties at your SDK, then:
./gradlew assembleDebug        # build the APK
./gradlew testDebugUnitTest    # run the full test suite (JVM + Robolectric)
```

Min SDK 26, target SDK 35. No permissions are requested; the app is fully
offline and uses no network, no accounts, no foreground services.

## Architecture

Single Gradle module (`:app`) with strict package layering — the packages map
one-to-one onto the future module split and dependency arrows only point
downwards:

```
com.ripple.town
├── core/model        Pure Kotlin domain: Resident, Building, Relationship,
│                     WorldEvent, DelayedEffect, Goal, Memory, WorldState…
│                     Fully kotlinx-serializable. No Android imports.
├── core/simulation   The deterministic engine. SimulationCoordinator runs a
│                     fixed 15-step tick pipeline over WorldState. No Android,
│                     no Compose — plain Kotlin, tested on the JVM.
├── core/database     Room: 22 entities, DAOs, mappers. Event log +
│                     checkpoints are the durable source of truth.
├── core/ui           Theme (warm pixel-art palette), procedural SpriteProvider,
│                     shared composables.
├── data              WorldRepository (engine ⇄ Room ⇄ UI snapshots),
│                     SettingsRepository (DataStore), immutable WorldUi models.
├── feature/town      Full-screen Canvas town renderer (pan/zoom/tap), HUD,
│                     resident/building/event/intervention bottom sheets.
├── feature/people    Follow list, favourites, family, friends, search.
├── feature/news      The generated weekly newspaper + archive.
├── feature/history   Town chronicle, importance timeline, cause viewer entry.
├── feature/onboarding  Three-step first launch.
├── feature/settings  A deliberately tiny settings sheet.
├── work              WorkManager catch-up worker (bounded, on-demand).
└── di                Hilt modules, including no-op future-architecture seams.
```

**MVVM + unidirectional data**: ViewModels expose `StateFlow`s; Compose reads
only immutable `WorldUi` snapshots rebuilt after each simulation batch. The
mutable engine state is confined to a single dispatcher inside
`WorldRepository` and is never touched by UI code.

### Key design decisions

- **Determinism.** All randomness flows through `SimRandom` (SplitMix64),
  seeded per tick from `(worldSeed, tickNumber)` and consumed in stable
  iteration order. Same seed ⇒ same town ⇒ same future. Verified by tests that
  serialise entire world states and compare bytes.
- **Event sourcing.** Every meaningful change emits a `WorldEvent` with typed
  payload, severity, visibility (public/private/hidden) and **cause links**.
  News, History and the "Why did this happen?" viewer are all queries over the
  log. Events gain historical importance retroactively when later events cite
  them as causes.
- **Consequences, not scripts.** `ConsequenceEngine` holds small reusable
  rules (job loss → stress now, *maybe* crime temptation in 5–40 days if still
  poor). `DelayedEffect`s wait in a window, decay, check conditions, and fire
  probabilistically — chains compose instead of being authored.
- **Interventions never guarantee outcomes.** Every verb (Delay, Introduce,
  Reveal, Inspire, Warn…) only alters circumstance: timings, chances,
  awareness, idea seeds. The ordinary engine resolves everything downstream.
  Interventions are recorded permanently but stay invisible in cause chains
  until their consequences surface.
- **Two simulation levels.** 30 detailed residents run the full pipeline;
  background residents tick statistically and get **promoted** to detailed
  when hired, followed, or swept into a major event.
- **Persistence & catch-up.** The engine checkpoints the full serialised
  `WorldState` every 6 in-game hours and on backgrounding; normalized mirrors
  keep the world queryable at rest. On reopen, elapsed real time is converted
  to game time (1 s ≈ 1 min), **capped at 30 in-game days**, and replayed in
  bounded batches behind a progress overlay. No background simulation ever runs.
- **The narrative layer can never mutate facts.** The future-architecture seams
  (`NarrativeTextProvider`, `DialogueProvider`, `ExternalWorldEventProvider`,
  `WorldPressureMapper`, `CloudSaveRepository`) may describe the world or feed
  abstract pressures; only the engine writes state. Local no-ops ship today.

### Performance

- One tick = 10 in-game minutes; at 1× a tick fires every 10 real seconds.
- Bounded work everywhere: max 8 social interactions, 6 delayed effects and
  24 consequence-rule applications per tick; catch-up runs ≤ 12 ticks per
  runner wake-up.
- Rendering: the ground layer is pre-baked into one bitmap per map; buildings
  and residents draw from cached, nearest-neighbour-scaled sprite bitmaps.
  A ~11 fps animation clock eases sprite positions between simulation
  snapshots, so visual liveliness is decoupled from simulation rate.
- A benchmark test asserts a tick stays well under a frame budget.

## Documentation

- [`docs/simulation-rules.md`](docs/simulation-rules.md) — every system's
  rules, formulas and tuning constants.
- [`docs/backlog.md`](docs/backlog.md) — the next three development phases.

## Testing

`./gradlew testDebugUnitTest` runs ~50 tests: deterministic generation and
evolution, checkpoint-restore equivalence, utility-AI scoring, relationship
dynamics, consequence chains with valid cause links, delayed-effect windows
and conditions, intervention limits/cooldowns/non-guarantees, death and
inheritance invariants (no dead workers, no employed children, no
over-capacity businesses), newspaper generation and archiving, offline
catch-up caps, DAO behaviour, schema-v1 migration baseline, ViewModel logic,
and Compose navigation over the production nav scaffold.

## Content notes

All residents, names, businesses and events are fictional. Health simulation
is deliberately generalised and fictionalised — it is a game system, not
medical guidance.
