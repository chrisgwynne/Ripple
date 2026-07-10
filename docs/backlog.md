# Ripple — Development Backlog

The prototype proves the foundation. Three phases follow.

## Session log

### 2026-07-10 — NarrativeTextProvider/DialogueProvider (last open Phase 4 item)

The last remaining open Phase 4 backlog item: "an LLM narrative layer that
writes flavour prose and dialogue *from* facts, never creating facts —
enforced by the existing engine-only-mutates rule and validated against the
event log." Had the checkout to itself this round — no concurrent agent, no
file-contention risk. No `./gradlew` or `git` commands run, per this
session's constraints — code-only, ready for the orchestrating session to
build/test/commit.

- **Scoping decision, stated upfront.** A real LLM-backed implementation
  needs an API key/budget/model choice that is the user's call, not
  something to build blind — that decision was explicitly out of scope this
  pass. But the codebase already anticipates exactly this seam:
  `NarrativeTextProvider`/`DialogueProvider` interfaces already existed in
  `core/simulation/providers/FutureProviders.kt`, DI-wired in `di/
  AppModule.kt`, bound to `NoOpNarrativeTextProvider`/`NoOpDialogueProvider`
  (return `null`/nothing). Confirmed via grep, as instructed, that neither
  was ever actually called anywhere in the UI — wired in DI but genuinely
  unused. The task: build a real, deterministic, template-based **default**
  implementation of both (no LLM call, no network, no API key) and wire it
  in for real, not leave it unused a second time.
- **Read the instructed files first, in full**, per the task brief:
  `data/ChronicleBuilder.kt` (this session's closest and most relevant
  precedent — its own header doc comment explicitly calls itself "a
  scoped-down sibling of the still-open `NarrativeTextProvider`/
  `DialogueProvider` backlog item"), `core/simulation/NewspaperGenerator.kt`
  (headline/body generation from `WorldEvent`s, with deterministic
  `rng.pick` template variation), `core/model/WorldEvent.kt`'s `EventType`
  enum, and `core/model/Resident.kt`'s `Personality`/`skills`/`memories`.
  Also checked `ImportanceScorer.baseImportance`'s and
  `NewspaperGenerator.headlineFor`'s `when` blocks for which `EventType`
  values already get special treatment — used as the prioritisation signal
  the brief pointed at for which types deserve their own templates versus
  the generic fallback.
- **`TemplateNarrativeTextProvider`/`TemplateDialogueProvider`** — one new
  file, `core/simulation/providers/TemplateProviders.kt`. Both are pure
  reads: `elaborate(event, state)` fills one of several fixed sentence
  templates per `EventType` (26 of the ~55 event types get dedicated
  templates — the ones already special-cased in `ImportanceScorer`/
  `NewspaperGenerator` — everything else falls back to three generic
  templates) from real fields: involved residents' names/occupations
  (`state.resident(id)`), `event.severity` (bucketed into an adverb:
  barely/quietly/deeply/profoundly), time of day (`SimTime.timeOfDay`) and
  weather (`state.weather`) from live `WorldState`, and a name-checked nod
  to the event's first `causeIds` entry if one exists (never inventing a
  cause — only ever reading one the engine already recorded).
  `lineFor(residentId, situation)` returns a short line from a closed,
  documented set of seven situation strings (grieving/celebrating/working/
  arguing/socialising/worried/idle — there was no existing call site to
  infer a vocabulary from, so this pass defined and documented one rather
  than guessing); a personality-aware overload picks from a smaller
  trait-flavoured override table (courage/empathy/impulsiveness for
  grieving; courage/patience/kindness for arguing; sociability/ambition for
  celebrating) keyed off the resident's dominant `Personality` trait
  (highest of the ten 0..1 continuous fields), falling back to the plain
  pool for traits/situations without a dedicated override.
- **Determinism discipline, kept even though it isn't strictly required
  here.** Template/line selection is picked by `event.id`/`residentId`
  modulo the candidate list size, never `Math.random()`/
  `kotlin.random.Random` — this is presentation-layer text, not simulation
  state, so nothing downstream depends on it for replay correctness, but
  the task brief was explicit about keeping the same "no unseeded
  randomness" discipline the rest of this codebase applies everywhere else,
  and it was cheap to honour, so it was.
- **Wired for real — two concrete integration points, not left unused.**
  - `EventSheetContent` (`feature/town/TownSheets.kt`): a new "More detail
    ▼" `TextButton` under the event's existing terse description, expanding
    to show the elaboration in a `Surface` block — lazily fetched (only on
    first expand, cached per `eventId` via `remember`) so the sheet's
    default at-a-glance read is unchanged. New `WorldRepository
    .elaborateEvent(eventId)` loads the full `WorldEvent` (via
    `EventDao.event`/`causeIdsOf` + `toDomain`) and reads live
    `coordinator.state` on the confined `engineDispatcher` — the same
    access pattern every other `WorldState`-touching `WorldRepository`
    function already uses — then calls the injected `NarrativeTextProvider`.
    `TownViewModel.requestElaboration(eventId, onReady)` wraps it in
    `viewModelScope.launch`, the exact one-shot
    suspend-call-from-Composable pattern `requestChronicle` (built earlier
    this session) already established — reused rather than inventing a
    second shape.
  - Resident sheet (`ResidentSheetContent`, same file): a new italic quoted
    line under the existing activity/mood text, shown only when the
    resident's current `Activity` maps onto one of the seven supported
    situation strings (a new local `situationFor()` mapper — `MOURNING` →
    "grieving", `CELEBRATING` → "celebrating", `WORKING` → "working",
    `ARGUING` → "arguing", `SOCIALISING`/`VISITING` → "socialising", high
    stress/low financial security → "worried"; everything else shows
    nothing rather than forcing a generic line onto every sheet). New
    `WorldRepository.dialogueLineFor(residentId, situation)` reads the
    resident's live `Personality` off `coordinator.state` and calls the
    personality-aware overload when the bound provider is
    `TemplateDialogueProvider` specifically (an `is` check), falling back
    to the plain interface call otherwise — so a future non-template
    `DialogueProvider` implementation isn't forced to support the
    personality overload just to compile. `TownViewModel
    .requestDialogueLine` mirrors `requestElaboration`'s shape exactly.
- **`di/AppModule.kt`** — `provideNarrativeTextProvider`/
  `provideDialogueProvider` now return `TemplateNarrativeTextProvider()`/
  `TemplateDialogueProvider()` instead of the `NoOp` classes. The `NoOp`
  classes themselves are untouched and still exist in `FutureProviders.kt`
  — kept for tests (both existing Room/Robolectric integration test files
  that construct `WorldRepository` directly, `WorldRepositoryTest.kt` and
  `TownViewModelTest.kt`, were updated to pass `NoOpNarrativeTextProvider()`/
  `NoOpDialogueProvider()` explicitly now that the constructor takes two new
  parameters) and as the documented fallback shape for a future swap.
- **The swap-in seam actually holds.** `WorldRepository`'s new
  `elaborateEvent`/`dialogueLineFor` depend only on the
  `NarrativeTextProvider`/`DialogueProvider` interfaces (constructor-injected,
  Hilt-resolved) — never on `TemplateNarrativeTextProvider`/
  `TemplateDialogueProvider` concretely, except for the one `is` check
  gating the personality-aware dialogue overload, which is written to
  degrade gracefully (falls back to the plain interface call) rather than
  assume the template implementation is always bound. A future real
  LLM-backed provider needs only new `@Provides` bindings in `AppModule.kt`
  — no changes to `WorldRepository`, `TownViewModel`, or either Composable
  call site.

Deliberately scoped out, stated explicitly: **the real LLM-backed
implementation itself** — needs an API key/budget/model choice from the
user, a decision this pass was explicitly told not to make blind; only the
template-based default and the swap-in seam were built. No `situation`
vocabulary beyond the seven strings defined here (a real LLM provider might
support much richer free-text situations, but the template provider needs a
closed set to have any lines to pick from at all). No elaboration/dialogue
surfaced anywhere beyond the two chosen integration points (no newspaper
story elaboration, no ambient/background dialogue ticker) — two genuine,
valuable call sites were judged enough to prove the seam is real without
sprawling across every screen in one pass. No caching/persistence of
generated lines beyond the per-`eventId`/`residentId` in-Composable
`remember` — regenerated fresh each time a sheet opens, cheap enough not to
need it, matching this session's `EraSummary`/chronicle precedent of
"recomputed, not persisted."

**Not run this session:** `./gradlew` build/test and any `git` commands.
Code-only, ready for the orchestrating session to build/test/commit. **Not
verified to compile** — no build was run; the new code was written by
careful reading of `FutureProviders.kt`, `ChronicleBuilder.kt`,
`NewspaperGenerator.kt`, `WorldRepository.kt`, `TownViewModel.kt`, and
`TownSheets.kt` for exact signatures and conventions, but this is not a
substitute for an actual `./gradlew` build. **Not verified on a real device
or emulator** — none was available in this environment; the two new UI call
sites (the expandable "More detail" toggle and the quoted dialogue line)
compile-reason correctly against existing Compose patterns in the same file
but have not been eyeballed on a real render — text wrapping, the expand/
collapse toggle's tap target, and the italic quoted-line styling next to
the existing activity/mood text all warrant a real-device look before being
fully trusted.

### 2026-07-10 — Shareable town chronicles (last Phase 4 backlog item)

Last remaining Phase 4 backlog item: "Shareable town chronicles: export a
family's saga as text/images." Another agent was concurrently extending
`core/simulation/ExternalWorldEventProvider.kt` and `core/model` files for a
national-layer pressure extension in the same checkout, so this pass stayed
out of both entirely — all new code lives in a new `data/ChronicleBuilder.kt`
file plus additive, read-only-in-spirit changes to `data/WorldRepository.kt`
(a new `buildChronicle` suspend function, no changes to any existing
function), `feature/town/TownViewModel.kt` (one new method), and
`feature/town/TownSheets.kt` (one new button in the existing resident sheet).
No `./gradlew` or `git` commands run, per the parallel-work constraint —
code-only, ready for the orchestrating session to build/test/commit.

- **Read in the instructed order.** `docs/simulation-rules.md`'s "Family &
  generations" section and `WorldRepository.buildEraSummary` in full first —
  confirmed the "notable public events at/above `ImportanceScorer
  .HISTORY_THRESHOLD`, capped and sorted by importance" convention it
  already established, and reused it verbatim rather than inventing a second
  "notable" bar. Then `core/model/Resident.kt` for the raw material
  (`memories`, `childIds`, `motherId`/`fatherId`, `partnerId`,
  `occupation`, `relationshipStatus`) and `feature/people/FamilyTreeScreen.kt`
  for the existing two-generations-each-way traversal shape
  (grandparents/parents/self/children/grandchildren via one extra hop each
  direction on `motherId`/`fatherId`/`childIds`) — reused that exact shape
  in `ChronicleBuilder` rather than reinventing family-graph traversal a
  third time (`FamilyTreeScreen` was itself the second reuse, of
  `PeopleScreen.familyOf()`). Confirmed no `Intent.ACTION_SEND`/
  `ShareCompat` usage existed anywhere in the app — this is the first.
- **`ChronicleBuilder`** (`data/ChronicleBuilder.kt`, new file, pure
  UI-layer — reads only the already-loaded `WorldUi` snapshot plus a
  caller-supplied per-resident "notable events witnessed" map, never the
  engine-confined `WorldState`): walks the same bounded family graph
  `FamilyTreeScreen` draws, and for each traceable person (self, up to 2
  ancestor generations, up to 2 descendant generations) generates one
  fixed-template paragraph from real fields — alive/dead status and cause,
  age, occupation, child count, relationship status, up to 4 quoted
  memories (matching `EraSummary.definingMemories`'s existing cap), and up
  to 3 notable public events they lived through. **Deliberately template
  sentence construction, not generated prose** — stated explicitly in the
  file's own header doc comment as the scope boundary against the separate,
  still-open `NarrativeTextProvider`/`DialogueProvider` backlog item, the
  same distinction the Phase-4-kickoff session already drew for
  `CuratedWorldPressureFeed`'s hand-written pressure strings.
- **`WorldRepository.buildChronicle(residentId): String?`** (new suspend
  function, added alongside `statistics()`/before the `// internals` marker
  — no existing function touched) gathers what `ChronicleBuilder` can't: for
  every person in the bounded family graph, queries `EventDao.eventsBetween`
  across that specific person's own lifetime (birth to death, or birth to
  "now" if alive — reusing the exact birth/death windowing
  `buildEraSummary` already established) filtered to `PUBLIC` events at/above
  `HISTORY_THRESHOLD`, then hands the whole map to `ChronicleBuilder.build`.
  Unlike `buildEraSummary`, this works for **any** resident, living or dead
  — not gated to the one death-of-followed moment — since the whole point is
  a chronicle the player can pull up and share at any time, not only at a
  funeral.
- **Entry point — real and reachable.** A new "📜 Share saga" `FilterChip` on
  `ResidentSheetContent` (`feature/town/TownSheets.kt`), next to the
  existing Follow/Favourite/Nudge chips — reachable from any resident's
  sheet (tap a resident on the map or from the People screen), not only the
  followed one, and works whether that resident is alive or dead. Tapping it
  calls the new `TownViewModel.requestChronicle(residentId, onReady)`
  (builds the text off the main thread via `viewModelScope.launch`, hands
  the result back to the Composable), which then builds a plain-text share
  `Intent` via `ShareCompat.IntentBuilder(context).setType("text/plain")
  .setSubject(...).setText(chronicle)` and launches it through
  `Intent.createChooser` — the standard, well-established share-sheet
  pattern named in the task brief, nothing novel. The ViewModel itself stays
  free of Android `Intent`/`Context` concerns, matching how
  `SettingsSheet.kt` already keeps its own `ActivityResultContracts`
  permission launcher entirely in the Composable layer rather than the
  ViewModel.
- **Text-only export, chosen deliberately and stated explicitly.** The task
  brief offered image export (Compose `graphicsLayer`/capture-to-bitmap) as
  an optional stretch, explicitly calling out that a scope-down to text-only
  is "a perfectly acceptable, honest scope-down if image capture feels too
  risky to leave unverified." With no emulator/device available in this
  environment to confirm a capture actually renders correctly (text overflow,
  clipping, empty/placeholder sprite frames, scroll-container capture
  quirks are all real Compose bitmap-capture pitfalls that only show up on a
  real render), and given the share-intent flow itself already carries the
  same "unverified without a device" risk on its own, stacking a second,
  independently-unverifiable Compose-capture mechanism on top was judged the
  wrong trade — text-only was chosen as the honest, fully-reasoned-through
  option.
- No manifest changes were needed: a plain `text/plain` `ACTION_SEND` share
  carries the chronicle as `Intent.EXTRA_TEXT`, not a file `Uri`, so no
  `FileProvider`/`<provider>` entry or `grantUriPermissions` was required —
  confirmed by checking `AndroidManifest.xml` first, which has no
  `<provider>` beyond the pre-existing WorkManager-disabling one.

Deliberately scoped out, stated explicitly: **image export** (see above —
text-only was the deliberate, reasoned choice, not an oversight); in-laws
and any relative outside the bounded 2-generations-each-way graph (same
boundary `FamilyTreeScreen` already draws, for the same reason — the brief's
own acceptance language never asked for affinal relatives); any persistence
of a generated chronicle (rebuilt fresh from current data on every tap,
cheap enough not to need caching, matching `EraSummary`'s own
"recomputed, not persisted" precedent); a dedicated chronicle-preview screen
before sharing (the share sheet itself is the only UI — no in-app preview
was built, since the share-sheet's own "Copy" / recipient-app preview
already lets a user see the text before sending); wiring a chronicle button
into `FamilyTreeScreen.kt` or `DeathSummaryDialog` as *additional* entry
points beyond the resident sheet (the resident sheet alone already makes
this reachable for every resident in the game, alive or dead, so a second
or third entry point was judged unnecessary surface area for this pass).

**Not run this session** (per the parallel-work constraint): `./gradlew`
build/test and any `git` commands. Code-only, ready for the orchestrating
session to build/test/commit. **Not verified on a real device or
emulator — none was available in this environment**, and this matters more
here than for prior pure-Compose UI passes: `Intent.ACTION_SEND` and
`ShareCompat.IntentBuilder` compile cleanly but their actual runtime
behaviour — whether the system chooser genuinely appears, whether the
receiving app (Messages, Gmail, WhatsApp, etc.) renders the multi-paragraph
plain-text body sensibly, whether `setSubject` is honoured or ignored by a
given target app — is real OS/other-app behaviour that compilation cannot
confirm. Needs a real-device pass before being trusted, specifically: tap
"Share saga" for a resident with a rich family (multiple generations,
several quoted memories) and confirm the chooser appears with a sensible
preview; confirm the shared text is well-formed (line breaks intact,
correctly UTF-8, no truncation) once actually received in a target app;
and try a resident with no traceable family at all to confirm the chronicle
still reads sensibly for a single, isolated life rather than looking broken
or sparse.

### 2026-07-10 — Phase 4: national layer (taxes, trends) on top of external world pressure

Phase 4 backlog item: "lightweight country context (taxes, trends) as
pressures" — the national layer, built explicitly as a small extension of
the existing `CuratedWorldPressureFeed`/`WorldPressureMechanicMapper` system
from the earlier "Phase 4 kicked off" session, not a new parallel mechanic.
Another agent was concurrently building a shareable-town-chronicles export
feature in this same checkout, so this pass stayed entirely inside
`core/simulation`/`core/model`, touching neither export/share files nor any
UI. No `./gradlew` or `git` commands run, per the parallel-work constraint —
code-only, ready for the orchestrating session to build/test/commit.

- **Read the existing Phase 4 system first, in full, per the task brief** —
  confirmed only `FUEL_PRICES_RISE`/`FUEL_PRICES_EASE` (of eight kinds) map
  to any mechanical effect, and that no "tax" concept existed anywhere in
  `core/simulation` or `core/model` before this pass (checked both,
  case-insensitive).
- **Taxes — a new pressure pair with a genuine, bounded mechanical effect.**
  Two more entries on the *same* `ExternalPressureKind` enum,
  `TAX_RATE_RISES`/`TAX_RATE_EASES`, joining the curated list
  `CuratedWorldPressureFeed` already rolls from — no new orchestration, no
  new daily-roll/duration logic; they're picked, timed (14–45 in-game days)
  and resolved by the exact same unmodified machinery every other kind
  already uses, and still subject to the existing "at most one active
  pressure town-wide" rule (a tax pressure and a fuel-price pressure can
  never coexist). What's genuinely new is a standing national-context value:
  `WorldState.nationalTaxRate` (`Double`, default `1.0`), nudged once per day
  by a new `WorldPressureMechanicMapper.nudgeNationalTaxRate` — a slow
  `TAX_RATE_STEP_PER_DAY` (0.004) walk towards `NATIONAL_TAX_RATE_MAX` (1.1)
  while `TAX_RATE_RISES` is active, towards `NATIONAL_TAX_RATE_MIN` (0.9)
  while `TAX_RATE_EASES` is active, or back towards neutral `1.0` the rest of
  the time (including once the pressure resolves) — deliberately a slow
  multi-week drift, never an instant jump, and genuinely bounded to a ±10%
  swing as the task brief asked for. The mechanical hook itself is one clean
  line, mirroring the fuel-price precedent exactly: `WorldPressureMechanicMapper
  .livingCostMultiplier(state)` returns the (already-bounded) rate, and
  `EconomySystem.dailySettlement`'s existing per-resident daily living-cost
  deduction becomes `LIVING_COST_PER_DAY *
  WorldPressureMechanicMapper.livingCostMultiplier(state)` — landing on the
  one place in the codebase that already models a resident's unavoidable
  daily outgoings, the resident-wealth equivalent of where the fuel-price
  pair already lands on business overhead. Deliberately did **not** touch
  business `balance`/`priceLevel`/`demand` or any other system in the same
  pass, for the same "one clean traceable hook" reason the original Phase 4
  session gave for fuel prices.
- **Trends — a short rolling pressure history.** New
  `WorldState.pressureHistory: MutableList<PressureHistoryEntry>` (kind,
  `startedAt`, `endsAt` — null while still active), capped at a new
  `CuratedWorldPressureFeed.PRESSURE_HISTORY_LIMIT` (5, oldest dropped
  first). `CuratedWorldPressureFeed.start`/`resolve` — both already existing,
  unmodified in shape — now also append/close one history entry alongside
  the pre-existing `externalPressure` start/resolve logic, one entry per
  pressure covering its full start-to-end span rather than a second live
  copy. This gives the town a standing sense of "how things have been going
  nationally" — the last few pressures, not just the single current one —
  matching the task brief's "ongoing backdrop, not a single on/off toggle"
  framing. **Deliberately not surfaced in any UI, newspaper, or town-overview
  sheet this pass** — modelled and maintained only, exactly the same
  data-first-then-UI-later shape the earlier `EraSummary`/family-reputation
  work in this log already used, and explicitly out of scope for this
  backend-only pass given the concurrent chronicles-export UI work underway
  in this same checkout.
- Checked `ImportanceScorer`/`NewspaperGenerator` first for the two new
  enum entries' knock-on effects: `EventType.NATIONAL_PRESSURE` already
  existed and both new kinds flow through its existing `startDescription`/
  `resolveDescription` `when` blocks (two new fixed, hand-written lines each,
  no generated text) — no scoring/newspaper wiring changes were needed.
  `SimulationCoordinator.kt` needed no new call site — the tax-rate nudge and
  history bookkeeping both live inside the existing
  `CuratedWorldPressureFeed.updateDaily`, already the last call in the
  `if (newDay)` block; only its own explanatory comment was expanded to
  mention the new behaviour.
- Docs: new "National layer: taxes and trends (added 2026-07-10)" subsection
  in `docs/simulation-rules.md` directly under "Phase 4: External world
  pressure," with the tick-pipeline summary line at the top also updated.
  `docs/backlog.md`'s Phase 4 "National layer" bullet marked `[x]`, scoped
  exactly as described above.

Deliberately scoped out, stated explicitly: no UI/newspaper/town-overview
surfacing of either `nationalTaxRate` or `pressureHistory` — both are
invisible to the player today; no stacking/compounding between the tax and
fuel-price multipliers (structurally impossible anyway, since only one
pressure is ever active town-wide); `pressureHistory` is read-only and does
not itself feed back into any mechanical system (no "tax fatigue," no effect
on future pressure odds); still no LLM-authored prose anywhere in this
system. The `NarrativeTextProvider`/`DialogueProvider` LLM layer remains a
separate, unattempted Phase 4 item, untouched by this pass. Shareable town
chronicles were being built concurrently by another agent in this same
checkout and were not touched here.

Not run this session (per the parallel-work constraint): `./gradlew`
build/test and any `git` commands. Code-only, ready for the orchestrating
session to build/test/commit. **Not verified to compile** — no build was
run; the new code was written by careful reading of the existing
`ExternalWorldEventProvider.kt`, `EconomySystem.kt`, `WorldState.kt` and
`SimulationCoordinator.kt` for exact signatures and conventions, but this is
not a substitute for an actual `./gradlew` build.

### 2026-07-10 — Benchmark infrastructure (last remaining Phase 2 Product item)

Last remaining Phase 2 **Product** backlog item: "Benchmarks in CI
(macrobenchmark for town rendering, JMH-style micro for ticks)." Another
agent was concurrently editing `core/ui/SpriteProvider.kt` (silhouette work)
in the same checkout, so this pass avoided that file entirely — all new code
lives in two new test files plus one new CI workflow file. No `./gradlew` or
`git` commands were run, per this session's constraints — code-only, ready
for the orchestrating session to build/test/commit. Also hit the same kind
of file-contention race on this very document that an earlier session in
this log already described (a couple of `Edit` calls against
`docs/backlog.md` failed their conflict check mid-session because another
agent was rewriting it at the same moment); re-reading and retrying resolved
it without losing either agent's work.

- **Scoping decision, stated upfront and honestly.** The backlog bullet's own
  wording names two specific, real Android tools: an `androidx.benchmark.macro`
  Compose macrobenchmark for town rendering, and a JMH microbenchmark for
  ticks. Both genuinely require infrastructure this environment does not
  have — a macrobenchmark needs a connected physical/virtual device to
  measure real frame timing and `Canvas` draw-call cost, and this environment
  has no emulator configured (the same constraint every UI-touching item in
  this session's log has already called out); a JMH benchmark needs the
  `me.champeau.jmh` Gradle plugin and a new module, and this session was
  explicitly steered away from adding Gradle-module surface area given prior
  instability with the existing Robolectric test setup. Building either as
  inert, unrun scaffolding was considered and rejected in favour of something
  that actually runs and reports a real number in this environment — see the
  task brief's own explicit preference for the achievable JVM approach.
- **Read `SimulationCoordinator.tick()` first**, per the task brief, since the
  whole game is a tick loop and its per-tick cost is the direct bound on how
  large a population/town this engine can support — ties into the
  "residents at scale" engineering-debt concern flagged earlier this session.
  New `SimulationTickBenchmark.kt`
  (`app/src/test/kotlin/com/ripple/town/simulation/`): a plain
  `System.nanoTime()` warmup+measure harness (not JMH — no fork isolation, no
  dead-code-elimination blackholing, no JIT-mode control; the file's own doc
  comment says so explicitly) run against `TestWorld.newCoordinator()`, the
  same seeded-world helper every other engine test in this package already
  uses. Two cases: a freshly generated town, and a coordinator that's already
  run 10 in-game days (relationships/memories/events accumulated — more
  representative of a mid-game tick, since several daily systems scale with
  accumulated state rather than pure population count). Runs as an ordinary
  fast JUnit test in the existing `test` source set — no new module, no new
  plugin, no gating behind a special slow CI job. Prints mean/min/max/stddev
  to stdout and asserts a generous ceiling (50ms / 75ms) as a regression
  tripwire, not a tuned frame budget — no real device numbers exist here to
  derive an honest budget from.
- **Read `TownRenderer.kt`'s Canvas draw loop next**, per the task brief.
  Confirmed the same device gap applies to a true macrobenchmark of the
  actual `canvas.drawImageRect` calls — genuinely unmeasurable here. Instead,
  scoped to option (a) from the task brief: a benchmark of the pure-data-layer
  cost feeding the renderer. New `TownRenderingDataBenchmark.kt`, same
  package, timing `SnapshotBuilder.build()` (`data/WorldSnapshot.kt`) — the
  `WorldUi` construction step `TownRenderer` consumes every time the
  simulation layer publishes a new frame of state, and a real, testable
  bottleneck contributor since it walks every resident's relationships and
  every building on each call. Same fresh-town / after-30-days pairing as the
  tick benchmark, same honest harness, same stdout reporting, same generous
  ceiling-as-tripwire assertions (20ms / 30ms). The file's own header states
  plainly what it does NOT measure: the actual `Canvas.drawImageRect` cost
  inside `TownRenderer` remains unmeasured without a device.
- **CI wiring — minimal, and explicitly unverified.** Added
  `.github/workflows/benchmarks.yml` (no `.github/` directory existed before
  this pass, confirming the task brief's own note that "in CI" was
  aspirational until now) — runs the two new test classes via
  `./gradlew :app:testDebugUnitTest --tests ...` on push/PR to `main` and on
  manual dispatch, uploads the test report as an artifact. The workflow
  file's own header comment states plainly: it has **not** been executed or
  verified in this session, since there is no way to run GitHub Actions from
  here — it needs an actual push to prove the runner/JDK/Gradle invocation
  actually works before a green check should be trusted. It also does not
  attempt to run real `androidx.benchmark` macro/micro benchmarks — a
  GitHub-hosted runner has no device by default, and no emulator-in-CI
  action (e.g. `reactivecircus/android-emulator-runner`) is configured.
- Docs: `docs/backlog.md`'s Phase 2 **Product** bullet marked `[~]` (not
  `[x]`) with the full honest breakdown of what's real/running vs. still
  open, matching the task brief's explicit instruction not to overclaim "CI
  benchmarks" when nothing has actually been proven to run in a CI
  environment from this session.

Deliberately left open, stated explicitly: a real `androidx.benchmark.macro`
macrobenchmark module for `TownRenderer` (needs a device/emulator); a real
JMH microbenchmark module for `tick()` (needs the `me.champeau.jmh` plugin
and a new Gradle module, deliberately not added this pass); actual `Canvas`
draw-call cost for town rendering (only the upstream data-layer snapshot cost
is measured); any CI verification at all, including of the plain-unit-test
workflow file added in this pass.

Not run this session (per the parallel-work constraint): `./gradlew`
build/test and any `git` commands. Code-only, ready for the orchestrating
session to build/test/commit. **Not verified to compile or pass** — no build
was run; the two new test files were written by careful reading of
`TestWorld.kt`, `SnapshotBuilder.build`, `WorldState.population()`, and
existing test files (`DeterminismTest.kt`, `SnapshotAndFollowTest.kt`) for
exact signatures, but this is not a substitute for an actual
`./gradlew :app:testDebugUnitTest` run.

### 2026-07-10 — Real local notifications (opt-in, POST_NOTIFICATIONS)

Phase 2 **Product** item: real system notifications for followed/favourite
residents. Another agent was concurrently building a family-tree/relationship-map
UI in `feature/people/*` in the same checkout, so this pass stayed out of that
package entirely — new code lives in new `notifications/` and `work/` files plus
small, targeted edits to `AndroidManifest.xml`, `SettingsSheet.kt`,
`SettingsRepository.kt`, `MainViewModel.kt`, `RippleApplication.kt`, and two DAOs.
No `./gradlew` or `git` commands run, per the parallel-work constraint — code-only,
ready for the orchestrating session to build/test/commit. **No Android
emulator/device is configured in this environment**, which matters more here than
for prior pure-Compose passes: manifest/permission/WorkManager code touches real
OS behaviour (the permission dialog, notification delivery, periodic-Worker
scheduling) that compilation cannot verify — see the explicit device-test caveat
on the backlog bullet above and repeated at the end of this entry.

- **Permission flow.** `POST_NOTIFICATIONS` declared in `AndroidManifest.xml`
  (API 33+; a no-op below that, where the permission is implicit at install
  time). Requested only via the standard
  `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`
  pattern in `SettingsSheet.kt`, fired only from an explicit tap on a new "Push
  notifications" toggle — never on app launch. Turning the toggle off cancels the
  periodic Worker and persists the opt-in as false without touching the OS grant;
  turning it on checks whether the permission is already granted (skips the
  dialog if so) and otherwise launches the system prompt. A denial still persists
  the opt-in as on (matching the "the user made a choice, don't nag" convention)
  but delivery stays silently suppressed via `NotificationHelper
  .canPostNotifications()` until granted through system settings — the app never
  re-prompts itself, consistent with the platform's own single-prompt policy.
- **Settings surface.** No dedicated settings screen existed to extend — reused
  the existing minimal `SettingsSheet.kt` bottom sheet, which already had one
  toggle (`notificationsEnabled`, gating the pre-existing in-app alert banners in
  `WorldRepository.notifyIfRelevant`). Added a second, clearly distinct toggle
  (`pushNotificationsEnabled`) rather than repurposing the existing one, since
  overloading it would have silently changed what the pre-existing in-app-banner
  toggle does. New `Settings.pushNotificationsEnabled` (default `false` — an
  opt-in, not a pre-ticked box) and `Settings.lastNotifiedEventId` (the
  de-duplication cursor, see below) in `SettingsRepository.kt`.
- **`NotificationChannel`.** One channel, `"followed_resident_updates"`
  ("Followed resident updates"), created idempotently in
  `RippleApplication.onCreate()` via `NotificationHelper.ensureChannel()` — cheap
  and safe to call unconditionally on every process start; channel existence
  doesn't itself post anything, the opt-in/permission checks still gate that.
- **Shared check-and-notify logic — `FollowedResidentNotifier`.** Deliberately
  DB-only: reads `WorldDao`'s followed/favourite residents
  (`FollowDao.allOnce()`, a new suspend snapshot query alongside the existing
  `Flow`-returning `all()`) and notable events since a persisted cursor
  (`EventDao.notableEventsSince`, a new suspend query mirroring the existing
  `importantEvents` Flow query's threshold/ordering but scoped by event id
  instead of a live collector). Reuses `ImportanceScorer.HISTORY_THRESHOLD` —
  the same bar History/era-summary/Follow-moments already use — rather than
  inventing a second "notable" definition. Capped at
  `MAX_NOTIFICATIONS_PER_CHECK` (3) notifications per check, per the brief's
  explicit anti-storm requirement; the cursor (`lastNotifiedEventId`) always
  advances past everything scanned (whether notified or not) so a quiet check
  never re-scans the same backlog forever, and never moves backwards under
  concurrent callers.
- **Delivery mechanism (a) — app open.** `MainViewModel.init` calls
  `notifier.checkAndNotify()` right after `worldRepository.restoreIfPresent()`
  finishes (including any offline catch-up that ran as part of that restore),
  so it sees events from time that was just caught up on, not just the
  pre-close state.
- **Delivery mechanism (b) — periodic WorkManager.** New
  `NotificationCheckWorker`, a plain Hilt-injected `CoroutineWorker` (same
  `@HiltWorker`/`@AssistedInject` pattern as the pre-existing, currently-unused
  `CatchUpWorker`), enqueued as a unique `PeriodicWorkRequest` at WorkManager's
  own minimum interval (15 minutes, `ExistingPeriodicWorkPolicy.KEEP`), with
  `setRequiresBatteryNotLow(true)` and no network requirement. No wake locks, no
  foreground service — respects Doze/battery-optimization defaults, as instructed.
  **Deliberately does not run the full simulation catch-up from the Worker** —
  documented at length in the Worker's own doc comment: (1) the simulation engine
  is confined to `WorldRepository`'s private single-threaded dispatcher and only
  ever constructed via `restoreIfPresent()`/`createWorld()`, so running it from a
  cold background process would mean duplicating that whole
  restore/catch-up/checkpoint lifecycle in a second place; (2) doing so would also
  silently advance game time on a schedule the player isn't watching, outside the
  existing bounded, UI-visible `CatchUpProgress` flow — a bigger behavioural
  change than "send a notification" should require; (3) the actual goal doesn't
  need a fresh tick at all — everything needed is already sitting in the
  `world_events` table from the last time the app was open, so the Worker just
  calls the same `FollowedResidentNotifier.checkAndNotify()` the app-open path
  uses. Net effect, stated explicitly: a long-closed app's periodic checks won't
  surface anything *newer* than what was already simulated as of the last real
  open — a delivery mechanism for already-known notable events, not a way to keep
  the town moving in the background, consistent with "still no continuous
  background work."
- **Tap target.** Notifications open `MainActivity` via a `PendingIntent`
  (`FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP`, `FLAG_IMMUTABLE`) — a
  plain app-open, not a deep link to the specific event/resident.
  `MainActivity`/`RippleApp.kt` have no existing intent-extra or deep-link
  handling to build on, and adding a full deep-link path (extra parsing →
  navigation → `TownViewModel.openEvent`/`openResident` on cold start, which
  today only happens from in-app navigation calls) was judged more than the
  "small addition" the brief allowed for — scoped down and stated explicitly
  as deferred, per the brief's own instruction.
- **Notification icon.** `NotificationCompat.Builder.setSmallIcon()` cannot use
  the existing adaptive `ic_launcher` (background+foreground layers — the system
  can't silhouette that for the status bar). Added a small, purpose-built flat
  vector, `res/drawable/ic_notification.xml`, reusing the launcher's own
  concentric-ripple motif at status-bar weight, rather than risk a blank/white-
  square icon at runtime — a real, if minor, platform-specific pitfall worth
  naming since it's exactly the kind of thing that's invisible at compile time
  and only shows up on a real device.
- No new Gradle dependencies were needed — `androidx.work:work-runtime-ktx` and
  `androidx.core:core-ktx` (for `NotificationManagerCompat`) were already present
  in `app/build.gradle.kts`/`libs.versions.toml`, along with `hilt-work`/
  `hilt-ext-compiler` for `@HiltWorker`. No Room schema migration was needed
  either — only new DAO *queries* were added (`FollowDao.allOnce()`,
  `EventDao.notableEventsSince`), no new entities/columns/tables.

Deliberately scoped out, stated explicitly: deep-linking a notification tap to
the specific event/resident (plain app-open only, see above); richer
notification content/grouping beyond Android's own default per-app/channel
shade bundling; running the periodic Worker's check against a fresh simulation
tick rather than already-persisted events (see the three reasons above); a
second, separate "quiet hours" or notification-frequency setting beyond the one
opt-in toggle and WorkManager's own 15-minute floor.

**Not run this session** (per the parallel-work constraint): `./gradlew`
build/test and any `git` commands. Code-only, ready for the orchestrating
session to build/test/commit. **Not verified on a real device or emulator —
none was available in this environment.** This is materially riskier to leave
unverified than this session's (and prior sessions') pure-Compose UI work:
manifest/permission declarations, the actual system permission-request dialog
and its grant/deny branches, `NotificationChannel` creation, and WorkManager's
periodic scheduling all depend on real OS behaviour that compilation alone
cannot catch. Needs a real-device pass before being trusted, specifically:
requesting the permission both ways (grant and deny) and confirming the app
behaves correctly either way; confirming a notification actually appears when a
followed resident has a notable event and that tapping it opens the app;
confirming the toggle-off path actually cancels the periodic Worker (e.g. via
WorkManager's own inspector or `adb shell dumpsys jobscheduler`); and leaving
the app closed for 15+ minutes with a followed resident's life continuing (via
a seeded notable event) to confirm the periodic check genuinely fires on a real
device and behaves as designed.

### 2026-07-10 — Family tree & relationship map (Phase 2 Product, People screen)

Picked up the first remaining "Product" item under Phase 2 — the family
tree/relationship-map item this same file's own "People — done 2026-07-10"
entry (above, in the Mobile UI rebuild section) explicitly deferred: that
earlier pass built only a flat expandable text listing per resident row
(`familyOf()` + an `ExpandMore`/`ExpandLess` indented `Column`), and called
out the graphical version as a separate, larger backlog item — this is that
item. Another agent was concurrently working on Android
notifications/WorkManager in this same checkout (`RippleApp.kt`, a new
notification system, DI/manifest), so this pass stayed strictly inside
`feature/people/*`, touching `RippleApp.kt` and any manifest/notification
file not at all. No `./gradlew` or git commands were run — code-only, blind
(no emulator), same risk acceptance as every other UI item in this session's
log.

- **Read the existing entry first, as instructed.** Confirmed `familyOf()`
  in `PeopleScreen.kt` already does the immediate-generation lookup
  (partner/mother/father/children/siblings-by-shared-parent, deduplicated)
  reading `ResidentUi.partnerId/motherId/fatherId/childIds`
  (`data/WorldSnapshot.kt`) — reused verbatim for the resident+partner and
  parent rows of the new tree rather than reinventing it.
- **Checked whether any data was actually missing before adding anything —
  it wasn't.** `WorldSnapshot.kt`'s `SnapshotBuilder.residentUi` already
  builds `ResidentUi.relationships: List<RelationUi>` from
  `state.relationshipsOf(r.id)`, already filtered to `familiarity > 5`,
  already sorted by `rel.warmth()` descending, and already capped at 12 —
  precisely the "top 12 by familiarity/warmth" the brief asked for. `RelationUi`
  already carries `kindLabel`, `warmth`, `trust`, `affection`, `resentment`,
  `familiarity`. Grandparent/grandchild traversal for the tree (two
  generations each way) is also derivable purely from existing
  `motherId`/`fatherId`/`childIds` chains, one extra hop each direction — no
  new field anywhere. Net result: zero changes to `WorldRepository.kt`,
  `WorldSnapshot.kt`, `WorldState.kt`, or any `core/simulation`/`core/model`
  file. This is pure UI reading data that already existed, exactly as the
  task brief hoped for but didn't guarantee.
- **New file: `feature/people/FamilyTreeScreen.kt`.** A full-screen
  `androidx.compose.ui.window.Dialog` (`usePlatformDefaultWidth = false`)
  with two `FilterChip`-toggled tabs, matching the tab-row pattern already
  used in `TownSheets.kt`'s `ResidentSheetContent` (Life/Relationships/
  Memories/Skills/History) rather than inventing a new tab mechanic. Chose a
  `Dialog` over a new nav-graph route specifically because `RippleApp.kt`
  (where `NavHost`/`Routes` live) was off-limits for the duration of the
  concurrent notifications work — the dialog is entirely self-contained,
  opened via a local `remember { mutableStateOf<Long?>(null) }` in
  `PeopleScreen.kt`, no navigation graph changes.
  - **Family tree tab.** Five generation rows (grandparents → parents →
    resident+partner → children → grandchildren) laid out as a horizontally
    scrollable `Column` of `Row`s, each node a `PixelAvatar` (reusing
    `poseFor`/`SpriteProvider`, the same avatar call every other sheet in
    the app makes) with first name + role label underneath, and a small
    Canvas-drawn connector (`drawLine` + end-cap `drawCircle`, mirroring the
    dotted-connector technique `core/ui/Components.kt`'s existing
    `CauseConnector` already uses for cause chains) between each generation
    row rather than a plain gap — this is the "genuinely more graphical than
    a flat list" requirement the brief was explicit about. Grandparents are
    only shown where actually traceable (both the resident's parent AND
    that parent's own mother/fatherId must resolve) — most residents won't
    have any, and the tab correctly shows nothing for that row rather than
    empty placeholder circles, with a small explanatory caption underneath
    when generations are partially known. Tapping any node calls
    `onOpenResident`, reusing the resident sheet the rest of the app already
    uses — no duplicate resident-detail UI was built.
  - **Relationship map tab.** A radial layout: the resident's own
    `PixelAvatar` centred in a `Box`, `ResidentUi.relationships` (excluding
    family-ish kinds — `Family`, `Estranged family`, `Partner`, `Spouse`,
    since those already have their own dedicated tree tab) placed as chips
    around it at even angles (`cos`/`sin` over `2π × index/count`), each
    chip's spoke drawn by a `Canvas` layer underneath (`RadialConnectors`)
    with line thickness scaled by `warmth` and colour keyed by
    `RelationshipKind` label (`Friend`/`Close friend` green shades, `Rival`
    brick red, `Secret affair` blush, `Former partner` muted brown,
    `Acquaintance`/`Stranger` blues/soft-ink) via a small local
    `KIND_COLOURS` map, plus a text legend below. Deliberately the simpler
    of the two views per the brief's own guidance ("a radial cluster is
    enough, not a force-directed graph") — no physics simulation, no
    drag/reposition, no edge bundling.
- **Entry points wired, not left dangling.** Two call sites in
  `PeopleScreen.kt`: (1) a new "View family tree & relationships"
  `OutlinedButton` on the existing "Following" card, next to the family
  summary line that card already showed; (2) a new `TextButton` inside every
  `PersonRow`'s existing expandable family section (the one the earlier
  "People — done 2026-07-10" pass built) — so any resident's row, not just
  the followed one, can open the graphical view for *that* resident, not
  only the one currently followed. `PersonRow` gained an `onOpenTree: (Long)
  -> Unit = {}` parameter (default no-op so no other call site needed
  updating for compilation, though all six `PersonRow(...)` call sites in
  `PeopleScreen` were in fact updated to pass it through).
- **Deliberately scoped out / left open:**
  - **In-laws are not shown.** The tree covers blood generations
    (grandparents/parents/children/grandchildren) plus the resident's own
    partner, but not the partner's parents/siblings — the brief's acceptance
    language ("2 generations up/down") didn't ask for affinal relatives, and
    `familyOf()` itself never included them either, so this keeps the same
    boundary the existing text-list feature already drew.
  - **No force-directed/physics graph** for the relationship map, per the
    brief's own explicit steer toward "radial cluster is enough."
  - **No drag-to-reposition, no pinch-zoom** on either canvas — both are
    scrollable/static layouts sized to fit typical family/relationship
    counts; a resident with an unusually large family could overflow
    sideways (handled via `horizontalScroll`) rather than being laid out
    with true constraint-based graph packing.
  - **Grandparent/grandchild traversal is a plain two-hop walk**, not a
    general ancestor-search — a resident whose grandparent chain skips a
    generation (e.g. missing an intermediate parent record) simply won't
    show that branch, matching the "handle missing data gracefully, don't
    error" instruction rather than attempting inference.
  - **Not visually verified on a device/emulator** — no emulator was
    available for this session, and building the app was explicitly
    disallowed. The radial angle math, connector Canvas draws, and dialog
    layout were reasoned through against existing Canvas usage in
    `TownRenderer.kt`/`Components.kt` and Compose layout semantics, but
    actual on-screen spacing/overlap (especially the relationship map at
    high relationship counts, or the family tree at maximum traceable
    depth) has not been eyeballed.
  - **No changes to `RippleApp.kt`, `AndroidManifest.xml`, or any
    notification/WorkManager file** — respected the concurrent agent's
    territory for the entire task; the new feature is fully self-contained
    inside `feature/people/*` as a `Dialog`, not a nav-graph route, for
    exactly this reason.

### 2026-07-10 — Phase 4 kicked off: external world pressure

First Phase 4 backlog item — `ExternalWorldEventProvider` — implemented as a
deliberately small, scoped-down MVP per the task brief's explicit
instruction not to attempt the whole Phase 4 vision in one pass (this phase
is a substantially larger, different kind of item than anything in Phase
2/3: an outside-world layer, not another town-internal system). Another
agent was concurrently editing UI/Compose files in the same checkout, so
this pass stayed strictly backend/simulation — no `./gradlew` or `git`
commands run, code-only, ready for the orchestrating session to
build/test/commit. Also hit (and worked around) a live file-contention race
with the concurrent agent while editing this very file — a couple of `Edit`
calls against this document failed their conflict check mid-session because
the file was being rewritten at the same moment; re-reading and retrying
immediately resolved it without losing either agent's work.

- Read `docs/simulation-rules.md`'s Economy/Price drift sections first, per
  the task brief, since "fuel prices rise → delivery costs rise" is
  explicitly economy-flavored — confirmed `EconomySystem.dailySettlement`'s
  per-business `overheads(biz.type)` daily expense calculation is the
  natural, already-existing landing point for a delivery-cost pressure, and
  that `WorldState` still had no macro/world-level pressure field at all
  (confirmed still true, as `PriceDriftSystem`'s author had already noted).
- **Naming collision found and resolved.** The brief's exact names —
  `ExternalWorldEventProvider` / `WorldPressureMapper` — turned out to
  already be claimed by pre-existing placeholder interfaces in
  `core/simulation/providers/FutureProviders.kt` (`suspend fun
  pendingPressures(sinceRealMs: Long): List<WorldPressure>`, wired for DI in
  `di/AppModule.kt` with `NoOp...` defaults) — a seam clearly reserved for a
  *later*, real external/async feed (network- or LLM-backed), not this
  task's small deterministic in-engine system. Same identifiers in a
  different package would still compile, but silently — a real risk of an
  accidental wrong import down the line, and it would blur exactly the kind
  of distinction this task's own brief cares about (curated/deterministic
  vs. a future real feed). Resolved by naming the concrete engine-internal
  types `CuratedWorldPressureFeed` and `WorldPressureMechanicMapper`
  instead — same responsibilities the backlog item describes, distinct
  identifiers from the untouched future-architecture placeholder (left
  completely alone, still wired to its `NoOp` defaults in `AppModule.kt`).
- **`CuratedWorldPressureFeed`** (the curated feed) and
  **`WorldPressureMechanicMapper`** (the strict mechanical mapping) — two
  clearly separated `object`s in one new file, `ExternalWorldEventProvider.kt`
  (kept this filename since it's the backlog item's own title), per the
  brief's explicit "strict mapping" language keeping curation and mechanical
  effect visibly apart even though they live in the same file. Run daily
  (`CuratedWorldPressureFeed.updateDaily`), last in `SimulationCoordinator`'s
  `if (newDay)` block.
  - **Curated feed.** Eight entirely fictional, abstract pressure kinds in
    matched rise/ease pairs (fuel prices, national harvest, trade routes,
    economic confidence) — no real place names, companies, politics or
    current events, consistent with the rest of Ripple's fictional town.
    Deliberately scoped to **at most one active pressure at a time,
    town-wide** — no overlapping/stacking pressures, no per-business or
    per-resident targeting; explicitly called out as a deliberate scope-down
    in both docs, matching how every other system this session has been
    honest about what's deferred. A small daily roll
    (`START_CHANCE_PER_DAY`, 2%) may begin one (via `ctx.rng.pick`) only
    while none is active; a started pressure runs 14–45 in-game days before
    auto-resolving. New, narrowly-scoped `EventType.NATIONAL_PRESSURE`
    (`PUBLIC`) fires on both start and resolution — checked
    `ImportanceScorer`'s and `NewspaperGenerator`'s `else ->` fallback
    safety first, same as every other new event type this session (8.0 base
    importance, `StoryCategory.TOWN_NEWS`), so no further scoring/newspaper
    wiring was needed. Deliberately did not reuse `EventType.TOWN_MILESTONE`
    even though it "might fit loosely" per the brief — its 60.0 base
    importance and "real town accomplishment" flavour don't suit ambient
    background news the town merely overhears, so a new type was the more
    honest choice. Framed as background/abstract town-wide news (a line of
    overheard talk), never personal.
  - **Mechanical mapping — one clean hook only.** Per the brief's explicit
    instruction to pick ONE well-justified hook rather than touching
    multiple systems: only `FUEL_PRICES_RISE`/`FUEL_PRICES_EASE` currently
    map to anything mechanical — a multiplier
    (`WorldPressureMechanicMapper.overheadMultiplier`, 1.15 rise / 0.92 ease
    / 1.0 otherwise) composed directly into `EconomySystem.dailySettlement`'s
    existing per-business overhead expense line
    (`overheads(biz.type) * WorldPressureMechanicMapper.overheadMultiplier(state)`)
    — the literal "fuel prices rise → delivery costs rise" chain the
    backlog names, landing on the one place in the codebase that already
    models a business's costs. `PriceDriftSystem`'s struggling-bias and
    `BusinessRivalrySystem`'s standing calculation were deliberately left
    untouched in this pass — composing a second hook onto either would blur
    which system actually caused a given price/rivalry change, undermining
    the "strict mapping" / traceability constraint the brief explicitly
    calls out. The other six curated kinds (harvest, trade routes,
    confidence) are recorded and reported on but carry **no** mechanical
    effect yet — honestly scoped as flavour-only rather than bolted onto an
    unrelated system just to give every kind "something to do".
- Modelled with a new `ExternalPressureKind` enum and `ExternalPressure`
  data class (`core/model/WorldState.kt`) plus a single new
  `WorldState.externalPressure: ExternalPressure?` field (`null` most of the
  time) — a plain new field with a safe default, no schema migration. All
  randomness (whether a pressure starts, which kind, how long it runs) goes
  through `ctx.rng`, never `Math.random()`.
- Docs: new "Phase 4: External world pressure" top-level section in
  `docs/simulation-rules.md`, placed after the existing Phase 3 sections and
  before "Offline catch-up" (the first Phase 4 content in that document);
  tick-pipeline summary line at the top also updated.
  `docs/backlog.md`'s Phase 4 bullet list updated: `ExternalWorldEventProvider`
  marked `[x]` as a scoped-down MVP with `WorldPressureMapper` named
  explicitly; `NarrativeTextProvider`/`DialogueProvider`, the national layer,
  and shareable town chronicles all explicitly marked still open, with a
  one-line note each on why this pass didn't (and shouldn't have) touched
  them.

Deliberately scoped out, stated explicitly per the task brief: the
`NarrativeTextProvider`/`DialogueProvider` LLM narrative layer (this
system's pressure descriptions are small, fixed, hand-written strings per
kind, not generated text, and are not a substitute for that item); the
richer "national layer" country context (taxes, broader trends) beyond this
one pressure slot; shareable town chronicles export. Within
`ExternalWorldEventProvider` itself: no overlapping/stacking pressures (one
active at a time, by design); no mechanical effect for six of the eight
curated kinds (harvest/trade routes/confidence are flavour-only for now).

Not run this session (per the parallel-work constraint): `./gradlew`
build/test and any `git` commands. Code-only, ready for the orchestrating
session to build/test/commit.

### 2026-07-10 — Phase 3: generational play — family reputation, era summary

Closes out the "Generational play" Phase 3 item's last two open pieces
(inherited beliefs and heirlooms were already implemented in an earlier
pass) — family reputation and the death-of-followed flow growing into an
"era summary." Another agent was concurrently building council-seats/
elections in the same checkout, touching new files and `SimulationCoordinator
.kt`; this pass deliberately avoided that file entirely (neither feature
needed pipeline wiring) and, where it landed in the same file the other
agent was also editing (`BuildingLifecycleSystem.kt`), the two changes
composed additively without conflict — confirmed by re-reading the file
after the other agent's concurrent edit landed a `ElectionSystem
.repairChanceBonus(state)` term in the very same `repairChance` expression.
No `./gradlew` or `git` commands run, per the parallel-work constraint —
code-only, ready for the orchestrating session to build/test/commit.

- **Family reputation.** New `FamilyReputationSystem.kt` — deliberately
  *not* a new persisted running total. `Resident.reputation` already exists
  and already reacts to exactly the kind of collective family deeds that
  should feed a lineage's name (petitions, crime, business fortune, affairs,
  elections — all already move it via `ConsequenceEngine`/`PetitionSystem`/
  `CrimeSystem`/`ElectionSystem`), so a second hand-maintained figure would
  either duplicate that bookkeeping or silently drift out of sync with it.
  Instead `familyReputationOf(state, resident)` computes a weighted mean **at
  read time**: the resident's own reputation at full weight, every other
  living member of their current household at 0.7 (households already
  persist and merge across marriages via `ConsequenceEngine`'s existing
  "households merge" rule), and up to two generations of direct ancestors
  (`motherId`/`fatherId`, alive or dead) at a weight decaying ×0.4 per
  generation — a family's name outlives the people who built it, but fades.
  Falls back to the 50.0 town-wide default for anyone with no traceable
  family. `standingModifier(state, resident, maxSwing)` turns that into a
  small, bounded, centred-on-zero modifier for composing into existing rolls.
  Given two genuine, small, bounded effects — checked `ConsequenceEngine`'s
  marriage household-merge logic first to confirm households were the right
  persistence unit, and `BuildingLifecycleSystem`/`InteractionSystem` for
  natural, low-risk hook points that didn't require touching
  `SimulationCoordinator`:
  - `BuildingLifecycleSystem.updateDaily`'s home-repair chance (not
    business — family standing is a personal-name effect) now adds
    `standingModifier(…, 0.05)` on top of the existing 0.15 base chance,
    clamped overall to 0.02–0.35.
  - `InteractionSystem.interact`'s pleasant-exchange branch now adds a small
    ±3.0 trust nudge from each side's family standing, but *only* on a
    resident's first-ever meeting with someone (`familiarity < 5.0`) — a
    reputation precedes a first impression, then gets out of the way once
    real shared history exists.
  See `docs/simulation-rules.md#family--generations`.
- **Era summary.** The death-of-followed UI flow already existed in full
  (`WorldRepository.detectFollowedDeath` builds a `DeathSummary` — family
  left behind, a life-summary line, follow suggestions — surfaced via the
  pre-existing `DeathSummaryDialog`), so this extends it rather than
  building anything new. `LifecycleSystem.die`'s only change: `PERSON_DIED`'s
  payload gains `"bornAt"` (the deceased's `bornAt` sim-minutes), since the
  engine itself has no queryable event history inside `WorldState` — only
  the database-layer event log does (`EventDao.eventsBetween`, pre-existing,
  previously used only for newspaper-buffer priming). `WorldRepository`
  gains `EraSummary` (years lived, notable-town-event count + the top 4
  descriptions, the resident's own top 4 memories by importance, and a warm-
  relationship count) and `buildEraSummary`, computed **only** for the
  resident actually being followed (`DeathSummary.era: EraSummary?`, null
  otherwise — deliberately not computed for every death in the log).
  Reuses `ImportanceScorer.HISTORY_THRESHOLD` (30.0) as the "notable" bar —
  the same one the History timeline itself uses — rather than inventing a
  second one. No new screen: `DeathSummaryDialog` (`feature/town/
  TownSheets.kt`) gained a "Their era" section directly under the existing
  life summary, only rendered when `era` is non-null. See
  `docs/simulation-rules.md#family--generations`.

Deliberately scoped out: no UI surface for family reputation itself (no
"family reputation" number shown anywhere in the app yet — it only acts as
an invisible modifier on the two hooks above); no further consequence-rule
integration (e.g. family reputation is not yet read by `PetitionSystem` or
`ElectionSystem`'s candidate scoring — left alone specifically to avoid any
risk of touching files the concurrent elections work was actively changing);
no era-summary UI beyond the two new text blocks in the existing dialog (no
dedicated "era" screen, no illustrated timeline); `EraSummary` is built once
at death and not persisted anywhere (recomputed from the DB/state each time
`detectFollowedDeath` runs, cheap enough not to need caching).

### 2026-07-10 — Phase 3: local politics — council seats & campaign-driven elections

Seventh Phase 3 backlog item and, with this, the "Local politics" bullet is
now fully closed (petitions were already done; this closes out council
seats, campaigns, and a policy effect). Another agent was working
family/generational simulation files (`LifecycleSystem.kt` explicitly
off-limits this round) in the same checkout, so this was code-only — no
`./gradlew` or `git` calls made, and `LifecycleSystem.kt` was read but never
edited.

- **New `ElectionSystem.updateDaily`**, wired into `SimulationCoordinator`'s
  `if (newDay)` block directly after `LifecycleSystem.updateDaily` (so it
  always sees that call's same-day `nextElectionAt`/`mayorId` result), same
  bounded-`object` pattern as every other daily system. Deliberately layers
  on top of the pre-existing, unmodified `LifecycleSystem.election()` rather
  than replacing it — that function already existed, already read
  `nextElectionAt`/`mayorId`, and already factored `reputation` into who
  wins; rewriting it would have meant touching the one file explicitly ruled
  out this round anyway, so the scoped design turned that constraint into
  the actual shape of the feature: campaigns work *by* nudging the same
  `reputation` field the untouched vote logic already reads, rather than a
  second, parallel outcome rule.
  - **Calling.** 20 days ahead of `nextElectionAt`, the same candidate pool
    shape `LifecycleSystem.election()` will independently re-derive at the
    vote gets declared early and given a campaign (`Candidacy(residentId,
    support, actionsTaken)` per candidate, new `WorldState.candidacies`
    list). `EventType.ELECTION_CALLED` fires here — it already existed in
    `EventType` and was already fully wired into `ImportanceScorer` (30.0
    base importance) and `NewspaperGenerator` (`StoryCategory.TOWN_NEWS`,
    falls through cleanly to its existing `else -> e.type.label` headline
    case) but nothing had ever actually emitted it before now; checked both
    first, as instructed, before adding anything.
  - **Campaigning.** A bounded daily roll per candidate (30% chance, capped
    at 10 campaigning days total — a short push, not a sub-simulation).
    Support gained isn't a coin flip: a base amount plus a **track record**
    bonus (petitions the candidate personally started *and won*, via
    `PetitionSystem`'s own success list — reusing that system's existing
    data rather than inventing a parallel "political achievements" stat)
    plus a **familiarity** bonus (mean `Relationship.familiarity` across
    everyone the candidate already knows — a stranger with no relationships
    gets nothing here) plus a small `ctx.rng` wobble. Each landed campaign
    day also nudges the candidate's own `reputation` up — the actual
    mechanism by which campaigning influences the vote, since
    `LifecycleSystem.election()`'s existing scoring already reads
    `reputation` — and sends them to the town hall for a visible `COMMUNITY`
    campaign stop via the existing `ctx.sendTo`.
  - **Council seats — the genuinely new piece.** The day the vote lands
    (detected by watching `nextElectionAt` advance past the campaign's
    `campaignEndsAt`, which `LifecycleSystem.election()` always does once it
    fires), runners-up (everyone in the race except the winning `mayorId`)
    are ranked by accumulated campaign `support` and the top 2 seated as
    councillors in a new `WorldState.councillorIds` list — replaced each
    election, not additive. Each new councillor gets an occupation label
    (if previously unemployed), a small reputation/purpose lift, and an
    `ACHIEVEMENT` memory; a `TOWN_MILESTONE` event announces the seated
    council.
  - **Policy effect.** While `mayorId` is non-null,
    `ElectionSystem.repairChanceBonus` adds a small, fixed bonus to
    `BuildingLifecycleSystem`'s per-building daily repair-chance roll —
    discovered mid-session that `BuildingLifecycleSystem.kt` had already
    grown a `FamilyReputationSystem`-driven repair-chance modifier since this
    session started reading it (the other agent's concurrent
    family/generational work), so the mayoral bonus was wired to compose
    additively with that existing modifier rather than the flat
    `REPAIR_THRESHOLD` originally sketched — same file, same roll, both
    sources add together, still clamped to that system's existing
    `0.02..0.35` bounds. `BuildingLifecycleSystem.kt` is a different file
    from the off-limits `LifecycleSystem.kt`, so editing it stayed within
    this session's constraints.
- Modelled with a new `Candidacy` data class (`core/model/WorldState.kt`)
  plus `WorldState.campaignEndsAt: Long?` and
  `WorldState.councillorIds: MutableList<Long>` — plain new fields with safe
  defaults, no schema migration. `WorldUi`/`WorldSnapshot.kt` also threads
  `councillorIds` through to the UI layer alongside the pre-existing
  `mayorId`, for parity (only construction site updated). All randomness
  through `ctx.rng`.
- Docs: new "Local politics: elections" section in `docs/simulation-rules.md`
  right after "Local politics: petitions"; `docs/backlog.md`'s Local
  politics bullet marked `[x]` — this closes the entire bullet (petitions,
  council seats, campaigns, and a policy effect are all now implemented).
  Deliberately out of scope, called out explicitly in both docs: policy
  platforms/issue positions for candidates, individual voter ballots (the
  vote itself is still the existing aggregate scoring, unchanged),
  councillor-specific powers beyond the shared repair bonus, recall
  elections/resignations, negative campaigning between rivals.

Not run this session (per the parallel-work constraint, and the explicit
`LifecycleSystem.kt` no-touch rule): `./gradlew` build/test and any `git`
commands. Code-only, ready for the orchestrating session to build/test/commit.

### 2026-07-10 — Phase 3: Economy v2 — property market (households buying homes)

Sixth Phase 3 backlog item, closing out Economy v2's fourth and last piece —
the property market — after rivalries, price drift and business succession.
New `PropertyMarketSystem.updateDaily`, run daily right after
`BusinessSuccessionSystem`, following the same bounded-`object` pattern as
every other daily system. Another agent was working UI/Compose files in the
same checkout, so this was code-only — no `./gradlew` or `git` calls made.

Deliberately a scoped-down MVP, not a full real-estate sim: a household buys
the home it already lives in (`Household.homeBuildingId`) once it's unowned
(`Building.ownerId == null` — a field previously never set for homes at all,
only ever used for business buildings via `GoalSystem.openBusiness` /
`WorldGenerator` seeding) and the household's pooled in-town adult `wealth`
clears the asking price (`Building.value`) plus a
`MIN_RESERVE_AFTER_PURCHASE` (200) cushion, so a purchase never strips a
family to nothing. Cash only, straight from resident `wealth` — no
mortgages, loans, or instalments. The price is drawn from the household's
wealthiest adult first, then other in-town adult members in descending
wealth order, until covered, so it's a genuine household purchase rather
than one person's balance going negative. Deliberately reuses the existing
"who ends up with a home" machinery (`GoalSystem.MOVE_HOME`'s free
relocation, `LifecycleSystem.promoteIfNeeded`/`studentReturns`,
`ConsequenceEngine`'s marriage household-merge) rather than inventing a
parallel vacancy-scanning mechanism of its own — this system only ever adds
the "who bought it, and when" fact on top of homes households already ended
up living in through those paths. No negotiation/haggling (asking price is
exactly `Building.value`), no competing bidders (first eligible household
each day in stable id order simply buys), no rental-to-ownership transition
modelling beyond what already existed (an unowned home behaves identically
day-to-day; there's no landlord/tenant concept), and no selling/resale
(moving out via `MOVE_HOME` doesn't clear `ownerId`). New
`EventType.HOME_PURCHASED` (`PUBLIC`) — checked `ImportanceScorer`'s and
`NewspaperGenerator`'s `else ->` fallbacks first (8.0 base importance,
`StoryCategory.TOWN_NEWS`, same pattern `PRICES_SHIFTED`/
`BUSINESS_SUCCESSION` already relied on), so no further wiring was needed.
Bounded to `MAX_HOUSEHOLDS_PER_DAY` (40) households/day; the mechanic is
fully deterministic (pooled-wealth comparison against a fixed price), no
`ctx.rng` roll needed for whether a sale happens, matching
`BusinessRivalrySystem`'s precedent for a bounded daily pass that doesn't
strictly need a dice roll to still be "the bounded daily pattern."

Docs: new "Property market" section in `docs/simulation-rules.md` right
after "Business succession" (tick-pipeline summary line also updated to
list all three previously-undocumented recent daily systems, not just this
one); `docs/backlog.md`'s Economy v2 bullet marked `[x]` — this closes the
entire Economy v2 item (rivalries, prices-that-move, succession, property
market all now implemented). Non-family succession and multi-heir disputes
remain open but were never part of Economy v2's four originally-scoped
pieces.

Not run this session (per the parallel-work constraint): `./gradlew`
build/test and any `git` commands. Code-only, ready for the orchestrating
session to build/test/commit.

### 2026-07-10 — Phase 3: Economy v2 — business succession (voluntary retirement handoff)

Fifth Phase 3 backlog item, closing out Economy v2's third and last
originally-scoped piece. New `BusinessSuccessionSystem.updateDaily`, run
daily right after `PriceDriftSystem`, following the same bounded-`object`
pattern as the rest. Deliberately narrow: only one succession shape is
modelled — an owner at or past `RETIREMENT_AGE` (68) with an adult child
already *employed at that same business* has a small daily chance
(`SUCCESSION_CHANCE_PER_DAY`, 6%) of handing the business down and
retiring. `Business.ownerId` moves to the heir, the heir's employment record
there ends (they run it now, they don't work for it), any active
`RETIRE_WELL` goal completes, a new `BUSINESS_SUCCESSION` event fires
(`PUBLIC`), and both parent and child get an `ACHIEVEMENT` memory of the
day. Bounded to `MAX_BUSINESSES_PER_DAY` (40). This is distinct from —
and doesn't touch — the pre-existing silent ownership transfer in
`LifecycleSystem.die`, which remains the fallback for an owner who dies
without having already retired. See
`docs/simulation-rules.md#business-succession`.

Still open, explicitly out of scope for this pass: non-family succession
(sale to an outside buyer), disputes between multiple ready heirs, and the
property market (residents actually buying/selling homes) — the last
genuinely separate item under the Economy v2 umbrella.

### 2026-07-10 — Phase 3: Economy v2 — prices that move (town-wide price drift)

Fourth Phase 3 backlog item, continuing directly off the price-competition-
and-rivalries slice below: "prices that move" — general, town-wide price
inflation/deflation, independent of `BusinessRivalrySystem`'s per-pair demand
competition. Property market and further business succession remain
deliberately untouched. Another agent was working UI/Compose files
(`feature/people/*`) in the same checkout, so this was code-only — no
`./gradlew` or `git` calls made.

- New `PriceDriftSystem.updateDaily`, wired into `SimulationCoordinator`'s
  `if (newDay)` block right after `BusinessRivalrySystem`, same `object`
  pattern as every other daily system. Deliberately kept as its own file
  rather than folded into `EconomySystem` or `BusinessRivalrySystem` — it's a
  genuinely separate mechanic (town-wide drift vs. per-pair competition) and
  keeping it separate makes the "these two never double-count" invariant easy
  to verify by reading either file in isolation.
  - **Axis separation from rivalries.** `BusinessRivalrySystem` already owns
    `demand` shifts between competing pairs; this system only ever touches
    `priceLevel` and never reads or writes `demand`, so the two mechanics
    compose without conflicting or double-counting the same signal.
  - **No existing macro driver found.** Checked `WorldState` for any
    aggregate wealth/employment/seasonal-pressure field that could drive
    price drift deterministically from macro conditions — none exists yet
    (no inflation index, no town-wide economic indicator). Rather than invent
    new `WorldState` fields for a narrow slice, went with the same "small
    bounded random walk through `ctx.rng`" shape every other system in this
    codebase already uses for its daily dice rolls (`EconomySystem`'s
    footfall roll, `SeasonalEventSystem`'s flood chance), so this stays
    consistent with precedent rather than adding a new kind of mechanic.
  - **Mechanic**: each open, non-public-service business independently rolls
    `DRIFT_CHANCE` (12%) per day; when it drifts, the direction is biased —
    `STRUGGLING_DOWN_BIAS` (75% down) for a business with `daysInTrouble > 0`
    or a negative balance (discounting to chase trade), `PROSPEROUS_UP_BIAS`
    (65% up) for one above `EconomySystem.EXPANSION_BALANCE` (9 000, reusing
    the existing "healthy business" threshold rather than inventing a new
    one), otherwise a 50/50 coin flip. Step size `DRIFT_STEP` (0.02, ~2%),
    clamped to `PRICE_LEVEL_MIN`/`PRICE_LEVEL_MAX` (0.7–1.4) on the same
    `Business.priceLevel` field `EconomySystem.hourlyFootfall` already
    multiplies into customer spend and `BusinessRivalrySystem.standing`
    already factors into competition — composes with both existing readers,
    duplicates neither.
  - **Newsworthiness**: a single day's 2% step isn't a headline. New
    `EventType.PRICES_SHIFTED` (`PUBLIC`) fires only the day a business's
    `priceLevel` first crosses `NEWSWORTHY_SWING` (0.10 from the 1.0
    baseline) in a given direction — checked `ImportanceScorer.baseImportance`
    and `NewspaperGenerator.categoryFor`/`headlineFor` first: both have safe
    `else ->` fallbacks (8.0 base importance; `StoryCategory.TOWN_NEWS`), so
    adding the new type needed no further wiring in either place.
  - Bounded to `MAX_BUSINESSES_PER_DAY` (60) businesses/day, matching the
    "cap processed entities per day" pattern every other daily system uses.
    All randomness (whether a business drifts, direction, when biased-but-
    not-certain) goes through `ctx.rng`, never `Math.random()`.
- Docs: new "Price drift" section in `docs/simulation-rules.md` right after
  "Business rivalries" (with the actual constants); `docs/backlog.md`'s
  Economy v2 bullet updated — "prices that move" now marked implemented,
  property market and business succession still explicitly called out as
  open.

Not run this session (per the parallel-work constraint): `./gradlew`
build/test and any `git` commands. Code-only, ready for the orchestrating
session to build/test/commit.

### 2026-07-10 — Phase 3: Economy v2 — business price competition & rivalries

Third Phase 3 backlog item, scoped down per the task brief: same-type
business price/demand competition and owner rivalries only; general price
inflation/deflation ("prices that move"), the property market and further
business succession work deliberately left for separate passes. Another
agent was working UI files (`feature/news/`) in the same checkout, so this
was code-only — no `./gradlew` or `git` calls made.

- New `BusinessRivalrySystem.updateDaily`, wired into `SimulationCoordinator`'s
  existing `if (newDay)` block after `PetitionSystem`, following the
  established `object` pattern (`CrimeSystem`/`PetitionSystem`/
  `SeasonalEventSystem`) rather than folding into `EconomySystem` — kept as a
  clearly separable, well-commented addition:
  - **Price/demand competition**: every open, same-`BusinessType` business
    pair is compared on *standing* (`reputation − (priceLevel − 1) × 40`);
    the better-standing business gains `demand`, the worse one loses it —
    `DEMAND_SHIFT_PER_DAY` (2.0), `coerceIn(5.0, 95.0)` matching
    `EconomySystem`'s existing clamp convention. Deliberately gentle so
    competing businesses drift apart over weeks, not days.
  - **Owner rivalry**: gated on the pair's standing gap staying within
    `CLOSE_COMPETITION_THRESHOLD` (20.0) — a business simply outclassing its
    rival doesn't count as ongoing competition. While close, the owners'
    existing relationship (`state.relationshipOrCreate`) drifts daily
    (resentment `+0.6`, affection `−0.3`) and, once it crosses the *same*
    thresholds `InteractionSystem.updateKind` already uses for personal
    rivalries (resentment > 55, affection < 30), the relationship kind is set
    to `RelationshipKind.RIVAL` and `RIVALRY_FORMED` emitted directly in this
    system rather than routed through `updateKind` — investigated first and
    confirmed `updateKind` is only reachable from co-located
    `InteractionSystem.interact()` calls, so two business owners competing
    for trade would otherwise never trigger it. `RIVAL` isn't in
    `InteractionSystem.FIXED_KINDS`, so this is safe; family/partner/spouse/
    former-partner/affair relationships are explicitly skipped so business
    competition never overwrites a relationship that means something else,
    and an owner can't rival themselves if they own both businesses.
  - Bounded to `MAX_PAIRS_PER_DAY` (40) pairs/day; all randomness would go
    through `ctx.rng` (none needed here — the mechanic is fully deterministic
    once standing is computed, matching the "bounded, deterministic daily
    roll" pattern without literally needing a dice roll).
- Docs: new "Business rivalries" section in `docs/simulation-rules.md` right
  after "Economy" (with the actual constants), tick-pipeline line updated;
  `docs/backlog.md`'s Economy v2 bullet marked `[~]` with prices-that-move,
  property market and further succession explicitly called out as still
  open (business-to-heir handoff on death already existed beforehand, in
  `LifecycleSystem.die`, and is unchanged by this work).

### 2026-07-10 — Phase 3: generational play — inherited beliefs, heirlooms

Second Phase 3 backlog item, scoped down per the task brief: inherited
beliefs/trauma and heirlooms only; family reputation and the "era summary"
growth of the death-of-followed flow deliberately left for a separate pass.
Another agent was working UI/rendering files (`feature/history/`,
`feature/news/`) in the same checkout, so this was code-only — no
`./gradlew` or `git` calls made.

- Extended `LifecycleSystem.die` (no new system file — this is a natural
  extension of the existing wealth/business inheritance logic right there)
  with two bounded, deterministic additions, both run once per death:
  - **`passDownBeliefs`**: ranks the deceased's memories by `importance`
    then `emotionalIntensity`, keeps those with a non-null `beliefFormed`
    and `importance ≥ 65`, takes the top 2, and adds each as a new
    `CHILDHOOD` memory ("*Name* used to say: \"...\"") to every surviving
    detailed child, at 45% of the original memory's intensity — a
    secondhand family story, deliberately duller than the lived memory.
  - **`passDownHeirloom`**: if the deceased holds a positive memory
    (`ACHIEVEMENT`/`INSPIRATION`/`ROMANCE`) with `importance ≥ 75`, one
    heir (adult in-town detailed child preferred, tie broken via
    `ctx.rng.pick`, falling back to any surviving detailed child, then the
    partner — reusing the same "who inherits" shape as the existing
    business-inheritance code just above it) gets a small trade-themed
    heirloom string (e.g. carpenter → "well-used toolbox") appended to
    their `ideaSeeds` — the same list `GoalSystem` already reads to help
    trigger `START_BUSINESS` — plus an `INSPIRATION` memory recording the
    gift. No new inventory data model or UI; this composes with the
    existing idea-seed → goal-formation pipeline instead of adding a new
    mechanic.
- All randomness (heir tie-breaking) goes through `ctx.rng`, never
  `Math.random()`. No new `Resident`/`WorldState` fields were needed —
  `ideaSeeds` and `Memory.beliefFormed` already existed for exactly this.
- Docs: `docs/simulation-rules.md`'s "Family & generations" section gets a
  new "Inherited beliefs" / "Heirlooms" subsection with the actual
  thresholds; `docs/backlog.md`'s Phase 3 bullet marked `[~]` with family
  reputation and "era summary" explicitly called out as still open.

### 2026-07-10 — Phase 3: local politics — petitions (noise, rents)

First Phase 3 backlog item, scoped down per the task brief: petitions only,
council seats and campaign-driven elections deliberately left for a separate,
larger pass. Same rigour as the Phase 2 items above (implementation +
`docs/simulation-rules.md` section + backlog checkbox), and same
parallel-work constraint as the seasonal-events pass below — another agent
was working UI/rendering files in the same checkout, so this was code-only,
reasoned through by reading, no `./gradlew` or `git` calls made.

- **New `PetitionSystem`**, called daily from `SimulationCoordinator`
  alongside the other daily-pass systems. A politically-interested
  (`politicalInterest > 0.5`) in-town adult personally affected by a problem
  can start a petition (35% roll once eligible): **noise** (home within
  `NeedsSystem.NOISE_RADIUS` of a `noise > 40` building *and* comfort
  genuinely suffering, reusing `NeedsSystem`'s own proximity rule rather than
  inventing a new one) or **rent** (household `monthlyRent × 1.5` exceeds
  personal wealth — a burden test, not a flat threshold). Capped at 2 active
  petitions town-wide, 1 new start per day, no resident stacking a second
  petition of their own. Each day, sympathetic detailed residents (same-radius
  neighbours / fellow rent-burdened, or just generally politically engaged)
  get a bounded daily chance to sign (22%, max 6 new signatures/petition/day).
  A petition resolves — success or lapsed deadline (21 days) — against a
  population-scaled threshold (`8 + pop/12`, capped 22): success applies a
  real, bounded policy effect (noise −18 on the target building plus a
  one-off comfort lift for nearby residents; rent −40 on the target household
  plus financial-security/stress relief for its members) and a
  reputation/purpose boost for the organiser; failure costs the organiser
  reputation and stress, no policy effect. New `PETITION_STARTED`/
  `PETITION_RESOLVED` event types, `PUBLIC` visibility (town politics is
  public business, unlike affairs/rumours), resolution's `causeIds` pointing
  back at the start event so the cause viewer shows the whole arc, both fed
  through `ConsequenceEngine.onEvent`. Modelled with a new `Petition`
  data class in `core/model/WorldState.kt` (new `WorldState.petitions` list,
  default-safe since `WorldState` checkpoints as one JSON blob — no schema
  migration). See `docs/simulation-rules.md#local-politics-petitions`.

Not run this session (per the parallel-work constraint): `./gradlew`
build/test and any `git` commands. Code-only, ready for the orchestrating
session to build/test/commit.

### 2026-07-10 — Seasonal events: harvest fair, winter market, river floods

Sixth Phase 2 **Simulation** backlog item, same rigour as the other five
(implementation + `docs/simulation-rules.md` section + backlog checkbox).
Note: this ran alongside a parallel session doing unrelated Mobile UI rebuild
work on the same checkout, so this pass deliberately made no `./gradlew` or
`git` calls of its own — code-only, reasoned through by reading, left for the
orchestrating session to build/test/commit.

- **`SeasonalEventSystem`**, called daily from `SimulationCoordinator`
  alongside `HealthSystem`/`LifecycleSystem`/`GoalSystem`/
  `BuildingLifecycleSystem`. Two fixed calendar dates plus one weather-gated
  mechanic:
  - **Harvest fair** (month 8, day 15 — ahead of the winter months used
    elsewhere): detailed in-town residents get a social/purpose/stress lift,
    open bakeries/grocers/pubs get a demand boost, and a `COMMUNITY_EVENT`
    fires at the park (if one exists) — following the same precedent as
    `ConsequenceEngine`'s "community gathers" rule for `PERSON_DIED`.
  - **Winter market** (month 11, day 10): the same shape, smaller and
    comfort-flavoured, boosting cafés/hardware shops/tailors instead, anchored
    at the town hall.
  - **River floods**: `WorldGenerator` already seeds `TileType.WATER` down
    the map's east edge. While it's raining or storming, a small daily chance
    (5%/8%) hits one building within 3 tiles of water — condition −18..−32,
    harsher than the existing generic storm-damage roll in
    `NeedsSystem.updateWeather` (−6..−18, not water-proximity-aware), plus a
    safety/comfort hit for any resident currently inside, `visibleChanges`
    marked "Flood damage" (capped at 6), and a `WEATHER_DAMAGE` event with
    flood-specific text/severity fed through `ConsequenceEngine.onEvent` for
    further fallout. Bounded to one flood per day.

  See `docs/simulation-rules.md#seasonal-events`.

Not run this session (per the parallel-work constraint above): `./gradlew`
build/test and any `git` commands. The change is code-only and ready for the
orchestrating session to verify and commit.

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

*(Update: all three addressed in a later follow-up pass — see the
"Engineering debt to pay alongside" section below for root causes and
fixes.)*

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
- [x] Population moved out of the top strip into a town-overview overlay:
  `HudChip("Pop …")` removed from the always-visible HUD row; a new
  `HudVectorIconButton(Icons.Filled.Insights, …)` next to the settings gear
  opens `TownSheet.TownOverviewSheet` (new sealed-class case, reuses the
  existing `ModalBottomSheet`). Content (`TownOverviewSheetContent` in
  `TownSheets.kt`) shows population, in-work count, wellbeing (derived as
  `100 - average stress`), average health, and average savings — all
  computed client-side in a new lazy `WorldUi.townStats` (`WorldSnapshot.kt`)
  from the resident list already on the snapshot. **Simplification:** the
  simulation has no dedicated town-statistics tracker (no crime rate,
  education, or environment metric anywhere in `core/model`), so those
  brief-requested metrics are not shown — the overlay says so explicitly
  rather than fabricating numbers, and only surfaces what's genuinely
  computable from `ResidentUi` fields already exposed (`health`, `stress`,
  `wealth`, `occupation`/`employerName`).
- [x] Time controls collapsed into a single expandable pill: bottom-left
  now shows one `SpeedButton` with the current speed (e.g. "▶ 1×", "⏸
  Paused") when collapsed; tapping it reveals the full pause/1×/3×/10× row
  in an `AnimatedVisibility(fadeIn/fadeOut)`, and picking any option (or the
  same option again) collapses it back via a local
  `remember { mutableStateOf(false) }` in `TownScreen.kt`. No new ViewModel
  state needed.
- [x] Event banners now stack up to 2 with enter/exit animation: replaced
  the old "always show `recentEvents.firstOrNull()`, never dismissed" ticker
  with a local `mutableStateListOf<EventUi>` capped at 2 (newest first). A
  `LaunchedEffect(recentEvents)` appends newly-seen event ids; each banner
  gets its own `LaunchedEffect(banner.id)` that waits ~4s then flips its
  `visible` flag off (giving `AnimatedVisibility` — fade + slight vertical
  slide — time to animate out) before removing it from the list, mirroring
  the existing `alerts`/`collectLatest`+`delay` pattern already used for the
  gold alert banner.
- Already close to the brief before this pass: the town canvas is already
  full-bleed (`Modifier.fillMaxSize()`), the top HUD is already a compact
  translucent chip strip (date/time/weather/nudges, not a dense stats
  bar), the followed-resident indicator is already a small pill (not a full
  banner). These weren't rebuilt since they already roughly match the
  brief's intent; revisit if the user wants them restyled rather than just
  re-architected.
- Not yet started: the entire modular pixel-art asset system (building
  variants/condition states, resident appearance/animation, environmental
  props) — buildings and residents are still flat procedural rectangles
  (`SpriteProvider`), which is most of what the brief is actually about
  visually and the biggest remaining gap. That's Phase 2 (Visual identity),
  tracked separately, and needs real visual iteration this environment
  can't do.

Verified via the full local unit test suite: same 3 pre-existing failures
as before (nothing new), `NavigationTest` still passes. Committed and
pushed directly to `main`.

**2026-07-10, later same day — remainder of Phase 1 (population overlay,
collapsible time pill, stacked animated event banners) implemented by a
parallel session working only on `feature/town/*` + `core/ui` files (no
Gradle/git access in that session — another agent handled backend
simulation work concurrently in the same working tree). Changed files:
`TownScreen.kt`, `TownViewModel.kt`, `TownSheets.kt`, `data/WorldSnapshot.kt`.
Not yet compiled/tested by that session — needs a build + the Robolectric
suite run before the next commit.**

## Mobile UI rebuild (current priority — supersedes new Phase 2 Simulation work for now)

User brief, 2026-07-10, reference: a dense desktop pixel-art town-management
dashboard mockup. **Reinterpret, don't port**: mobile-first, town-canvas-
dominant, detail lives in overlays/sheets, not permanent panels. Central
experience to preserve: *"Open Ripple. Find the person you follow. Watch
their world continue without you."*

Development order from the brief (status noted inline):

1. **Town canvas** — full-bleed map, pan/zoom, camera follow, compact
   overlays, compact time controls. *All session-tracked items done: camera
   follow-with-override, vector nav icons, population moved into a
   town-overview overlay, collapsible time-control pill, stacked animated
   event banners — see session log above. Remaining gap for this phase is
   purely visual (Phase 2, below), not interaction/architecture.*
2. **Visual identity** — modular buildings (footprint × wall × roof ×
   windows × door × chimney × sign × awning × garden × fence × condition
   overlay…), environmental props, resident appearance variation
   (body/hair/clothing/occupation cues), consistent icons, palette/type
   system. *First slice done 2026-07-10 (built blind, no emulator to verify
   — see risk note below); most of the phase is still open.* Done:
   distinguishing silhouette elements added to `drawBuilding()` in
   `core/ui/SpriteProvider.kt` for BAKERY (striped awning + delivery
   crates), CLINIC (red cross on the sign band), PUB (hanging sign on a
   bracket + outdoor table), SCHOOL (flagpole + fenced-yard hint), GROCER
   (produce crate/stall by the door), and FACTORY (loading-bay hatch marking
   added alongside the pre-existing chimney). `Building.condition` (0-100,
   already drove real simulation consequences but was invisible) is now
   wired through: `SpriteProvider.building()` takes a new `condition`
   parameter (`TownRenderer.kt` passes `b.condition`), and non-abandoned
   buildings below condition 40 get a small worn-wall patch, below 20 a
   roof-damage patch, layered underneath the existing (more severe)
   `abandoned` full-boarding treatment. Cache key now folds in a 5-bucket
   condition range so wear states don't collide with a fresh building's
   cached bitmap.
   **Second pass, same day (still blind, same caveats):** the previously-
   flagged "still open" list — HOUSE, COTTAGE, TERRACE, TOWN_HALL, CAFE,
   BOOKSHOP, TAILOR, HARDWARE, WORKSHOP, VACANT — is now also covered in the
   same `drawBuilding()` `when (type)` block, same additive small-accent
   style, same pre-existing wear/abandoned overlay composing unchanged
   underneath: HOUSE (chimney-smoke wisp above the roofline), COTTAGE
   (flower box under the front window), TERRACE (a low two-tone porch step
   at the door threshold — the three residential types are now visually
   distinct from each other, not just by footprint), TOWN_HALL (grander
   doorway pediment, a small centred clock-face dot on the facade, and a
   taller centred flagpole distinct from SCHOOL's corner one), CAFE (a
   cup-shaped motif on the sign band plus a single small outdoor table —
   deliberately smaller than PUB's — so it reads distinctly from both
   BAKERY's stripes and PUB's bracket sign), BOOKSHOP (a three-book stack
   silhouette by the door), TAILOR (a small mannequin — head dot + shoulder
   block — by the door), HARDWARE (a rung ladder leaning against the wall),
   WORKSHOP (a timber stack by the door, distinct from FACTORY's loading-bay
   hatch), and VACANT (a grimy boxed-in window, a small hanging "to let"
   sign, and a row of weed pixels along the base — so an untenanted
   building reads as visibly derelict even before condition drops low
   enough to trigger the existing worn-wall/roof-damage wear cues or a
   building is flagged fully `abandoned`). No cache-key changes were needed
   for this pass — all ten additions are per-`BuildingType` only (no new
   state-dependent variation), and `buildingCache`'s key already folds in
   `type.ordinal`, so the existing key scheme covers them without
   modification. *Still open for this item: clothing **sets** (see the
   resident-appearance note below — still flat colour swatches, no
   silhouette differences per garment), environmental props (fences/gardens
   outside PARK/CEMETERY, still completely untouched), and animation states
   (see Phase 3 below). Every `BuildingType` now has at least one unique
   silhouette element. Not visually verified on a device/emulator — same
   risk note as the rest of this pass.*

   **Resident appearance variation — second slice, also 2026-07-10 (blind,
   same caveats).** `drawResident()` in `core/ui/SpriteProvider.kt` now
   varies by `LifeStage` and a coarse occupation cue, not just skin/hair/
   shirt/trouser colour: CHILD and TEEN are drawn shorter (head/body/legs
   shifted down 2px/1px on the fixed 10×14 canvas, legs shrunk by the same
   amount so feet still land on the same ground row), ELDER keeps adult
   height but the head/shoulders lean 1px forward (a stoop) and gets a
   1px walking-stick pixel beside the trailing leg. A small set of
   occupation accessory cues (1-3px each) are drawn from `Resident
   .occupation`'s free-text role string (set by `EconomySystem.roleFor()`):
   a pale apron band across the chest for bakery/café/pub/grocery roles, a
   satchel-strap accent for classroom/clerk/bookseller roles, a tool-accent
   pixel at the shoulder for workshop/joinery/repair roles — matched via
   substring on the lowercased occupation string since it's free text, not
   an enum (deliberate choice: `roleFor()` is the single source of truth
   for those strings across all `BusinessType`s, so keying off it is more
   robust than trying to introduce a parallel enum). `SpriteProvider
   .resident()` gained two new defaulted parameters (`lifeStage:
   LifeStage = ADULT`, `occupation: String = ""`) so the one other call
   site (`PixelAvatar` in `Components.kt`, used by resident list rows/
   sheets) kept compiling without changes, though it's now also wired to
   pass the real values through from `ResidentUi` for richer portraits.
   `ResidentUi` gained a new `lifeStage: LifeStage` field (computed from
   the existing `Resident.lifeStageAt()`), following the same "add a UI
   field, thread it through the one construction site" pattern as
   `BuildingUi.condition` before it. Cache key folds in `lifeStage.ordinal`
   and a small `OccupationCue` bucket ordinal so varied sprites don't
   collide in `residentCache`. *Still open, and this is the bigger gap:
   no distinct clothing **sets** per the brief (still just flat shirt/
   trouser colour swatches, no silhouette differences for e.g. a smock vs.
   a suit), no mobility aids beyond the single elder stick pixel, no
   pregnant/injured/carrying states, no body-type range beyond the three
   height tiers, and only 3 of the ~12 occupation roles get a visual cue
   (the rest — hardware/tailor/factory/school-teacher-proper/town-hall/
   mayor/constable — are undifferentiated). Environmental props (fences/
   gardens outside PARK/CEMETERY) and animation states are also still
   completely untouched. All of this needs either a genuine asset pipeline
   or a much richer procedural generator, and real visual iteration
   against a device/emulator to catch pixel-math mistakes — this
   environment has neither, so both slices were written by reasoning
   through coordinates by hand and need a sighted pass before being
   trusted.*
3. **Life animation** — resident movement/behaviour states (idle, walk,
   talk, work, eat, sit, sleep, argue, hug, celebrate, mourn, ill, injured,
   carry, wait, run — brief wants 2-4 frames each), town rhythm (shops
   open/close, school run, deliveries, traffic, weather, day/night).

   **Scoped-down slice done 2026-07-10 (blind, no emulator — same risk
   note as Phase 2 above; this pass is riskier than the session's other
   blind changes since it's about motion/timing, not just static layout).**
   Deliberately did **not** attempt true multi-frame walk-cycle animation
   (real sprite-asset/frame-timing infrastructure — that's the separate,
   larger Phase 2 Product backlog item, "Sprite atlas support... walk
   cycles with 4 frames"). Instead extended the existing pose-derivation
   and weather/tint machinery with small, cheap, deterministic visual
   cues read from data already on `WorldUi`/`ResidentUi`/`BuildingUi` —
   nothing here reads or invents new simulation state, and nothing uses
   per-frame randomness (the animation clock + resident/building id are
   the only inputs, so a frame is reproducible and a crowd doesn't
   animate in lockstep).
   - **`TownRenderer.poseFor` gained a `ResidentUi` overload** (the old
     `poseFor(Activity)` overload is kept, still used unchanged by
     `PixelAvatar` call sites that only had an `Activity` handy) that
     additionally reads `ResidentUi.conditionLabels` — already computed
     by `Resident.activeConditions()` and exposed for the health-notes
     text in `TownSheets.kt` — to split **injured** out from generic
     **ill**: a resident whose activity is `RESTING_ILL`/`AT_CLINIC` and
     who has an active `HealthConditionType.INJURY` condition now maps to
     a new `Pose.INJURED` instead of `Pose.ILL`. `SpriteProvider
     .drawResident()` draws `INJURED` with an uneven two-leg stance (one
     leg 1px shorter, reusing the same asymmetric-leg technique the walk
     cycle's frame-1 already uses) plus a small bandage-patch status mark,
     instead of the sickly-green status dot `ILL` gets. The two other
     `poseFor` call sites (`PeopleScreen.kt`'s followed-resident card,
     `TownSheets.kt`'s resident-sheet header) were switched from
     `poseFor(x.activity)` to `poseFor(x)` so portraits show the same
     injured/ill split as the town canvas, not just a generic sick icon.
   - **Idle "breathing" cue for `Pose.STAND`.** Previously a resident with
     no active pose-changing activity (`Activity.IDLE` and a few others)
     rendered as a single static frame — dead-looking in a crowd next to
     animated walkers. `TownRenderer` now derives a slow animation frame
     for `STAND` (`(clock + residentId) / 6`, six times slower than the
     walk-cycle's per-frame toggle) and `SpriteProvider.drawResident()`
     nudges the arms 1px down on the second frame — a subtle sway/settle,
     not a walk cycle. Reuses the exact same `frame % 2` cache-key/redraw
     path `WALK` already had; no new caching logic needed.
   - **Town rhythm — shops visibly open/close.** `BuildingUi.businessOpen`
     (already computed per-building from `Building.business?.open`, used
     today only in the building bottom sheet's text) was previously never
     read by the canvas itself. `TownRenderer` now draws a flat dusk-toned
     wash over any building where `businessOpen == false` (null for homes
     and non-business buildings, so this only ever touches shops/pub/
     clinic/etc. that actually trade) — a cheap per-building overlay
     rect, drawn once per building per frame from data already iterated
     for the sprite draw call, no extra passes over the resident/building
     lists.
   - Day/night tint and the four weather washes (rain/storm/fog/snow)
     were already implemented before this session and are unchanged.

   **Explicitly out of scope for this slice, from the brief's ~15-state
   list:** *idle* (partially — the STAND sway above) and *walk* (already
   existed) are the only two states that now have any per-frame motion at
   all; **talk, work, eat, sit, sleep, argue, celebrate, mourn, ill**
   already had a distinct *static* pose from before this session and were
   not changed; **injured** is new this session but is a static pose
   variant like the others, not an animation. **hug, carry, wait, run**
   were not implemented at all — `hug` has no distinct simulation signal
   from general `SOCIALISING`/`VISITING` to key off (would need either a
   new `Activity` value or inventing state, both against this task's
   constraint of only reading what's already exposed); `carry` likewise
   has nothing on `ResidentUi` to indicate a resident is carrying
   anything; `wait` has no data distinguishing it from `IDLE`; `run` would
   need a speed/urgency signal that doesn't exist on `Activity` or
   `ResidentUi` today (`TRAVELLING` always maps to the same `WALK` pose
   regardless of how urgent the trip is). None of the four are safe to
   fake with a per-frame-random cue per this task's determinism
   constraint, so they're left untouched rather than guessed at. **Town
   rhythm beyond shop open/close** — school run, deliveries, and traffic
   all imply resident/vehicle movement patterns tied to schedule logic
   that isn't surfaced on `WorldUi` today (there's no "this resident is
   currently commuting to school" signal distinct from generic
   `TRAVELLING`, and no delivery/vehicle concept in the model at all) —
   not attempted.
   *Not visually verified on a device/emulator — in particular the STAND
   sway's timing/amount and the INJURED leg-asymmetry pixel placement are
   exactly the kind of "how something looks/moves" details this
   environment can't catch mistakes in by reasoning alone; flagged as
   higher-risk than the static-layout changes elsewhere in this session
   and worth a sighted pass before being trusted.*
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
   `NewsScreen` are functional but per the brief "feel like unfinished
   placeholders" relative to the reference's density — not yet assessed in
   detail against the specific acceptance criteria.*

   **History — done 2026-07-10 (blind, no emulator — see risk note in
   Phase 2 above; straightforward Compose patterns only, needs a sighted
   pass before being trusted).** `HistoryScreen.kt` rebuilt around a real
   vertical timeline: events now group by day (`SimTime.dayIndex`) nested
   under year headers, not a flat per-year list, using the same dot +
   connector-line timeline motif the old screen already had, just applied
   per-day instead of per-year. Minor vs major presentation: events stay
   filtered at `ImportanceScorer.HISTORY_THRESHOLD` (30) as before — that
   part was already correct and untouched — but events at or above 2×
   that threshold (60) now render as a larger `MajorEventCard` (bigger dot,
   tinted card, type label + description + "why?" link) instead of the
   compact one-line `MinorEventRow` every other event still gets. Added a
   non-wrapping category filter row (`LazyRow` + `FilterChip`, the same
   pattern `NewsScreen.kt`'s archive row already uses — `PeopleScreen.kt`'s
   filter row is a plain `Row` and does *not* actually satisfy "never
   wraps" at high chip counts, so that one was not copied) with All /
   People / Business / Crime / Health / Town & politics buckets, reusing
   `StoryCategory` from `core/model/Goal.kt` (the same enum
   `NewspaperGenerator` categorises stories into) rather than inventing a
   new taxonomy. Since `NewspaperGenerator.categoryFor()` is private, added
   an equivalent `historyCategoryFor(EventType): StoryCategory` local to
   `HistoryScreen.kt` that covers the fuller set of `EventType`s the
   history feed can show (relationships/family/goals/meetings fold into
   `HUMAN_INTEREST`, surfaced under the "People" chip). Tapping any event
   (minor or major) calls the existing `onOpenEvent` callback exactly as
   before, which `RippleApp.kt` wires to `TownViewModel.openEvent(id)` —
   the existing cause-chain sheet is unchanged and untouched. Small
   supporting change: `EventUi` (`data/WorldRepository.kt`) gained a
   `type: EventType?` field (parsed the same way `typeLabel` already was)
   since the UI model previously only exposed the human-readable label,
   not the enum needed for category bucketing — the one construction site
   (`WorldEventEntity.toUi()`) was updated to match.
   *Still open: the brief's day/month/year/**era** zoom levels (only
   day+year grouping was built — era-level was explicitly out of scope
   for this pass); no richer cause-chain visualization (the existing sheet
   is unchanged, still whatever it was before); no dedicated "player
   interventions" filter chip (interventions still only surface via the
   existing nudge count on the town-today card, not as a timeline filter);
   not visually verified on a device/emulator.*

   **News — done 2026-07-10 (blind, no emulator — same risk note as
   History above).** `NewsScreen.kt` rebuilt to read like an actual issue
   rather than a flat card feed, reusing what was already mostly correct
   (masthead, per-`StoryCategory` grouping, headline vs. secondary type
   treatment) and filling the real gaps:
   - **Front page vs. registers, more clearly differentiated.** The
     `HEADLINE` story now sits in its own full-width `RippleColors.Cream`
     block directly under the masthead with `headlineLarge` bold type —
     previously it was just a bigger `Text` in the same flat list as
     everything else. Every non-headline `StoryCategory` section (Town
     news, Business, Births, Deaths, Weddings, Crime & order, Health,
     Weather, Public notices — labels from the enum in `core/model/
     Goal.kt`, unchanged) now gets a rule line above its bold, uppercase
     section header instead of a plain label, so registers read as
     distinct blocks of a real paper rather than one continuous scroll.
     Ordering follows `StoryCategory.entries` as before — untouched, since
     `NewspaperGenerator`'s own emission order already matches it.
   - **Archive navigation, two ways.** The existing bottom `LazyRow` of
     issue chips (already a non-wrapping `FilterChip` row, same pattern
     `HistoryScreen.kt` copied from it) is kept but its labels now include
     the issue date, not just the number, and it's explicitly sorted by
     `issueNumber` (`sortedIssues`) rather than relying on query order.
     New: a compact prev/next control (`Icons.Filled.ChevronLeft/
     ChevronRight`, disabled/dimmed at the ends) straddling the issue-date
     line right under the masthead, so paging through the archive one
     issue at a time doesn't require scrolling to the bottom of a long
     issue first. Both controls call the same existing `viewModel.select
     (issueId)` — no new repository methods needed. Confirmed old issues
     are never deleted: `NewspaperGenerator.generate()` only ever inserts
     (via `state.nextIssueId++`), nothing in `WorldRepository` or the DAOs
     issues a delete against `newspaper_issues`/`newspaper_stories`, matching
     `docs/simulation-rules.md`'s "old issues stay in the archive forever."
   - **Richer day-one empty state.** `NewsViewModel` now also exposes
     `worldTime` (mapped from the existing `WorldRepository.worldUi`
     StateFlow, same source `TownViewModel` reads elsewhere — no new
     plumbing). When available, the empty state computes the next 8am
     boundary using the same day-start logic `NewspaperGenerator.isDue`
     encodes (first issue fires the morning after world start) and says
     "Expect the first edition `<date>`, 8 o'clock sharp" instead of the
     generic "Come back tomorrow morning" — which remains the fallback if
     `worldTime` hasn't loaded yet.
   *Still open, honestly: no paper-texture styling (needs real asset/
   shader work, out of scope per the brief's own exclusion list), no
   serif/typography system overhaul (separate design-system item), and the
   "registers as distinct sections" treatment is still built from
   `MaterialTheme` type scale + colour blocks, not the denser
   newspaper-column layout the original desktop mockup shows — that would
   need real visual iteration against a device to get right. Not visually
   verified on a device/emulator.*

   **People — done 2026-07-10 (blind, no emulator — same risk note as
   History/News above).** `PeopleScreen.kt` picked up the four things the
   brief called out by name:
   - **Search** already existed (an `OutlinedTextField` filtering
     `w.residents` by name into a "Search results" section) — confirmed
     present, left as-is, no changes needed here.
   - **Non-wrapping filter chips.** The brief's own note under History
     flagged that `PeopleScreen.kt`'s filter row was a plain `Row` and
     didn't actually satisfy "never wraps" at high chip counts, unlike
     `HistoryScreen.kt`/`NewsScreen.kt`. Converted it to the same
     `LazyRow` + `items(PeopleFilter.entries, key = { it.name })` +
     `FilterChip` pattern those two screens use — copied directly, no new
     mechanic invented.
   - **Followed-resident card.** The "Following" card already existed and
     already read `w.followedResidentId`/`w.resident(...)` (the same
     `WorldState.followedResidentId` plumbing the town-camera-follow work
     earlier this session also reads) — reused as-is, no new state. Made it
     more prominent per the brief: bigger avatar (52dp → 56dp), `titleLarge`
     name instead of `titleMedium`, and a new one-line family summary
     ("Family: Alex (Partner), Sam (Child)…") appended under the mood line
     when the followed resident has any `familyOf(...)` results — composes
     with the family-tree work below rather than adding a second query.
   - **Expandable family tree (scoped down deliberately, matching how every
     other item in this Mobile UI rebuild section has been scoped down):
     this is a simple expandable text listing, not a graphical tree.** The
     brief separately lists "Family tree visualisation (proper generational
     graph)" as its own, larger Phase 2 Product backlog item below — that
     one was explicitly not attempted here. What was built: `PersonRow`
     (used by every list section — search results, favourites, family,
     friends, frictions, discovered) now takes an optional `family` list
     and, when non-empty, shows a small `ExpandMore`/`ExpandLess`
     `IconButton` that reveals an indented `Column` of "Role: Name" lines
     (`AnimatedVisibility` + `expandVertically`/`shrinkVertically`, mirroring
     the `AnimatedVisibility` pattern already used for the town screen's
     speed pill and event banners) via a local `remember(r.id) {
     mutableStateOf(false) }` per row — no new ViewModel state, no new
     repository calls. The data itself is not new either: `familyOf(world,
     resident)` already existed in this same file (partner, mother, father,
     children, siblings-by-shared-parent, deduplicated) and was already
     used to build the followed resident's own "…'s family" section: it's
     now also called per-row so every listed resident, not just the
     followed one, can expand to show their immediate family. Reads
     `ResidentUi.partnerId/motherId/fatherId/childIds` (`data/
     WorldSnapshot.kt`), which were already threaded from `Resident` — no
     `core/simulation` or model changes were needed or made.
   *Still open: no dedicated relationship-network overlay (also a separate,
   larger Phase 2 Product item, not attempted); the expandable family
   section is text-only with no visual tree/graph lines connecting
   generations — intentionally, per the scoping above; family lookups
   (`familyOf`) walk one generation each way plus partner/siblings only, the
   same depth the pre-existing followed-family section already used —
   grandparents/grandchildren/in-laws are not included; not visually
   verified on a device/emulator.*

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
- [x] Seasonal events: harvest fair, winter market, floods by the river
  tiles. *Implemented: `SeasonalEventSystem.updateDaily`. Fixed-date harvest
  fair (month 8/day 15, wellbeing + food/drink demand boost, `COMMUNITY_EVENT`
  at the park) and winter market (month 11/day 10, smaller comfort boost +
  café/hardware/tailor demand, `COMMUNITY_EVENT` at the town hall); river
  floods roll a small daily chance during rain/storm against buildings near
  the seeded east-edge river, harsher than generic storm damage, feeding
  `ConsequenceEngine` via `WEATHER_DAMAGE`. See
  `docs/simulation-rules.md#seasonal-events`.*

**Product**
- [x] Family tree visualisation (proper generational graph) and a relationship
  map canvas on the People screen. *Implemented — see the 2026-07-10 "Family
  tree & relationship map" session-log entry near the top of this file for
  full detail. Summary: a new `FamilyTreeScreen.kt`
  (`feature/people/FamilyTreeScreen.kt`) full-screen `Dialog` with two tabs —
  a genuinely graphical Canvas-connected generational tree (grandparents →
  parents → resident+partner → children → grandchildren, where traceable)
  and a radial relationship-map canvas (non-family relationships as spokes
  from a centre node, coloured/grouped by `RelationshipKind`, capped at 12 by
  warmth). Entry points wired from both the Following card and every
  expandable `PersonRow` family section in `PeopleScreen.kt` — no dangling
  UI. Reused `familyOf()` and the already-exposed, already-capped
  `ResidentUi.relationships`/`RelationUi` — no `WorldRepository`,
  `WorldSnapshot`, or simulation-core changes were needed. Not visually
  verified on a device/emulator (blind implementation, per this session's
  constraints).*
- Cause viewer as a dedicated branching timeline UI (multi-parent display).
- [x] Follow "moments": short vignette cards when the followed resident does
  something notable. *Implemented in `TownScreen.kt`, reusing the event-banner
  stacking mechanism (`mutableStateListOf`/`LaunchedEffect`/`AnimatedVisibility`)
  built earlier this phase rather than inventing a new animation system. A
  "moment" is the newest event from `recentEvents` where the followed resident
  (`WorldUi.followedResidentId`) is source or target
  (`EventUi.involvedResidentIds`) **and** it clears the same notability bar
  used everywhere else in the app for "this mattered"
  (`ImportanceScorer.HISTORY_THRESHOLD`, the same constant the History
  timeline and era-summary feature already use) — no new repository surface
  area was needed, this is all client-side filtering over data already
  exposed. Presentation is deliberately more prominent than the plain event
  banner: a single Gold-toned card (vs. the banner's plain Cream), the
  followed resident's own `PixelAvatar` (reusing `poseFor`/`SpriteProvider`,
  the same call the resident sheet already makes), and the event's own
  `description` text as the narrative line — no separate text-generation
  system. Bounded to feel special rather than noisy: at most one moment card
  on screen at a time (a single `followMoment` slot, not a list), an 8s dwell
  time (vs. banners' 4s) before it fades out, and a 15s cooldown after
  dismissal before the next moment can appear. Tapping the card reuses the
  existing `viewModel.openEvent(id)` navigation the generic banner already
  had. If the same event would also have appeared as a plain banner, it's
  filtered out of that stack to avoid showing the same happening twice at
  once. Deferred: no distinct "moment" sound/haptic, no queueing of
  near-miss notable events once the cooldown/slot is occupied (they're simply
  dropped, matching the "special, not a queue" design goal), no dedicated
  history of past moments (they remain visible via the ordinary event log).
  Not visually verified on a device/emulator — implemented by careful reading
  of the existing Compose patterns in this file, pending manual review.*
- [x] Real local notifications (opt-in, POST_NOTIFICATIONS permission flow) for
  followed/favourite residents only, delivered on app open or via WorkManager
  summary — still no continuous background work. *Implemented — see the
  2026-07-10 "Real local notifications" session-log entry below for the full
  writeup. Summary: an explicit opt-in toggle in `SettingsSheet` drives the
  standard `rememberLauncherForActivityResult(RequestPermission())` flow for
  `POST_NOTIFICATIONS` (API 33+, no unprompted launch-time request); one
  idempotent `NotificationChannel` ("Followed resident updates"); two delivery
  mechanisms sharing one DB-only `FollowedResidentNotifier.checkAndNotify()`
  (reuses `ImportanceScorer.HISTORY_THRESHOLD`, capped at 3 notifications per
  check) — (a) on app open/resume from `MainViewModel.init`, and (b) a 15-minute
  `PeriodicWorkRequest` (`NotificationCheckWorker`, plain `CoroutineWorker`, no
  wake locks/foreground service). Deferred: deep-linking a tap to the specific
  event/resident (falls back to a plain app-open via `MainActivity`'s existing
  `singleTask` launch mode — no intent-extra handling exists yet to build on);
  richer notification content/grouping (Android's own notification-shade
  bundling by app/channel applies by default, nothing custom built). **The
  periodic Worker is deliberately DB-only, not a full simulation catch-up** —
  see `NotificationCheckWorker`'s doc comment for the three reasons (engine
  confinement to `WorldRepository`'s dispatcher, not silently advancing game
  time off-schedule, and the check not actually needing a fresh tick). **Not
  verified on a real device or emulator — none was available in this
  environment.** This item is materially riskier to leave unverified than the
  session's pure-Compose UI work: manifest/permission declarations, the actual
  system permission-request dialog and its grant/deny branches, notification
  channel creation, and WorkManager's periodic scheduling all depend on real
  OS behaviour that compilation alone cannot catch. Needs a real-device pass
  before being trusted: request the permission both ways (grant and deny),
  confirm a notification actually appears and tapping it opens the app,
  confirm the toggle-off path cancels the periodic Worker
  (`adb shell dumpsys jobscheduler` or WorkManager's own inspector), and leave
  the app closed for 15+ minutes with a followed resident's life continuing
  (via a separate device/emulator instance or a manually seeded notable event)
  to confirm the periodic check actually fires and behaves.*
- Sprite atlas support in `SpriteProvider` + commissioned pixel art replacing
  the procedural placeholders; walk cycles with 4 frames and directions.
- Sound: gentle ambient loops by time-of-day/weather.
- [~] Benchmarks in CI (macrobenchmark for town rendering, JMH-style micro for
  ticks). *Partially implemented, scoped down honestly — see the 2026-07-10
  "Benchmark infrastructure" session-log entry for full detail. Summary: no
  device/emulator exists in this environment, so the two things the bullet's
  own wording names — a real `androidx.benchmark.macro` Compose macrobenchmark
  and a real JMH microbenchmark — were NOT built, since neither can be run or
  even sanity-checked without a connected device (macrobenchmark) or an
  established JMH module (micro). What WAS built and is real, working,
  JVM-only infrastructure: `SimulationTickBenchmark.kt`
  (`app/src/test/kotlin/com/ripple/town/simulation/`), a plain
  `System.nanoTime()` warmup+measure harness timing
  `SimulationCoordinator.tick()` on a seeded `TestWorld` coordinator (fresh
  town and after 10 in-game days), and `TownRenderingDataBenchmark.kt` in the
  same package, timing `SnapshotBuilder.build()` — the `WorldUi` construction
  step that feeds `TownRenderer`'s `Canvas` draw loop (fresh town and after 30
  in-game days). Both run as ordinary fast JVM unit tests (no new Gradle
  module, no new plugin), print mean/min/max timings to the test log, and
  assert a generous ceiling as a regression tripwire — not a tuned frame
  budget, since no real device numbers exist here to derive one honestly.
  `TownRenderingDataBenchmark` explicitly does NOT measure the actual
  `Canvas.drawImageRect` cost inside `TownRenderer` itself — only the
  data-layer snapshot-build step upstream of it. A minimal
  `.github/workflows/benchmarks.yml` was also added, running these two test
  classes via `./gradlew :app:testDebugUnitTest --tests ...` on push/PR —
  **this workflow file has NOT been executed or verified in this session; no
  way exists here to run GitHub Actions.** It needs an actual push to prove
  the runner/JDK/Gradle invocation works before a green check should be
  trusted. Still fully open: a real `androidx.benchmark.macro` macrobenchmark
  module for `TownRenderer` (needs a device/emulator — none available here,
  and GitHub-hosted runners don't provide one without additional
  emulator-in-CI setup not configured); a real JMH microbenchmark module for
  `tick()` (would need the `me.champeau.jmh` plugin and a new Gradle module,
  deliberately not added per this session's own scoping decision to avoid
  destabilising the existing test setup); CI verification of anything in
  this pass, including the plain unit-test workflow file itself.*

## Phase 3 — A town with a memory (systems that compound)

- [x] Generational play: family reputation, inherited trauma/beliefs from
  memories, heirlooms; the death-of-followed flow grows into an "era summary".
  *Implemented: inherited beliefs (top 2 significant `beliefFormed` memories,
  `importance ≥ 65`, passed to surviving children as diminished-intensity
  `CHILDHOOD` "family story" memories) and heirlooms (one heir receives a
  trade-themed heirloom via `ideaSeeds` + an `INSPIRATION` memory, gated on
  a positive memory with `importance ≥ 75`), both in `LifecycleSystem.die`.
  *Implemented — family reputation: new `FamilyReputationSystem`, a lineage
  standing computed **at read time** from existing `Resident.reputation`
  values (self, living household members, two generations of ancestors,
  decayed) rather than a second persisted running total — deliberately, so
  it can never drift out of sync with the individual reputation changes
  that already happen all over the simulation. Given real, bounded effect
  in two places: a small nudge on `BuildingLifecycleSystem`'s daily home-
  repair chance, and a small first-meeting trust nudge in
  `InteractionSystem.interact` (a family's name precedes them, until two
  people actually get to know each other). See
  `docs/simulation-rules.md#family--generations`.*
  *Implemented — era summary: the existing death-of-followed flow
  (`WorldRepository.detectFollowedDeath` / `DeathSummary` /
  `DeathSummaryDialog`, all pre-existing) gains a new `EraSummary` — years
  lived, notable public town events witnessed (same `ImportanceScorer
  .HISTORY_THRESHOLD` bar the History timeline uses), the resident's own
  top memories, and a count of warm relationships formed — built only for
  the resident actually being followed, from the full-lifetime event log
  (`EventDao.eventsBetween`, keyed off a new `payload["bornAt"]` on
  `PERSON_DIED`). No new screen: surfaced as an extra "Their era" section
  inside the existing death dialog.*
- [x] Local politics: council seats, petitions (noise, rents), policy effects
  on the economy; elections become campaigns influenced by reputation events.
  *Implemented — petitions: `PetitionSystem`, run daily. Politically-
  interested residents personally affected by noise or rent burden can start
  a petition; sympathetic townsfolk sign it over following days; it resolves
  — with a real, bounded policy effect (noise cut, rent cut) on success, a
  reputation/stress dip for the organiser on failure — once it clears a
  population-scaled signature threshold or its 21-day deadline lapses.
  `PETITION_STARTED`/`PETITION_RESOLVED` events, `PUBLIC` visibility, causally
  linked, through `ConsequenceEngine`. See
  `docs/simulation-rules.md#local-politics-petitions`.
  *Implemented — council seats & campaign-driven elections: `ElectionSystem`,
  run daily right after `LifecycleSystem.updateDaily`. Layers on top of the
  pre-existing (untouched) `LifecycleSystem.election()` rather than replacing
  it: a `CAMPAIGN_WINDOW_DAYS` (20) campaign opens ahead of the vote
  (`ELECTION_CALLED` — the event type already existed and was already fully
  wired into `ImportanceScorer`/`NewspaperGenerator` but had never actually
  been fired by anything before this), each candidate gets a bounded daily
  chance to campaign (support gained from a track record of *won* petitions
  plus mean relationship familiarity with the town, not personality alone),
  and campaigning nudges the candidate's own `reputation` — the same field
  the existing vote logic already scores candidates on, so campaigns
  genuinely influence who wins without a second, competing outcome rule.
  Runners-up who don't win the mayoralty fill `COUNCIL_SEATS` (2) council
  seats, ranked by accumulated campaign support. Policy effect: a sitting
  mayor's term adds a small bonus to `BuildingLifecycleSystem`'s per-building
  repair-chance roll, town-wide. See
  `docs/simulation-rules.md#local-politics-elections`. Deliberately out of
  scope: policy platforms/issue positions, individual voter ballots (the
  vote itself is still the existing aggregate scoring), councillor-specific
  powers beyond the shared repair bonus, recall elections, negative
  campaigning.*
- [x] Economy v2: prices that move, property market (residents actually
  buy/sell homes), business succession and rivalries.
  *Implemented (price competition + rivalries + price-drift slices):
  `BusinessRivalrySystem`, run daily. Open, same-`BusinessType` business pairs
  are compared on standing (reputation minus a price-level penalty); the
  better-standing one gains `demand` and the other loses it, a small daily
  nudge (`±2.0`, `coerceIn(5.0, 95.0)`) so competing businesses visibly drift
  apart over weeks rather than swinging dramatically. When a pair's standing
  stays closely matched (gap ≤ 20), their owners' existing relationship
  (`state.relationshipOrCreate`) also drifts daily — resentment `+0.6`,
  affection `−0.3` — and once it crosses the *same* thresholds
  `InteractionSystem.updateKind` uses for personal rivalries (resentment >
  55, affection < 30), the relationship kind is set to `RelationshipKind.RIVAL`
  and `RIVALRY_FORMED` fires directly (not routed through `updateKind`, since
  two business owners may never be co-located to trigger the ordinary
  interaction path). Family/partner/spouse/former-partner/affair relationships
  are never overwritten. See `docs/simulation-rules.md#business-rivalries`.
  **"Prices that move" now also implemented**: new `PriceDriftSystem`, run
  daily straight after `BusinessRivalrySystem` — slow, town-wide
  `priceLevel` drift, deliberately kept on a separate axis from rivalry's
  `demand` shifts so the two mechanics never double-count. Each open,
  non-public-service business independently rolls a 12% daily chance to
  drift `priceLevel` by ±0.02, biased down for struggling businesses and up
  for prosperous ones (balance > `EconomySystem.EXPANSION_BALANCE`),
  clamped to 0.7–1.4; a new `PRICES_SHIFTED` event fires the day a
  business's price first crosses 10% away from baseline. See
  `docs/simulation-rules.md#price-drift`. **Business succession now also
  implemented**: new `BusinessSuccessionSystem`, run daily straight after
  `PriceDriftSystem` — an owner at or past age 68 with an adult child
  already employed at that same business has a small daily chance (6%) of
  voluntarily handing it down and retiring, distinct from (and not touching)
  the pre-existing silent death-of-owner heir handoff in
  `LifecycleSystem.die`, which remains the fallback for an owner who dies
  before retiring. `Business.ownerId` transfers, a `BUSINESS_SUCCESSION`
  event fires, both parties get an `ACHIEVEMENT` memory. See
  `docs/simulation-rules.md#business-succession`. **Property market now also
  implemented**: new `PropertyMarketSystem`, run daily straight after
  `BusinessSuccessionSystem` — a household buys the home it already lives in
  (`Building.ownerId`, a field previously never set for homes at all) once
  its pooled adult wealth clears the asking price (`Building.value`) plus a
  `MIN_RESERVE_AFTER_PURCHASE` (200) cushion; cash only, straight from
  resident `wealth`, no mortgages. `HOME_PURCHASED` event fires, buyer gets
  an `ACHIEVEMENT` memory. Deliberately excludes negotiation/haggling,
  competing bidders, mortgages/loans of any kind, and any rental-to-ownership
  transition beyond the existing free `MOVE_HOME` path. See
  `docs/simulation-rules.md#property-market`. This closes the Economy v2
  backlog item in full — non-family succession and multi-heir disputes
  within succession itself remain open but are a separate, smaller item, not
  part of Economy v2's four originally-scoped pieces.*
- Multiple towns: `World` already separates from `Town`; add a second map and
  slow migration between towns.
- Counterfactual viewer ("what nearly happened"): replay a checkpoint with
  one intervention removed — the deterministic engine makes this cheap.
  Strictly a *viewer*; never shown as future prediction.
- Cloud save via the existing `CloudSaveRepository` seam.
- Modding-friendly data: consequence rules and goal templates as data files.

## Phase 4 — The outside world (the seams come alive)

- [x] `ExternalWorldEventProvider`: curated, abstracted real-world pressure
  feed (fuel prices rise → delivery costs rise → the chain the prototype
  already models). Strict mapping through `WorldPressureMapper`; no real
  names, no politics-of-the-day. **Implemented 2026-07-10 as a deliberately
  scoped-down MVP**, per the task brief's explicit instruction not to
  attempt the whole vision: at most one active pressure at a time
  (town-wide, no stacking), eight hand-curated abstract kinds in matched
  rise/ease pairs, one clean mechanical hook only (`FUEL_PRICES_RISE`/
  `FUEL_PRICES_EASE` nudge `EconomySystem`'s per-business overhead expense
  via a mapper's `overheadMultiplier`) — the other six kinds are
  flavour-only, honestly reported with no mechanical effect yet rather than
  bolted onto an unrelated system. **Naming note:** the brief's exact names
  turned out to already be claimed by pre-existing, unrelated placeholder
  interfaces (`core/simulation/providers/FutureProviders.kt`, DI-wired in
  `AppModule.kt`, reserved for a later real/async feed) — the concrete
  engine-internal types here are named `CuratedWorldPressureFeed` and
  `WorldPressureMechanicMapper` instead, to avoid a same-name-different-
  package collision; the placeholder interfaces are untouched. See
  `docs/simulation-rules.md#phase-4-external-world-pressure`.
- [x] `NarrativeTextProvider` / `DialogueProvider`: an LLM narrative layer that
  writes flavour prose and dialogue *from* facts, never creating facts —
  enforced by the existing engine-only-mutates rule and validated against the
  event log. **Implemented 2026-07-10 as a template-based (non-LLM) default**
  — `TemplateNarrativeTextProvider`/`TemplateDialogueProvider`
  (`core/simulation/providers/TemplateProviders.kt`), now bound as the real
  default in `di/AppModule.kt` (`NoOpNarrativeTextProvider`/
  `NoOpDialogueProvider` remain in `FutureProviders.kt` for tests/fallback
  only). Both are pure reads over `WorldEvent`/`WorldState`/`Resident` —
  fixed templates filled from real structured data, deterministic template
  selection by `event.id`/`residentId` (never `Math.random()`/
  `kotlin.random.Random`), same discipline as `ChronicleBuilder`/
  `NewspaperGenerator`. **A real LLM-backed implementation remains a
  separate, still-open decision** — needs an API key/budget/model choice
  from the user, deliberately not attempted here; the whole point of this
  seam already existing is that swapping one in later means changing only
  the two `@Provides` functions in `AppModule.kt`, zero call-site changes,
  since every caller depends on the interface only. Wired to two real UI
  call sites, not left unused: `EventSheetContent`
  (`feature/town/TownSheets.kt`) shows the elaboration as expandable "More
  detail" text under an event's existing terse description; the resident
  sheet shows a short personality-flavoured quoted line for a resident
  currently grieving/celebrating/working/arguing/socialising/worried (see
  `TemplateDialogueProvider`'s doc comment for the closed set of supported
  situation strings). Both go through new `WorldRepository.elaborateEvent`/
  `dialogueLineFor` and `TownViewModel.requestElaboration`/
  `requestDialogueLine`, the same one-shot suspend-call-from-Composable
  pattern `requestChronicle` already established.
- [x] National layer: lightweight country context (taxes, trends) as
  pressures. **Implemented 2026-07-10 as a small, additive extension of
  `CuratedWorldPressureFeed`/`WorldPressureMechanicMapper`** — not a new
  parallel mechanic. Two pieces: (1) **taxes** — a new `TAX_RATE_RISES`/
  `TAX_RATE_EASES` curated pressure pair, picked/timed/resolved by the exact
  same unmodified daily-roll machinery every other kind uses, driving a new
  standing `WorldState.nationalTaxRate` (bounded 0.9x–1.1x, nudged
  `TAX_RATE_STEP_PER_DAY` = 0.004/day towards its bound while active and back
  towards neutral otherwise) that composes into `EconomySystem`'s existing
  daily living-cost deduction — the same "one clean traceable hook"
  discipline the fuel-price/overhead mapping already established, landing on
  resident wealth rather than business balance so the two hooks never
  overlap; (2) **trends** — a new `WorldState.pressureHistory` (capped at 5,
  oldest dropped first) recording each pressure's full start-to-end span, so
  the town has a standing sense of "how things have been going nationally"
  beyond the single live pressure slot, ready for a future UI/newspaper pass
  to read. See `docs/simulation-rules.md#national-layer-taxes-and-trends-added-2026-07-10`.
- [x] Shareable town chronicles: export a family's saga as text/images.
  **Implemented 2026-07-10, scoped to text-only** (image export explicitly
  deferred — see the session-log entry below for why). `ChronicleBuilder`
  (`data/ChronicleBuilder.kt`) builds a templated multi-generation narrative
  — self, traceable parents/grandparents, children/grandchildren, each a
  short templated paragraph from real `Resident`/`Memory`/event-log data —
  reusing `FamilyTreeScreen.kt`'s existing generation-traversal shape and
  `buildEraSummary`'s "notable public events at/above `HISTORY_THRESHOLD`"
  convention. Deliberately template-based, not LLM prose — that's the
  separate, still-open `NarrativeTextProvider`/`DialogueProvider` item above.
  New `WorldRepository.buildChronicle(residentId)` gathers the per-person
  event-log lookups; `TownViewModel.requestChronicle` hands the text to a new
  "📜 Share saga" button on `ResidentSheetContent` (`feature/town/
  TownSheets.kt`), which launches the standard `Intent.ACTION_SEND` share
  sheet via `ShareCompat.IntentBuilder` — the app's first share-intent usage.
  See `docs/simulation-rules.md#family--generations`.

## Engineering debt to pay alongside

- Scale to thousands of residents: spatial partitioning for tick/render
  proximity queries (currently linear scans over `state.residents.values`),
  distance/interest-based culling so only nearby or `DetailLevel.DETAILED`
  residents get full per-tick simulation, and event-driven wake instead of
  polling every system every tick. Not urgent at the current small seeded
  population, but every daily system added this session (`PetitionSystem`,
  `BusinessRivalrySystem`, `PriceDriftSystem`, `BusinessSuccessionSystem`,
  etc.) does an unbounded-by-population `state.X.values.filter{}` pass
  bounded only by a flat `MAX_...` cap, not by proximity/relevance — fine
  today, a real ceiling once population count grows substantially.
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
  code-path analysis and flagged as background tasks; **all three now fixed
  and verified** (2026-07-10, see session log):
  - `WorldGeneratorTest` "scenario seeds are planted" — root cause was a
    hand-authored coordinate mistake in `WorldGenerator.slots()`: "The Old
    Lantern" (x4,y11,4×3) and "Ashcombe School" (x2,y12,5×4) overlapped at
    x4-6,y12-13. Seed-independent (the street plan isn't procedurally
    placed), so it affected every seed identically. Moved the school's
    footprint to y15 and its door to y19 to match. Also added a fail-fast
    overlap/river check in `buildBuildings` so any future hand-edit to
    `slots()` throws immediately instead of silently corrupting the map.
  - `GoalAndEconomyTest` "goals form from combined circumstances not
    randomness" — `GoalSystem`'s `START_BUSINESS` condition required
    `financialSecurity < 60`, which isn't part of the documented rule
    (`docs/simulation-rules.md#goals`: "unemployed + carpentry > 55 +
    vacant granary + idea seed + ambition"). Ash Thistle's seeded debt
    (250) computes a `financialSecurity` of 80 after the `Needs` model's
    `coerceIn(20.0, 80.0)` floor, failing that extra gate. Replaced the
    threshold with `state.employmentOf(r) == null`, matching the
    already-documented "unemployed" condition and the same pattern
    `FIND_JOB` already uses.
  - `MigrationTest` "schema v1 matches..." — the schema file and
    `room { schemaDirectory(...) }` config were fine; the actual bug was in
    *where* the schema needed to live for Robolectric to see it. Traced via
    `app/build/intermediates/unit_test_config_directory/.../test_config.properties`
    (`android_merged_assets=...\mergeDebugAssets`): Robolectric's
    `isIncludeAndroidResources` reads assets from the **debug variant's own
    merged assets output**, not from any test-only source set — the
    `apk-for-local-test.ap_` resource archive it also reads contains no
    `assets/` entries at all for local unit tests, and there's no
    `mergeDebugUnitTestAssets`-equivalent task. Moved the schema wiring from
    `sourceSets.getByName("test").assets.srcDir(...)` to
    `sourceSets.getByName("debug").assets.srcDir(...)` in
    `app/build.gradle.kts` — confirmed fixed via an isolated
    `--tests MigrationTest` run before re-verifying against the other two.
