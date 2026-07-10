package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.Weather

/**
 * Per-tick drift and activity effects on needs, plus arrival handling and weather.
 * All rates are per 10-minute tick.
 */
object NeedsSystem {

    fun update(ctx: TickContext) {
        updateWeather(ctx)
        for (r in ctx.state.residentsOrdered()) {
            if (!r.inTown) continue

            // Complete travel
            if (r.activity == Activity.TRAVELLING && ctx.now >= r.travelArrivesAt) {
                r.currentBuildingId = r.travelToBuildingId
                r.travelFromBuildingId = null
                r.travelToBuildingId = null
                val planned = r.plannedActivity
                if (planned != null) {
                    ctx.beginActivity(r, planned, r.plannedActivityMinutes, r.plannedActivityReason)
                } else {
                    ctx.beginActivity(r, Activity.IDLE, 10, "Arrived")
                }
            }

            if (r.detailLevel == DetailLevel.BACKGROUND) {
                updateBackground(ctx, r)
                continue
            }

            val n = r.needs
            // Baseline drift
            n.hunger -= 0.55
            n.energy -= if (r.activity == Activity.WORKING) 0.5 else 0.28
            n.social -= 0.18
            n.comfort -= 0.08
            n.purpose -= 0.05

            // Recent, severe trauma dampens how fast stress recovers and purpose rebuilds —
            // a small bounded multiplier on the *recovery-direction* deltas below, not a new
            // decay mechanic. Without this, a resident who just lost their business/livelihood
            // can be soothed back to "calm" by a single nap or relaxing session, which reads as
            // the simulation not registering the event at all. See traumaRecoveryDamping().
            val recoveryDamping = traumaRecoveryDamping(ctx.now, r)

            // Activity effects
            when (r.activity) {
                Activity.SLEEPING -> { n.energy += 1.6; n.stress -= 0.35 * recoveryDamping; n.comfort += 0.2 }
                Activity.EATING -> { n.hunger += 5.5; n.comfort += 0.4 }
                Activity.WORKING -> { n.purpose += 0.35 * recoveryDamping; n.stress += 0.12; n.financialSecurity += 0.03 }
                Activity.AT_SCHOOL -> {
                    n.purpose += 0.3; n.social += 0.25
                    // Schooling builds general grounding, faster for the more disciplined.
                    val gain = 0.02 + r.personality.discipline * 0.02
                    r.skills[SkillType.TEACHING] = (r.skill(SkillType.TEACHING) + gain).coerceAtMost(100.0)
                }
                Activity.SOCIALISING, Activity.COMMUNITY -> { n.social += 1.4; n.stress -= 0.25 * recoveryDamping }
                Activity.VISITING -> { n.social += 1.1; n.stress -= 0.2 * recoveryDamping }
                Activity.SHOPPING -> { n.comfort += 0.5 }
                Activity.EXERCISING -> { n.health += 0.15; n.energy -= 0.5; n.stress -= 0.3 * recoveryDamping }
                Activity.LEARNING -> { n.purpose += 0.5 * recoveryDamping }
                Activity.RESTING_ILL -> { n.energy += 0.9; n.health += 0.12; n.stress -= 0.15 * recoveryDamping }
                Activity.AT_CLINIC -> { n.stress -= 0.1 * recoveryDamping }
                Activity.RELAXING -> { n.comfort += 0.8; n.stress -= 0.4 * recoveryDamping; n.energy += 0.4 }
                Activity.ARGUING -> { n.stress += 1.2; n.social -= 0.4 }
                Activity.CELEBRATING -> { n.social += 1.6; n.stress -= 0.5 * recoveryDamping; n.purpose += 0.3 }
                Activity.MOURNING -> { n.stress += 0.4; n.comfort -= 0.2 }
                else -> {}
            }

            // Hunger and exhaustion feed back into health and stress
            if (n.hunger < 12) { n.health -= 0.12; n.stress += 0.25 }
            if (n.energy < 10) { n.stress += 0.3; n.health -= 0.05 }
            if (n.stress > 80) n.health -= 0.05

            // Illness drags health
            for (c in r.activeConditions()) {
                n.health -= (c.severity / 100.0) * if (c.type.serious) 0.28 else 0.08
            }

            // Noise near home chips at comfort (scenario: noisy business near houses)
            val home = r.homeBuildingId?.let { ctx.state.building(it) }
            if (home != null && r.currentBuildingId == home.id) {
                val noisy = ctx.state.buildings.values
                    .filter { it.id != home.id && it.noise > 40 }
                    .any { it.centre().manhattan(home.centre()) <= NOISE_RADIUS }
                if (noisy) n.comfort -= 0.25
                // A home falling into disrepair chips at comfort too.
                if (home.condition < 40.0) n.comfort -= 0.15
            }

            // Financial security tracks wealth vs debt slowly
            val target = financialTarget(r.wealth, r.debt)
            n.financialSecurity += (target - n.financialSecurity) * 0.02

            n.clampAll()
        }
    }

    /**
     * Small, bounded (0.4..1.0) multiplier applied to stress/purpose *recovery* deltas when a
     * resident has a recent, severe negative memory (LOSS, BETRAYAL, HUMILIATION, ARGUMENT,
     * FEAR, NEGLECT with importance >= 70, within the last ~14 in-world days). Full recovery
     * speed (1.0) resumes once no such memory is recent enough — this does not touch the memory
     * system itself, does not resurface memories, and does not floor stress/purpose at a fixed
     * value; it only slows the existing recovery-direction deltas above, matching this
     * codebase's "small bounded modifier" convention (see `FamilyReputationSystem
     * .standingModifier`). At minimum damping (0.4) a resident still recovers, just ~2.5x slower
     * — trauma fades, it isn't erased in a handful of ticks nor frozen forever.
     */
    private fun traumaRecoveryDamping(now: Long, r: Resident): Double {
        val windowMinutes = 14 * SimTime.MINUTES_PER_DAY
        val worst = r.memories
            .asSequence()
            .filter { it.importance >= 70.0 }
            .filter {
                it.type == MemoryType.LOSS || it.type == MemoryType.BETRAYAL ||
                    it.type == MemoryType.HUMILIATION || it.type == MemoryType.ARGUMENT ||
                    it.type == MemoryType.FEAR || it.type == MemoryType.NEGLECT
            }
            .filter { now - it.createdAt in 0..windowMinutes }
            .maxOfOrNull { memory ->
                val age = (now - memory.createdAt).coerceIn(0L, windowMinutes)
                val recency = 1.0 - (age.toDouble() / windowMinutes) // 1.0 = just happened, 0.0 = at window edge
                val severity = (memory.importance / 100.0).coerceIn(0.0, 1.0)
                recency * severity
            } ?: 0.0
        // worst in [0,1] -> damping in [1.0, 0.4], i.e. up to 60% slower recovery when fresh + severe.
        return (1.0 - worst * 0.6).coerceIn(0.4, 1.0)
    }

    fun financialTarget(wealth: Double, debt: Double): Double {
        val w = (wealth / 40.0).coerceIn(0.0, 70.0)
        val d = (debt / 30.0).coerceIn(0.0, 60.0)
        return (25.0 + w - d).coerceIn(0.0, 100.0)
    }

    private fun updateBackground(ctx: TickContext, r: com.ripple.town.core.model.Resident) {
        // Lightweight residents drift gently and follow a statistical routine.
        val n = r.needs
        n.hunger = (n.hunger - 0.2).coerceAtLeast(40.0)
        n.energy = (n.energy - 0.1).coerceAtLeast(40.0)
        if (ctx.now % SimTime.MINUTES_PER_DAY == 0L) {
            n.hunger = 75.0; n.energy = 75.0
            n.stress = (n.stress + ctx.rng.nextDouble(-2.0, 2.0)).coerceIn(5.0, 70.0)
        }
    }

    private fun updateWeather(ctx: TickContext) {
        val state = ctx.state
        if (ctx.now < state.weatherEndsAt) return
        val month = SimTime.monthIndex(ctx.now)
        val winter = month == 11 || month == 0 || month == 1
        val summer = month in 5..7
        val roll = ctx.rng.nextDouble()
        state.weather = when {
            winter && roll < 0.12 -> Weather.SNOW
            roll < 0.08 -> Weather.STORM
            roll < 0.28 -> Weather.RAIN
            roll < 0.36 -> Weather.FOG
            roll < if (summer) 0.85 else 0.68 -> Weather.CLEAR
            else -> Weather.CLOUDY
        }
        state.weatherEndsAt = ctx.now + ctx.rng.nextLong(4, 16) * 60L

        if (state.weather == Weather.STORM && ctx.rng.nextBoolean(0.3)) {
            val candidates = state.buildings.values.filter { it.condition > 20 }.sortedBy { it.id }
            val hit = ctx.rng.pickOrNull(candidates) ?: return
            hit.condition = (hit.condition - ctx.rng.nextDouble(6.0, 18.0)).coerceAtLeast(5.0)
            hit.visibleChanges += "Storm damage"
            val damage = ctx.emit(
                com.ripple.town.core.model.EventType.WEATHER_DAMAGE,
                "A storm battered ${hit.name}, leaving visible damage.",
                buildingId = hit.id,
                severity = 0.45
            )
            // Bridge 2/3 — same cross-system consequences as the river-flood mechanic in
            // SeasonalEventSystem: a housed business takes a real operational hit, and anyone
            // caught inside gets a genuine FEAR memory, not just a generic need hit.
            com.ripple.town.core.simulation.PressureBridgeSystem.onBuildingWeatherDamaged(ctx, damage)
            com.ripple.town.core.simulation.PressureBridgeSystem.onSevereWeatherNearResidents(
                ctx, damage, "That storm hit hard while we were still inside — properly frightening."
            )
        }
    }

    const val NOISE_RADIUS = 6
}
