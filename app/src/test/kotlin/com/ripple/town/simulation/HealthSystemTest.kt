package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.HealthCondition
import com.ripple.town.core.model.HealthConditionType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.simulation.HealthSystem
import org.junit.Test

/**
 * Audit item #29 — unit tests for [HealthSystem].
 *
 * Sweep loops are capped at 500 iterations.
 * DAYS_PER_YEAR = 360 (12 months × 30 days, see SimTime).
 * All RNG goes through ctx.rng / SimRandom — no Math.random() here.
 */
class HealthSystemTest {

    // -----------------------------------------------------------------------
    // Onset — maybeStartCondition fires for an at-risk DETAILED resident
    // -----------------------------------------------------------------------

    /**
     * `maybeStartCondition` must fire at least once across ≤500 daily ticks for a
     * DETAILED resident whose age > 75, stress is near-maximum, energy near-zero and
     * health near-zero (maximises the risk accumulation in the formula).
     *
     * Risk per tick at these values (from the source):
     *   0.002 (base) + 0.006 (stress) + 0.004 (energy) + 0.006 (health) + 0.008 (age>75)
     *   = 0.026 per tick, so we expect onset in ~40 ticks on average.
     */
    @Test
    fun `maybeStartCondition fires for a high-risk elderly stressed resident`() {
        var conditionAdded = false
        for (salt in 0L until 500L) {
            val state = TestWorld.newState()
            // Choose the oldest DETAILED resident; if none qualifies, skip this salt.
            val r = state.detailedResidents()
                .filter { it.lifeStageAt(state.time) != LifeStage.CHILD }
                .maxByOrNull { it.ageAt(state.time) } ?: continue

            // Maximise risk without breaking model invariants.
            r.needs.stress = 100.0
            r.needs.energy = 0.0
            r.needs.health = 0.0

            // Make them "old enough" by pushing bornAt back to yield age > 75.
            // Each MINUTES_PER_YEAR of extra bornAt-offset is one extra year of age.
            val currentAge = r.ageAt(state.time)
            val extraYears = (76 - currentAge).coerceAtLeast(0)
            // Resident is a data class with val bornAt — replace in state map.
            state.residents[r.id] = r.copy(
                bornAt = r.bornAt - extraYears.toLong() * SimTime.MINUTES_PER_YEAR
            )
            val elder = state.residents[r.id]!!
            assertThat(elder.ageAt(state.time)).isAtLeast(76)

            // Clear any existing conditions so the size < 2 gate does not block onset.
            elder.conditions.clear()

            val condCountBefore = elder.conditions.size
            val ctx = TestWorld.contextFor(state, salt = salt)
            HealthSystem.updateDaily(ctx)

            if (elder.conditions.size > condCountBefore) {
                conditionAdded = true
                // The newly added condition must be active (no recoveredAt yet).
                assertThat(elder.activeConditions()).isNotEmpty()
                break
            }
        }
        assertThat(conditionAdded).isTrue()
    }

    /**
     * `maybeStartCondition` does NOT fire while a resident already has 2 active
     * conditions (the gate `r.activeConditions().size >= 2`).
     */
    @Test
    fun `maybeStartCondition is blocked when resident already has two active conditions`() {
        val state = TestWorld.newState()
        val r = state.detailedResidents()
            .first { it.lifeStageAt(state.time) != LifeStage.CHILD }
        r.needs.stress = 100.0
        r.needs.energy = 0.0
        r.needs.health = 0.0
        // Two active conditions — the gate blocks a third.
        r.conditions += HealthCondition(
            id = state.nextConditionId++, residentId = r.id,
            type = HealthConditionType.COLD, severity = 50.0, startedAt = state.time
        )
        r.conditions += HealthCondition(
            id = state.nextConditionId++, residentId = r.id,
            type = HealthConditionType.FLU, severity = 50.0, startedAt = state.time
        )
        val countBefore = r.conditions.size

        for (salt in 0L until 100L) {
            val ctx = TestWorld.contextFor(state, salt = salt)
            HealthSystem.updateDaily(ctx)
        }
        // No new condition should have been added (size stays at 2 active + possibly
        // conditions with recoveredAt set, but never a third active one from maybeStart).
        assertThat(r.activeConditions().size).isAtMost(countBefore)
    }

    // -----------------------------------------------------------------------
    // Progression — severity advances on each daily tick
    // -----------------------------------------------------------------------

    /**
     * A non-serious condition's severity must CHANGE (up or down based on stress/rest)
     * on each call to `updateDaily`. We verify that at least one tick results in a
     * delta, confirming `progressConditions` ran.
     */
    @Test
    fun `a non-serious active condition changes severity on a daily tick`() {
        val state = TestWorld.newState()
        val r = state.detailedResidents().first()
        // Add a non-serious condition at mid-range severity so it won't immediately recover.
        r.conditions.clear()
        val startSeverity = 50.0
        r.conditions += HealthCondition(
            id = state.nextConditionId++, residentId = r.id,
            type = HealthConditionType.COLD, severity = startSeverity, startedAt = state.time
        )
        // No resting activity — condition progresses by delta.
        r.activity = Activity.IDLE
        r.needs.stress = 40.0

        val ctx = TestWorld.contextFor(state, salt = 1L)
        HealthSystem.updateDaily(ctx)

        val cond = r.conditions.first { it.type == HealthConditionType.COLD }
        // Either the condition progressed or it recovered (severity ≤ 0 → recoveredAt set).
        val changed = cond.severity != startSeverity || cond.recoveredAt != null
        assertThat(changed).isTrue()
    }

    /**
     * A serious condition also advances its severity on a daily tick (positive or
     * negative depending on stress/rest, but always a non-zero delta from the rng jitter
     * alone). We confirm it changed after one tick.
     */
    @Test
    fun `a serious active condition changes severity on a daily tick`() {
        val state = TestWorld.newState()
        val r = state.detailedResidents().first()
        r.conditions.clear()
        val startSeverity = 50.0
        r.conditions += HealthCondition(
            id = state.nextConditionId++, residentId = r.id,
            type = HealthConditionType.WEAK_HEART,   // serious = true, chronic = true
            severity = startSeverity, startedAt = state.time
        )
        r.activity = Activity.IDLE
        r.needs.stress = 50.0

        val ctx = TestWorld.contextFor(state, salt = 2L)
        HealthSystem.updateDaily(ctx)

        val cond = r.conditions.first { it.type == HealthConditionType.WEAK_HEART }
        assertThat(cond.severity).isNotEqualTo(startSeverity)
    }

    // -----------------------------------------------------------------------
    // Clinic treatment — severity reduced by 2.5 per tick for non-serious
    // -----------------------------------------------------------------------

    /**
     * A DETAILED resident AT_CLINIC with a non-serious condition that is already
     * diagnosed (diagnosedAt != null) must have its severity reduced by 2.5 per
     * `updateUrgent` call. We call `updateUrgent` once and verify the drop.
     *
     * From the source: `c.severity -= if (c.type.serious) 1.2 else 2.5`
     */
    @Test
    fun `AT_CLINIC treatment reduces non-serious severity by 2_5 per urgent tick`() {
        val state = TestWorld.newState()
        val r = state.detailedResidents().first()
        r.conditions.clear()
        val startSeverity = 40.0
        val cond = HealthCondition(
            id = state.nextConditionId++, residentId = r.id,
            type = HealthConditionType.FLU,   // serious = false
            severity = startSeverity, startedAt = state.time,
            diagnosedAt = state.time,          // already diagnosed — avoids diagnose() side-effects
            hidden = false
        )
        r.conditions += cond
        r.activity = Activity.AT_CLINIC
        r.detailLevel = DetailLevel.DETAILED

        val ctx = TestWorld.contextFor(state, salt = 0L)
        HealthSystem.updateUrgent(ctx)

        // Severity must have dropped by exactly 2.5 (or been cleared to 0 if below 0).
        val expectedSeverity = (startSeverity - 2.5).coerceAtLeast(0.0)
        if (cond.recoveredAt == null) {
            assertThat(cond.severity).isWithin(0.001).of(expectedSeverity)
        } else {
            // recovered: severity was 0.0 or the condition was removed — both are correct
            assertThat(cond.severity).isEqualTo(0.0)
        }
    }

    /**
     * A DETAILED resident AT_CLINIC with a SERIOUS condition must have severity reduced
     * by 1.2 per urgent tick (not 2.5).
     */
    @Test
    fun `AT_CLINIC treatment reduces serious severity by 1_2 per urgent tick`() {
        val state = TestWorld.newState()
        val r = state.detailedResidents().first()
        r.conditions.clear()
        val startSeverity = 40.0
        val cond = HealthCondition(
            id = state.nextConditionId++, residentId = r.id,
            type = HealthConditionType.WEAK_HEART,  // serious = true
            severity = startSeverity, startedAt = state.time,
            diagnosedAt = state.time, hidden = false
        )
        r.conditions += cond
        r.activity = Activity.AT_CLINIC
        r.detailLevel = DetailLevel.DETAILED

        val ctx = TestWorld.contextFor(state, salt = 0L)
        HealthSystem.updateUrgent(ctx)

        val expectedSeverity = (startSeverity - 1.2).coerceAtLeast(0.0)
        if (cond.recoveredAt == null) {
            assertThat(cond.severity).isWithin(0.001).of(expectedSeverity)
        } else {
            assertThat(cond.severity).isEqualTo(0.0)
        }
    }

    /**
     * Repeated urgent ticks drive a non-serious condition all the way to recovery
     * (severity ≤ 0 → `recover()` sets `recoveredAt`).
     */
    @Test
    fun `AT_CLINIC repeated urgent ticks eventually recover a non-serious condition`() {
        val state = TestWorld.newState()
        val r = state.detailedResidents().first()
        r.conditions.clear()
        r.conditions += HealthCondition(
            id = state.nextConditionId++, residentId = r.id,
            type = HealthConditionType.COLD,  // serious = false, chronic = false
            severity = 10.0, startedAt = state.time,
            diagnosedAt = state.time, hidden = false
        )
        r.activity = Activity.AT_CLINIC
        r.detailLevel = DetailLevel.DETAILED

        // 10 / 2.5 = 4 ticks; run a few more to be safe.
        repeat(10) {
            val ctx = TestWorld.contextFor(state, salt = it.toLong())
            HealthSystem.updateUrgent(ctx)
        }

        val cond = r.conditions.first { it.type == HealthConditionType.COLD }
        assertThat(cond.recoveredAt).isNotNull()
    }

    // -----------------------------------------------------------------------
    // Condition death — checkMortality path
    // -----------------------------------------------------------------------

    /**
     * A resident with a maxed serious condition (severity = 100, which is > 70 and
     * contributes 0.010 + 30*0.0012 = 0.046 risk/tick) AND needs.health = 0.01
     * (< 12, adding 0.004) must eventually die.
     *
     * Total risk ≈ 0.050/tick → expected death within ~20 ticks on average.
     * We sweep 500 daily ticks across salts to confirm mortality fires.
     */
    @Test
    fun `resident with maxed serious illness and failing health eventually dies`() {
        var died = false
        for (salt in 0L until 500L) {
            val state = TestWorld.newState()
            val r = state.detailedResidents()
                .firstOrNull { it.lifeStageAt(state.time) != LifeStage.CHILD } ?: continue

            r.conditions.clear()
            r.conditions += HealthCondition(
                id = state.nextConditionId++, residentId = r.id,
                type = HealthConditionType.LUNG_ILLNESS,  // serious = true
                severity = 100.0,  // maximum — contributes highest risk
                startedAt = state.time,
                diagnosedAt = state.time, hidden = false
            )
            r.needs.health = 0.01   // < 12 threshold → adds 0.004/tick extra mortality

            val ctx = TestWorld.contextFor(state, salt = salt)
            HealthSystem.updateDaily(ctx)

            if (!r.alive) {
                died = true
                // Verify the death event was emitted.
                assertThat(ctx.newEvents.any { it.type == EventType.PERSON_DIED }).isTrue()
                break
            }
        }
        assertThat(died).isTrue()
    }

    /**
     * A young, healthy resident with no conditions and good health scores must NOT die
     * from `checkMortality` (risk = 0 → early return).
     */
    @Test
    fun `healthy young resident never dies from checkMortality`() {
        for (salt in 0L until 100L) {
            val state = TestWorld.newState()
            val r = state.detailedResidents()
                .first { it.lifeStageAt(state.time) == LifeStage.ADULT }
            r.conditions.clear()
            r.needs.health = 90.0  // above the < 12 threshold by a wide margin
            // Age well below 78 — no old-age mortality contribution.
            assertThat(r.ageAt(state.time)).isLessThan(78)

            val ctx = TestWorld.contextFor(state, salt = salt)
            HealthSystem.updateDaily(ctx)

            assertThat(r.alive).isTrue()
        }
    }

    // -----------------------------------------------------------------------
    // Determinism
    // -----------------------------------------------------------------------

    /**
     * Same world seed and same salt must produce the same health events across two
     * independent runs of `updateDaily`.
     */
    @Test
    fun `same seed produces identical daily health outcomes`() {
        fun runOnce(): List<Pair<EventType, Long?>> {
            val state = TestWorld.newState()
            // Maximise illness activity so there is something to compare.
            for (r in state.detailedResidents()) {
                r.needs.stress = 80.0
                r.needs.energy = 20.0
                r.needs.health = 20.0
            }
            val ctx = TestWorld.contextFor(state, salt = 13L)
            HealthSystem.updateDaily(ctx)
            return ctx.newEvents
                .filter {
                    it.type == EventType.ILLNESS_STARTED ||
                        it.type == EventType.ILLNESS_DIAGNOSED ||
                        it.type == EventType.ILLNESS_RECOVERED ||
                        it.type == EventType.PERSON_DIED
                }
                .map { it.type to it.sourceResidentId }
        }

        assertThat(runOnce()).isEqualTo(runOnce())
    }

    /**
     * Same seed produces the same clinic treatment outcomes from `updateUrgent`.
     */
    @Test
    fun `same seed produces identical urgent clinic outcomes`() {
        fun runOnce(): Double {
            val state = TestWorld.newState()
            val r = state.detailedResidents().first()
            r.conditions.clear()
            r.conditions += HealthCondition(
                id = state.nextConditionId++, residentId = r.id,
                type = HealthConditionType.FLU, severity = 30.0,
                startedAt = state.time, diagnosedAt = state.time, hidden = false
            )
            r.activity = Activity.AT_CLINIC
            val ctx = TestWorld.contextFor(state, salt = 5L)
            HealthSystem.updateUrgent(ctx)
            return r.conditions.first { it.type == HealthConditionType.FLU }.severity
        }

        assertThat(runOnce()).isEqualTo(runOnce())
    }
}
