# Ripple — Development Backlog

The prototype proves the foundation. Three phases follow.

## Session log

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
   cached bitmap. *Still open: most other building types (HOUSE, COTTAGE,
   TERRACE, TOWN_HALL, CAFE, BOOKSHOP, TAILOR, HARDWARE, WORKSHOP, VACANT)
   still have no unique silhouette, only colour — they're the majority of
   `BuildingType`.*

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
