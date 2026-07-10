# Ripple — Development Backlog

The prototype proves the foundation. Three phases follow.

## Session log

### 2026-07-10 — Phase 2 simulation: affairs, rumours, building repairs

No `./gradlew` wrapper was checked in, so the README's build instructions
didn't actually work and the test suite had apparently never been run
locally. Added the wrapper (Gradle 9.4.1) and got a real build/test loop
going (JDK 21 via the Android Studio bundled JBR, Android SDK), then worked
three Phase 2 **Simulation** backlog items end to end — implementation, a
`docs/simulation-rules.md` section, and a full local `testDebugUnitTest`
pass after each:

- **Affairs and their discovery.** New `RelationshipKind.AFFAIR`. A
  committed resident can drift into one when their existing partnership is
  *vulnerable* (low affection / high resentment) and not too closely
  watched — *vigilance* is a modifier on the existing `dependency` and
  `resentment` dimensions rather than a new tracked "jealousy" value, so it
  composes with everything already there. Affairs begin `HIDDEN`
  (`AFFAIR_BEGAN`), surface naturally (chance grows with the affair's own
  shared history and the deceived partner's vigilance) or via the `Reveal`
  intervention (now also checks for an ongoing affair, not just hidden
  health conditions), and discovery (`AFFAIR_DISCOVERED`) crashes
  trust/affection, spikes resentment, and has a 55% chance of ending the
  partnership via the existing separation/divorce path. See
  `docs/simulation-rules.md#affairs--jealousy`.
- **Rumour system.** New `RumourSystem`, run every tick. Gossip-worthy
  `PRIVATE` events (arguments, affairs discovered, break-ups, rivalries,
  secrets revealed…) can leak into a new `PUBLIC` `RUMOUR_SPREAD` event,
  which is the *only* way something private reaches `NewspaperGenerator` —
  the paper only ever reads public events. Leak chance comes from severity
  plus how many high-familiarity relationship edges surround those
  involved. ~55% of leaks are accurate paraphrases with a real cause link
  back to the truth; the rest are distorted (wrong resident dragged in,
  story downplayed or inflated) and carry **no** cause link, so the cause
  viewer never shows a false lineage for something that didn't really
  happen that way. See `docs/simulation-rules.md#rumours`.
- **Building lifecycle — repairs.** `Building.condition`, `.value` and
  `.visibleChanges` already existed and storm damage already lowered
  condition, but nothing ever raised it back up. New
  `BuildingLifecycleSystem` runs daily: any non-abandoned building below
  condition 55 looks for a payer (the trading business, or the wealthiest
  resident actually living there for homes) and, if they can afford
  `(100 − condition) × 9`, has a 15%/day chance of getting it fixed —
  condition rises 25–45, `BUILDING_REPAIRED` fires, and "Storm damage"
  clears from `visibleChanges`. Homes below condition 40 now also chip at
  residents' comfort, the same way persistent noise does, so this isn't
  purely cosmetic. Renovation choices, new construction and demolition are
  still open (see backlog item below). See
  `docs/simulation-rules.md#building-lifecycle`.

Also fixed a stale assertion in `CatchUpAndNewspaperTest` — it expected 120
in-game minutes for a 2-hour catch-up, but the documented 1s-real =
1min-game pacing makes the correct value 7200; the test's own comment had
simply confused hours and minutes.

**Discovered and flagged, not fixed** (confirmed pre-existing and unrelated
to the above via code-path analysis — none of them touch the systems
changed this session — and each spun off as a separate background task
rather than scope-creeping into this pass):
- `WorldGeneratorTest` "scenario seeds are planted" — two seeded buildings
  overlap in the default map layout (`WorldGenerator`'s placement logic).
- `GoalAndEconomyTest` "goals form from combined circumstances not
  randomness" — the seeded resident Ash Thistle forms `FIND_JOB` instead of
  the expected `START_BUSINESS` (`GoalSystem` condition logic or the seed
  data has drifted).
- `MigrationTest` "schema v1 matches..." — `FileNotFoundException`; the
  exported Room schema-v1 JSON the test reads was never generated or
  committed (`copyRoomSchemas` shows `NO-SOURCE`).

*(Update: this work was pushed, then merged into `main` and the feature
branch deleted — see the entry below. `main` is now the repo's only branch.)*

### 2026-07-10 — Merge to `main`; education/skill pipeline + returning students

Merged `claude/ripple-android-prototype-a0gsi8` into `main` (clean, no
conflicts), pushed, set `main` as the GitHub default branch, and deleted the
now-redundant feature branch both locally and on origin — `main` is the only
branch left.

Continued with a fourth Phase 2 **Simulation** item, same rigour as above
(implementation + `docs/simulation-rules.md` section + full local test pass):

- [x] **Education & returning students.** Children/teens at school now
  build `SkillType.TEACHING` slowly (`NeedsSystem`, gated by `discipline`).
  Teens who form `LEAVE_FOR_EDUCATION` and leave town at 18 aren't gone for
  good: leaving schedules a `GOAL_SEED` delayed effect (note
  `"returning_student"`, 640–1400 day window) that
  `LifecycleSystem.studentReturns` resolves — a large `TEACHING` boost plus
  a secondary skill matching whichever personality trait runs strongest,
  rehoused (old household if it kept a home, an in-town parent's otherwise),
  a fresh `FIND_JOB` goal, and an `ACHIEVEMENT` memory for any parent still
  around. See `docs/simulation-rules.md#education--returning-students`.

Verified via the full local unit test suite: same 3 pre-existing failures
as before, nothing new. Committed and pushed directly to `main`.

### 2026-07-10 — Richer crime: motives, suspicion, a constable, false accusations

A fifth Phase 2 **Simulation** item, same rigour as the rest:

- [x] **Richer crime.** The old "may be reported" rule always correctly
  penalised the true culprit — perfect information, at odds with the
  "public understanding ≠ facts" principle already built for rumours. New
  `CrimeSystem` keeps one adult resident appointed constable
  (`WorldState.constableResidentId`, highest `honesty×0.6 + courage×0.4`,
  re-appointed if the post falls vacant) and has them investigate
  `CRIME_COMMITTED` by building a motive-weighted suspect pool (dishonesty,
  poor finances, resentment towards the victim's business owner) that
  always contains the true culprit but isn't guaranteed to land on them.
  `CRIME_REPORTED` now carries only what the constable *believes*
  (`payload["accurate"]`) — a false accusation costs the wrongly-accused
  resident more stress/reputation than a true one would, a `HUMILIATION`
  memory, and resentment/trust damage towards the constable, while the
  actual culprit gets away with it (private unease only). See
  `docs/simulation-rules.md#crime--suspicion`.

Verified via the full local unit test suite: same 3 pre-existing failures
as before, nothing new. Committed and pushed directly to `main`. (One test
run hit the Windows gradle-daemon file-lock issue seen earlier in this
session — `bundleDebugClassesToRuntimeJar` failing with
`FileSystemException` because a leftover daemon still held `classes.jar`
open; killing stray `java.exe` processes before retrying fixed it. Not a
code issue.)

**Not attempted this session:** everything else below. The remaining Phase
2 **Product** items need Compose/UI work or external art/audio assets;
Phase 3 and 4 are a substantially larger undertaking (generational systems,
local politics, an LLM narrative layer, etc.).

### 2026-07-10 — Mobile UI rebuild kicked off (Phase 1: town canvas)

The user provided a detailed mobile-UI-rebuild brief plus a desktop-dashboard
mockup as visual reference, explicitly reprioritising work: **pause new
simulation systems, focus on the visual/interaction layer**, reinterpreting
the dense reference (full pixel-art town, newspaper, family tree, town
stats…) as a mobile-first observation experience — full-screen town canvas,
bottom sheets, overlays, not a literal desktop-dashboard port. Full brief
condensed into a new `## Mobile UI rebuild` section below with its own
phase/status tracking; the five phases from the brief (canvas → visual
identity → life animation → information architecture → secondary screens)
are tracked there rather than folded into the Phase 2 Product list above,
since the brief is far more detailed than that list's single bullet.

**Constraint worth flagging:** no Android emulator/device is configured in
this environment, so Compose UI work here is verified by compilation +
the existing Robolectric UI tests (`NavigationTest`, `TownViewModelTest`),
not by looking at it. That's fine for interaction logic (camera behaviour,
state wiring) but risky for anything about *how something looks* (density,
spacing, the modular pixel-art asset system the brief asks for) — those
need actual visual iteration. Checked in with the user about pacing before
going further into the visually-unverifiable parts of the brief.

**Phase 1 (town canvas) progress this session:**
- [x] Camera follow-with-override: the camera now gently, continuously
  tracks the followed resident's eased on-screen position every animation
  frame (`TownCamera.easeToward`, 5%/frame) instead of only snapping once
  on load. Any manual pan or pinch-zoom clears `TownCamera.isFollowing`; a
  "Return to `<name>`" pill appears bottom-centre when tracking is off and
  re-engages it on tap. Jumping to a resident (from People, death-summary,
  etc.) now also re-engages tracking.
- [x] Navigation icons: swapped the four bottom-nav glyphs (`⌂ ☺ ✎ ⧗`, plain
  `Text`) for real Material vector icons (`Icons.Filled.Home/People/
  Newspaper/History`) — addresses the brief's "no emoji, consistent stroke
  weight, custom icons" requirement without needing custom art.
- Already close to the brief before this pass: the town canvas is already
  full-bleed (`Modifier.fillMaxSize()`), the top HUD is already a compact
  translucent chip strip (date/time/weather/pop/nudges, not a dense stats
  bar), the followed-resident indicator is already a small pill (not a full
  banner), time controls are already a compact row, and event/alert banners
  already auto-dismiss (~5s). These weren't rebuilt since they already
  roughly match the brief's intent; revisit if the user wants them
  restyled rather than just re-architected.
- Not yet started: population moved out of the always-visible top strip
  into a dedicated town-overview overlay; time controls collapsed to a
  single expandable pill rather than an always-visible row; event banners
  stacking up to 2 with slide/fade animation (currently one at a time, no
  animated transition); the entire modular pixel-art asset system (building
  variants/condition states, resident appearance/animation, environmental
  props) — buildings and residents are still flat procedural rectangles
  (`SpriteProvider`), which is most of what the brief is actually about
  visually and the biggest remaining gap.

Verified via the full local unit test suite: same 3 pre-existing failures
as before (nothing new), `NavigationTest` still passes. Committed and
pushed directly to `main`.

## Mobile UI rebuild (current priority — supersedes new Phase 2 Simulation work for now)

User brief, 2026-07-10, reference: a dense desktop pixel-art town-management
dashboard mockup. **Reinterpret, don't port**: mobile-first, town-canvas-
dominant, detail lives in overlays/sheets, not permanent panels. Central
experience to preserve: *"Open Ripple. Find the person you follow. Watch
their world continue without you."*

Development order from the brief (status noted inline):

1. **Town canvas** — full-bleed map, pan/zoom, camera follow, compact
   overlays, compact time controls. *In progress — see session log above
   for what's done (camera follow-with-override, vector nav icons) vs. not
   (population out of top strip, collapsible time-control pill, stacked
   animated event banners).*
2. **Visual identity** — modular buildings (footprint × wall × roof ×
   windows × door × chimney × sign × awning × garden × fence × condition
   overlay…), environmental props, resident appearance variation
   (body/hair/clothing/occupation cues), consistent icons, palette/type
   system. *Not started — this is the large one. Buildings/residents are
   currently flat procedural rectangles (`core/ui/SpriteProvider.kt`); the
   brief wants real distinguishable modular pixel art. This needs either a
   genuine asset pipeline or a much richer procedural generator, and ideally
   visual iteration against a real device/emulator — this environment has
   neither.*
3. **Life animation** — resident movement/behaviour states (idle, walk,
   talk, work, eat, sit, sleep, argue, hug, celebrate, mourn, ill, injured,
   carry, wait, run — brief wants 2-4 frames each), town rhythm (shops
   open/close, school run, deliveries, traffic, weather, day/night).
   *Partial groundwork exists (`TownRenderer.poseFor`, weather washes,
   day/night tint) but nowhere near the full state list.*
4. **Mobile information architecture** — resident bottom sheet (compact +
   expanded tabs: Life/Relationships/Memories/Skills/History, "why are they
   doing this?"), building bottom sheet, town overview overlay, event
   details, Outside World overlay. *`TownSheets.kt` already has resident/
   building/event/intervention sheets — brief wants them richer (tabs,
   explicit "why" reasoning surfaced) and a new town-overview + Outside
   World overlay that don't exist yet.*
5. **Secondary screens** — People rebuild (search, non-wrapping filter
   chips, followed-resident card, expandable family tree), News as a real
   newspaper (paper texture, masthead, registers, archive), History as a
   real vertical timeline with cause chains and filters, dedicated family
   tree + relationship-network overlays. *Existing `PeopleScreen`,
   `NewsScreen`, `HistoryScreen` are functional but per the brief "feel like
   unfinished placeholders" relative to the reference's density — not yet
   assessed in detail against the specific acceptance criteria.*

Full acceptance criteria (20 items), palette/typography guidance, and the
complete asset/animation checklists are in the original brief (session
transcript, 2026-07-10) — not reproduced in full here to keep this file
readable; re-derive detail from the brief text as each phase is tackled
rather than duplicating it wholesale into this doc.

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
- [x] Education/skill pipeline for children and teens; returning students
  (Kit-style leavers can come back changed). *Implemented: school builds
  `TEACHING` slowly for children/teens (`NeedsSystem`); `LEAVE_FOR_EDUCATION`
  leavers schedule a 1.8–3.9 year `GOAL_SEED` return
  (`LifecycleSystem.studentReturns`) — rehoused, a large skill boost plus a
  personality-matched secondary skill, fresh `FIND_JOB` goal, parent reunion
  memory. See `docs/simulation-rules.md#education--returning-students`.*
- [x] Richer crime: motives, suspicion, constable NPC role, false
  accusations. *Implemented: `CrimeSystem` keeps a constable appointed
  (`WorldState.constableResidentId`), investigates `CRIME_COMMITTED` with a
  motive-weighted (dishonesty, poor finances, resentment towards the victim)
  suspect pool that always contains the true culprit but isn't guaranteed to
  land on them — `CRIME_REPORTED` carries only what the constable believes
  (`payload["accurate"]`), with real consequences for a wrongly-accused
  resident. See `docs/simulation-rules.md#crime--suspicion`.*
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
