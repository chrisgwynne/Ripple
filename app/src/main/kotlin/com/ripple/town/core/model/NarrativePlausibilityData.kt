package com.ripple.town.core.model

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────
// Tuning knobs the NarrativePlausibilityEngine adjusts over time.
// Simulation systems read these to add realistic variation without
// altering causality.
// ─────────────────────────────────────────────────────────────────

@Serializable
data class NarrativeTuning(
    /** Extra energy-need pressure added at night for disciplined residents (0..0.4).
     *  Pushed up when the engine detects everyone sleeping at the same hour. */
    var sleepPressureVariance: Double = 0.0,

    /** Multiplier on emotional-recovery step size; pushed above 1.0 when recovery is
     *  detected as suspiciously instant (keeps existing causality intact — just paces it). */
    var recoveryDamper: Double = 1.0,

    /** Extra need-pressure variance on routine activities (0..0.3).
     *  Pushed up when all residents follow identical hour-by-hour schedules. */
    var routineVariance: Double = 0.0
)

// ─────────────────────────────────────────────────────────────────
// Rolling statistics accumulated per tick by NarrativePlausibilityEngine.
// Kept small: rolling windows cap themselves; only lightweight primitives.
// ─────────────────────────────────────────────────────────────────

@Serializable
data class NarrativePlausibilityData(
    /** Last 50 hour-of-day values (0–23) per EventType name, for timing-regularity analysis. */
    val eventTimingsByHour: MutableMap<String, MutableList<Int>> = mutableMapOf(),

    /** Days-open at closure for the last 20 BUSINESS_CLOSED events; for duration-variance check. */
    val businessClosureDurations: MutableList<Int> = mutableListOf(),

    /** Crime event count per residentId (victim tracking). Resets every 360-day year. */
    val crimeVictimCounts: MutableMap<Long, Int> = mutableMapOf(),

    /** (residentId → dayIndex) pairs for major life events in the last 90 days.
     *  Used to detect emotional-avalanche clustering. */
    val majorEventLog: MutableList<MajorEventEntry> = mutableListOf(),

    /** Day index of the last time crimeVictimCounts was reset. */
    var victimCountResetDay: Long = 0L,

    /** Day index the last report was generated. */
    var lastReportDay: Long = 0L,

    /** All emergence records discovered so far. */
    val emergenceRecords: MutableList<EmergenceRecord> = mutableListOf()
)

@Serializable
data class MajorEventEntry(val residentId: Long, val dayIndex: Long)

// ─────────────────────────────────────────────────────────────────
// Emergence — a genuinely surprising life arc the engine detected.
// ─────────────────────────────────────────────────────────────────

enum class EmergenceType(val label: String) {
    RAGS_TO_RICHES("Rags to riches"),
    DYNASTY_COLLAPSE("Dynasty collapse"),
    BUSINESS_EMPIRE("Business empire"),
    DISTRICT_TRANSFORMATION("District transformation"),
    CRIMINAL_REFORM("Criminal reformed"),
    GENERATIONAL_TALENT("Generational talent"),
    UNEXPECTED_LEADER("Unexpected leader")
}

@Serializable
data class EmergenceRecord(
    val type: String,           // EmergenceType.name — avoids enum serialization edge cases
    val residentId: Long,
    val description: String,
    val discoveredAt: Long,     // sim minutes
    val surpriseScore: Double   // 0..1
)

// ─────────────────────────────────────────────────────────────────
// The output: what the engine produces each month.
// ─────────────────────────────────────────────────────────────────

enum class PlausibilityIssueCategory(val label: String) {
    REPETITIVE_TIMING("Repetitive event timing"),
    DETERMINISTIC_OUTCOME("Deterministic outcome durations"),
    SUSPICIOUS_CONCENTRATION("Suspicious event concentration"),
    AVALANCHE_CLUSTERING("Life-event avalanche"),
    INSTANT_RECOVERY("Instant emotional recovery"),
    STATIC_VARIETY("Static social variety"),
    UNIFORM_CAREERS("Uniform career outcomes")
}

@Serializable
data class PlausibilityIssue(
    val category: String,   // PlausibilityIssueCategory.name
    val description: String,
    val severity: Double    // 0..1
)

@Serializable
data class NarrativePlausibilityReport(
    val dayIndex: Long,
    /** 0 = completely robotic and predictable; 100 = feels genuinely alive. */
    val overallScore: Double,
    val issues: List<PlausibilityIssue>,
    val newEmergences: List<EmergenceRecord>,
    val mostSurprisingStory: String?,
    val mostInfluentialEvent: String?,
    /** Human-readable descriptions of tuning changes applied this cycle. */
    val appliedTuning: List<String>
)
