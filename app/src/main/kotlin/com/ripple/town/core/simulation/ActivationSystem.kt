package com.ripple.town.core.simulation

import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.Household
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.model.isHome

/**
 * Controls the three-tier activation ladder (BACKGROUND → CONNECTED → DETAILED).
 *
 * Activation is driven by *social proximity to people the player already knows*:
 *   • Is the resident followed, or a direct kin/partner of a followed resident?  (high)
 *   • Do they live in the same household as a DETAILED resident?                  (high)
 *   • Are they employed at the same business as a DETAILED resident?              (medium)
 *   • Do they live in the same district as a DETAILED resident?                   (low)
 *
 * CONNECTED residents are guaranteed a home and household.  DETAILED residents
 * are additionally guaranteed they are in `discoveredResidentIds`.
 *
 * The system never demotes — once a tier is reached it persists.  Caps prevent
 * runaway CPU cost while leaving headroom for organic growth.
 */
object ActivationSystem {

    /** Soft cap on DETAILED residents.  Above this, CONNECTED→DETAILED stops. */
    const val DETAIL_CAP = 120

    /** Soft cap on CONNECTED residents.  Above this, BACKGROUND→CONNECTED stops. */
    const val CONNECTED_CAP = 400

    /** Max BACKGROUND→CONNECTED promotions per tick. */
    private const val PROMOTE_TO_CONNECTED_PER_TICK = 3

    /** Max CONNECTED→DETAILED promotions per tick. */
    private const val PROMOTE_TO_DETAILED_PER_TICK = 2

    /** Minimum relevance score to cross into CONNECTED. */
    private const val CONNECTED_THRESHOLD = 6.0

    /** Minimum relevance score to cross into DETAILED. */
    private const val DETAILED_THRESHOLD = 18.0

    fun updateTick(ctx: TickContext) {
        val state = ctx.state
        val detailedCount = state.residents.values.count { it.detailLevel == DetailLevel.DETAILED }
        val connectedCount = state.residents.values.count { it.detailLevel == DetailLevel.CONNECTED }

        val scorer = buildScorer(state)

        // Promote BACKGROUND → CONNECTED
        if (connectedCount < CONNECTED_CAP) {
            val candidates = state.residents.values
                .filter { it.alive && it.inTown && it.detailLevel == DetailLevel.BACKGROUND }
                .sortedByDescending { r: Resident -> scorer(r) }
            var promoted = 0
            for (r in candidates) {
                if (promoted >= PROMOTE_TO_CONNECTED_PER_TICK) break
                if (connectedCount + promoted >= CONNECTED_CAP) break
                if (scorer(r) < CONNECTED_THRESHOLD) break
                materialiseConnected(ctx, r)
                promoted++
            }
        }

        // Promote CONNECTED → DETAILED
        if (detailedCount < DETAIL_CAP) {
            val candidates = state.residents.values
                .filter { it.alive && it.inTown && it.detailLevel == DetailLevel.CONNECTED }
                .sortedByDescending { r: Resident -> scorer(r) }
            var promoted = 0
            for (r in candidates) {
                if (promoted >= PROMOTE_TO_DETAILED_PER_TICK) break
                if (detailedCount + promoted >= DETAIL_CAP) break
                if (scorer(r) < DETAILED_THRESHOLD) break
                materialiseDetailed(ctx, r)
                promoted++
            }
        }
    }

    /**
     * Build a relevance scorer for the current state snapshot.
     * Called once per tick so the expensive index construction runs only once.
     */
    private fun buildScorer(state: WorldState): (Resident) -> Double {
        val followedId = state.followedResidentId

        val detailedIds: Set<Long> = state.residents.values
            .filter { it.detailLevel == DetailLevel.DETAILED }
            .mapTo(HashSet()) { it.id }

        val householdOfDetailed: Set<Long> = buildSet {
            for (id in detailedIds) {
                val hhId = state.residents[id]?.householdId ?: continue
                val hh = state.households[hhId] ?: continue
                addAll(hh.memberIds)
            }
        }

        val coworkersOfDetailed: Set<Long> = buildSet {
            for (id in detailedIds) {
                val empId = state.residents[id]?.employmentId ?: continue
                val employment = state.employments[empId] ?: continue
                state.employeesOf(employment.businessId).forEach { add(it.residentId) }
            }
        }

        val districtsOfDetailed: Set<Long> = buildSet {
            for (id in detailedIds) {
                val r = state.residents[id] ?: continue
                val buildingId = r.homeBuildingId ?: r.currentBuildingId ?: continue
                val distId = state.building(buildingId)?.districtId ?: continue
                add(distId)
            }
        }

        val followedRelIds: Set<Long> = if (followedId != null)
            state.relationshipsOf(followedId)
                .filter { it.kind.ordinal >= RelationshipKind.ACQUAINTANCE.ordinal }
                .mapTo(HashSet()) { if (it.aId == followedId) it.bId else it.aId }
        else emptySet()

        return { r: Resident ->
            var s = 0.0
            if (r.id == followedId) s += 40.0
            if (r.id in followedRelIds) s += 22.0
            if (r.id in householdOfDetailed) s += 20.0
            if (r.id in coworkersOfDetailed) s += 12.0
            val distId = r.homeBuildingId?.let { state.building(it)?.districtId }
            if (distId != null && distId in districtsOfDetailed) s += 8.0
            s
        }
    }

    /**
     * Bring a BACKGROUND resident to CONNECTED — assign home and household
     * if not yet set, so the resident has an address when the economy starts
     * tracking their living costs.
     */
    private fun materialiseConnected(ctx: TickContext, r: Resident) {
        r.detailLevel = DetailLevel.CONNECTED
        if (r.homeBuildingId == null) assignHome(ctx, r)
    }

    /**
     * Bring a CONNECTED resident to DETAILED — assign home if missing, add to
     * discoveredResidentIds so they appear in the People screen.
     */
    private fun materialiseDetailed(ctx: TickContext, r: Resident) {
        r.detailLevel = DetailLevel.DETAILED
        if (r.homeBuildingId == null) assignHome(ctx, r)
        if (r.id !in ctx.state.discoveredResidentIds) ctx.state.discoveredResidentIds += r.id
    }

    /**
     * Assign a home building and create a household for `r`.  Picks the building
     * with the fewest residents relative to its capacity, tiebreaking by id.
     */
    private fun assignHome(ctx: TickContext, r: Resident) {
        val state = ctx.state
        val home = state.buildings.values
            .filter { it.type.isHome && !it.abandoned }
            .minByOrNull { b ->
                val occupants = state.households.values.count { it.homeBuildingId == b.id }
                occupants * 100L + b.id
            } ?: return

        val hh = Household(
            id = state.nextHouseholdId++,
            name = "${r.surname} household",
            homeBuildingId = home.id,
            monthlyRent = 260.0
        )
        hh.memberIds += r.id
        state.households[hh.id] = hh
        r.householdId = hh.id
        r.homeBuildingId = home.id
        if (r.currentBuildingId == null) r.currentBuildingId = home.id
    }

    /**
     * Ensure the player's followed resident is always DETAILED.
     * Called once per day so a newly followed resident is promoted by the next morning.
     */
    fun updateDaily(ctx: TickContext) {
        val followedId = ctx.state.followedResidentId ?: return
        val r = ctx.state.residents[followedId] ?: return
        if (r.detailLevel == DetailLevel.DETAILED) return
        if (r.detailLevel == DetailLevel.BACKGROUND) materialiseConnected(ctx, r)
        materialiseDetailed(ctx, r)
    }
}
