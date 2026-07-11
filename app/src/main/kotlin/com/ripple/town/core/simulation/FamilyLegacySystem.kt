package com.ripple.town.core.simulation

import com.ripple.town.core.model.FamilyLegacy
import com.ripple.town.core.model.FamilyReputationType
import com.ripple.town.core.model.SimTime

/**
 * Tracks the cumulative story of every family that has ever lived in the town.
 *
 * A [FamilyLegacy] is created the first time a surname appears in the resident list. It
 * accumulates across generations: mayorships, businesses opened and closed, criminal
 * convictions, degrees earned. Reputation type is re-evaluated monthly and reported as
 * a named archetype ("Political Dynasty", "Business Empire", "Notorious", etc.).
 *
 * Addresses audit theme: "Family legacies (families as institutions)" from the Civilisation
 * brief, and extends the existing [FamilyReputationSystem]'s individual reputation tracking
 * to a multi-generational family arc.
 */
object FamilyLegacySystem {

    const val UPDATE_INTERVAL_DAYS = 30L

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        val now = ctx.now
        val living = state.livingResidents()

        // ─── Ensure a record exists for every surname ─────────────────────
        for (resident in living) {
            if (!state.familyLegacies.containsKey(resident.surname)) {
                state.familyLegacies[resident.surname] = FamilyLegacy(
                    id = state.nextFamilyLegacyId++,
                    surname = resident.surname,
                    foundingResidentId = resident.id,
                    foundedAt = ctx.now,
                    startingWealth = resident.wealth
                )
            }
        }

        // ─── Update each legacy ──────────────────────────────────────────��─
        for ((surname, legacy) in state.familyLegacies) {
            val members = living.filter { it.surname == surname }
            legacy.livingMembers = members.size
            legacy.lastUpdatedAt = ctx.now

            if (members.isEmpty()) continue

            // Wealth arc
            val totalWealth = members.sumOf { it.wealth }
            legacy.currentTotalWealth = totalWealth
            if (totalWealth > legacy.peakWealth) legacy.peakWealth = totalWealth

            // Mayorships: government records where the leader has this surname
            legacy.mayorships = state.governmentRecords.count { gov ->
                state.resident(gov.leaderId)?.surname == surname
            }

            // Councillorships: family members currently holding a council seat
            legacy.councillorships = state.councillorIds.count { id ->
                state.resident(id)?.surname == surname
            }

            // Businesses: count businesses owned by family members (alive or not)
            val familyResidentIds = state.residents.values
                .filter { it.surname == surname }
                .map { it.id }
                .toSet()
            legacy.businessesOwned = state.businesses.values
                .count { biz -> biz.ownerId in familyResidentIds }

            // Criminal convictions from corruption incidents
            legacy.criminalConvictions = state.corruptionIncidents.count { inc ->
                inc.perpetratorId in familyResidentIds
            }

            // Generations: walk parent chain depth
            legacy.generations = computeGenerations(ctx, members, familyResidentIds)

            // Reputation score: blend of achievement, criminal record, and size
            val score = (legacy.mayorships * 15.0) + (legacy.councillorships * 5.0) +
                (legacy.businessesOwned * 3.0) + (legacy.generations * 5.0) -
                (legacy.criminalConvictions * 10.0)
            val targetRep = (50.0 + score).coerceIn(0.0, 100.0)
            legacy.reputation = legacy.reputation * 0.9 + targetRep * 0.1

            // Named reputation type
            legacy.reputationType = evaluateReputationType(legacy).name
        }
    }

    private fun computeGenerations(
        ctx: TickContext,
        members: List<com.ripple.town.core.model.Resident>,
        familyIds: Set<Long>
    ): Int {
        var maxDepth = 1
        for (r in members) {
            var depth = 1
            var parentId = r.fatherId ?: r.motherId
            var visited = mutableSetOf(r.id)
            while (parentId != null && parentId !in visited) {
                val parent = ctx.state.resident(parentId) ?: break
                if (parent.surname !in setOf(r.surname)) break
                visited += parentId
                depth++
                parentId = parent.fatherId ?: parent.motherId
            }
            if (depth > maxDepth) maxDepth = depth
        }
        return maxDepth
    }

    private fun evaluateReputationType(legacy: FamilyLegacy): FamilyReputationType {
        if (legacy.criminalConvictions >= 3) return FamilyReputationType.CRIMINAL
        if (legacy.criminalConvictions >= 1 && legacy.mayorships == 0) return FamilyReputationType.NOTORIOUS
        if (legacy.generations >= 4 && legacy.mayorships >= 1) return FamilyReputationType.FOUNDING_FAMILY
        if (legacy.mayorships >= 2) return FamilyReputationType.POLITICAL_DYNASTY
        if (legacy.businessesOwned >= 3 && legacy.mayorships == 0) return FamilyReputationType.BUSINESS_EMPIRE
        if (legacy.generations >= 3 && legacy.reputation > 70.0) return FamilyReputationType.INFLUENTIAL
        if (legacy.peakWealth > 0 && legacy.currentTotalWealth < legacy.peakWealth * 0.1 && legacy.generations >= 2) {
            return FamilyReputationType.FALLEN
        }
        if (legacy.reputation > 70.0) return FamilyReputationType.RESPECTED
        if (legacy.reputation < 30.0) return FamilyReputationType.NOTORIOUS
        return FamilyReputationType.ORDINARY
    }
}
