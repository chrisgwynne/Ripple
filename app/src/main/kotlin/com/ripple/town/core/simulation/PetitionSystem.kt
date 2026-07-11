package com.ripple.town.core.simulation

import com.ripple.town.core.model.Building
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.Household
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Petition
import com.ripple.town.core.model.PetitionStatus
import com.ripple.town.core.model.PetitionSubject
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime

/**
 * Local politics, short of a council seat: a politically-interested resident personally
 * affected by a problem can start a petition against noise or high rent; other sympathetic
 * townsfolk sign it over the following days; it resolves — with a real, bounded policy
 * effect — once it clears its signature threshold or its deadline passes. Public business,
 * so everything here is `PUBLIC` visibility (unlike affairs/rumours), and start/resolution
 * are causally linked so the cause viewer can show the whole arc.
 */
object PetitionSystem {

    /** A resident needs at least this much political interest to start a petition. */
    const val STARTER_POLITICAL_INTEREST = 0.5

    /** Never more than this many petitions running at once. */
    const val MAX_ACTIVE_PETITIONS = 2

    /** Bounded daily rolls, mirroring every other system's pattern. */
    const val MAX_NEW_PETITIONS_PER_DAY = 1
    const val MAX_SIGNATURES_PER_PETITION_PER_DAY = 6

    /** A home counts as noise-affected under the same rule `NeedsSystem` uses for comfort loss. */
    const val NOISE_THRESHOLD = 40.0
    const val NOISE_RADIUS = NeedsSystem.NOISE_RADIUS
    const val NOISE_COMFORT_TRIGGER = 45.0

    /** Rent burden: monthly rent share vs wealth. */
    const val RENT_BURDEN_WEALTH_RATIO = 1.5

    const val SIGNATURE_THRESHOLD_BASE = 8
    const val SIGNATURE_THRESHOLD_POPULATION_DIVISOR = 12
    const val SIGNATURE_THRESHOLD_MAX = 22
    const val DEADLINE_DAYS = 21.0

    const val DAILY_SIGN_CHANCE = 0.22

    // Group-cohesion bloc amplification.
    const val BLOC_MIN_SIZE = 3
    const val BLOC_PRESSURE_PER_MEMBER = 0.15
    const val MAX_BLOC_BONUS = 1.0

    // Policy effects on success.
    const val NOISE_REDUCTION_ON_SUCCESS = 18.0
    const val RENT_REDUCTION_ON_SUCCESS = 40.0
    const val STARTER_SUCCESS_REPUTATION = 10.0
    const val STARTER_SUCCESS_PURPOSE = 12.0
    const val SIGNEE_SUCCESS_PURPOSE = 3.0

    // Consequences on failure.
    const val STARTER_FAILURE_REPUTATION = -4.0
    const val STARTER_FAILURE_STRESS = 6.0

    fun updateDaily(ctx: TickContext) {
        resolveDue(ctx)
        gatherSignatures(ctx)
        applyBlocAmplification(ctx)
        maybeStartPetition(ctx)
    }

    // ------------------------------------------------------------ starting

    private fun maybeStartPetition(ctx: TickContext) {
        val state = ctx.state
        var activeCount = state.petitions.count { it.status == PetitionStatus.ACTIVE }
        if (activeCount >= MAX_ACTIVE_PETITIONS) return

        var budget = MAX_NEW_PETITIONS_PER_DAY
        val candidates = state.detailedResidents()
            .filter { it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            .filter { it.politicalInterest > STARTER_POLITICAL_INTEREST }
            .sortedBy { it.id }
        for (starter in candidates) {
            if (budget <= 0 || activeCount >= MAX_ACTIVE_PETITIONS) break
            // Already have a petition running? Don't stack.
            if (state.petitions.any { it.status == PetitionStatus.ACTIVE && it.starterId == starter.id }) continue

            val noisyTarget = noiseTargetFor(ctx, starter)
            val rentTarget = if (noisyTarget == null) rentTargetFor(ctx, starter) else null
            when {
                noisyTarget != null -> {
                    if (!ctx.rng.nextBoolean(0.35)) continue
                    startNoisePetition(ctx, starter, noisyTarget)
                    budget--
                    activeCount++
                }
                rentTarget != null -> {
                    if (!ctx.rng.nextBoolean(0.35)) continue
                    startRentPetition(ctx, starter, rentTarget)
                    budget--
                    activeCount++
                }
            }
        }
    }

    /** The noisy building near the starter's home, if their comfort is genuinely suffering. */
    private fun noiseTargetFor(ctx: TickContext, starter: Resident): Building? {
        val state = ctx.state
        if (starter.needs.comfort >= NOISE_COMFORT_TRIGGER) return null
        val home = starter.homeBuildingId?.let { state.building(it) } ?: return null
        return state.buildings.values
            .filter { it.id != home.id && it.noise > NOISE_THRESHOLD }
            .filter { it.centre().manhattan(home.centre()) <= NOISE_RADIUS }
            .sortedByDescending { it.noise }
            .firstOrNull()
    }

    /** The starter's own household, if rent looks like a real burden vs their wealth. */
    private fun rentTargetFor(ctx: TickContext, starter: Resident): Household? {
        val hh = starter.householdId?.let { ctx.state.household(it) } ?: return null
        if (hh.monthlyRent <= 0.0) return null
        return if (hh.monthlyRent * RENT_BURDEN_WEALTH_RATIO > starter.wealth.coerceAtLeast(1.0)) hh else null
    }

    private fun startNoisePetition(ctx: TickContext, starter: Resident, target: Building) {
        val state = ctx.state
        val threshold = signatureThreshold(state.population())
        val petitionId = state.nextPetitionId++
        val event = ctx.emit(
            EventType.PETITION_STARTED,
            "${starter.fullName} has started a petition over the noise from ${target.name}.",
            sourceResidentId = starter.id,
            buildingId = target.id,
            severity = 0.3,
            payload = mapOf("subject" to PetitionSubject.NOISE.name, "petitionId" to petitionId.toString())
        )
        state.petitions += Petition(
            id = petitionId,
            subject = PetitionSubject.NOISE,
            starterId = starter.id,
            targetBuildingId = target.id,
            startedAt = ctx.now,
            deadlineAt = ctx.now + (DEADLINE_DAYS * SimTime.MINUTES_PER_DAY).toLong(),
            signatureThreshold = threshold,
            signatureIds = mutableListOf(starter.id),
            startEventId = event.id
        )
        starter.needs.purpose = (starter.needs.purpose + 4.0).coerceAtMost(100.0)
    }

    private fun startRentPetition(ctx: TickContext, starter: Resident, target: Household) {
        val state = ctx.state
        val threshold = signatureThreshold(state.population())
        val petitionId = state.nextPetitionId++
        val event = ctx.emit(
            EventType.PETITION_STARTED,
            "${starter.fullName} has started a petition for rent relief.",
            sourceResidentId = starter.id,
            severity = 0.3,
            payload = mapOf("subject" to PetitionSubject.RENT.name, "petitionId" to petitionId.toString())
        )
        state.petitions += Petition(
            id = petitionId,
            subject = PetitionSubject.RENT,
            starterId = starter.id,
            targetHouseholdId = target.id,
            startedAt = ctx.now,
            deadlineAt = ctx.now + (DEADLINE_DAYS * SimTime.MINUTES_PER_DAY).toLong(),
            signatureThreshold = threshold,
            signatureIds = mutableListOf(starter.id),
            startEventId = event.id
        )
        starter.needs.purpose = (starter.needs.purpose + 4.0).coerceAtMost(100.0)
    }

    private fun signatureThreshold(population: Int): Int =
        (SIGNATURE_THRESHOLD_BASE + population / SIGNATURE_THRESHOLD_POPULATION_DIVISOR)
            .coerceAtMost(SIGNATURE_THRESHOLD_MAX)

    // --------------------------------------------------------- signatures

    private fun gatherSignatures(ctx: TickContext) {
        val state = ctx.state
        for (petition in state.petitions.filter { it.status == PetitionStatus.ACTIVE }.sortedBy { it.id }) {
            var budget = MAX_SIGNATURES_PER_PETITION_PER_DAY
            val sympathisers = sympathisersFor(ctx, petition)
                .filter { it.id !in petition.signatureIds }
                .sortedBy { it.id }
            for (r in sympathisers) {
                if (budget <= 0) break
                if (!ctx.rng.nextBoolean(DAILY_SIGN_CHANCE)) continue
                petition.signatureIds += r.id
                r.needs.purpose = (r.needs.purpose + 1.0).coerceAtMost(100.0)
                budget--
            }
        }
    }

    /**
     * After each day's signatures are gathered, recompute [Petition.pressure] as raw signature
     * count + a group-cohesion bloc bonus. For every active [CommunityGroup] that has at least
     * [BLOC_MIN_SIZE] members signed, those members move as a bloc and contribute
     * [BLOC_PRESSURE_PER_MEMBER] × cohort size to the pressure. The total bonus across all groups
     * is capped at [MAX_BLOC_BONUS].
     *
     * Records the name of any qualifying group so the resolution event can mention it.
     */
    private fun applyBlocAmplification(ctx: TickContext) {
        val state = ctx.state
        for (petition in state.petitions.filter { it.status == PetitionStatus.ACTIVE }.sortedBy { it.id }) {
            val signatorySet = petition.signatureIds.toHashSet()
            var blocBonus = 0.0
            for (group in state.communityGroups.values.sortedBy { it.id }) {
                if (!group.active) continue
                val cohortSize = group.memberIds.count { it in signatorySet }
                if (cohortSize >= BLOC_MIN_SIZE) {
                    blocBonus += cohortSize * BLOC_PRESSURE_PER_MEMBER
                    if (blocBonus >= MAX_BLOC_BONUS) {
                        blocBonus = MAX_BLOC_BONUS
                        break
                    }
                }
            }
            petition.pressure = petition.signatureCount.toDouble() + blocBonus
        }
    }

    /** Residents with above-average sympathy for the petition's subject. */
    private fun sympathisersFor(ctx: TickContext, petition: Petition): List<Resident> {
        val state = ctx.state
        val pool = state.detailedResidents().filter { it.inTown && it.lifeStageAt(ctx.now) != LifeStage.CHILD }
        return when (petition.subject) {
            PetitionSubject.NOISE -> {
                val target = petition.targetBuildingId?.let { state.building(it) } ?: return emptyList()
                pool.filter { r ->
                    val home = r.homeBuildingId?.let { state.building(it) } ?: return@filter r.politicalInterest > 0.6
                    home.centre().manhattan(target.centre()) <= NOISE_RADIUS || r.politicalInterest > 0.6
                }
            }
            PetitionSubject.RENT -> {
                pool.filter { r ->
                    val hh = r.householdId?.let { state.household(it) }
                    val rentBurdened = hh != null && hh.monthlyRent * RENT_BURDEN_WEALTH_RATIO > r.wealth.coerceAtLeast(1.0)
                    rentBurdened || r.politicalInterest > 0.6
                }
            }
        }
    }

    // --------------------------------------------------------- resolution

    private fun resolveDue(ctx: TickContext) {
        val state = ctx.state
        for (petition in state.petitions.filter { it.status == PetitionStatus.ACTIVE }.sortedBy { it.id }) {
            // Use pressure (signature count + bloc bonus) as the resolution criterion so that
            // community groups signing as a bloc can tip a petition over the threshold.
            val succeeded = petition.pressure >= petition.signatureThreshold
            val expired = ctx.now >= petition.deadlineAt
            if (!succeeded && !expired) continue
            if (succeeded) resolveSuccess(ctx, petition) else resolveFailure(ctx, petition)
            // Level 2 "protest disruption" incident — extends this system rather than being a
            // separate mechanic; see `IncidentSystem.updateProtestDisruption`'s KDoc. Checked
            // once, right after resolution, so it can read the petition's final status/counts.
            IncidentSystem.updateProtestDisruption(ctx, petition)
        }
    }

    private fun resolveSuccess(ctx: TickContext, petition: Petition) {
        val state = ctx.state
        petition.status = PetitionStatus.SUCCEEDED
        val starter = state.resident(petition.starterId)

        // Identify any community groups that signed as a bloc (≥ BLOC_MIN_SIZE members signed).
        val signatorySet = petition.signatureIds.toHashSet()
        val blocGroups = state.communityGroups.values
            .filter { it.active && it.memberIds.count { id -> id in signatorySet } >= BLOC_MIN_SIZE }
            .sortedBy { it.id }
        val blocSuffix = if (blocGroups.isNotEmpty()) {
            " " + blocGroups.joinToString("; ") { "The ${it.name} signed as a bloc." }
        } else ""

        val description = when (petition.subject) {
            PetitionSubject.NOISE -> {
                val target = petition.targetBuildingId?.let { state.building(it) }
                if (target != null) {
                    target.noise = (target.noise - NOISE_REDUCTION_ON_SUCCESS).coerceAtLeast(0.0)
                    target.visibleChanges += "${SimTime.formatDate(ctx.now)} — Noise abatement notice"
                    if (target.visibleChanges.size >= Building.MAX_VISIBLE_CHANGES) target.visibleChanges.removeAt(0)
                }
                "The town council has upheld the petition against ${target?.name ?: "a noisy premises"} — quieter hours are now in force.$blocSuffix"
            }
            PetitionSubject.RENT -> {
                val hh = petition.targetHouseholdId?.let { state.household(it) }
                if (hh != null) hh.monthlyRent = (hh.monthlyRent - RENT_REDUCTION_ON_SUCCESS).coerceAtLeast(0.0)
                "The rent relief petition has succeeded — a modest reduction has been agreed.$blocSuffix"
            }
        }

        val event = ctx.emit(
            EventType.PETITION_RESOLVED,
            description,
            sourceResidentId = petition.starterId,
            targetResidentIds = petition.signatureIds.filter { it != petition.starterId },
            buildingId = petition.targetBuildingId,
            severity = 0.4,
            payload = mapOf(
                "subject" to petition.subject.name,
                "outcome" to "succeeded",
                "petitionId" to petition.id.toString(),
                "signatures" to petition.signatureCount.toString()
            ),
            causeIds = listOf(petition.startEventId)
        )

        if (starter != null) {
            starter.reputation = (starter.reputation + STARTER_SUCCESS_REPUTATION).coerceAtMost(100.0)
            starter.needs.purpose = (starter.needs.purpose + STARTER_SUCCESS_PURPOSE).coerceAtMost(100.0)
            ctx.addMemory(starter, MemoryType.ACHIEVEMENT, "Our petition actually worked.", 65.0, event.id)
        }
        for (id in petition.signatureIds) {
            if (id == petition.starterId) continue
            val r = state.resident(id) ?: continue
            r.needs.purpose = (r.needs.purpose + SIGNEE_SUCCESS_PURPOSE).coerceAtMost(100.0)
        }
        // Anyone directly affected gets a wellbeing lift from the policy taking hold.
        when (petition.subject) {
            PetitionSubject.NOISE -> {
                val target = petition.targetBuildingId?.let { state.building(it) }
                if (target != null) {
                    for (r in state.detailedResidents()) {
                        val home = r.homeBuildingId?.let { state.building(it) } ?: continue
                        if (home.centre().manhattan(target.centre()) <= NOISE_RADIUS) {
                            r.needs.comfort = (r.needs.comfort + 6.0).coerceAtMost(100.0)
                        }
                    }
                }
            }
            PetitionSubject.RENT -> {
                val hh = petition.targetHouseholdId?.let { state.household(it) }
                if (hh != null) {
                    for (id in hh.memberIds) {
                        val r = state.resident(id) ?: continue
                        r.needs.financialSecurity = (r.needs.financialSecurity + 8.0).coerceAtMost(100.0)
                        r.needs.stress = (r.needs.stress - 4.0).coerceAtLeast(0.0)
                    }
                }
            }
        }

        ConsequenceEngine.onEvent(ctx, event)
    }

    private fun resolveFailure(ctx: TickContext, petition: Petition) {
        val state = ctx.state
        petition.status = PetitionStatus.FAILED
        val starter = state.resident(petition.starterId)

        val subjectLabel = when (petition.subject) {
            PetitionSubject.NOISE -> "the noise petition"
            PetitionSubject.RENT -> "the rent relief petition"
        }
        val event = ctx.emit(
            EventType.PETITION_RESOLVED,
            "${starter?.fullName ?: "The organiser"} let $subjectLabel lapse — it never gathered enough support.",
            sourceResidentId = petition.starterId,
            buildingId = petition.targetBuildingId,
            severity = 0.25,
            payload = mapOf(
                "subject" to petition.subject.name,
                "outcome" to "failed",
                "petitionId" to petition.id.toString(),
                "signatures" to petition.signatureCount.toString()
            ),
            causeIds = listOf(petition.startEventId)
        )

        if (starter != null) {
            starter.reputation = (starter.reputation + STARTER_FAILURE_REPUTATION).coerceAtLeast(0.0)
            starter.needs.stress = (starter.needs.stress + STARTER_FAILURE_STRESS).coerceAtMost(100.0)
        }

        ConsequenceEngine.onEvent(ctx, event)
    }
}
