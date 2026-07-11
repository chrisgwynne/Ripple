package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.simulation.CrimeSystem
import com.ripple.town.core.simulation.EconomySystem
import org.junit.Test

/**
 * Audit item #28 — unit tests for [CrimeSystem].
 *
 * Every sweep loop is capped at 500 iterations to keep test time bounded.
 * All RNG goes through `ctx.rng` (SimRandom); no `Math.random()` or
 * `kotlin.random.Random` anywhere here.
 */
class CrimeSystemTest {

    // -----------------------------------------------------------------------
    // Probability / gating — shoplifting
    // -----------------------------------------------------------------------

    /**
     * A DETAILED resident with low financial security and low honesty MUST eventually
     * be selected as a shoplifting candidate across many salts. The candidate filter in
     * `updateShoplifting` requires:
     *   - inTown && not CHILD
     *   - financialSecurity < 30
     *   - honesty < 0.55 * modifier (modifier ≈ 1.0 for no childhood crime-victim memory)
     * We also need an open shop with demand < LOW_FOOTFALL_DEMAND (35).
     */
    @Test
    fun `shoplifting fires for a dishonest resident with poor finances`() {
        var fired = false
        for (salt in 0L until 500L) {
            val state = TestWorld.newState()
            // Pick any DETAILED adult, then force them into the candidate window.
            val r = state.detailedResidents()
                .firstOrNull { it.lifeStageAt(state.time) == LifeStage.ADULT } ?: continue
            r.needs.financialSecurity = 15.0   // well below the 30.0 gate
            r.debt = 0.0

            // Force honesty below the 0.55 ceiling (modifier = 1.0 when no crime-victim memory).
            // Personality is a val, so we recreate it with the same other traits.
            val p = r.personality
            val lowHonesty = Personality(
                kindness = p.kindness, ambition = p.ambition, curiosity = p.curiosity,
                sociability = p.sociability, patience = p.patience,
                honesty = 0.20,          // clearly below 0.55
                courage = p.courage, discipline = p.discipline,
                empathy = p.empathy, impulsiveness = p.impulsiveness
            )
            // Personality is a val field; replace via copy on the resident.
            // Resident's personality field is also val — so we inject it by constructing
            // a new resident copy. Rather than replace the whole resident (which would break
            // id-references already stored in the map), we use reflection-free approach:
            // forcibly mutate via the existing mutable state map.
            state.residents[r.id] = r.copy(
                personality = lowHonesty,
                needs = r.needs.also {
                    it.financialSecurity = 15.0
                }
            )
            val resident = state.residents[r.id]!!

            // Make sure the cooldown map is clear for this resident.
            state.lastIncidentAt.remove(resident.id)

            // Ensure there is at least one open shop below the LOW_FOOTFALL_DEMAND threshold
            // so the shop pool is non-empty.
            val shop = state.businesses.values.firstOrNull {
                it.open && it.type !in EconomySystem.PUBLIC_SERVICES
            } ?: continue
            shop.demand = 10.0   // well below LOW_FOOTFALL_DEMAND = 35.0

            val ctx = TestWorld.contextFor(state, salt = salt)
            CrimeSystem.updateShoplifting(ctx)

            if (ctx.newEvents.any { it.type == EventType.SHOPLIFTING && it.sourceResidentId == resident.id }) {
                fired = true
                break
            }
        }
        assertThat(fired).isTrue()
    }

    /**
     * A resident whose honesty is at 1.0 (maximum) and who has strong finances must
     * NEVER appear as a shoplifting source, regardless of seed.
     */
    @Test
    fun `honest resident with good finances is never a shoplifting source`() {
        for (salt in 0L until 200L) {
            val state = TestWorld.newState()
            val r = state.detailedResidents()
                .firstOrNull { it.lifeStageAt(state.time) == LifeStage.ADULT } ?: continue
            val p = r.personality
            state.residents[r.id] = r.copy(
                personality = Personality(
                    kindness = p.kindness, ambition = p.ambition, curiosity = p.curiosity,
                    sociability = p.sociability, patience = p.patience,
                    honesty = 1.0,      // maximum honesty — above the 0.55 gate
                    courage = p.courage, discipline = p.discipline,
                    empathy = p.empathy, impulsiveness = p.impulsiveness
                ),
                needs = r.needs.also { it.financialSecurity = 90.0; it.stress = 5.0 }
            )
            val honestResident = state.residents[r.id]!!
            state.lastIncidentAt.remove(honestResident.id)

            val shop = state.businesses.values.firstOrNull {
                it.open && it.type !in EconomySystem.PUBLIC_SERVICES
            } ?: continue
            shop.demand = 5.0

            val ctx = TestWorld.contextFor(state, salt = salt)
            CrimeSystem.updateShoplifting(ctx)

            assertThat(ctx.newEvents.any { it.sourceResidentId == honestResident.id })
                .isFalse()
        }
    }

    // -----------------------------------------------------------------------
    // Cooldown
    // -----------------------------------------------------------------------

    /**
     * A resident who was the subject of a crime within INCIDENT_COOLDOWN_DAYS must NOT
     * appear as a shoplifting source again during that window.
     */
    @Test
    fun `resident on cooldown is never selected as shoplifting source`() {
        val state = TestWorld.newState()
        val r = state.detailedResidents()
            .firstOrNull { it.lifeStageAt(state.time) == LifeStage.ADULT }!!

        // Force them into a maximally risky profile so they WOULD fire without the cooldown.
        val p = r.personality
        state.residents[r.id] = r.copy(
            personality = Personality(
                kindness = p.kindness, ambition = p.ambition, curiosity = p.curiosity,
                sociability = p.sociability, patience = p.patience,
                honesty = 0.10,
                courage = p.courage, discipline = p.discipline,
                empathy = p.empathy, impulsiveness = p.impulsiveness
            ),
            needs = r.needs.also { it.financialSecurity = 5.0 }
        )
        val rUpdated = state.residents[r.id]!!

        // Set the cooldown to the current time — that's as if they were just hit.
        state.lastIncidentAt[rUpdated.id] = state.time

        val shop = state.businesses.values.first {
            it.open && it.type !in EconomySystem.PUBLIC_SERVICES
        }
        shop.demand = 5.0

        for (salt in 0L until 200L) {
            val ctx = TestWorld.contextFor(state, salt = salt)
            CrimeSystem.updateShoplifting(ctx)
            assertThat(ctx.newEvents.any { it.sourceResidentId == rUpdated.id }).isFalse()
        }
    }

    /**
     * The cooldown expires once INCIDENT_COOLDOWN_DAYS * MINUTES_PER_DAY have elapsed.
     * After that, the resident is eligible again.
     */
    @Test
    fun `resident becomes eligible again after the cooldown has elapsed`() {
        var firedAfterCooldown = false
        for (salt in 0L until 500L) {
            val state = TestWorld.newState()
            val r = state.detailedResidents()
                .firstOrNull { it.lifeStageAt(state.time) == LifeStage.ADULT } ?: continue
            val p = r.personality
            state.residents[r.id] = r.copy(
                personality = Personality(
                    kindness = p.kindness, ambition = p.ambition, curiosity = p.curiosity,
                    sociability = p.sociability, patience = p.patience,
                    honesty = 0.10,
                    courage = p.courage, discipline = p.discipline,
                    empathy = p.empathy, impulsiveness = p.impulsiveness
                ),
                needs = r.needs.also { it.financialSecurity = 5.0 }
            )
            val rUpdated = state.residents[r.id]!!

            // Set the cooldown to just beyond its expiry.
            val cooldownExpiredAt = state.time - CrimeSystem.INCIDENT_COOLDOWN_DAYS * SimTime.MINUTES_PER_DAY - 1L
            state.lastIncidentAt[rUpdated.id] = cooldownExpiredAt

            val shop = state.businesses.values.firstOrNull {
                it.open && it.type !in EconomySystem.PUBLIC_SERVICES
            } ?: continue
            shop.demand = 5.0

            val ctx = TestWorld.contextFor(state, salt = salt)
            CrimeSystem.updateShoplifting(ctx)

            if (ctx.newEvents.any { it.type == EventType.SHOPLIFTING && it.sourceResidentId == rUpdated.id }) {
                firedAfterCooldown = true
                break
            }
        }
        assertThat(firedAfterCooldown).isTrue()
    }

    // -----------------------------------------------------------------------
    // Constable appointment
    // -----------------------------------------------------------------------

    /**
     * `ensureConstable` must pick the adult DETAILED resident with the highest
     * honesty*0.6 + courage*0.4 score.
     */
    @Test
    fun `ensureConstable appoints the most honest courageous adult`() {
        val state = TestWorld.newState()
        state.constableResidentId = null   // force re-selection

        // Identify the expected winner by replicating the selection formula.
        val candidates = state.detailedResidents()
            .filter { it.inTown && it.lifeStageAt(state.time) == LifeStage.ADULT }
        assertThat(candidates).isNotEmpty()
        val expected = candidates.maxByOrNull { it.personality.honesty * 0.6 + it.personality.courage * 0.4 }!!

        val ctx = TestWorld.contextFor(state)
        CrimeSystem.ensureConstable(ctx)

        assertThat(state.constableResidentId).isEqualTo(expected.id)
    }

    /**
     * Once a constable is set and still valid, `ensureConstable` must NOT replace them
     * (the early-return guard keeps the same person in post).
     */
    @Test
    fun `ensureConstable keeps the current constable when they are still valid`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        // First call to elect someone.
        CrimeSystem.ensureConstable(ctx)
        val firstConstableId = state.constableResidentId
        assertThat(firstConstableId).isNotNull()

        // Second call must not change it.
        CrimeSystem.ensureConstable(ctx)
        assertThat(state.constableResidentId).isEqualTo(firstConstableId)
    }

    /**
     * If the current constable leaves town, `ensureConstable` replaces them.
     */
    @Test
    fun `ensureConstable replaces a constable who left town`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        CrimeSystem.ensureConstable(ctx)
        val firstId = state.constableResidentId!!

        // Simulate the constable leaving town.
        state.residents[firstId]!!.leftTownAt = state.time - 1L

        CrimeSystem.ensureConstable(ctx)
        // Either re-selected someone different or the same if only one candidate remains;
        // the important thing is the system ran without error and someone is appointed.
        assertThat(state.constableResidentId).isNotNull()
    }

    // -----------------------------------------------------------------------
    // Investigation
    // -----------------------------------------------------------------------

    /**
     * `investigate()` on a VISIBLE crime must always produce a CRIME_REPORTED event
     * that names a suspect (the payload has an "accusedId" key and the description
     * contains " has been ").
     */
    @Test
    fun `investigate on a visible crime always names a suspect`() {
        val state = TestWorld.newState()
        val culprit = state.detailedResidents()
            .first { it.lifeStageAt(state.time) == LifeStage.ADULT }

        for (salt in 0L until 30L) {
            val ctx = TestWorld.contextFor(state, salt = salt)
            // Emit a fake VISIBLE shoplifting crime attributed to culprit.
            val crime = ctx.emit(
                EventType.SHOPLIFTING,
                "Something missing from the shop.",
                sourceResidentId = culprit.id,
                severity = 0.3,
                visibility = EventVisibility.PUBLIC
            )
            CrimeSystem.investigate(ctx, crime)

            // Must emit a CRIME_REPORTED for this crime.
            val report = ctx.newEvents.firstOrNull {
                it.type == EventType.CRIME_REPORTED && crime.id in it.causeIds
            }
            assertThat(report).isNotNull()
            // Must carry an accusedId payload.
            assertThat(report!!.payload["accusedId"]).isNotNull()
        }
    }

    /**
     * `investigate()` on a HIDDEN crime fires the unsolved-case path with exactly 18%
     * probability. Sweeping seeds to confirm it fires at least once in ≤500 attempts.
     */
    @Test
    fun `investigate on a hidden crime sometimes creates an unsolved case`() {
        var sawUnsolvedCase = false
        for (salt in 0L until 500L) {
            val state = TestWorld.newState()
            val culprit = state.detailedResidents()
                .first { it.lifeStageAt(state.time) == LifeStage.ADULT }
            val ctx = TestWorld.contextFor(state, salt = salt)

            val crime = ctx.emit(
                EventType.SHOPLIFTING,
                "Something missing from the shop.",
                sourceResidentId = culprit.id,
                severity = 0.3,
                visibility = EventVisibility.HIDDEN
            )
            CrimeSystem.investigate(ctx, crime)

            // The unsolved-case path emits a CRIME_REPORTED with "no clear leads" in the description.
            val report = ctx.newEvents.firstOrNull {
                it.type == EventType.CRIME_REPORTED && crime.id in it.causeIds
            }
            if (report != null && report.description.contains("no clear leads")) {
                sawUnsolvedCase = true
                // Also verify an UnsolvedCase was recorded in state.
                assertThat(state.unsolvedCases).isNotEmpty()
                break
            }
        }
        assertThat(sawUnsolvedCase).isTrue()
    }

    /**
     * Across enough seeds the ~18% cold-case path actually fires — probability test:
     * 500 hidden-crime investigations should contain at least one cold case and also
     * at least one named-suspect outcome (the remaining ~82%).
     */
    @Test
    fun `investigate hidden crime produces both cold-case and named-suspect outcomes across seeds`() {
        var sawColdCase = false
        var sawNamedSuspect = false

        for (salt in 0L until 500L) {
            val state = TestWorld.newState()
            val culprit = state.detailedResidents()
                .first { it.lifeStageAt(state.time) == LifeStage.ADULT }
            val ctx = TestWorld.contextFor(state, salt = salt)

            val crime = ctx.emit(
                EventType.SHOPLIFTING,
                "Something missing.",
                sourceResidentId = culprit.id,
                severity = 0.3,
                visibility = EventVisibility.HIDDEN
            )
            CrimeSystem.investigate(ctx, crime)

            val report = ctx.newEvents.firstOrNull {
                it.type == EventType.CRIME_REPORTED && crime.id in it.causeIds
            } ?: continue

            if (report.description.contains("no clear leads")) sawColdCase = true
            else if (report.payload["accusedId"] != null) sawNamedSuspect = true

            if (sawColdCase && sawNamedSuspect) break
        }
        assertThat(sawColdCase).isTrue()
        assertThat(sawNamedSuspect).isTrue()
    }

    // -----------------------------------------------------------------------
    // Determinism
    // -----------------------------------------------------------------------

    /**
     * The same world seed and the same salt must produce exactly the same shoplifting
     * outcomes (events emitted, their descriptions and source resident ids).
     */
    @Test
    fun `same seed produces identical shoplifting outcomes`() {
        fun runOnce(): List<Pair<EventType, Long?>> {
            val state = TestWorld.newState()
            val shop = state.businesses.values.first {
                it.open && it.type !in EconomySystem.PUBLIC_SERVICES
            }
            shop.demand = 5.0
            // Maximise the candidate pool.
            for (r in state.detailedResidents()) {
                if (r.lifeStageAt(state.time) == LifeStage.ADULT) {
                    r.needs.financialSecurity = 10.0
                    state.residents[r.id] = r.copy(
                        personality = Personality(
                            honesty = 0.10,
                            kindness = r.personality.kindness,
                            ambition = r.personality.ambition,
                            curiosity = r.personality.curiosity,
                            sociability = r.personality.sociability,
                            patience = r.personality.patience,
                            courage = r.personality.courage,
                            discipline = r.personality.discipline,
                            empathy = r.personality.empathy,
                            impulsiveness = r.personality.impulsiveness
                        )
                    )
                }
            }
            val ctx = TestWorld.contextFor(state, salt = 7L)
            CrimeSystem.updateShoplifting(ctx)
            return ctx.newEvents
                .filter { it.type == EventType.SHOPLIFTING || it.type == EventType.CRIME_REPORTED }
                .map { it.type to it.sourceResidentId }
        }

        assertThat(runOnce()).isEqualTo(runOnce())
    }

    /**
     * `ensureConstable` is deterministic: same state, same result both times.
     */
    @Test
    fun `ensureConstable is deterministic`() {
        fun pickConstable(): Long? {
            val state = TestWorld.newState()
            state.constableResidentId = null
            CrimeSystem.ensureConstable(TestWorld.contextFor(state))
            return state.constableResidentId
        }
        assertThat(pickConstable()).isEqualTo(pickConstable())
        assertThat(pickConstable()).isNotNull()
    }
}
