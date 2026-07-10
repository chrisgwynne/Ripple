package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime

/**
 * Lower-stakes, non-police incidents for the severity-graded incident system
 * (added 2026-07-10; see `docs/simulation-rules.md`'s "Incidents" section for the
 * full causal writeup of every type below). Crime-flavoured incidents that
 * genuinely warrant constable investigation (shoplifting, burglary, mugging,
 * vehicle theft, fraud, arson attempt) live in `CrimeSystem` instead — this file
 * is for the town's ordinary friction: vandalism from resentment, a household
 * strain finally boiling over, a resident going missing, a bad day at work,
 * a petition's supporters getting rowdy.
 *
 * Same discipline as every other daily system in this codebase: every check is
 * gated on real resident/building/relationship state (never a flat daily dice
 * roll), bounded by `MAX_...` candidate caps, cooled down per resident via
 * `WorldState.lastIncidentAt` (shared with `CrimeSystem`'s incidents — one
 * resident shouldn't be the subject of back-to-back incidents of *either*
 * flavour), and every roll goes through `ctx.rng`.
 */
object IncidentSystem {

    const val MAX_VANDALISM_CANDIDATES_PER_DAY = 20
    const val MAX_DOMESTIC_DISTURBANCE_CANDIDATES_PER_DAY = 15
    const val MAX_MISSING_PERSON_CANDIDATES_PER_DAY = 10
    const val MAX_WORKPLACE_ACCIDENT_CANDIDATES_PER_DAY = 15

    /** Same cooldown window as `CrimeSystem`'s incidents — one shared pool of "recently notable". */
    const val INCIDENT_COOLDOWN_DAYS = 21L

    /** How many in-game days a missing person stays missing before being found. */
    const val MISSING_MIN_DAYS = 1.0
    const val MISSING_MAX_DAYS = 4.0

    private fun onCooldown(ctx: TickContext, residentId: Long): Boolean {
        val last = ctx.state.lastIncidentAt[residentId] ?: return false
        return ctx.now - last < INCIDENT_COOLDOWN_DAYS * SimTime.MINUTES_PER_DAY
    }

    private fun markCooldown(ctx: TickContext, residentId: Long) {
        ctx.state.lastIncidentAt[residentId] = ctx.now
    }

    /** Daily entry point, called from `SimulationCoordinator`'s `if (newDay)` block. */
    fun updateDaily(ctx: TickContext) {
        updateVandalism(ctx)
        updateDomesticDisturbance(ctx)
        updateMissingPerson(ctx)
        resolveMissingPersons(ctx)
        updateWorkplaceAccident(ctx)
    }

    // ============================================================
    // Level 1 — Vandalism
    // ============================================================

    /**
     * Vandalism: reuses the exact tension/resentment mechanics `InteractionSystem`
     * already tracks rather than inventing a new "aggression" stat — a resentful
     * rival taking it out on the building tied to who they resent (a rival's
     * business, an estranged family member's home), or a stressed teen with
     * nowhere to put their frustration hitting a public building instead.
     * Preconditions: a `RIVAL`-kind relationship with genuine resentment (> 50),
     * or a teen/young adult with high stress and low patience — never a flat roll.
     */
    fun updateVandalism(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_VANDALISM_CANDIDATES_PER_DAY

        // Route 1: rivalry-driven — target the rival's business building, if they own one.
        val rivalries = state.relationships.values
            .filter { it.kind == com.ripple.town.core.model.RelationshipKind.RIVAL && it.resentment > 50.0 }
            .sortedBy { it.aId }
        for (rel in rivalries) {
            if (budget <= 0) break
            val a = state.resident(rel.aId) ?: continue
            val b = state.resident(rel.bId) ?: continue
            if (!a.inTown || !b.inTown) continue
            val vandal = listOf(a, b).firstOrNull {
                !onCooldown(ctx, it.id) && it.personality.impulsiveness > 0.5
            } ?: continue
            val target = if (vandal.id == a.id) b else a
            val targetBiz = state.businesses.values.firstOrNull { it.open && it.ownerId == target.id } ?: continue
            budget--

            val risk = ((rel.resentment / 100.0) * 0.25 + vandal.personality.impulsiveness * 0.1)
                .coerceIn(0.0, VANDALISM_MAX_CHANCE)
            if (!ctx.rng.nextBoolean(risk)) continue
            markCooldown(ctx, vandal.id)
            commitVandalism(ctx, vandal, targetBiz.buildingId, targetBiz.name, listOf(target.id))
        }

        // Route 2: a stressed, impatient teen/young adult taking it out on a public building —
        // nobody in particular to blame, just somewhere to put the frustration.
        val publicBuildings = state.buildings.values
            .filter { !it.abandoned && (it.type == BuildingType.PARK || it.type == BuildingType.TOWN_HALL) }
            .sortedBy { it.id }
        if (publicBuildings.isEmpty()) return
        val restless = state.detailedResidents()
            .filter {
                it.inTown && !onCooldown(ctx, it.id) &&
                    (it.lifeStageAt(ctx.now) == LifeStage.TEEN ||
                        (it.lifeStageAt(ctx.now) == LifeStage.ADULT && it.ageAt(ctx.now) < 25)) &&
                    it.needs.stress > 60.0 && it.personality.patience < 0.4
            }
            .sortedBy { it.id }
        for (r in restless) {
            if (budget <= 0) break
            budget--
            val risk = ((r.needs.stress - 60.0) / 40.0 * 0.15 + (0.4 - r.personality.patience) * 0.2)
                .coerceIn(0.0, VANDALISM_MAX_CHANCE)
            if (!ctx.rng.nextBoolean(risk)) continue
            markCooldown(ctx, r.id)
            val target = publicBuildings[ctx.rng.nextInt(publicBuildings.size)]
            commitVandalism(ctx, r, target.id, target.name, emptyList())
        }
    }

    private fun commitVandalism(ctx: TickContext, vandal: Resident, buildingId: Long, buildingName: String, targets: List<Long>) {
        val building = ctx.state.building(buildingId)
        building?.let {
            it.condition = (it.condition - ctx.rng.nextDouble(6.0, 16.0)).coerceAtLeast(5.0)
            it.visibleChanges += "Fresh scrawl on the wall"
            if (it.visibleChanges.size > 6) it.visibleChanges.removeAt(0)
        }
        val event = ctx.emit(
            EventType.VANDALISM,
            "$buildingName was left with fresh damage overnight.",
            sourceResidentId = vandal.id, buildingId = buildingId,
            targetResidentIds = targets,
            severity = 0.3, visibility = EventVisibility.HIDDEN
        )
        vandal.needs.stress -= 4.0 // the act itself is a release, even if nobody knows it was them
        ConsequenceEngine.onEvent(ctx, event)
        if (ctx.rng.nextBoolean(0.3)) CrimeSystem.investigate(ctx, event)
    }

    // ============================================================
    // Level 2 — Domestic disturbance
    // ============================================================

    /**
     * Domestic disturbance: reuses the exact relationship dimensions
     * `InteractionSystem.argue` already tracks per-pair, applied specifically to
     * partners/spouses sharing a household under real, sustained strain — high
     * resentment, low affection, both under stress — rather than any new
     * mechanic. This is what it looks like when an ordinary `ARGUMENT` (which
     * already exists and fires far more often, see `docs/simulation-rules.md`)
     * escalates into something the neighbours actually notice and someone
     * reports. Gated on both being co-located at home, so it's a real domestic
     * moment, not an abstract relationship stat check.
     */
    fun updateDomesticDisturbance(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_DOMESTIC_DISTURBANCE_CANDIDATES_PER_DAY

        val partnerships = state.relationships.values
            .filter {
                it.kind == com.ripple.town.core.model.RelationshipKind.SPOUSE ||
                    it.kind == com.ripple.town.core.model.RelationshipKind.PARTNER
            }
            .filter { it.resentment > 55.0 && it.affection < 30.0 }
            .sortedBy { it.aId }

        for (rel in partnerships) {
            if (budget <= 0) break
            val a = state.resident(rel.aId) ?: continue
            val b = state.resident(rel.bId) ?: continue
            if (!a.inTown || !b.inTown) continue
            if (onCooldown(ctx, a.id) || onCooldown(ctx, b.id)) continue
            val home = a.homeBuildingId ?: continue
            if (b.homeBuildingId != home) continue
            // Both actually at home right now — a real domestic moment, not a stat check.
            if (a.currentBuildingId != home || b.currentBuildingId != home) continue
            budget--

            val strain = (rel.resentment / 100.0) * 0.3 + ((100.0 - rel.affection) / 100.0) * 0.1
            val bothStressed = ((a.needs.stress + b.needs.stress) / 200.0) * 0.15
            val risk = (strain + bothStressed).coerceIn(0.0, DOMESTIC_DISTURBANCE_MAX_CHANCE)
            if (!ctx.rng.nextBoolean(risk)) continue

            markCooldown(ctx, a.id)
            markCooldown(ctx, b.id)
            a.activity = Activity.ARGUING
            b.activity = Activity.ARGUING
            a.activityEndsAt = ctx.now + 30
            b.activityEndsAt = ctx.now + 30
            rel.resentment = (rel.resentment + 10.0).coerceAtMost(100.0)
            rel.affection = (rel.affection - 6.0).coerceAtLeast(0.0)
            rel.trust = (rel.trust - 5.0).coerceAtLeast(0.0)
            a.needs.stress += 12.0
            b.needs.stress += 12.0
            a.needs.safety -= 6.0
            b.needs.safety -= 6.0
            // The prior ARGUMENT-shaped strain is the real cause when a recent one is on record.
            val priorArgument = ctx.state.recentEventIds.asReversed()
                .mapNotNull { ctx.eventIndex.get(it) }
                .firstOrNull {
                    it.type == EventType.ARGUMENT &&
                        it.involvedResidentIds().containsAll(listOf(a.id, b.id))
                }
            val event = ctx.emit(
                EventType.DOMESTIC_DISTURBANCE,
                "Raised voices were heard from ${a.fullName} and ${b.fullName}'s home — a neighbour mentioned it in passing.",
                sourceResidentId = a.id, targetResidentIds = listOf(b.id), buildingId = home,
                severity = 0.45, visibility = EventVisibility.PRIVATE,
                causeIds = listOfNotNull(priorArgument?.id)
            )
            ctx.addMemory(a, MemoryType.ARGUMENT, "Things got loud with ${b.firstName} again.", 55.0, event.id, listOf(b.id))
            ctx.addMemory(b, MemoryType.ARGUMENT, "Things got loud with ${a.firstName} again.", 55.0, event.id, listOf(a.id))
            rel.clampAll()
            ConsequenceEngine.onEvent(ctx, event)
        }
    }

    // ============================================================
    // Level 2 — Missing person
    // ============================================================

    /**
     * Missing person: gated on a genuine at-risk condition — severe stress
     * (> 75), a recent `LOSS`/`FEAR` memory (grief or a bad fright), or elder
     * confusion (an elder resident with low health, standing in for the sort of
     * disorientation that leads to wandering off). Deliberately not violent or
     * sinister — the town-appropriate read is "went off alone to clear their
     * head" or "wandered further than they meant to", resolved within a few
     * days via [resolveMissingPersons] rather than an ongoing mystery.
     */
    fun updateMissingPerson(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_MISSING_PERSON_CANDIDATES_PER_DAY

        val atRisk = state.detailedResidents()
            .filter {
                it.inTown && !onCooldown(ctx, it.id) &&
                    !state.missingResidentIds.contains(it.id) &&
                    it.lifeStageAt(ctx.now) != LifeStage.CHILD
            }
            .filter { r ->
                val severeStress = r.needs.stress > 75.0
                val recentGriefOrFright = r.memories.any {
                    (it.type == MemoryType.LOSS || it.type == MemoryType.FEAR) &&
                        ctx.now - it.createdAt < 14 * SimTime.MINUTES_PER_DAY
                }
                val elderConfusion = r.lifeStageAt(ctx.now) == LifeStage.ELDER && r.needs.health < 40.0
                severeStress || recentGriefOrFright || elderConfusion
            }
            .sortedBy { it.id }

        for (r in atRisk) {
            if (budget <= 0) break
            budget--
            val stressTerm = ((r.needs.stress - 60.0).coerceAtLeast(0.0) / 40.0) * 0.06
            val griefTerm = if (r.memories.any {
                    (it.type == MemoryType.LOSS || it.type == MemoryType.FEAR) &&
                        ctx.now - it.createdAt < 14 * SimTime.MINUTES_PER_DAY
                }) 0.03 else 0.0
            val elderTerm = if (r.lifeStageAt(ctx.now) == LifeStage.ELDER && r.needs.health < 40.0) 0.03 else 0.0
            val risk = (stressTerm + griefTerm + elderTerm).coerceIn(0.0, MISSING_PERSON_MAX_CHANCE)
            if (!ctx.rng.nextBoolean(risk)) continue

            markCooldown(ctx, r.id)
            state.missingResidentIds += r.id
            val griefEvent = r.memories
                .filter { it.type == MemoryType.LOSS || it.type == MemoryType.FEAR }
                .maxByOrNull { it.createdAt }?.eventId
            val event = ctx.emit(
                EventType.MISSING_PERSON_REPORTED,
                "${r.fullName} hasn't been seen since yesterday — the town has started to worry.",
                sourceResidentId = r.id, severity = 0.5,
                causeIds = listOfNotNull(griefEvent)
            )
            state.missingPersonEventId[r.id] = event.id
            val daysMissing = ctx.rng.nextDouble(MISSING_MIN_DAYS, MISSING_MAX_DAYS)
            state.missingResolveAt[r.id] = ctx.now + (daysMissing * SimTime.MINUTES_PER_DAY).toLong()
            // A partner or close family carries the worry most.
            val worriers = listOfNotNull(r.partnerId?.let { state.resident(it) }) +
                listOfNotNull(r.motherId?.let { state.resident(it) }, r.fatherId?.let { state.resident(it) })
            for (w in worriers) {
                if (!w.inTown) continue
                w.needs.stress += 15.0
                ctx.addMemory(w, MemoryType.FEAR, "${r.firstName} went missing and we didn't know where.", 65.0, event.id, listOf(r.id))
            }
            ConsequenceEngine.onEvent(ctx, event)
        }
    }

    /** Resolves anyone whose missing window has closed — always found, always safe (town-appropriate tone). */
    private fun resolveMissingPersons(ctx: TickContext) {
        val state = ctx.state
        val due = state.missingResidentIds.filter { id ->
            (state.missingResolveAt[id] ?: Long.MAX_VALUE) <= ctx.now
        }
        for (id in due) {
            val r = state.resident(id)
            if (r == null) {
                cleanupMissing(state, id)
                continue
            }
            val causeEventId = state.missingPersonEventId[id]
            val event = ctx.emit(
                EventType.MISSING_PERSON_FOUND,
                "${r.fullName} has turned up safe and well — a relief to everyone who was worried.",
                sourceResidentId = r.id, severity = 0.35,
                causeIds = listOfNotNull(causeEventId)
            )
            r.needs.stress = (r.needs.stress - 10.0).coerceAtLeast(0.0)
            r.needs.safety = (r.needs.safety + 5.0).coerceAtMost(100.0)
            val worriers = listOfNotNull(r.partnerId?.let { state.resident(it) }) +
                listOfNotNull(r.motherId?.let { state.resident(it) }, r.fatherId?.let { state.resident(it) })
            for (w in worriers) {
                if (!w.inTown) continue
                w.needs.stress = (w.needs.stress - 12.0).coerceAtLeast(0.0)
            }
            ConsequenceEngine.onEvent(ctx, event)
            cleanupMissing(state, id)
        }
    }

    private fun cleanupMissing(state: com.ripple.town.core.model.WorldState, id: Long) {
        state.missingResidentIds.remove(id)
        state.missingResolveAt.remove(id)
        state.missingPersonEventId.remove(id)
    }

    // ============================================================
    // Level 2 (should-tier) — Workplace accident
    // ============================================================

    /**
     * Workplace accident: gated on genuinely hazardous work (`FACTORY`/`WORKSHOP`
     * businesses — the only building types with real physical machinery in this
     * town) and the worker's own exhaustion (`needs.energy` low, `needs.stress`
     * high — a tired, distracted worker is a real safety risk) rather than a
     * flat daily roll. Deliberately mundane in outcome — an `INJURY`-flavoured
     * hurt, never life-threatening — reusing `HealthConditionType.INJURY`
     * exactly as `HealthSystem` already models ordinary injuries, so this
     * doesn't invent a second injury system.
     */
    fun updateWorkplaceAccident(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_WORKPLACE_ACCIDENT_CANDIDATES_PER_DAY

        val hazardousBusinesses = state.businesses.values
            .filter { it.open && (it.type == com.ripple.town.core.model.BusinessType.FACTORY || it.type == com.ripple.town.core.model.BusinessType.WORKSHOP) }
            .sortedBy { it.id }
        for (biz in hazardousBusinesses) {
            if (budget <= 0) break
            val workers = state.employeesOf(biz.id).mapNotNull { state.resident(it.residentId) }
                .filter { it.inTown && !onCooldown(ctx, it.id) && it.activity == Activity.WORKING }
                .sortedBy { it.id }
            for (worker in workers) {
                if (budget <= 0) break
                if (worker.needs.energy > 30.0 && worker.needs.stress < 65.0) continue
                budget--
                val exhaustion = ((30.0 - worker.needs.energy).coerceAtLeast(0.0) / 30.0) * 0.1
                val stressTerm = ((worker.needs.stress - 65.0).coerceAtLeast(0.0) / 35.0) * 0.06
                val risk = (exhaustion + stressTerm).coerceIn(0.0, WORKPLACE_ACCIDENT_MAX_CHANCE)
                if (!ctx.rng.nextBoolean(risk)) continue

                markCooldown(ctx, worker.id)
                val condition = com.ripple.town.core.model.HealthCondition(
                    id = state.nextConditionId++, residentId = worker.id,
                    type = com.ripple.town.core.model.HealthConditionType.INJURY,
                    severity = ctx.rng.nextDouble(25.0, 55.0), startedAt = ctx.now
                )
                worker.conditions += condition
                worker.needs.health -= 12.0
                worker.needs.stress += 10.0
                val event = ctx.emit(
                    EventType.WORKPLACE_ACCIDENT,
                    "${worker.fullName} was hurt in an accident at ${biz.name} — nothing life-threatening, but they'll be off their feet a while.",
                    sourceResidentId = worker.id, businessId = biz.id, buildingId = biz.buildingId,
                    severity = 0.5
                )
                ctx.addMemory(worker, MemoryType.FEAR, "That accident at ${biz.name} could have been worse.", 55.0, event.id)
                ConsequenceEngine.onEvent(ctx, event)
            }
        }
    }

    // ============================================================
    // Level 2 (should-tier) — Protest disruption
    // ============================================================

    /**
     * Protest disruption: extends `PetitionSystem` rather than being an
     * unrelated new mechanic — a petition with an unusually high signature
     * count relative to its threshold (strong, organised backing) or one that
     * just *failed* despite real support (a controversial resolution, sympathy
     * ignored) can spark a public disruption from its most invested
     * supporters. Checked once, the day a petition resolves — reads
     * `Petition.status`/`signatureCount`/`signatureThreshold` directly rather
     * than tracking anything new.
     */
    fun updateProtestDisruption(ctx: TickContext, petition: com.ripple.town.core.model.Petition) {
        val state = ctx.state
        val starter = state.resident(petition.starterId) ?: return
        if (!starter.inTown || onCooldown(ctx, starter.id)) return

        val overwhelming = petition.signatureCount.toDouble() / petition.signatureThreshold.coerceAtLeast(1) > PROTEST_SIGNATURE_RATIO
        val controversialFailure = petition.status == com.ripple.town.core.model.PetitionStatus.FAILED &&
            petition.signatureCount >= petition.signatureThreshold / 2
        if (!overwhelming && !controversialFailure) return
        // Needs a genuinely passionate starter — high political interest, low patience —
        // otherwise strong support alone just means the petition succeeded quietly.
        if (starter.politicalInterest < 0.6 || starter.personality.patience > 0.5) return

        val risk = (if (overwhelming) 0.12 else 0.08) +
            (starter.politicalInterest - 0.6) * 0.2
        if (!ctx.rng.nextBoolean(risk.coerceIn(0.0, PROTEST_MAX_CHANCE))) return

        markCooldown(ctx, starter.id)
        val gatheringSpot = petition.targetBuildingId?.let { state.building(it) }
            ?: state.buildings.values.firstOrNull { it.type == BuildingType.TOWN_HALL }
        val supporters = petition.signatureIds.mapNotNull { state.resident(it) }.filter { it.inTown }
        val event = ctx.emit(
            EventType.PROTEST_DISRUPTION,
            "Feelings ran high outside ${gatheringSpot?.name ?: "the town hall"} as supporters of the petition made their voices heard.",
            sourceResidentId = starter.id, buildingId = gatheringSpot?.id,
            targetResidentIds = supporters.map { it.id }.take(6),
            severity = 0.4, visibility = EventVisibility.PUBLIC,
            causeIds = listOf(petition.startEventId)
        )
        starter.needs.purpose = (starter.needs.purpose + 4.0).coerceAtMost(100.0)
        starter.reputation = (starter.reputation - 3.0).coerceAtLeast(0.0)
        for (s in supporters.take(6)) {
            s.needs.stress += 3.0
        }
        ConsequenceEngine.onEvent(ctx, event)
    }

    // ---- Chance ceilings ----
    const val VANDALISM_MAX_CHANCE = 0.10
    const val DOMESTIC_DISTURBANCE_MAX_CHANCE = 0.12
    const val MISSING_PERSON_MAX_CHANCE = 0.04
    const val WORKPLACE_ACCIDENT_MAX_CHANCE = 0.05
    const val PROTEST_SIGNATURE_RATIO = 1.6
    const val PROTEST_MAX_CHANCE = 0.15
}
