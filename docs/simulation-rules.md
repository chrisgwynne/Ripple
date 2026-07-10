# Ripple — Simulation Rules

The single guiding principle:

> **Given everything that has happened so far, what would naturally happen next?**

This document describes each system's rules and tuning constants as
implemented in `core/simulation`.

## Time

- 1 tick = **10 in-game minutes**; 144 ticks per day.
- Calendar: 12 months × 30 days = 360-day years, month names are fictional.
- Real-time pacing at 1×: 1 real second = 1 in-game minute (1 real minute =
  1 in-game hour). Speeds: pause, 1×, 3×, 10×.
- The world starts in year 12 of the town's own history so residents have
  plausible pasts.

## Determinism

- `SimRandom` is a SplitMix64 generator. Each tick constructs one instance
  from `mix(worldSeed, tickNumber)` and consumes draws in stable, id-sorted
  iteration order.
- Interventions use a salted stream (`salt = 0x1EAF`) so they don't disturb
  the tick stream.
- Checkpoints serialise the complete `WorldState` (including RNG-relevant
  counters like `time` and all id counters), so restore-then-run equals
  never-stopped. Tests verify byte-identical state JSON.

## Tick pipeline (fixed order)

1. Advance time by 10 minutes.
2. `NeedsSystem` — need drift, activity effects, travel arrivals, weather.
3. `HealthSystem.updateUrgent` — clinic treatment, hidden-condition discovery.
4. `DecisionSystem` — utility-scored action choice for idle detailed residents.
5. `InteractionSystem` — bounded social contact, romance arcs at 21:00.
6. `EconomySystem` — hourly footfall; daily settlement at 23:50.
7. `DelayedEffectSystem` — fire due effects (bounded).
8. Daily at midnight: `HealthSystem.updateDaily`, `LifecycleSystem.updateDaily`
   (births, separations, elections, memory decay), `GoalSystem.updateDaily`,
   `BuildingLifecycleSystem.updateDaily`, `SeasonalEventSystem.updateDaily`
   (harvest fair / winter market / river floods), `PetitionSystem.updateDaily`
   (local politics: noise / rent petitions), `BusinessRivalrySystem.updateDaily`
   (same-type price/demand competition, owner rivalries),
   `PriceDriftSystem.updateDaily` (town-wide price inflation/deflation),
   `BusinessSuccessionSystem.updateDaily` (voluntary owner-to-child handoff),
   `PropertyMarketSystem.updateDaily` (households buying the home they live in),
   `CuratedWorldPressureFeed.updateDaily` (Phase 4: a single curated,
   abstract national-scale pressure, mapped through `WorldPressureMechanicMapper`).
9. Nudge regeneration.
10. Newspaper when due (weekly, 08:00).
11. Daily statistics; checkpoint flag every 36 ticks (6 in-game hours).

## Needs (0–100)

Hunger, energy, health, safety, social, comfort, purpose, stress (inverted),
financial security. Per-tick drift: hunger −0.55, energy −0.28 (−0.5 working),
social −0.18. Activities push back (sleep +1.6 energy, eating +5.5 hunger,
socialising +1.4 social…). Starvation and exhaustion feed into health and
stress. Financial security eases towards `25 + wealth/40 − debt/30`.
Homes within 6 tiles of a building with noise > 40 lose comfort (the joinery
works next to Rowan Street is the seeded example).

## Decision system

Each candidate action gets:

```
score = needPressure × personalityFit × expectedReward × confidence
        × socialInfluence × opportunity
        − risk − cost − effort − moralResistance
```

Actions: eat (home/out), sleep, go to work, school, visit friend, socialise in
public, shop, exercise, learn/work on goal, rest ill, visit clinic, relax,
wander. Highest score wins; RNG only breaks ties within 5 % of the best score
— randomness never causes a major decision alone. The chosen action's utility
terms are kept on the resident (`activityReason`) so the UI can answer
*"Why this action?"*.

## Relationships

Eight dimensions (familiarity, trust, affection, attraction, respect,
resentment, dependency, shared history), one record per unordered pair.
Interactions are sampled from co-located sociable residents, max 8 per tick:

- Pleasant exchange: familiarity +3, affection +1.2 + 2.2×compatibility,
  trust +0.8+…
- Helping someone stressed (kindness > 0.55): trust +4, dependency +2, leaves
  a `KINDNESS_RECEIVED` memory.
- Tension (stress, resentment, impulsiveness vs patience) can boil into an
  argument: resentment +7, affection −4, both stressed, `ARGUMENT` event.
- Kind transitions: warmth > 32 & familiarity > 30 → friend; > 55/60 → close
  friend; resentment > 55 & affection < 30 → rival. Transitions emit events.
- Romance: single compatible adults accumulate attraction; > 45 attraction and
  > 45 affection may start dating (35 %/day); engagement, marriage (households
  merge), break-ups, separation (resentment > 72, affection < 25), divorce and
  reconciliation all follow value thresholds plus daily probability.
- Absence > 7 days decays affection −0.15/day; shared history barely fades.

## Affairs & jealousy

A committed resident can still feel a rare spark outside their partnership —
gated on *vulnerability*: `((100 − partner-relationship affection) +
partner-relationship resentment) / 200`, reduced by that same relationship's
*vigilance* (`dependency × 0.4 + resentment × 0.3`), which stands in for
jealousy as a **modifier** on existing dimensions rather than a tracked value
of its own. A troubled marriage is fertile ground; a watchful one dampens it.
Enough attraction and affection with someone outside the partnership can begin
a `RelationshipKind.AFFAIR` (`AFFAIR_BEGAN`, `HIDDEN` — nobody in town knows
yet, but it stands ready as a cause). Each day the affair can fizzle
(affection too low, or a flat 2 % chance), or be found out: discovery chance
grows with the affair's own shared history and the deceived partner's
vigilance. Discovery emits `AFFAIR_DISCOVERED` (`PRIVATE`), leaving `BETRAYAL`
and `HUMILIATION` memories, and consequences: the marriage's trust/affection
crash and resentment spikes, relationship pressure follows, and there is a
55 % chance the partnership doesn't survive it (reuses the normal
separation/divorce path). The `Reveal` intervention can also expose an
ongoing affair, using the same `REVELATION_CHANCE` delayed effect already
used for hidden health conditions.

## Rumours

Private events don't stay private forever. Each tick, `RumourSystem` looks at
that tick's own `PRIVATE` events of gossip-worthy types (arguments, affairs
discovered, break-ups, rivalries, secrets revealed…) and rolls a leak chance
from severity plus *exposure* — how many high-familiarity (> 60) relationship
edges surround those involved, capped at 6. A leak becomes its own new
`PUBLIC` `RUMOUR_SPREAD` event, which is what actually reaches
`NewspaperGenerator` — the paper only ever reads public events, so a rumour is
the *only* way something private becomes news. 55 % of leaks are accurate
paraphrases and keep a real cause link back to the truth; the rest are
distorted (a wrong resident dragged in, the story downplayed or inflated) and
carry **no** cause link at all — the cause viewer, reading only known history,
never shows a false lineage for something that didn't really happen that way.
Bounded to 2 leaks per tick.

## Building lifecycle

Storm damage (30 % of storms, condition −6..−18, floor 5) is the only thing
that currently lowers a building's `condition`; `BuildingLifecycleSystem` is
what brings it back. Daily, any non-abandoned building below condition 55
looks for a payer — the business trading there (from its balance) or, for
homes, the wealthiest resident actually living there (personal wealth, must
be > 200) — at a cost of `(100 − condition) × 9`. If the payer can afford it,
there's a 15 %/day chance they get round to it: condition rises 25–45,
"Storm damage" clears from `visibleChanges`, and a `BUILDING_REPAIRED` event
is emitted. Bounded to 3 repairs per day. A home below condition 40 also
chips at its residents' comfort, the same way persistent noise does — repairs
aren't just cosmetic.

## Seasonal events

The calendar carries its own fixed rhythm on top of daily life, run from
`SeasonalEventSystem.updateDaily`:

- **Harvest fair** — a calendar-fixed autumn date (month 8, day 15; ahead of
  the winter stretch used elsewhere, `month == 11 || month == 0 || month ==
  1`). Every detailed in-town resident gets a wellbeing lift (social +8,
  purpose +4, stress −6); open bakeries, grocers and pubs get a demand bump
  (+14); a `COMMUNITY_EVENT` fires at the park, if one exists.
- **Winter market** — the same shape, smaller and comfort-flavoured (comfort
  +5, social +4, stress −3), on a fixed winter date (month 11, day 10). Open
  cafés, hardware shops and tailors get a smaller demand bump (+9); the
  `COMMUNITY_EVENT` is anchored at the town hall instead of the park.
- **River floods** — `WorldGenerator` seeds a river down the map's east edge
  (`TileType.WATER`, the last two columns). While it's raining or storming, a
  small daily chance (5%/8% for rain/storm) hits one non-abandoned building
  within 3 tiles of a water tile: condition −18..−32 (floor 5), harsher than
  the generic storm-damage roll in `NeedsSystem.updateWeather` (−6..−18,
  which isn't water-proximity-aware), plus a safety/comfort hit for any
  resident currently inside. Marks `visibleChanges` with "Flood damage"
  (capped at 6 like other systems) and emits `WEATHER_DAMAGE` with
  flood-specific text and higher severity (0.65 vs 0.45), fed through
  `ConsequenceEngine` like any other event. Bounded to one flood per day.

## Education & returning students

Children and teens at school (`Activity.AT_SCHOOL`, weekdays 8–14) build
`SkillType.TEACHING` slowly — `0.02 + discipline × 0.02`/tick — general
grounding rather than a trade. Curious, ambitious teens (`curiosity > 0.7`,
`ambition > 0.6`) can form a `LEAVE_FOR_EDUCATION` goal; once it's 80 % along
and they've turned 18, they leave town (`RESIDENT_LEFT_TOWN`,
`leftTownAt` set, parents get a `LOSS` memory) — but they aren't gone for
good. Leaving schedules a `GOAL_SEED` delayed effect (note
`"returning_student"`) with a 640–1400 day window (roughly 1.8–3.9 years).
When it fires, `LifecycleSystem.studentReturns` brings them home changed: a
large `TEACHING` boost (20–40) plus a secondary skill matching whichever
personality trait runs strongest (ambition → business, curiosity →
creativity, empathy → medicine, courage → politics, discipline → repair,
kindness → cooking), rehoused with their old household if it kept a home
or with an in-town parent otherwise, a fresh `FIND_JOB` goal, a warm
`RESIDENT_ARRIVED`-flavoured event, and an `ACHIEVEMENT` memory for any
parent still in town.

## Local politics: petitions

Grassroots politics short of a council seat, run daily from
`PetitionSystem.updateDaily` (resolve due petitions → gather today's
signatures → maybe start a new one, in that order so a petition can't both
gain its last signature and resolve on the same day it started). Two
subjects, matching what the backlog names — council seats and campaign-driven
elections are a separate, larger item and remain open:

- **Starting.** A politically-interested (`politicalInterest >
  0.5`), in-town adult who is personally affected can start a petition
  (35% roll once eligible, so it doesn't fire the instant someone qualifies):
  - **Noise** — their home sits within `NeedsSystem.NOISE_RADIUS` (6 tiles) of
    a building with `noise > 40`, *and* their own comfort has genuinely
    dropped (< 45) — reusing the exact proximity rule `NeedsSystem` already
    uses for the comfort penalty, not a new one. Targets the loudest
    qualifying building.
  - **Rent** — their household's `monthlyRent × 1.5` exceeds their personal
    wealth — a burden check, not a flat rent threshold, so a wealthy resident
    in an expensive home doesn't qualify.
  A resident can't stack a second petition while one of theirs is still
  active. At most `MAX_ACTIVE_PETITIONS` (2) run at once town-wide, and at
  most `MAX_NEW_PETITIONS_PER_DAY` (1) starts per day. `PETITION_STARTED`
  fires `PUBLIC` (town politics is public business, unlike affairs/rumours) —
  the starter is the sole initial signatory and gets a small purpose lift.
- **Signing.** Each day, every other detailed non-child resident who is
  *sympathetic* — for noise, living within the same radius of the target
  building; for rent, also rent-burdened by the same `1.5×` test; either way,
  `politicalInterest > 0.6` alone also qualifies — gets a `22%` daily roll to
  sign, capped at `MAX_SIGNATURES_PER_PETITION_PER_DAY` (6) new names per
  petition per day. Signing nudges purpose slightly.
- **Resolving.** The threshold is `8 + population/12`, capped at 22 — scales
  with the town without ever demanding an unreachable majority from a small
  cast. A petition resolves the day it clears its threshold, or after its
  21-day deadline, whichever comes first:
  - **Succeeds:** a real, bounded policy effect. Noise petitions cut the
    target building's `noise` by 18 (a mandated quiet-hours easing, not
    silence) and give every nearby resident's comfort a one-off +6; rent
    petitions cut the target household's `monthlyRent` by 40 and give every
    member's financial security +8 / stress −4. The starter gets +10
    reputation, +12 purpose and an `ACHIEVEMENT` memory; every other signatory
    gets a small purpose lift too.
  - **Fails:** no policy effect. The starter takes −4 reputation and +6
    stress; signatories are otherwise unaffected.
  Either way `PETITION_RESOLVED` fires with `causeIds` pointing back at the
  `PETITION_STARTED` event (so the cause viewer shows the whole arc) and a
  `payload["outcome"]` of `"succeeded"`/`"failed"`, then goes through
  `ConsequenceEngine.onEvent` like any other event (no rules registered yet —
  a hook for future consequence chains, same as `INTERVENTION_APPLIED`).

Modelled with a new lightweight `Petition` data class
(`core/model/WorldState.kt`) — subject, starter, target
(building-or-household), signature list, threshold, deadline, status — held
in a new `WorldState.petitions` list. `WorldState` checkpoints as one JSON
blob, so this is a plain new field with a sensible default (`emptyList`);
no schema migration involved.

## Local politics: elections

Council seats and campaign-driven elections, run daily by
`ElectionSystem.updateDaily` right after `LifecycleSystem.updateDaily` in
`SimulationCoordinator`'s `if (newDay)` block — deliberately positioned there
so it always sees the same-day result of `LifecycleSystem.election()`, the
pre-existing (and unmodified) function that actually decides the mayor from
`WorldState.nextElectionAt`/`mayorId`. `ElectionSystem` doesn't replace that
vote or duplicate its outcome rule; it makes the weeks before it mean
something, and adds a genuinely new piece the vote never had — council seats.

- **Calling.** `CAMPAIGN_WINDOW_DAYS` (20) before `nextElectionAt`,
  `callElection` derives the same candidate pool `LifecycleSystem.election()`
  will independently re-derive at the vote itself (politically-interested
  in-town adults, `politicalInterest > 0.35`, ranked by
  `politicalInterest × 50 + reputation + POLITICS skill`, top `MAX_CANDIDATES`
  (3)) and gives it a name and a campaign: a `Candidacy` (`support = 0`,
  `actionsTaken = 0`) per candidate in a new `WorldState.candidacies` list,
  and `WorldState.campaignEndsAt` set to `nextElectionAt`. `ELECTION_CALLED`
  fires here — the type already existed in `EventType` and was already fully
  wired into `ImportanceScorer` (30.0 base) and `NewspaperGenerator`
  (`StoryCategory.TOWN_NEWS`, falls through to its `else -> e.type.label`
  headline, "Election called") but had never actually been emitted by
  anything before this system; this is the first thing that fires it.
- **Campaigning.** While a campaign is open, `runCampaigns` gives each
  candidate a bounded `DAILY_CAMPAIGN_CHANCE` (30%) roll per day, capped at
  `MAX_CAMPAIGN_ACTIONS` (10) campaigning days total — a short push, not a
  grind or a sub-simulation of its own. A landed campaign day:
  - Adds to the candidate's `Candidacy.support`: a `CAMPAIGN_SUPPORT_GAIN_BASE`
    (4.0) plus a **track record** bonus (`TRACK_RECORD_BONUS_PER_SUCCESS`
    (2.0) per petition the candidate has personally started *and won*, capped
    at `MAX_TRACK_RECORD_BONUS` (8.0) — genuine local-politics history via
    `PetitionSystem`'s own success/failure record, not a personality stat)
    plus a **familiarity** bonus (mean `Relationship.familiarity` across
    everyone the candidate already has a relationship with, divided by
    `FAMILIARITY_SUPPORT_DIVISOR` (25) — a stranger with no relationships
    contributes nothing here) plus a small random wobble
    (`ctx.rng.nextDouble(-1.0, 2.0)`).
  - Nudges the candidate's own `reputation` up by `CAMPAIGN_REPUTATION_GAIN`
    (1.5) and `needs.purpose` by 3.0. This is the actual mechanism by which
    campaigning influences who wins: `LifecycleSystem.election()` already
    reads `reputation` when scoring candidates at the vote, so a campaign
    that lands composes with the existing outcome rule instead of
    introducing a second, competing one.
  - Sends the candidate to the town hall (`BuildingType.TOWN_HALL`, if one
    exists) for a `COMMUNITY` activity — a visible campaign stop, reusing
    `TickContext.sendTo` rather than inventing new activity/travel plumbing.
- **Council seats.** The day the vote lands — detected by watching
  `nextElectionAt` advance past the `campaignEndsAt` the campaign opened
  with, which `LifecycleSystem.election()` always does once it fires
  (whether or not it actually changes `mayorId`) — `fillCouncil` takes every
  candidate *other than* the winning `mayorId`, ranks them by accumulated
  `Candidacy.support` (ties broken by resident id for determinism), and
  seats the top `COUNCIL_SEATS` (2) as councillors in a new
  `WorldState.councillorIds` list (cleared and replaced each election, not
  additive — a councillor who isn't re-selected loses the seat). Each new
  councillor gets the `"Councillor"` occupation label if they were
  unemployed, +4 reputation, +8 purpose, and an `ACHIEVEMENT` memory; a
  `TOWN_MILESTONE` event announces the seated council (only if there was at
  least one seat filled). Campaign bookkeeping
  (`campaignEndsAt`/`candidacies`) is cleared at the end of this step either
  way, ready for the next `callElection` window.
- **Policy effect.** A term in office has one small, real, bounded
  consequence: while `mayorId` is non-null, `ElectionSystem.repairChanceBonus`
  adds `MAYORAL_REPAIR_CHANCE_BONUS` (0.04) to
  `BuildingLifecycleSystem`'s per-building daily repair-chance roll — read
  where that system already composes its base chance with the family-standing
  modifier, so all three sources (base, family reputation, mayoral term) add
  together rather than one overriding another, and the roll stays clamped to
  that system's existing `0.02..0.35` bounds. Zero effect while the office is
  vacant (a fresh world, before the first election lands).

Modelled with a new `Candidacy` data class (`core/model/WorldState.kt`) —
resident id, accumulated support, campaign-actions-taken — held in a new
`WorldState.candidacies` list, plus `WorldState.campaignEndsAt: Long?` and
`WorldState.councillorIds: MutableList<Long>`. All plain new fields with
safe defaults (`null`/`emptyList`); no schema migration involved. All
randomness goes through `ctx.rng`, never `Math.random()`.

**Deliberately out of scope for this pass:** no policy platforms or issue
positions for candidates to campaign on (a campaign day is a single
undifferentiated "campaigning" action, not a choice between stances); no
voter-level individual ballots or turnout modelling (the vote itself is
still `LifecycleSystem.election()`'s existing aggregate scoring, unchanged);
no councillor-specific duties or powers beyond the shared mayoral repair
bonus (a councillor's seat is a standing/reputation outcome, not yet a
second lever on the simulation); no recall elections, resignations, or
scandal-driven early elections; no negative campaigning or rival
sabotage between candidates.

## Family & generations

Births to fertile couples (both 20–44, affection ≥ 55) at
`0.12 %/day × affection × 1/(1+children)`. Children inherit each trait as a
bell-ish sample around the parents' mean (±0.25 spread) — tendencies, not
copies. Estates (wealth − debt) split between partner and children;
businesses pass to an adult heir or the partner. Death widows partners,
shrinks households, and leaves strong memories in everyone warm to the
deceased.

**Inherited beliefs.** On death, the deceased's memories with `importance ≥
65` and a formed `beliefFormed` are ranked by importance then intensity; the
top 2 are handed down to every surviving detailed child as a new
`CHILDHOOD` memory ("*Name* used to say: \"...\"") at ~45% of the original
memory's `emotionalIntensity` — a secondhand family story, not the lived
experience. No belief, no threshold met, or no surviving children means
nothing is passed down.

**Heirlooms.** If the deceased holds a positive memory (`ACHIEVEMENT`,
`INSPIRATION` or `ROMANCE`) with `importance ≥ 75`, one heir — an adult
child if one is in town and detailed (picked via `ctx.rng` if several tie),
else any surviving detailed child, else the partner — receives a small,
loosely trade-themed heirloom (e.g. a carpenter's "well-used toolbox", a
cook's "worn recipe book"). This is deliberately lightweight: no new
inventory model, just a formatted `"heirloom:<name>'s <item>"` entry on the
heir's `ideaSeeds` (the same list `GoalSystem` already reads to help trigger
`START_BUSINESS`) plus an `INSPIRATION` memory recording the gift.

**Family reputation.** Distinct from an individual's own `Resident
.reputation`: a lineage's standing, as it bears on one particular living
member right now. Deliberately **not** a second persisted running total —
`Resident.reputation` already reacts to exactly the collective deeds that
should feed a family's name (petitions won/lost, crime, business
success/failure, affairs discovered, elections… all already move an
individual's `reputation` via `ConsequenceEngine`/`PetitionSystem`/
`CrimeSystem`/etc.), so a second hand-maintained number would either
duplicate that bookkeeping or drift out of sync with it. Instead
`FamilyReputationSystem.familyReputationOf` computes a weighted mean **at
read time**: the resident's own reputation (full weight), every other
living member of their current household (weight 0.7 — the household is
also what persists and merges across a marriage, see "households merge"
under `EventType.MARRIAGE` above), and up to two generations of direct
ancestors via `motherId`/`fatherId` (weight decaying ×0.4 per generation,
whether the ancestor is alive or dead) — a family's name still carries
weight a generation or two after the people who built it are gone, but
fades rather than lingering forever. Falls back to the town-wide default
(50.0) for a resident with no traceable family at all (e.g. a founding
resident). `FamilyReputationSystem.standingModifier` turns that into a
small, bounded modifier — centred on 0 at the 50.0 default, clamped to a
caller-supplied `maxSwing` — for composing into existing rolls without ever
letting one family's name dominate an outcome:
- **Building repairs.** `BuildingLifecycleSystem`'s daily repair roll for a
  home (not a business — family standing is a personal-name effect, not a
  balance-sheet one) now adds `standingModifier(…, FAMILY_REPUTATION_CHANCE_
  SWING = 0.05)` on top of its `BASE_REPAIR_CHANCE` (0.15), composing
  additively alongside the councillor/mayoral repair bonus
  (`ElectionSystem.repairChanceBonus`) added in the same file around the
  same time, clamped overall to 0.02–0.35 — a well-regarded family gets
  tradespeople to their door a little faster; a poorly-regarded one waits a
  little longer.
- **First impressions.** `InteractionSystem.interact`'s "pleasant exchange"
  branch now adds a small trust nudge (`FIRST_MEETING_TRUST_SWING`, ±3.0 for
  each side) on a resident's **first-ever** meeting with someone
  (`rel.familiarity < 5.0`) — a family's reputation precedes them, but only
  until the two people actually get to know each other themselves, at which
  point their own shared history takes over exactly as before.

**Era summary.** When the resident the player is actually *following*
(`WorldState.followedResidentId`) dies, the existing death-of-followed flow
(`WorldRepository.detectFollowedDeath`, which already built a `DeathSummary`
— family left behind, a one-line life summary, whose lives to follow next —
surfaced via the existing `DeathSummaryDialog`) now also builds a genuine
retrospective of the era that life spanned, attached as `DeathSummary.era:
EraSummary?` (`null` for anyone who wasn't being followed — this is
deliberately not computed for every death in the log, only the one the
player was actually watching). Built entirely at the UI/repository layer,
not inside `LifecycleSystem.die` — the engine doesn't keep queryable event
history in `WorldState` itself, only the append-only event log the database
layer already indexes by time (`EventDao.eventsBetween`) — so `die()`'s only
change is a new `payload["bornAt"]` on the `PERSON_DIED` event, letting the
repository know what time range to query. `WorldRepository.buildEraSummary`
then:
- Queries every `PUBLIC` event between birth and death and counts how many
  clear the *same* `ImportanceScorer.HISTORY_THRESHOLD` (30.0) the History
  timeline itself already uses for "notable" — no second bar invented — then
  keeps the 4 highest-importance descriptions as `witnessed`.
- Reads the resident's own `memories` (already ranked by `importance` then
  `emotionalIntensity`, the same ordering `passDownBeliefs` above already
  uses) and keeps the top 4 as `definingMemories`.
- Counts current relationships at `FRIEND`/`CLOSE_FRIEND`/`PARTNER`/
  `SPOUSE`/`FAMILY` kind as `relationshipsFormed` — genuine closeness, not
  mere acquaintance.
- `years` is simply `(deathTime − bornAt) / MINUTES_PER_YEAR`.

  Surfaced minimally, per scope — no new screen: `DeathSummaryDialog`
  (`feature/town/TownSheets.kt`) gains a "Their era" section listing the
  town events lived through and the memories they'll be remembered for,
  directly under the existing life summary, only rendered when `era` is
  non-null.

## Health

Conditions: cold, flu, injury, exhaustion, back trouble (chronic), weak heart
(serious/chronic), lung illness (serious), frailty. Daily onset risk is
assembled from stress, low energy, low health, age (> 60, > 75), heavy factory
work and winter — a controlled biological floor of 0.2 %/day. Serious
conditions may start hidden. Severity moves with rest vs stress; the clinic
treats faster and can discover hidden conditions (35 %/visit). Serious
diagnosis: stress +18, a `GET_HEALTHY` goal, reduced working hours (income
drops), partner stress and dependency rise. Mortality risk comes from severe
serious conditions, age > 78 and collapsed health — never a bare dice roll.

## Economy

Residents: living costs 9/day, monthly rent split across household adults,
gentle daily debt interest, automatic repayments from surplus. Businesses:
footfall each open hour from `demand × weather`, plus real purchases by
simulated residents; wages and overheads daily at 23:50. Balance < 0 for 5
days → `BUSINESS_STRUGGLING` (owner stress, staff hours cut); 18 days →
closure: building abandoned, every employee gets a `JOB_LOST` event caused by
the closure, demand shifts to same-type rivals over the following week.
Healthy businesses (> 9 000) may expand (+capacity, building extension);
demand > 62 with spare capacity hires — preferring detailed residents with
active `FIND_JOB` goals, promoting background residents otherwise.

## Business rivalries

Economy v2 slice, scoped down to price/demand competition and rivalries
between same-type businesses (general price inflation/deflation, the
property market and further business-succession work beyond the existing
death-of-owner heir handoff are separate, still-open backlog items — see
`docs/backlog.md`). Run daily from `BusinessRivalrySystem.updateDaily`, after
`PetitionSystem`, bounded to `MAX_PAIRS_PER_DAY` (40) business pairs per day:

- **Standing.** Every open, non-public-service business pair sharing a
  `BusinessType` is compared on *standing* = `reputation − (priceLevel − 1) ×
  40` — cheaper and better-reputed both help.
- **Price/demand competition.** Whichever of the pair has the better standing
  gains `DEMAND_SHIFT_PER_DAY` (2.0) `demand`, the other loses the same,
  every day both are open — the same `coerceIn(5.0, 95.0)` clamp `EconomySystem`
  already uses. Deliberately gentle: pairs drift apart over weeks, not days.
- **Owner rivalry.** Only kicks in when the pair's standing gap is within
  `CLOSE_COMPETITION_THRESHOLD` (20.0) — a runaway leader and an already-
  settled laggard aren't "competing" any more, so their owners' relationship
  is left alone. While two owners' businesses stay closely matched, their
  existing relationship (`state.relationshipOrCreate`) drifts daily:
  resentment `+0.6`, affection `−0.3`. Once resentment exceeds 55 and
  affection falls below 30 — the *exact* thresholds `InteractionSystem.
  updateKind` uses for personal rivalries — the relationship kind flips to
  `RelationshipKind.RIVAL` and `RIVALRY_FORMED` fires (`PRIVATE`), applied
  directly here (checked explicitly, same as `updateKind`, rather than
  routed through it) since two business owners competing for trade may never
  actually be co-located to trigger the ordinary interaction path. Skipped
  entirely for `FIXED_KINDS`-equivalent relationships (family, partner,
  spouse, former partner, affair) so business competition never overwrites a
  relationship that means something else, and for an owner competing with
  themselves (one person owning both businesses). Rivalry is meant to feel
  earned: only pairs that stay closely, persistently matched for a sustained
  stretch cross the threshold — most same-type pairs never do.

## Price drift

Economy v2 slice: "prices that move" — slow, town-wide price inflation/
deflation, independent of the same-type demand competition owned by
`BusinessRivalrySystem` above. That system shifts `demand` between competing
pairs; `PriceDriftSystem.updateDaily` (run daily, straight after
`BusinessRivalrySystem`) nudges `priceLevel` itself instead, town-wide, one
business at a time — the two never touch the same field and never
double-count. No macro "inflation index" exists elsewhere in `WorldState` to
drive this off aggregate conditions yet, so it's a small bounded random walk
through `ctx.rng` (never `Math.random()`), matching the shape of every other
system's dice-roll daily nudge. Bounded to `MAX_BUSINESSES_PER_DAY` (60) open,
non-public-service businesses per day:

- **Whether a business drifts at all.** Each eligible business independently
  rolls `DRIFT_CHANCE` (12%) per day — most days, most businesses don't move.
- **Direction.** A struggling business (`daysInTrouble > 0` or a negative
  balance) is biased `STRUGGLING_DOWN_BIAS` (75%) to drift its `priceLevel`
  *down* — discounting to chase trade, the way a real business under pressure
  would. A prosperous business (balance over `EconomySystem.EXPANSION_BALANCE`,
  9 000 — the same threshold that already gates business expansion) is biased
  `PROSPEROUS_UP_BIAS` (65%) to drift *up*. Anything in between is an even
  50/50 coin flip.
- **Size and bounds.** Each drift is one `DRIFT_STEP` (0.02, a ~2% nudge),
  clamped to `PRICE_LEVEL_MIN`/`PRICE_LEVEL_MAX` (0.7–1.4) — the same
  `priceLevel` field `EconomySystem.hourlyFootfall` already multiplies into
  spend and `BusinessRivalrySystem.standing` already factors into
  competition, so this composes with both instead of adding a parallel price
  concept.
- **Newsworthiness.** A single day's drift step is too small to matter on its
  own. A `PRICES_SHIFTED` event (`PUBLIC`) only fires the day a business's
  cumulative `priceLevel` crosses `NEWSWORTHY_SWING` (0.10 away from the 1.0
  baseline) for the first time in that direction — a `else -> 8.0`-scored
  event type in `ImportanceScorer` and folded into `NewspaperGenerator`'s
  default `StoryCategory.TOWN_NEWS` bucket, so no scoring/newspaper wiring
  changes were needed for the new type.

## Business succession

Economy v2's last open slice: **voluntary, in-life** ownership handoff, as
distinct from the pre-existing, unchanged silent transfer in
`LifecycleSystem.die` (which still applies whenever an owner dies without
having already retired — the "no chosen heir was ready in time" fallback).
`BusinessSuccessionSystem.updateDaily` runs daily, straight after
`PriceDriftSystem`, and models exactly one shape of succession — a parent
handing the business to a child who already works there — deliberately
narrow; founding a new business or a non-family sale to an outside buyer are
not attempted:

- **Eligibility.** The owner must be alive, in town, and at least
  `RETIREMENT_AGE` (68). They must also have an adult child (alive, in town)
  currently *employed at that same business* — the heir already knows the
  trade, so this is a natural handoff rather than a random pick, not a
  general "any relative" rule.
- **Timing.** Once eligible, a `SUCCESSION_CHANCE_PER_DAY` (6%) daily roll
  through `ctx.rng` decides whether it happens that day — a slow, considered
  decision spread over weeks/months, matching every other daily system's
  gentle pacing rather than firing the instant someone turns 68.
- **What happens.** `Business.ownerId` moves to the heir; the heir's
  employment record at that business ends (they're not staff anymore, they
  run it) via the same `Employment.endedAt` field job-loss/quitting already
  use. Any active `RETIRE_WELL` goal the former owner was working towards is
  marked `COMPLETED`. A `BUSINESS_SUCCESSION` event (`PUBLIC`) fires, and
  both parties get an `ACHIEVEMENT` memory of the day — pride for the parent
  handing it on, trust for the child receiving it. Bounded to
  `MAX_BUSINESSES_PER_DAY` (40) businesses considered per day, like every
  other daily system.
- **Still open:** non-family succession (selling to an outside buyer),
  succession disputes between multiple ready heirs (currently just picks the
  first eligible child by id), and voluntary sale/closure unrelated to
  retirement — none of these are attempted here. Combined with price drift
  and rivalries above, this closes out the Economy v2 backlog item's three
  originally-scoped pieces (rivalries, prices-that-move, succession); the
  property market (residents buying/selling homes) is a related but
  separate, still-open item.

## Property market

Economy v2's last open slice: residents actually **buying** the home they
live in — as distinct from the pre-existing, unchanged free-relocation path
(`GoalSystem`'s `MOVE_HOME` goal, which walks a household into any vacant
home with no money changing hands and no ownership recorded at all).
`PropertyMarketSystem.updateDaily` runs daily, straight after
`BusinessSuccessionSystem`, and closes out the Economy v2 backlog item's
fourth and last originally-scoped piece. Deliberately a scoped-down MVP, not
a full real-estate sim:

- **What counts as "for sale."** `Building.ownerId` was previously never set
  for homes at all (only business buildings get an owner, via
  `GoalSystem.openBusiness` / seeded in `WorldGenerator`) — a genuinely
  unused field this system repurposes cleanly. A home with `ownerId == null`
  reads as "not yet owned"; buying it just records who owns it going
  forward. There is no separate landlord/tenant model, no rental agreements,
  and no eviction — a household can carry on living in an unowned home
  indefinitely, exactly as before this system existed.
- **Who can buy.** Every day, up to `MAX_HOUSEHOLDS_PER_DAY` (40) households
  with a home and at least one living, in-town adult member are considered,
  in stable id order. A household buys its *own* current home (the one it
  already lives in via `Household.homeBuildingId`) if it's unowned and the
  household's pooled adult wealth clears the asking price (`Building.value`)
  plus a `MIN_RESERVE_AFTER_PURCHASE` (200) cushion, so a purchase never
  strips a family down to nothing. This deliberately covers every route a
  household can already end up with a home — an existing household buying
  the place it's rented for years, a newly-formed household (post-marriage
  merge, a returning student rehoused, someone coming of age and promoted
  into their own household) eventually saving enough to buy in — without
  inventing a second, parallel "who gets a home" mechanism alongside
  `MOVE_HOME`/`promoteIfNeeded`/`studentReturns`.
- **Payment.** Cash only, straight from resident `wealth` — no mortgages, no
  loans, no instalment plans. The price is drawn from the nominal buyer (the
  household's wealthiest adult) first, then any other in-town adult
  household members in descending wealth order, until the full price is
  covered — a genuine household purchase, not one person's balance going
  arbitrarily negative while everyone else's savings sit untouched.
- **No negotiation, no competing bidders.** The asking price is exactly
  `Building.value` — no haggling, discounts, or bidding wars — and the first
  eligible household found each day simply buys; there's no auction or
  multi-household contest over the same property.
- **Newsworthiness.** A new `HOME_PURCHASED` event (`PUBLIC`) fires on every
  purchase and an `ACHIEVEMENT` memory is recorded for the buyer — checked
  `ImportanceScorer.baseImportance` and `NewspaperGenerator.categoryFor`/
  `headlineFor` first, same as `PRICES_SHIFTED`/`BUSINESS_SUCCESSION` before
  it: both have safe `else ->` fallbacks (8.0 base importance;
  `StoryCategory.TOWN_NEWS`; the type's own label as headline), so no
  further scoring/newspaper wiring was needed for the new type.
- **Still open, deliberately:** no negotiation/haggling and no competing
  bidders (as above); no mortgages or loans of any kind, cash purchase
  against existing `wealth` only; no rental-to-ownership *transition*
  modelling beyond what already exists — an unowned home doesn't behave any
  differently day-to-day than an owned one (no rent distinction, no
  landlord), this system only ever adds the "who bought it" fact on top; no
  selling (a household moving out via `MOVE_HOME` doesn't clear `ownerId`,
  so a bought home stays "owned" by its buyer even if they later move
  elsewhere — modelling resale is separate, unattempted work); no price
  appreciation tied to ownership history. Combined with rivalries, price
  drift and succession above, this closes out the Economy v2 backlog item in
  full.

## Crime & suspicion

Motive: poverty. `JOB_LOST` seeds a `CRIME_TEMPTATION` delayed effect
(honesty and impulsiveness govern whether it's yielded to, `STILL_POOR`
condition); if it fires, money goes missing from a business, recorded as a
`HIDDEN` `CRIME_COMMITTED` — nobody in town knows yet, including whether
anything happened at all. 60% chance it's investigated. `CrimeSystem` keeps
one adult resident appointed **constable** (highest
`honesty×0.6 + courage×0.4`, re-appointed if the post falls vacant) and has
them build a suspect pool from every other detailed adult, weighted by
*plausible motive* rather than certainty: dishonesty, poor finances
(`financialSecurity < 35` or `debt > 500`), and resentment towards the
victim's business owner. The true culprit is always in the pool but the
weighted pick can land on someone else — a `CRIME_REPORTED` event carries
only what the constable *believes* (`payload["accurate"]`), never the
engine's ground truth, same principle as [rumours](#rumours). An accurate
accusation costs the real culprit stress and reputation; a false one costs
the wrongly-accused resident more of both, a `HUMILIATION` memory, and
resentment/trust damage towards the constable — while the actual culprit
gets away with it (a little private unease, never public).

## Events, causes, importance

`WorldEvent` fields include type, severity, visibility (PUBLIC / PRIVATE /
HIDDEN), structured payload, `causeIds`, `consequenceDepth` and importance.
Importance = type base × (0.6 + 0.8×severity) × reach factor, and **+4 per
later event that cites it as a cause**. Events ≥ 30 importance appear in the
History timeline. Cause chains are read straight from the log — only known
history, never futures.

## Consequence rules & delayed effects

Rules are small data objects: cause type → probability → bounded application.
Examples shipped: job loss (immediate strain + partner pressure + crime
temptation + consider-moving + health erosion, all conditional), business
closure (demand shifts), struggling business (owner stress, hours cuts),
arguments (grudge *or* forgiveness depending on patience), separation
(move-out goal, children carry memories), serious diagnosis (work, partner),
crime (town safety, reporting), death (grief decay, community gathering),
marriage (glow, households merge), secrets revealed (trust shake).

`DelayedEffect`s carry strength, a `[earliest, latest]` window, a condition
(STILL_POOR, STILL_STRESSED, STILL_RESENTFUL, STILL_UNEMPLOYED, BOTH_ALIVE),
and optional decay. Each tick, due effects fire with
`p ≈ strength × 8 / windowTicks` (max 6 per tick); lapsed windows cancel
silently. Chains cap at depth 10 and 24 rule applications per tick.

## Memories

Created for emotionally significant moments (arguments, kindness, loss,
romance, achievements, fear). Fields include intensity, accuracy, importance
and optional formed beliefs ("The clinic caught it in time"). Intensity and
accuracy decay yearly; memories below importance 40 evaporate once faded;
a resident keeps at most 40, discarding the least important. Memories feed
goal generation (e.g. inspiration memories enable `START_BUSINESS`).

## Goals

Compositional generation from circumstance (need + skill + opportunity +
memory/idea seed), e.g. unemployed + carpentry > 55 + vacant granary + idea
seed + ambition → *start a small business*. Progress accrues through the
`WORK_ON_GOAL` action and daily updates; `START_BUSINESS` additionally needs
400 startup capital and converts the vacant building into a workshop.
Other goals: find job, find partner, pay off debt, get healthy, leave for
education (teens actually leave town at 18), move home, run for office,
learn skill. Goals abandon under despair (stress > 90, 5 %/day).

## Interventions

Wallet of **3 nudges**; 1 regenerates per 12 observed in-game hours; a given
person can only be nudged once per 24 in-game hours. Verbs: Delay, Divert,
Reveal, Conceal, Introduce, Misplace, Encourage, Distract, Inspire, Warn.
Every verb only changes circumstance (timings, chances, awareness, seeds).
Nothing is guaranteed: Introduce creates a *meeting chance* delayed effect;
if both residents actually show up, the ordinary interaction system decides
what happens. Interventions are stored permanently and emit HIDDEN events
that surface in cause chains only when later events cite them.

## Newspaper

*The <Town> Argus*, weekly at 08:00 (first issue the morning after creation).
Input: the week's PUBLIC events only. Sections: front-page headline (most
important event, with deterministic template variation), secondary stories,
births, deaths, weddings, trade notices, constable's column, weather, public
notices. Old issues stay in the archive forever.

## Detail levels & promotion

Background residents have needs clamped to a statistical routine and skip
decisions, interactions, health and goals. Promotion to detailed happens on:
being followed, being hired into an on-map business, or arriving as a new
family — promotion assigns housing when available.

## Phase 4: External world pressure

The first Phase 4 item — "the outside world" starting to press in on the
town. Deliberately the smallest honest slice of the backlog's much larger
vision (see `docs/backlog.md`'s Phase 4 section): the `NarrativeTextProvider`/
`DialogueProvider` LLM layer, the richer "national layer" context, and
shareable town chronicles are all separate, unattempted items. This is not a
real-world news feed and never will be — every pressure is entirely
fictional/abstract, consistent with the rest of Ripple's fictional town: no
real place names, no real companies, no real politics or current events.

**Naming note.** The backlog names this item `ExternalWorldEventProvider` /
`WorldPressureMapper`, but those exact identifiers were already claimed by
pre-existing placeholder interfaces in `core/simulation/providers/
FutureProviders.kt` (`suspend fun pendingPressures(...)`, wired for DI in
`di/AppModule.kt`) — a seam intentionally reserved for a *later*, real
external/async feed (network- or LLM-backed). This task is the deliberate
opposite of that: a small, curated, fully deterministic, `ctx.rng`-driven
system living inside the ordinary tick pipeline, not an injected async
dependency. To avoid a confusing same-name-different-package collision, the
concrete types below are named `CuratedWorldPressureFeed` and
`WorldPressureMechanicMapper` instead — same responsibilities the backlog
item describes, distinct identifiers from the unrelated future-architecture
placeholder.

Two clearly separated responsibilities, per the backlog brief's explicit
"strict mapping" instruction, both in `ExternalWorldEventProvider.kt`:

- **`CuratedWorldPressureFeed`** — the curated feed. Run daily
  (`updateDaily`, last in `SimulationCoordinator`'s `if (newDay)` block).
  Deliberately scoped to **at most one active pressure at a time**, town-wide
  — no overlapping/stacking pressures, no per-business or per-resident
  targeting. While none is active, a small daily roll (`START_CHANCE_PER_DAY`,
  2%) may begin one, picked via `ctx.rng.pick` from a small hand-curated list
  of eight abstract kinds in matched rise/ease pairs: fuel prices rise/ease,
  poor/strong national harvest, trade routes disrupted/flourishing, economic
  confidence dips/rises. A started pressure runs for a random 14–45 in-game
  days (`PRESSURE_MIN_DAYS`/`PRESSURE_MAX_DAYS`) before automatically
  resolving. Both the start and the resolution fire a new, narrowly-scoped
  `EventType.NATIONAL_PRESSURE` (`PUBLIC`, severity 0.3/0.2) — checked
  `ImportanceScorer` and `NewspaperGenerator` first, same as every other new
  event type this session: both have safe `else ->` fallbacks (8.0 base
  importance; `StoryCategory.TOWN_NEWS`), so no further wiring was needed.
  Framed as background/abstract town-wide news (a line of overheard talk, not
  a personal event) — deliberately did not reuse `TOWN_MILESTONE`, since that
  type's 60.0 base importance and "a real town accomplishment" flavour don't
  fit a piece of ambient background news the town merely hears about.
- **`WorldPressureMechanicMapper`** — the strict mechanical translation, and
  only ever one clean hook. Reads `WorldState.externalPressure` and, if it's
  currently `FUEL_PRICES_RISE` or `FUEL_PRICES_EASE`, returns a multiplier
  (`FUEL_RISE_OVERHEAD_MULTIPLIER` 1.15, `FUEL_EASE_OVERHEAD_MULTIPLIER` 0.92;
  `1.0` — no effect — otherwise) that `EconomySystem.dailySettlement`
  composes directly into its existing per-business overhead-expense
  calculation (`overheads(biz.type) * WorldPressureMechanicMapper.overheadMultiplier(state)`)
  — the literal "fuel prices rise -> delivery costs rise" chain the backlog
  names, landing on the one place in the codebase that already models a
  business's costs. Deliberately did **not** also touch `PriceDriftSystem`'s
  struggling-bias or `BusinessRivalrySystem`'s standing calculation in the
  same pass — the brief explicitly asks for one clean, traceable hook rather
  than a vague multiplier sprinkled across several systems, so touching just
  overhead expense keeps the effect fully explainable by reading one line in
  one system.

**Deliberately out of scope for this pass**, matching the brief's explicit
scoped-down instruction:

- The other six curated kinds (harvest, trade routes, confidence — both
  directions) are recorded and reported on but carry **no** mechanical effect
  yet — flavour-only background news, honestly scoped as such rather than
  wired to some unrelated system just so every kind "does something".
- No overlapping/stacking pressures — a second pressure simply can't start
  while one is already active, even if its daily roll would otherwise land.
- No LLM-authored prose for the pressure descriptions — the curated
  start/resolve lines are small, hand-written, fixed strings per kind (a
  handful of lines each), not generated text. The genuinely generative
  `NarrativeTextProvider`/`DialogueProvider` layer remains a separate,
  unattempted backlog item.
- No richer "national layer" context (taxes, broader trends) beyond this one
  pressure slot, and no shareable chronicles export — both separate,
  unattempted Phase 4 items.

Modelled with a new `ExternalPressureKind` enum and `ExternalPressure` data
class (`core/model/WorldState.kt`) plus a single new
`WorldState.externalPressure: ExternalPressure?` field (`null` most of the
time) — a plain new field with a safe default, no schema migration. All
randomness (whether a pressure starts, which kind, how long it runs) goes
through `ctx.rng`, never `Math.random()`.

## Offline catch-up

`elapsedRealMs → gameMinutes` at the 1× rate, capped at **30 in-game days**,
run in bounded batches (per-call tick caps) with progress reported to the UI,
then summarised ("While you were away, 12 days passed — 3 notable things
happened."). The cap and batching are covered by tests.
