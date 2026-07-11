package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.NewspaperIssue
import com.ripple.town.core.model.NewspaperStory
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.StoryCategory
import com.ripple.town.core.model.Weather
import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState

/**
 * The Ashcombe Argus: a weekly paper generated purely from public simulation
 * events, with deterministic template variation. It is the town's *public*
 * understanding — private and hidden events never appear.
 */
object NewspaperGenerator {

    const val ISSUE_PERIOD_DAYS = 7L

    fun isDue(state: WorldState): Boolean {
        if (state.lastNewspaperAt < 0) {
            // First issue a day after the world starts.
            return state.time - (state.time % SimTime.MINUTES_PER_DAY) > 0 &&
                SimTime.hourOfDay(state.time) == 8 && state.issuesPublished == 0
        }
        return state.time - state.lastNewspaperAt >= ISSUE_PERIOD_DAYS * SimTime.MINUTES_PER_DAY &&
            SimTime.hourOfDay(state.time) == 8
    }

    fun generate(state: WorldState, periodEvents: List<WorldEvent>, rng: SimRandom): NewspaperIssue {
        val public = periodEvents.filter { it.visibility == EventVisibility.PUBLIC }
        val issue = NewspaperIssue(
            id = state.nextIssueId++,
            issueNumber = ++state.issuesPublished,
            publishedAt = state.time,
            masthead = "The ${state.townName} Argus"
        )
        var order = 0
        fun add(category: StoryCategory, headline: String, body: String, eventId: Long? = null) {
            issue.stories += NewspaperStory(
                id = state.nextStoryId++, issueId = issue.id, category = category,
                headline = headline, body = body, eventId = eventId, orderInIssue = order++
            )
        }

        // Main headline: most important public event of the week.
        val headline = public.maxByOrNull { it.importance }
        if (headline != null) {
            add(StoryCategory.HEADLINE, headlineFor(headline, state, rng), bodyFor(headline, state, rng), headline.id)
        } else {
            add(
                StoryCategory.HEADLINE,
                rng.pick(QUIET_WEEK_HEADLINES),
                "Little of consequence troubled ${state.townName} this week, and residents report being perfectly content with that."
            )
        }

        // Secondary stories: a mixed sample across importance tiers, not just the top of
        // the pile. A real small-town paper is mostly everyday notices with the occasional
        // community item and rarely a major story — so we bucket the week's public events by
        // importance and sample roughly 70% low / 20% medium / 10% high, deterministically via
        // `rng`. This deliberately reaches past the old importance>20 floor, which excluded
        // almost every low-severity ctx.emit() in the codebase (MEETING, COMMUNITY_EVENT,
        // BUILDING_REPAIRED, RUMOUR_SPREAD, SKILL_MILESTONE, PRICES_SHIFTED, etc.) from ever
        // being read by the newspaper at all.
        val candidates = public.filter { it.id != headline?.id }
        val (low, medium, high) = bucketByImportance(candidates)
        val secondary = sampleMixed(low, medium, high, count = 5, rng)
        secondary.forEach { add(categoryFor(it.type), headlineFor(it, state, rng), bodyFor(it, state, rng), it.id) }

        // Registers
        public.filter { it.type == EventType.PERSON_BORN }.forEach {
            add(StoryCategory.BIRTHS, "New arrival", it.description, it.id)
        }
        public.filter { it.type == EventType.PERSON_DIED }.forEach {
            add(StoryCategory.DEATHS, "In memoriam", it.description, it.id)
        }
        public.filter { it.type == EventType.MARRIAGE || it.type == EventType.ENGAGEMENT }.forEach {
            add(StoryCategory.WEDDINGS, "Bells and blessings", it.description, it.id)
        }
        public.filter { it.type == EventType.BUSINESS_OPENED || it.type == EventType.BUSINESS_CLOSED || it.type == EventType.BUSINESS_EXPANDED }
            .filter { it.id != headline?.id }
            .forEach { add(StoryCategory.BUSINESS, "Trade notices", it.description, it.id) }
        public.filter { it.type == EventType.CRIME_REPORTED }.forEach {
            add(StoryCategory.CRIME, "Constable's column", it.description, it.id)
        }

        // Weather corner — always present.
        add(StoryCategory.WEATHER, "The week ahead", weatherCopy(state.weather, rng))

        // A public notice for flavour — genuinely mundane, everyday material tying into the
        // real simulated town (park, café, actual businesses) rather than free-floating text.
        add(StoryCategory.NOTICES, "Public notices", noticeCopy(state, rng))

        // Mysteries surfaced passively — no announcement, just the paper noticing things
        val legend = if (rng.nextBoolean(0.25)) LegendSystem.strongestActive(state) else null
        if (legend != null) {
            add(StoryCategory.HUMAN_INTEREST,
                "Word around town",
                "It has been said — and this paper neither confirms nor denies — that ${legend.text}")
        }
        val anomaly = if (rng.nextBoolean(0.15)) AnomalyDetector.recentAnomaly(state) else null
        if (anomaly != null) {
            add(StoryCategory.HUMAN_INTEREST, "A curious observation", anomaly.description)
        }
        val coldCase = UnsolvedCaseSystem.anniversaryCase(state)
        if (coldCase != null) {
            val years = UnsolvedCaseSystem.yearsOpen(coldCase, state.time)
            val s = if (years == 1L) "" else "s"
            val snippet = coldCase.description.take(60).trimEnd { it == ' ' || it == ',' }
            add(StoryCategory.CRIME,
                "$years year$s on:",
                "It has now been $years year$s since $snippet. The case remains unsolved.")
        }

        state.lastNewspaperAt = state.time
        return issue
    }

    /** Importance tiers used for the 70/20/10 mixed sample. Mirrors the History timeline's
     *  [ImportanceScorer.HISTORY_THRESHOLD] on the high end, but — unlike History — the paper
     *  actively wants the low tier, which is most of what `ctx.emit()` actually produces. */
    private fun bucketByImportance(events: List<WorldEvent>): Triple<List<WorldEvent>, List<WorldEvent>, List<WorldEvent>> {
        val low = events.filter { it.importance < 20.0 }
        val medium = events.filter { it.importance in 20.0..ImportanceScorer.HISTORY_THRESHOLD }
        val high = events.filter { it.importance > ImportanceScorer.HISTORY_THRESHOLD }
        return Triple(low, medium, high)
    }

    /** Deterministically samples up to [count] events from the three tiers at roughly a
     *  70/20/10 split, falling back to whatever tiers actually have material so a quiet week
     *  doesn't come up short just because nothing "major" happened — which, most weeks, is
     *  the point: a real small-town paper is mostly everyday notices. */
    private fun sampleMixed(
        low: List<WorldEvent>, medium: List<WorldEvent>, high: List<WorldEvent>,
        count: Int, rng: SimRandom
    ): List<WorldEvent> {
        val targetLow = (count * 0.7).toInt().coerceAtLeast(1)
        val targetMedium = (count * 0.2).toInt().coerceAtLeast(1)
        val targetHigh = (count - targetLow - targetMedium).coerceAtLeast(0)

        val picked = mutableListOf<WorldEvent>()
        val leftovers = mutableListOf<WorldEvent>()
        fun takeFrom(pool: List<WorldEvent>, n: Int) {
            val shuffled = shuffle(pool, rng)
            picked += shuffled.take(n)
            leftovers += shuffled.drop(n)
        }
        takeFrom(high, targetHigh)
        takeFrom(medium, targetMedium)
        takeFrom(low, targetLow)

        // Backfill from whatever's left (any tier) if a tier came up short, so the issue still
        // reads full most weeks without ever forcing importance>threshold stories to appear.
        if (picked.size < count) {
            val remaining = shuffle(leftovers, rng)
            picked += remaining.take(count - picked.size)
        }
        return picked.distinctBy { it.id }.sortedByDescending { it.importance }
    }

    /** Deterministic Fisher-Yates shuffle using the engine's own [SimRandom], never
     *  `kotlin.random.Random`/`Collection.shuffled()` — the whole point is that the same
     *  seed + tick always produces the same issue. */
    private fun shuffle(list: List<WorldEvent>, rng: SimRandom): List<WorldEvent> {
        val out = list.toMutableList()
        for (i in out.indices.reversed()) {
            if (i == 0) break
            val j = rng.nextInt(i + 1)
            val tmp = out[i]; out[i] = out[j]; out[j] = tmp
        }
        return out
    }

    private fun categoryFor(type: EventType): StoryCategory = when (type) {
        EventType.BUSINESS_OPENED, EventType.BUSINESS_CLOSED, EventType.BUSINESS_EXPANDED,
        EventType.BUSINESS_STRUGGLING, EventType.JOB_STARTED, EventType.JOB_LOST -> StoryCategory.BUSINESS
        EventType.CRIME_COMMITTED, EventType.CRIME_REPORTED,
        EventType.SHOPLIFTING, EventType.BURGLARY, EventType.MUGGING,
        EventType.VEHICLE_THEFT, EventType.FRAUD, EventType.ARSON_ATTEMPT,
        EventType.VANDALISM -> StoryCategory.CRIME
        EventType.ILLNESS_DIAGNOSED, EventType.ILLNESS_RECOVERED, EventType.WORKPLACE_ACCIDENT -> StoryCategory.HEALTH
        EventType.WEATHER_DAMAGE -> StoryCategory.WEATHER
        EventType.ELECTION_WON, EventType.ELECTION_CALLED,
        EventType.PETITION_STARTED, EventType.PETITION_RESOLVED, EventType.PROTEST_DISRUPTION -> StoryCategory.TOWN_NEWS
        EventType.MEETING, EventType.FRIENDSHIP_FORMED, EventType.COMMUNITY_EVENT,
        EventType.RUMOUR_SPREAD, EventType.BUILDING_REPAIRED, EventType.SKILL_MILESTONE,
        EventType.APOLOGY, EventType.INJURY,
        EventType.MISSING_PERSON_REPORTED, EventType.MISSING_PERSON_FOUND,
        EventType.DOMESTIC_DISTURBANCE -> StoryCategory.HUMAN_INTEREST
        EventType.PRICES_SHIFTED -> StoryCategory.BUSINESS
        else -> StoryCategory.TOWN_NEWS
    }

    private fun headlineFor(e: WorldEvent, state: WorldState, rng: SimRandom): String {
        return when (e.type) {
            EventType.BUSINESS_CLOSED -> {
                val biz = e.businessId?.let { state.businesses[it] }
                val years = biz?.let { ((state.time - it.openedAt) / SimTime.MINUTES_PER_YEAR).toInt() } ?: 0
                rng.pick(listOf(
                    "${biz?.name ?: "Local business"} closes" + (if (years > 1) " after $years years" else ""),
                    "End of an era as ${biz?.name ?: "a local business"} shuts its doors",
                    "High street loses ${biz?.name ?: "another trader"}"
                ))
            }
            EventType.BUSINESS_OPENED -> rng.pick(listOf(
                "New venture breathes life into town",
                "Open for business: a fresh start",
                "Green shoots on the high street"
            ))
            EventType.PERSON_DIED -> {
                val r = e.sourceResidentId?.let { state.resident(it) }
                "${state.townName} mourns ${r?.fullName ?: "a resident"}"
            }
            EventType.ELECTION_WON -> {
                val r = e.sourceResidentId?.let { state.resident(it) }
                rng.pick(listOf(
                    "${r?.fullName ?: "New mayor"} takes the chain of office",
                    "Election night: ${r?.surname ?: "a new name"} carries the town"
                ))
            }
            EventType.WEATHER_DAMAGE -> rng.pick(listOf(
                "Storm leaves its mark", "Night of wind and worry", "Weather takes its toll"
            ))
            EventType.RESIDENT_ARRIVED -> rng.pick(listOf(
                "New faces in town", "Welcome to ${state.townName}"
            ))
            EventType.RESIDENT_LEFT_TOWN -> "One of our own moves on"
            EventType.MARRIAGE -> "A wedding to remember"
            EventType.PERSON_BORN -> "A new resident, pocket-sized"
            EventType.CRIME_REPORTED, EventType.CRIME_COMMITTED -> "Unease on the high street"
            EventType.BUSINESS_STRUGGLING -> "Hard times for a local trader"
            EventType.RUMOUR_SPREAD -> rng.pick(listOf(
                "Talk of the town", "What the town is saying", "Whispers and word-of-mouth"
            ))
            EventType.PETITION_STARTED -> rng.pick(listOf(
                "Residents rally behind new petition", "A cause takes root", "Voices raised at the town hall"
            ))
            EventType.PETITION_RESOLVED -> {
                val succeeded = e.payload["outcome"] == "succeeded"
                if (succeeded) rng.pick(listOf(
                    "Petition wins the day", "People power pays off", "Council bows to public pressure"
                )) else rng.pick(listOf(
                    "Petition falls short", "Campaign fizzles out", "Not enough names on the dotted line"
                ))
            }
            // Everyday, low-stakes stories — the bulk of a real small-town paper. These lean on
            // real structured data (the resident's own name/skill, the building they were seen
            // at) rather than free-floating flavour text, the same way every headline above does.
            EventType.MEETING -> {
                val a = e.sourceResidentId?.let { state.resident(it) }
                val b = e.targetResidentIds.firstOrNull()?.let { state.resident(it) }
                val where = e.buildingId?.let { state.buildings[it]?.name }
                if (a != null && b != null) rng.pick(listOf(
                    "${a.firstName} and ${b.firstName} spotted at ${where ?: "the high street"}",
                    "A friendly chat outside ${where ?: "the high street"}"
                )) else "Seen around town"
            }
            EventType.COMMUNITY_EVENT -> rng.pick(listOf(
                "A good turnout on the green", "${state.townName} gathers", "Neighbours make a morning of it"
            ))
            EventType.FRIENDSHIP_FORMED -> "A new friendship in the making"
            EventType.SKILL_MILESTONE -> {
                val r = e.sourceResidentId?.let { state.resident(it) }
                rng.pick(listOf(
                    "${r?.firstName ?: "A local"} is getting rather good at that",
                    "Practice pays off for ${r?.firstName ?: "one resident"}"
                ))
            }
            EventType.PRICES_SHIFTED -> rng.pick(listOf(
                "What things cost this week", "Shelf prices on the move", "Market-day watch"
            ))
            EventType.BUILDING_REPAIRED -> {
                val b = e.buildingId?.let { state.buildings[it] }
                "${b?.name ?: "A local building"} looking smarter"
            }
            EventType.APOLOGY -> "Bygones, mostly, be bygones"
            EventType.INJURY -> rng.pick(listOf(
                "A stumble, nothing more", "Minor mishap on the high street"
            ))
            EventType.GOAL_FORMED -> rng.pick(listOf(
                "Ambitions stirring", "Someone's got a plan"
            ))
            // Incident severity system (2026-07-10) — see docs/simulation-rules.md.
            EventType.SHOPLIFTING -> rng.pick(listOf(
                "Sticky fingers on the high street", "Till comes up short again"
            ))
            EventType.BURGLARY -> rng.pick(listOf(
                "Break-in shakes a quiet street", "Home targeted while family was out"
            ))
            EventType.MUGGING -> rng.pick(listOf(
                "Robbed in broad daylight", "Confronted and robbed — police appeal for calm"
            ))
            EventType.VEHICLE_THEFT -> "Cart taken from outside the owner's nose"
            EventType.FRAUD -> "Books don't add up at local trader"
            EventType.ARSON_ATTEMPT -> "Scorch marks spark alarm"
            EventType.VANDALISM -> rng.pick(listOf(
                "Fresh damage overnight", "Someone's had a bad night with a can of paint"
            ))
            EventType.DOMESTIC_DISTURBANCE -> "Raised voices worry the neighbours"
            EventType.MISSING_PERSON_REPORTED -> "Town asked to keep an eye out"
            EventType.MISSING_PERSON_FOUND -> "Relief as missing resident turns up safe"
            EventType.WORKPLACE_ACCIDENT -> "Accident at work — nothing life-threatening"
            EventType.PROTEST_DISRUPTION -> rng.pick(listOf(
                "Voices raised outside the town hall", "Feelings run high at public gathering"
            ))
            else -> e.type.label
        }
    }

    private fun bodyFor(e: WorldEvent, state: WorldState, rng: SimRandom): String {
        val opener = rng.pick(listOf(
            "Word reached the Argus this week that ",
            "Residents will already have heard: ",
            "It is confirmed that ",
            "The talk of the town: "
        ))
        return opener + e.description.replaceFirstChar { it.lowercase() } +
            rng.pick(listOf(
                " More as we have it.",
                " The Argus will follow the story.",
                " Neighbours are invited to lend a hand where they can.",
                ""
            ))
    }

    private fun weatherCopy(w: Weather, rng: SimRandom): String = when (w) {
        Weather.CLEAR -> rng.pick(listOf(
            "Clear skies expected. Wash-day recommended.",
            "Fine weather holds. The park will be busy."
        ))
        Weather.CLOUDY -> "Grey but dry. Take a coat anyway."
        Weather.RAIN -> "Rain setting in. Gutters, please, before it finds your ceiling."
        Weather.STORM -> "Storm warnings posted at the town hall. Tie down what matters."
        Weather.FOG -> "Fog on the river by morning. Mind the lane."
        Weather.SNOW -> "Snow likely. The school will decide by seven."
    }

    private val QUIET_WEEK_HEADLINES = listOf(
        "A quiet week, and no complaints",
        "Nothing happened, wonderfully",
        "All calm along the high street"
    )

    private val NOTICES = listOf(
        "The {town} allotment society seeks a new treasurer. Enthusiasm optional, honesty essential.",
        "Found: one glove, brown, near the park gates. Claim at the town hall.",
        "The library reminds residents that books do, eventually, need to come back.",
        "Choir practice moves to Thursday. The less said about last Tuesday, the better."
    )

    /** Mundane weather/wildlife/market-day flavour, tied to real buildings and businesses
     *  currently trading in the town rather than invented free-floating locations — a fox near
     *  the park, a menu change at whichever café is actually open, market-day footfall past a
     *  real shopfront. Falls back to the fixed [NOTICES] list if the town happens to have none
     *  of the referenced building types (shouldn't happen on the hand-authored map, but keeps
     *  this safe if the street plan ever changes). */
    private fun noticeCopy(state: WorldState, rng: SimRandom): String {
        val park = state.buildings.values.firstOrNull { it.type == com.ripple.town.core.model.BuildingType.PARK }
        val cafe = state.businesses.values.firstOrNull { it.type == com.ripple.town.core.model.BusinessType.CAFE && it.open }
        val grocer = state.businesses.values.firstOrNull { it.type == com.ripple.town.core.model.BusinessType.GROCER && it.open }
        val pub = state.businesses.values.firstOrNull { it.type == com.ripple.town.core.model.BusinessType.PUB && it.open }

        val everyday = buildList {
            if (park != null) {
                add("A fox was seen crossing ${park.name} at dusk on Tuesday. It did not stay for comment.")
                add("${park.name} was busy with market-day visitors this week — the bench by the gate remains the best seat.")
            }
            if (cafe != null) add("${cafe.name} has a new item on the board this week. Early reports are favourable.")
            if (grocer != null) add("${grocer.name} reports a good week for deliveries — shelves fuller than they have been.")
            if (pub != null) add("Quiz night returns to ${pub.name} on Thursday. Teams of four, as ever.")
            add("A dog answering to no particular name was reunited with its owner near Rowan Street on Wednesday.")
        }
        return if (everyday.isNotEmpty()) rng.pick(everyday)
        else rng.pick(NOTICES).replace("{town}", state.townName)
    }
}
