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
