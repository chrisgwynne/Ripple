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

        // Secondary stories: next most important, skipping the headline.
        public.filter { it.id != headline?.id && it.importance > 20 }
            .sortedByDescending { it.importance }
            .take(4)
            .forEach { add(categoryFor(it.type), headlineFor(it, state, rng), bodyFor(it, state, rng), it.id) }

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

        // A public notice for flavour.
        add(StoryCategory.NOTICES, "Public notices", rng.pick(NOTICES).replace("{town}", state.townName))

        state.lastNewspaperAt = state.time
        return issue
    }

    private fun categoryFor(type: EventType): StoryCategory = when (type) {
        EventType.BUSINESS_OPENED, EventType.BUSINESS_CLOSED, EventType.BUSINESS_EXPANDED,
        EventType.BUSINESS_STRUGGLING, EventType.JOB_STARTED, EventType.JOB_LOST -> StoryCategory.BUSINESS
        EventType.CRIME_COMMITTED, EventType.CRIME_REPORTED -> StoryCategory.CRIME
        EventType.ILLNESS_DIAGNOSED, EventType.ILLNESS_RECOVERED -> StoryCategory.HEALTH
        EventType.WEATHER_DAMAGE -> StoryCategory.WEATHER
        EventType.ELECTION_WON, EventType.ELECTION_CALLED -> StoryCategory.TOWN_NEWS
        EventType.MEETING, EventType.FRIENDSHIP_FORMED, EventType.COMMUNITY_EVENT -> StoryCategory.HUMAN_INTEREST
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
}
