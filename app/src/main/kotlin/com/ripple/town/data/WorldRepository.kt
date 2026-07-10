package com.ripple.town.data

import com.ripple.town.core.database.RippleDatabase
import com.ripple.town.core.database.SimulationCheckpointEntity
import com.ripple.town.core.database.TownEntity
import com.ripple.town.core.database.WorldEntity
import com.ripple.town.core.database.WorldEventEntity
import com.ripple.town.core.database.causeEntities
import com.ripple.town.core.database.skillCatalog
import com.ripple.town.core.database.skillEntities
import com.ripple.town.core.database.toCsv
import com.ripple.town.core.database.toDomain
import com.ripple.town.core.database.toEntity
import com.ripple.town.core.database.DbJson
import com.ripple.town.core.database.FollowedResidentEntity
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.InterventionVerb
import com.ripple.town.core.model.SimSpeed
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.CatchUpSummary
import com.ripple.town.core.simulation.ImportanceScorer
import com.ripple.town.core.simulation.InterventionResult
import com.ripple.town.core.simulation.SimulationCoordinator
import com.ripple.town.core.simulation.WorldGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

data class EventUi(
    val id: Long,
    val time: Long,
    val timeLabel: String,
    val typeLabel: String,
    val description: String,
    val importance: Double,
    val severity: Double,
    val buildingId: Long?,
    val involvedResidentIds: List<Long>,
    val hasCauses: Boolean
)

data class DeathSummary(
    val residentId: Long,
    val name: String,
    val age: Int,
    val cause: String,
    val lifeSummary: String,
    val familyLeft: List<Pair<Long, String>>,
    val suggestions: List<Pair<Long, String>>
)

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WorldRepository @Inject constructor(
    private val db: RippleDatabase,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope
) {
    /** All engine access is confined to this dispatcher. */
    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val initMutex = Mutex()

    private var coordinator: SimulationCoordinator? = null
    private var runnerJob: Job? = null
    private var foreground = false

    private val _worldUi = MutableStateFlow<WorldUi?>(null)
    val worldUi: StateFlow<WorldUi?> = _worldUi.asStateFlow()

    private val _speed = MutableStateFlow(SimSpeed.NORMAL)
    val speed: StateFlow<SimSpeed> = _speed.asStateFlow()

    private val _catchUp = MutableStateFlow<CatchUpProgress?>(null)
    val catchUpProgress: StateFlow<CatchUpProgress?> = _catchUp.asStateFlow()

    private val _followedDeath = MutableStateFlow<DeathSummary?>(null)
    val followedDeath: StateFlow<DeathSummary?> = _followedDeath.asStateFlow()

    private val _alerts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val alerts: SharedFlow<String> = _alerts.asSharedFlow()

    val isInitialized: Boolean get() = coordinator != null

    // ------------------------------------------------------------ lifecycle

    /** Restores the saved world if one exists. Returns true if a world is ready. */
    suspend fun restoreIfPresent(): Boolean = initMutex.withLock {
        if (coordinator != null) return true
        val settings = settingsRepository.current()
        if (!settings.onboarded) return false
        val checkpoint = db.worldDao().latestCheckpoint(WORLD_ID) ?: return false
        val state = runCatching {
            DbJson.json.decodeFromString(WorldState.serializer(), checkpoint.stateJson)
        }.getOrNull() ?: return false
        withContext(engineDispatcher) {
            val coord = SimulationCoordinator(state)
            // Re-prime the newspaper buffer with events since the last issue.
            val since = if (state.lastNewspaperAt >= 0) state.lastNewspaperAt else 0L
            val buffered = db.eventDao().eventsBetween(since, Long.MAX_VALUE)
                .map { it.toDomain() }
            coord.primeNewspaperBuffer(buffered)
            coordinator = coord
        }
        _speed.value = settings.speed
        publishSnapshot()
        // Catch up on time that passed while the app was closed.
        val elapsed = System.currentTimeMillis() - checkpoint.savedAtRealMs
        if (elapsed > 60_000) runCatchUp(elapsed)
        startRunnerIfNeeded()
        return true
    }

    /** Creates a brand-new world (from onboarding). */
    suspend fun createWorld(townName: String, speed: SimSpeed, seed: Long = System.currentTimeMillis()) {
        initMutex.withLock {
            val state = withContext(engineDispatcher) {
                WorldGenerator(seed, townName).generate(System.currentTimeMillis())
            }
            withContext(engineDispatcher) { coordinator = SimulationCoordinator(state) }
            settingsRepository.setOnboarded(townName, seed, speed)
            _speed.value = speed
            db.worldDao().upsertWorld(
                WorldEntity(
                    id = WORLD_ID, seed = seed, name = townName,
                    createdAtRealMs = state.createdAtRealMs,
                    simTimeMinutes = state.time,
                    lastSavedRealMs = System.currentTimeMillis(),
                    speedName = speed.name
                )
            )
            db.worldDao().upsertTown(
                TownEntity(
                    id = WORLD_ID, worldId = WORLD_ID, name = townName,
                    mapWidth = state.map.width, mapHeight = state.map.height,
                    mapJson = DbJson.json.encodeToString(
                        ListSerializer(kotlinx.serialization.serializer<TileType>()), state.map.tiles
                    )
                )
            )
            db.mirrorDao().upsertSkillCatalog(skillCatalog())
            state.followedResidentId?.let {
                db.followDao().upsert(FollowedResidentEntity(it, isPrimary = true, followedSinceSimTime = state.time))
            }
            saveCheckpoint()
            publishSnapshot()
        }
        startRunnerIfNeeded()
    }

    fun setForeground(fg: Boolean) {
        foreground = fg
        if (fg) startRunnerIfNeeded() else {
            runnerJob?.cancel()
            runnerJob = null
            appScope.launch { saveCheckpoint() }
        }
    }

    private fun startRunnerIfNeeded() {
        if (!foreground || coordinator == null || runnerJob?.isActive == true) return
        runnerJob = appScope.launch(engineDispatcher) {
            var accumulatedGameMinutes = 0.0
            var lastReal = System.currentTimeMillis()
            while (isActive) {
                delay(RUNNER_PERIOD_MS)
                val now = System.currentTimeMillis()
                val dtSeconds = (now - lastReal) / 1000.0
                lastReal = now
                val speedNow = _speed.value
                if (speedNow == SimSpeed.PAUSED) continue
                accumulatedGameMinutes += dtSeconds * SimTime.GAME_MINUTES_PER_REAL_SECOND_AT_1X * speedNow.multiplier
                var ticked = false
                var safety = 12 // bound work per wake-up
                while (accumulatedGameMinutes >= SimTime.MINUTES_PER_TICK && safety-- > 0) {
                    accumulatedGameMinutes -= SimTime.MINUTES_PER_TICK
                    val result = coordinator?.tick() ?: break
                    persistTickResult(result.events, result.importanceBoosts, result.newIssue, result.newStatistic, result.checkpointDue)
                    ticked = true
                }
                if (ticked) publishSnapshot()
            }
        }
    }

    // -------------------------------------------------------------- ticking

    private suspend fun persistTickResult(
        events: List<WorldEvent>,
        boosts: Map<Long, Double>,
        newIssue: com.ripple.town.core.model.NewspaperIssue?,
        newStat: com.ripple.town.core.model.TownStatistic?,
        checkpointDue: Boolean
    ) {
        if (events.isNotEmpty()) {
            db.eventDao().insertEvents(events.map { it.toEntity() })
            db.eventDao().insertCauses(events.flatMap { it.causeEntities() })
            notifyIfRelevant(events)
            detectFollowedDeath(events)
        }
        if (boosts.isNotEmpty()) db.eventDao().boostAll(boosts)
        if (newIssue != null) {
            db.newspaperDao().insertIssue(newIssue.toEntity())
            db.newspaperDao().insertStories(newIssue.stories.map { it.toEntity() })
        }
        if (newStat != null) db.statisticsDao().insert(newStat.toEntity())
        if (checkpointDue) saveCheckpoint()
    }

    private suspend fun runCatchUp(elapsedRealMs: Long) {
        val coord = coordinator ?: return
        val gameMinutes = minOf(
            (elapsedRealMs / 1000.0).toLong(),
            SimulationCoordinator.MAX_OFFLINE_DAYS * SimTime.MINUTES_PER_DAY
        )
        val totalTicks = (gameMinutes / SimTime.MINUTES_PER_TICK).toInt()
        if (totalTicks <= 0) return
        _catchUp.value = CatchUpProgress(running = true, totalTicks = totalTicks, doneTicks = 0)
        var done = 0
        val allEvents = mutableListOf<WorldEvent>()
        withContext(engineDispatcher) {
            while (done < totalTicks) {
                val batch = minOf(CATCHUP_BATCH_TICKS, totalTicks - done)
                val summary: CatchUpSummary = coord.catchUp(
                    elapsedRealMs = batch.toLong() * SimTime.MINUTES_PER_TICK * 1000L,
                    maxTicksPerCall = batch
                )
                persistTickResult(summary.events, summary.importanceBoosts, null, null, checkpointDue = false)
                summary.issues.forEach { issue ->
                    db.newspaperDao().insertIssue(issue.toEntity())
                    db.newspaperDao().insertStories(issue.stories.map { it.toEntity() })
                }
                summary.statistics.forEach { db.statisticsDao().insert(it.toEntity()) }
                allEvents += summary.events
                done += summary.ticksRun
                _catchUp.value = CatchUpProgress(running = true, totalTicks = totalTicks, doneTicks = done)
                if (summary.ticksRun == 0) break
            }
        }
        saveCheckpoint()
        publishSnapshot()
        val majorCount = allEvents.count { it.importance >= ImportanceScorer.HISTORY_THRESHOLD }
        val daysAway = totalTicks / SimTime.TICKS_PER_DAY
        _catchUp.value = CatchUpProgress(
            running = false, totalTicks = totalTicks, doneTicks = done,
            summary = when {
                daysAway <= 0 -> null
                majorCount == 0 -> "While you were away, $daysAway quiet day${if (daysAway == 1) "" else "s"} passed in ${coord.state.townName}."
                else -> "While you were away, $daysAway day${if (daysAway == 1) "" else "s"} passed — $majorCount notable thing${if (majorCount == 1) "" else "s"} happened."
            }
        )
    }

    fun dismissCatchUp() { _catchUp.value = null }

    private suspend fun publishSnapshot() {
        val coord = coordinator ?: return
        val snapshot = withContext(engineDispatcher) { SnapshotBuilder.build(coord.state) }
        _worldUi.value = snapshot
    }

    // ---------------------------------------------------------- persistence

    suspend fun saveCheckpoint() {
        val coord = coordinator ?: return
        val (json, state) = withContext(engineDispatcher) {
            DbJson.json.encodeToString(WorldState.serializer(), coord.state) to coord.state
        }
        db.worldDao().insertCheckpoint(
            SimulationCheckpointEntity(
                worldId = WORLD_ID,
                simTimeMinutes = state.time,
                savedAtRealMs = System.currentTimeMillis(),
                stateJson = json
            )
        )
        db.worldDao().pruneCheckpoints(WORLD_ID)
        db.worldDao().upsertWorld(
            WorldEntity(
                id = WORLD_ID, seed = state.seed, name = state.townName,
                createdAtRealMs = state.createdAtRealMs, simTimeMinutes = state.time,
                lastSavedRealMs = System.currentTimeMillis(), speedName = _speed.value.name
            )
        )
        mirrorState(state)
    }

    private suspend fun mirrorState(state: WorldState) {
        val dao = db.mirrorDao()
        dao.upsertResidents(state.residents.values.map { it.toEntity(WORLD_ID) })
        dao.upsertHouseholds(state.households.values.map { it.toEntity(WORLD_ID) })
        dao.upsertRelationships(state.relationships.values.map { it.toEntity() })
        dao.upsertBuildings(state.buildings.values.map { it.toEntity(WORLD_ID) })
        dao.upsertBusinesses(state.businesses.values.map { it.toEntity() })
        dao.upsertEmployments(state.employments.values.map { it.toEntity() })
        dao.upsertHealthConditions(state.residents.values.flatMap { r -> r.conditions.map { it.toEntity() } })
        dao.upsertMemories(state.residents.values.flatMap { r -> r.memories.map { it.toEntity() } })
        dao.upsertGoals(state.residents.values.flatMap { r -> r.goals.map { it.toEntity() } })
        dao.upsertResidentSkills(state.residents.values.flatMap { it.skillEntities() })
        dao.upsertDelayedEffects(state.delayedEffects.map { it.toEntity() })
    }

    // ------------------------------------------------------------- controls

    suspend fun setSpeed(speed: SimSpeed) {
        _speed.value = speed
        settingsRepository.setSpeed(speed)
    }

    suspend fun setPrimaryFollow(residentId: Long) {
        withContext(engineDispatcher) {
            val state = coordinator?.state ?: return@withContext
            state.followedResidentId = residentId
            if (residentId !in state.discoveredResidentIds) state.discoveredResidentIds += residentId
        }
        db.followDao().upsert(FollowedResidentEntity(residentId, isPrimary = true, followedSinceSimTime = currentTime()))
        db.followDao().clearPrimaryExcept(residentId)
        publishSnapshot()
    }

    suspend fun toggleFavourite(residentId: Long) {
        var added = false
        withContext(engineDispatcher) {
            val state = coordinator?.state ?: return@withContext
            if (residentId in state.favouriteResidentIds) {
                state.favouriteResidentIds.remove(residentId)
            } else {
                state.favouriteResidentIds.add(residentId)
                added = true
                if (residentId !in state.discoveredResidentIds) state.discoveredResidentIds += residentId
            }
        }
        if (added) {
            db.followDao().upsert(FollowedResidentEntity(residentId, isPrimary = false, followedSinceSimTime = currentTime()))
        } else {
            db.followDao().remove(residentId)
        }
        publishSnapshot()
    }

    fun dismissFollowedDeath() { _followedDeath.value = null }

    suspend fun applyIntervention(
        verb: InterventionVerb,
        targetResidentId: Long?,
        secondaryResidentId: Long? = null,
        targetBuildingId: Long? = null,
        free: Boolean = false
    ): InterventionResult {
        val coord = coordinator ?: return InterventionResult.Rejected("The world is still waking up.")
        val (result, events) = withContext(engineDispatcher) {
            coord.intervene(verb, targetResidentId, secondaryResidentId, targetBuildingId, free)
        }
        if (result is InterventionResult.Applied) {
            db.interventionDao().insert(result.intervention.toEntity())
            db.eventDao().insertEvents(events.map { it.toEntity() })
            db.eventDao().insertCauses(events.flatMap { it.causeEntities() })
            if (free) settingsRepository.setFreeNudgeUsed()
            publishSnapshot()
        }
        return result
    }

    private suspend fun currentTime(): Long =
        withContext(engineDispatcher) { coordinator?.state?.time ?: 0L }

    // -------------------------------------------------------------- queries

    fun latestEvents(limit: Int = 40): Flow<List<EventUi>> =
        db.eventDao().latestEvents(limit).map { rows -> rows.filter { it.visibility != "HIDDEN" }.map { it.toUi() } }

    fun historyEvents(limit: Int = 200): Flow<List<EventUi>> =
        db.eventDao().importantEvents(ImportanceScorer.HISTORY_THRESHOLD, limit)
            .map { rows -> rows.map { it.toUi() } }

    suspend fun eventsForResident(residentId: Long, limit: Int = 30): List<EventUi> =
        db.eventDao().eventsForResident(residentId, limit)
            .filter { it.visibility != "HIDDEN" }
            .map { it.toUi() }

    suspend fun eventsForBuilding(buildingId: Long, limit: Int = 30): List<EventUi> =
        db.eventDao().eventsForBuilding(buildingId, limit)
            .filter { it.visibility != "HIDDEN" }
            .map { it.toUi() }

    suspend fun event(id: Long): EventUi? = db.eventDao().event(id)?.toUi()

    /**
     * Cause chain for the "Why did this happen?" viewer: levels from the event
     * back through its recorded causes. Only known history — never futures.
     * Interventions stay hidden until their consequences have surfaced (their
     * events are HIDDEN visibility but appear here once cited as causes).
     */
    suspend fun causeChain(eventId: Long, maxDepth: Int = 8): List<List<EventUi>> {
        val levels = mutableListOf<List<EventUi>>()
        var frontier = listOf(eventId)
        val seen = mutableSetOf<Long>()
        var depth = 0
        while (frontier.isNotEmpty() && depth <= maxDepth) {
            val rows = db.eventDao().events(frontier).sortedByDescending { it.time }
            if (rows.isEmpty()) break
            levels += rows.map { it.toUi() }
            seen += frontier
            frontier = frontier.flatMap { db.eventDao().causeIdsOf(it) }.distinct().filter { it !in seen }
            depth++
        }
        return levels
    }

    fun newspaperIssues(): Flow<List<com.ripple.town.core.database.NewspaperIssueEntity>> = db.newspaperDao().issues()

    suspend fun storiesOf(issueId: Long): List<com.ripple.town.core.database.NewspaperStoryEntity> =
        db.newspaperDao().storiesOf(issueId)

    fun interventions(): Flow<List<com.ripple.town.core.database.InterventionEntity>> = db.interventionDao().all()

    fun statistics(limit: Int = 60): Flow<List<com.ripple.town.core.database.TownStatisticEntity>> =
        db.statisticsDao().latest(limit)

    // ------------------------------------------------------------ internals

    private fun WorldEventEntity.toUi(): EventUi = EventUi(
        id = id,
        time = time,
        timeLabel = SimTime.formatDateTime(time),
        typeLabel = runCatching { EventType.valueOf(type).label }.getOrDefault(type),
        description = description,
        importance = importance,
        severity = severity,
        buildingId = buildingId,
        involvedResidentIds = (listOfNotNull(sourceResidentId) + targetResidentIdsCsv.split(',')
            .filter { it.isNotBlank() }.map { it.toLong() }).distinct(),
        hasCauses = consequenceDepth > 0
    )

    private suspend fun notifyIfRelevant(events: List<WorldEvent>) {
        val settings = settingsRepository.current()
        if (!settings.notificationsEnabled) return
        val watched = withContext(engineDispatcher) {
            val s = coordinator?.state ?: return@withContext emptySet<Long>()
            (listOfNotNull(s.followedResidentId) + s.favouriteResidentIds).toSet()
        }
        if (watched.isEmpty()) return
        for (e in events) {
            if (e.visibility == com.ripple.town.core.model.EventVisibility.HIDDEN) continue
            val relevant = e.involvedResidentIds().any { it in watched }
            val major = e.importance >= ImportanceScorer.HISTORY_THRESHOLD
            if (relevant && (major || e.type in ALERT_TYPES)) {
                _alerts.tryEmit(e.description)
            }
        }
    }

    private suspend fun detectFollowedDeath(events: List<WorldEvent>) {
        val followed = withContext(engineDispatcher) { coordinator?.state?.followedResidentId } ?: return
        val death = events.firstOrNull { it.type == EventType.PERSON_DIED && it.sourceResidentId == followed }
            ?: return
        val summary = withContext(engineDispatcher) {
            val state = coordinator!!.state
            val r = state.resident(followed) ?: return@withContext null
            val family = (listOfNotNull(r.partnerId) + r.childIds)
                .mapNotNull { state.resident(it) }
                .filter { it.alive }
                .map { it.id to "${it.fullName} (${it.ageAt(state.time)})" }
            val suggestions = (
                family.map { it.first } +
                    state.relationshipsOf(r.id).sortedByDescending { it.warmth() }.map { it.other(r.id) }
                )
                .distinct()
                .mapNotNull { state.resident(it) }
                .filter { it.alive && it.inTown }
                .take(4)
                .map { it.id to "${it.fullName} — ${it.occupation}" }
            val worked = r.occupation
            DeathSummary(
                residentId = r.id,
                name = r.fullName,
                age = r.ageAt(state.time),
                cause = r.causeOfDeath ?: "unknown",
                lifeSummary = buildString {
                    append("${r.firstName} lived ${r.ageAt(state.time)} years")
                    if (worked.isNotBlank() && worked != "Unemployed") append(", latterly as ${worked.lowercase()}")
                    append(". ")
                    if (r.childIds.isNotEmpty()) append("Parent of ${r.childIds.size}. ")
                    append("Remembered by ${state.relationshipsOf(r.id).count { it.warmth() > 30 }} people in ${state.townName}.")
                },
                familyLeft = family,
                suggestions = suggestions
            )
        }
        if (summary != null) _followedDeath.value = summary
    }

    companion object {
        const val WORLD_ID = 1L
        const val RUNNER_PERIOD_MS = 500L
        const val CATCHUP_BATCH_TICKS = 288 // two in-game days per UI progress step
        val ALERT_TYPES = setOf(
            EventType.PERSON_BORN, EventType.PERSON_DIED, EventType.JOB_LOST, EventType.JOB_STARTED,
            EventType.MARRIAGE, EventType.SEPARATION, EventType.DIVORCE, EventType.ILLNESS_DIAGNOSED,
            EventType.RELATIONSHIP_STARTED, EventType.BUSINESS_OPENED, EventType.BUSINESS_CLOSED
        )
    }
}
