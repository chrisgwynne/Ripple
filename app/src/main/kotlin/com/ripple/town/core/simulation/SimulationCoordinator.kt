package com.ripple.town.core.simulation

import com.ripple.town.core.model.InterventionVerb
import com.ripple.town.core.model.NewspaperIssue
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.TownStatistic
import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState

/** Everything a single tick produced, for persistence and UI. */
data class TickResult(
    val tick: Long,
    val events: List<WorldEvent>,
    val importanceBoosts: Map<Long, Double>,
    val newIssue: NewspaperIssue?,
    val newStatistic: TownStatistic?,
    val checkpointDue: Boolean
)

data class CatchUpSummary(
    val simMinutesAdvanced: Long,
    val ticksRun: Int,
    val capped: Boolean,
    val events: List<WorldEvent>,
    val issues: List<NewspaperIssue>,
    val statistics: List<TownStatistic>,
    val importanceBoosts: Map<Long, Double>
)

/**
 * Owns the mutable [WorldState] and advances it deterministically, one bounded
 * tick at a time, in a fixed system order. Not thread-safe: callers must
 * confine access to a single dispatcher.
 */
class SimulationCoordinator(
    val state: WorldState,
    private val eventIndex: InMemoryEventIndex = InMemoryEventIndex(),
    /** Events since the last newspaper issue, kept for issue generation. */
    private val newspaperBuffer: MutableList<WorldEvent> = mutableListOf()
) {

    var ticksSinceCheckpoint = 0
        private set

    /** Pre-load context after restoring from a checkpoint. */
    fun primeNewspaperBuffer(events: List<WorldEvent>) {
        newspaperBuffer.clear()
        newspaperBuffer += events
        events.forEach { eventIndex.remember(it) }
    }

    /**
     * Advance the world by exactly one tick ([SimTime.MINUTES_PER_TICK] in-game
     * minutes), running the full system pipeline in fixed order.
     */
    fun tick(): TickResult {
        val tickNumber = SimTime.tickOf(state.time)
        val rng = SimRandom(state.seed, tickNumber)
        val ctx = TickContext(state, rng, eventIndex)

        // 1. Advance time.
        state.time += SimTime.MINUTES_PER_TICK
        val newDay = state.time % SimTime.MINUTES_PER_DAY < SimTime.MINUTES_PER_TICK

        // 2. Needs, weather, travel arrivals.
        NeedsSystem.update(ctx)
        // 3-4. Urgent health checks (clinic treatment, crises).
        HealthSystem.updateUrgent(ctx)
        // 5. Resident decisions (utility-scored actions).
        DecisionSystem.update(ctx)
        // 6-7. Social interactions and relationship arcs.
        InteractionSystem.update(ctx)
        // 8. Businesses and money.
        EconomySystem.update(ctx)
        // 9. Delayed effects whose windows are open.
        DelayedEffectSystem.update(ctx)
        // 9b. Private events may leak into public rumour.
        RumourSystem.update(ctx)
        // 10-12. Daily passes: health, lifecycle (births/deaths/election), council seats &
        // campaign-driven elections (layers on top of the same-day election result above), goals,
        // building repairs, seasonal events (harvest fair / winter market / river floods), local
        // politics (petitions over noise / rent), business rivalries (same-type price/
        // reputation competition, owner resentment), town-wide price drift (independent,
        // slow inflation/deflation on top of rivalry-driven demand shifts), business
        // succession (elderly owners voluntarily handing down to a working adult child),
        // property market (households buying the home they already live in, cash only).
        if (newDay) {
            HealthSystem.updateDaily(ctx)
            LifecycleSystem.updateDaily(ctx)
            ElectionSystem.updateDaily(ctx)
            GoalSystem.updateDaily(ctx)
            BuildingLifecycleSystem.updateDaily(ctx)
            SeasonalEventSystem.updateDaily(ctx)
            PetitionSystem.updateDaily(ctx)
            BusinessRivalrySystem.updateDaily(ctx)
            PriceDriftSystem.updateDaily(ctx)
            BusinessSuccessionSystem.updateDaily(ctx)
            PropertyMarketSystem.updateDaily(ctx)
        }
        // 13. Intervention influence regenerates through observation.
        InterventionEngine.regenerate(state, SimTime.MINUTES_PER_TICK)

        // 14. Newspaper when due.
        var newIssue: NewspaperIssue? = null
        newspaperBuffer += ctx.newEvents
        if (NewspaperGenerator.isDue(state)) {
            newIssue = NewspaperGenerator.generate(state, newspaperBuffer.toList(), rng)
            newspaperBuffer.clear()
        }

        // 15. Daily statistic + checkpoint cadence.
        var stat: TownStatistic? = null
        val dayIndex = SimTime.dayIndex(state.time)
        if (newDay && dayIndex != state.lastStatisticDay) {
            stat = buildStatistic(dayIndex)
            state.lastStatisticDay = dayIndex
            state.birthsToday = 0
            state.deathsToday = 0
        }

        ctx.newEvents.forEach { eventIndex.remember(it) }
        ticksSinceCheckpoint++
        val checkpointDue = ticksSinceCheckpoint >= CHECKPOINT_EVERY_TICKS
        if (checkpointDue) ticksSinceCheckpoint = 0

        return TickResult(
            tick = tickNumber,
            events = ctx.newEvents.toList(),
            importanceBoosts = ctx.importanceBoosts.toMap(),
            newIssue = newIssue,
            newStatistic = stat,
            checkpointDue = checkpointDue
        )
    }

    /**
     * Player intervention entry point. Runs inside the same confinement as ticks.
     */
    fun intervene(
        verb: InterventionVerb,
        targetResidentId: Long?,
        secondaryResidentId: Long? = null,
        targetBuildingId: Long? = null,
        free: Boolean = false
    ): Pair<InterventionResult, List<WorldEvent>> {
        val tickNumber = SimTime.tickOf(state.time)
        val rng = SimRandom(state.seed, tickNumber, salt = 0x1EAF)
        val ctx = TickContext(state, rng, eventIndex)
        val result = InterventionEngine.apply(ctx, verb, targetResidentId, secondaryResidentId, targetBuildingId, free)
        ctx.newEvents.forEach { eventIndex.remember(it) }
        newspaperBuffer += ctx.newEvents
        return result to ctx.newEvents.toList()
    }

    /**
     * Offline catch-up: convert elapsed real time into in-game time (at 1x pace),
     * cap it, and run it in bounded batches. Returns everything that happened.
     */
    fun catchUp(elapsedRealMs: Long, maxTicksPerCall: Int = MAX_CATCHUP_TICKS_PER_CALL): CatchUpSummary {
        val gameMinutes = (elapsedRealMs / 1000.0 * SimTime.GAME_MINUTES_PER_REAL_SECOND_AT_1X).toLong()
        val capMinutes = MAX_OFFLINE_DAYS * SimTime.MINUTES_PER_DAY
        val capped = gameMinutes > capMinutes
        val minutesToRun = minOf(gameMinutes, capMinutes)
        var ticksWanted = (minutesToRun / SimTime.MINUTES_PER_TICK).toInt()
        ticksWanted = minOf(ticksWanted, maxTicksPerCall)

        val events = mutableListOf<WorldEvent>()
        val issues = mutableListOf<NewspaperIssue>()
        val stats = mutableListOf<TownStatistic>()
        val boosts = mutableMapOf<Long, Double>()
        repeat(ticksWanted) {
            val result = tick()
            events += result.events
            result.newIssue?.let { issues += it }
            result.newStatistic?.let { stats += it }
            for ((k, v) in result.importanceBoosts) boosts[k] = (boosts[k] ?: 0.0) + v
        }
        return CatchUpSummary(
            simMinutesAdvanced = ticksWanted.toLong() * SimTime.MINUTES_PER_TICK,
            ticksRun = ticksWanted,
            capped = capped,
            events = events,
            issues = issues,
            statistics = stats,
            importanceBoosts = boosts
        )
    }

    private fun buildStatistic(dayIndex: Long): TownStatistic {
        val living = state.livingResidents()
        val adults = living.filter { it.lifeStageAt(state.time) == com.ripple.town.core.model.LifeStage.ADULT }
        val detailed = living.filter { it.detailLevel == com.ripple.town.core.model.DetailLevel.DETAILED }
        return TownStatistic(
            dayIndex = dayIndex,
            population = living.size,
            detailedPopulation = detailed.size,
            employedAdults = adults.count { state.employmentOf(it) != null },
            adultCount = adults.size,
            openBusinesses = state.businesses.values.count { it.open },
            averageWellbeing = if (detailed.isEmpty()) 0.0 else detailed.sumOf { it.needs.wellbeing() } / detailed.size,
            averageWealth = if (detailed.isEmpty()) 0.0 else detailed.sumOf { it.wealth } / detailed.size,
            births = state.birthsToday,
            deaths = state.deathsToday
        )
    }

    companion object {
        /** Cap offline progression at 30 in-game days. */
        const val MAX_OFFLINE_DAYS = 30L
        const val MAX_CATCHUP_TICKS_PER_CALL = 30 * 144 // 30 days of 10-minute ticks
        const val CHECKPOINT_EVERY_TICKS = 36 // every 6 in-game hours
    }
}
