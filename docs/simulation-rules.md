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
   (births, separations, elections, memory decay), `GoalSystem.updateDaily`.
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

## Family & generations

Births to fertile couples (both 20–44, affection ≥ 55) at
`0.12 %/day × affection × 1/(1+children)`. Children inherit each trait as a
bell-ish sample around the parents' mean (±0.25 spread) — tendencies, not
copies. Estates (wealth − debt) split between partner and children;
businesses pass to an adult heir or the partner. Death widows partners,
shrinks households, and leaves strong memories in everyone warm to the
deceased.

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

## Offline catch-up

`elapsedRealMs → gameMinutes` at the 1× rate, capped at **30 in-game days**,
run in bounded batches (per-call tick caps) with progress reported to the UI,
then summarised ("While you were away, 12 days passed — 3 notable things
happened."). The cap and batching are covered by tests.
