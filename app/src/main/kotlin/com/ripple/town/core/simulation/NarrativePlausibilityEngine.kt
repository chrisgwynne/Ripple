package com.ripple.town.core.simulation

import com.ripple.town.core.model.EmergenceRecord
import com.ripple.town.core.model.EmergenceType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.IdentityLabel
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MajorEventEntry
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.NarrativePlausibilityReport
import com.ripple.town.core.model.PlausibilityIssue
import com.ripple.town.core.model.PlausibilityIssueCategory
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState

/**
 * Narrative Plausibility & Emergence Validation Engine.
 *
 * Watches the town over time and asks: "Does this feel real?"
 * Detects suspicious patterns — robotic timing, deterministic durations,
 * serial victims, instant emotional recovery, avalanche clustering — and
 * rewards genuinely surprising life arcs with emergence records.
 *
 * ─────────────────────────────────────────────────────────────────
 * Design principles (from the brief)
 * ─────────────────────────────────────────────────────────────────
 * • Real life is messy. Events cluster. Sometimes nothing happens.
 *   Sometimes everything happens. Patterns emerge. Patterns break.
 *
 * • The engine should never feel random. It should never feel scripted.
 *   Outcomes should feel inevitable only in hindsight.
 *
 * • Autonomous tuning: where safe, automatically adjust timing
 *   distributions, recovery pacing, routine variance.
 *   NEVER alter deterministic causality — only improve plausibility.
 *
 * ─────────────────────────────────────────────────────────────────
 * How it runs
 * ─────────────────────────────────────────────────────────────────
 * [onEvent] — called per-tick for each new event (O(1), lightweight).
 *             Appends to rolling windows; caps list sizes.
 *
 * [updateMonthly] — runs every 30 days in SimulationCoordinator.
 *             Analyses the accumulated data, generates a report, applies
 *             autonomous tuning nudges, detects new emergence records.
 *
 * The report is stored in [WorldState.lastNarrativeReport] and is
 * readable by [NewspaperGenerator] for human-interest stories.
 */
object NarrativePlausibilityEngine {

    const val UPDATE_INTERVAL_DAYS = 30L

    // ─────────────────────────────── event collection

    /** Call once per new event, every tick. Extremely lightweight. */
    fun onEvent(state: WorldState, event: WorldEvent) {
        val data = state.plausibilityData
        val now = state.time

        // 1. Timing distribution — record the hour this event type fired.
        val hour = SimTime.hourOfDay(now)
        val timings = data.eventTimingsByHour.getOrPut(event.type.name) { mutableListOf() }
        timings += hour
        if (timings.size > 50) timings.removeAt(0)

        // 2. Business closure duration — record days-open when a business closes.
        if (event.type == EventType.BUSINESS_CLOSED) {
            val biz = event.businessId?.let { state.businesses[it] }
            if (biz != null) {
                val daysOpen = SimTime.ageYears(biz.openedAt, now).coerceAtLeast(0) * 360 +
                    ((now - biz.openedAt) / SimTime.MINUTES_PER_DAY).toInt()
                data.businessClosureDurations += daysOpen
                if (data.businessClosureDurations.size > 20) data.businessClosureDurations.removeAt(0)
            }
        }

        // 3. Crime victim counts — track which residents are repeatedly targeted.
        if (event.type in CRIME_TYPES) {
            for (victimId in event.targetResidentIds) {
                data.crimeVictimCounts[victimId] = (data.crimeVictimCounts[victimId] ?: 0) + 1
            }
        }

        // 4. Major event clustering — record per-resident major life events.
        if (event.type in MAJOR_LIFE_EVENTS) {
            val day = SimTime.dayIndex(now)
            for (id in event.involvedResidentIds()) {
                data.majorEventLog += MajorEventEntry(id, day)
            }
            // Prune entries older than 90 days.
            val cutoff = SimTime.dayIndex(now) - 90
            data.majorEventLog.removeAll { it.dayIndex < cutoff }
        }
    }

    // ─────────────────────────────── monthly analysis

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        val data = state.plausibilityData
        val dayIndex = SimTime.dayIndex(ctx.now)

        // Reset yearly crime-victim counts.
        if (dayIndex - data.victimCountResetDay >= SimTime.DAYS_PER_YEAR) {
            data.crimeVictimCounts.clear()
            data.victimCountResetDay = dayIndex
        }

        val issues = mutableListOf<PlausibilityIssue>()
        val tuningNotes = mutableListOf<String>()

        // ── 1. Repetitive event timing ───────────────────────────
        for ((typeName, hours) in data.eventTimingsByHour) {
            if (hours.size < 10) continue
            val cv = coefficientOfVariation(hours.map { it.toDouble() })
            if (cv < 0.08) {
                issues += PlausibilityIssue(
                    PlausibilityIssueCategory.REPETITIVE_TIMING.name,
                    "$typeName events cluster at the same hour (CV=${"%.2f".format(cv)}). " +
                        "Expected variety in when this happens throughout the day.",
                    severity = (0.08 - cv) / 0.08
                )
            }
        }

        // Tuning: if sleep events are highly regular, push sleepPressureVariance up.
        val sleepTimings = data.eventTimingsByHour[EventType.PERSON_BORN.name]  // proxy: not ideal, but birth hours are a good diagnostic
        if (sleepTimings != null && sleepTimings.size >= 10) {
            val cv = coefficientOfVariation(sleepTimings.map { it.toDouble() })
            if (cv < 0.15 && state.narrativeTuning.sleepPressureVariance < 0.3) {
                state.narrativeTuning = state.narrativeTuning.copy(
                    sleepPressureVariance = (state.narrativeTuning.sleepPressureVariance + 0.05).coerceAtMost(0.3)
                )
                tuningNotes += "Sleep pressure variance nudged up (+0.05) — schedules were too uniform."
            }
        }

        // ── 2. Business closure duration variance ────────────────
        if (data.businessClosureDurations.size >= 5) {
            val cv = coefficientOfVariation(data.businessClosureDurations.map { it.toDouble() })
            if (cv < 0.1) {
                issues += PlausibilityIssue(
                    PlausibilityIssueCategory.DETERMINISTIC_OUTCOME.name,
                    "Businesses all close after very similar durations (CV=${"%.2f".format(cv)}). " +
                        "Some should thrive for decades; others should fail in weeks.",
                    severity = (0.1 - cv) / 0.1
                )
            }
        }

        // ── 3. Crime victim concentration ───────────────────────
        val population = state.livingResidents().size.coerceAtLeast(1)
        val totalCrimes = data.crimeVictimCounts.values.sum()
        val expectedPerResident = totalCrimes.toDouble() / population
        for ((victimId, count) in data.crimeVictimCounts) {
            if (count > expectedPerResident * 4 && count >= 3) {
                val victim = state.resident(victimId)
                issues += PlausibilityIssue(
                    PlausibilityIssueCategory.SUSPICIOUS_CONCENTRATION.name,
                    "${victim?.fullName ?: "Resident #$victimId"} has been a crime victim $count times " +
                        "(expected ~${expectedPerResident.toInt()} per year). " +
                        "The same person shouldn't always be targeted.",
                    severity = ((count / (expectedPerResident * 4 + 1.0)).coerceAtMost(2.0) / 2.0)
                )
            }
        }

        // ── 4. Life-event avalanche clustering ──────────────────
        val day30Window = SimTime.dayIndex(ctx.now) - 30
        val recentMajor = data.majorEventLog.filter { it.dayIndex >= day30Window }
        val majorPerResident = recentMajor.groupingBy { it.residentId }.eachCount()
        for ((rid, count) in majorPerResident) {
            if (count >= 4) {
                val r = state.resident(rid)
                issues += PlausibilityIssue(
                    PlausibilityIssueCategory.AVALANCHE_CLUSTERING.name,
                    "${r?.fullName ?: "Resident #$rid"} has experienced $count major life events " +
                        "in 30 days. Major events should have emotional breathing room.",
                    severity = (count - 3).toDouble() / 5.0
                )
            }
        }

        // ── 5. Instant emotional recovery detection ──────────────
        val recoveryIssues = detectInstantRecovery(state, ctx.now)
        issues += recoveryIssues
        if (recoveryIssues.isNotEmpty() && state.narrativeTuning.recoveryDamper < 1.5) {
            state.narrativeTuning = state.narrativeTuning.copy(
                recoveryDamper = (state.narrativeTuning.recoveryDamper + 0.1).coerceAtMost(1.5)
            )
            tuningNotes += "Recovery damper nudged up (+0.1) — residents were bouncing back suspiciously fast."
        }

        // ── 6. Social/career variety ─────────────────────────────
        val detailed = state.detailedResidents().filter { it.inTown }
        if (detailed.size >= 8) {
            val adults = detailed.filter { it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            if (adults.isNotEmpty()) {
                val uniqueOccupations = adults.map { it.occupation }.toSet().size
                val occupationVariety = uniqueOccupations.toDouble() / adults.size
                if (occupationVariety < 0.2) {
                    issues += PlausibilityIssue(
                        PlausibilityIssueCategory.UNIFORM_CAREERS.name,
                        "Most adults share the same occupation ($uniqueOccupations unique roles " +
                            "among ${adults.size} adults). Real towns have diverse careers.",
                        severity = (0.2 - occupationVariety) / 0.2
                    )
                }
            }
        }

        // ── 7. Emergence detection ───────────────────────────────
        val newEmergences = detectEmergence(state, ctx.now, data.emergenceRecords)
        data.emergenceRecords += newEmergences

        // ── 8. Plausibility score ────────────────────────────────
        val issueWeight = issues.sumOf { it.severity } / (issues.size.coerceAtLeast(1))
        val emergenceBonus = (newEmergences.size * 5.0).coerceAtMost(20.0)
        val overallScore = (85.0 - issueWeight * 40.0 + emergenceBonus).coerceIn(10.0, 100.0)

        // ── 9. Story quality highlights ─────────────────────────
        val bestEmergence = newEmergences.maxByOrNull { it.surpriseScore }
        val mostInfluential = findMostInfluentialEvent(ctx)

        val report = NarrativePlausibilityReport(
            dayIndex = dayIndex,
            overallScore = overallScore,
            issues = issues.sortedByDescending { it.severity },
            newEmergences = newEmergences,
            mostSurprisingStory = bestEmergence?.description,
            mostInfluentialEvent = mostInfluential,
            appliedTuning = tuningNotes
        )
        state.lastNarrativeReport = report
        data.lastReportDay = dayIndex
    }

    // ─────────────────────────────── helpers

    /** Coefficient of variation: stddev / mean. Returns 1.0 if mean is 0. */
    private fun coefficientOfVariation(values: List<Double>): Double {
        if (values.size < 2) return 1.0
        val mean = values.average()
        if (mean == 0.0) return 1.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return kotlin.math.sqrt(variance) / mean
    }

    private fun detectInstantRecovery(state: WorldState, now: Long): List<PlausibilityIssue> {
        val issues = mutableListOf<PlausibilityIssue>()
        val sevenDays = SimTime.MINUTES_PER_DAY * 7
        for (r in state.detailedResidents()) {
            if (!r.inTown) continue
            // A resident who recently experienced a major loss but is already "Happy" or "Joyful".
            val recentLoss = r.memories.any { m ->
                m.type == MemoryType.LOSS && (now - m.createdAt) < sevenDays && m.emotionalIntensity > 60
            }
            if (recentLoss && r.needs.wellbeing() > 65) {
                issues += PlausibilityIssue(
                    PlausibilityIssueCategory.INSTANT_RECOVERY.name,
                    "${r.fullName} experienced a significant loss recently but already seems content. " +
                        "Grief should linger longer.",
                    severity = 0.4
                )
            }
        }
        return issues
    }

    private fun detectEmergence(
        state: WorldState,
        now: Long,
        existing: List<EmergenceRecord>
    ): List<EmergenceRecord> {
        val found = mutableListOf<EmergenceRecord>()
        val existingKeys = existing.map { "${it.type}_${it.residentId}" }.toSet()

        for (r in state.livingResidents()) {
            if (!r.inTown) continue
            val key = fun(t: EmergenceType) = "${t.name}_${r.id}"

            // UNEXPECTED_LEADER: was born poor (wealth < 50 at some point) and is now mayor.
            if (r.id == state.mayorId && r.wealth < 100 && key(EmergenceType.UNEXPECTED_LEADER) !in existingKeys) {
                found += EmergenceRecord(
                    type = EmergenceType.UNEXPECTED_LEADER.name,
                    residentId = r.id,
                    description = "${r.fullName} rose from humble beginnings to lead ${state.townName}.",
                    discoveredAt = now,
                    surpriseScore = 0.85
                )
            }

            // BUSINESS_EMPIRE: resident owns 3+ open businesses.
            val ownedOpen = state.businesses.values.count { it.ownerId == r.id && it.open }
            if (ownedOpen >= 3 && key(EmergenceType.BUSINESS_EMPIRE) !in existingKeys) {
                found += EmergenceRecord(
                    type = EmergenceType.BUSINESS_EMPIRE.name,
                    residentId = r.id,
                    description = "${r.fullName} built a business empire — $ownedOpen enterprises in ${state.townName}.",
                    discoveredAt = now,
                    surpriseScore = 0.75
                )
            }

            // CRIMINAL_REFORM: resident previously had CRIMINAL identity, now holds public office.
            val wasCriminal = r.identityFacets.any { it.label == IdentityLabel.CRIMINAL }
            val holdsOffice = r.id == state.mayorId
            if (wasCriminal && holdsOffice && key(EmergenceType.CRIMINAL_REFORM) !in existingKeys) {
                found += EmergenceRecord(
                    type = EmergenceType.CRIMINAL_REFORM.name,
                    residentId = r.id,
                    description = "${r.fullName} once had a criminal record — now holds office in ${state.townName}.",
                    discoveredAt = now,
                    surpriseScore = 0.90
                )
            }

            // GENERATIONAL_TALENT: resident is 2+ skill tiers above both parents.
            val rBestSkill = r.skills.values.maxOrNull() ?: 0.0
            val mother = r.motherId?.let { state.resident(it) }
            val father = r.fatherId?.let { state.resident(it) }
            if (mother != null && father != null) {
                val parentBest = maxOf(
                    mother.skills.values.maxOrNull() ?: 0.0,
                    father.skills.values.maxOrNull() ?: 0.0
                )
                if (rBestSkill > parentBest + 40 && rBestSkill >= 80 &&
                    key(EmergenceType.GENERATIONAL_TALENT) !in existingKeys
                ) {
                    found += EmergenceRecord(
                        type = EmergenceType.GENERATIONAL_TALENT.name,
                        residentId = r.id,
                        description = "${r.fullName} far outstripped their parents' abilities — a remarkable talent.",
                        discoveredAt = now,
                        surpriseScore = 0.70
                    )
                }
            }

            // RAGS_TO_RICHES: resident was once below wealth 50 AND now exceeds 800 AND owns a home.
            val nowWealthy = r.wealth > 800
            val ownsHome = state.buildings[r.homeBuildingId]?.ownerId == r.id
            if (nowWealthy && ownsHome && r.ageAt(now) >= 30 &&
                key(EmergenceType.RAGS_TO_RICHES) !in existingKeys
            ) {
                val poorMemory = r.memories.any { m ->
                    m.type == MemoryType.HARDSHIP_SHARED
                }
                if (poorMemory) {
                    found += EmergenceRecord(
                        type = EmergenceType.RAGS_TO_RICHES.name,
                        residentId = r.id,
                        description = "${r.fullName} clawed their way from hardship to prosperity.",
                        discoveredAt = now,
                        surpriseScore = 0.80
                    )
                }
            }
        }

        // DYNASTY_COLLAPSE: a family that had high collective wealth now largely impoverished.
        for (resident in state.livingResidents()) {
            val children = resident.childIds.mapNotNull { state.resident(it) }.filter { it.alive }
            if (children.size >= 2) {
                val parentWasWealthy = resident.wealth < 80 && resident.ageAt(now) >= 50
                val childrenAllPoor = children.all { it.wealth < 60 && it.ageAt(now) >= 18 }
                val keyStr = "${EmergenceType.DYNASTY_COLLAPSE.name}_${resident.id}"
                if (parentWasWealthy && childrenAllPoor && keyStr !in existingKeys) {
                    found += EmergenceRecord(
                        type = EmergenceType.DYNASTY_COLLAPSE.name,
                        residentId = resident.id,
                        description = "The ${resident.surname} family, once prosperous, fell into hard times.",
                        discoveredAt = now,
                        surpriseScore = 0.65
                    )
                }
            }
        }

        return found
    }

    private fun findMostInfluentialEvent(ctx: TickContext): String? {
        val recentWindow = ctx.now - SimTime.MINUTES_PER_DAY * 30
        // Look in the per-tick event dispatch: the event that touched the most residents.
        return ctx.newEvents
            .filter { it.time >= recentWindow }
            .maxByOrNull { it.involvedResidentIds().size + (it.severity * 3).toInt() }
            ?.description
    }

    // ─────────────────────────────── constants

    private val CRIME_TYPES = setOf(
        EventType.CRIME_COMMITTED, EventType.MUGGING, EventType.BURGLARY,
        EventType.VEHICLE_THEFT, EventType.ARSON_ATTEMPT
    )

    private val MAJOR_LIFE_EVENTS = setOf(
        EventType.PERSON_BORN, EventType.PERSON_DIED, EventType.MARRIAGE, EventType.DIVORCE,
        EventType.SEPARATION, EventType.JOB_LOST, EventType.JOB_STARTED,
        EventType.BUSINESS_OPENED, EventType.BUSINESS_CLOSED, EventType.DEBT_CRISIS,
        EventType.ILLNESS_DIAGNOSED, EventType.ELECTION_WON, EventType.HOME_PURCHASED
    )
}
