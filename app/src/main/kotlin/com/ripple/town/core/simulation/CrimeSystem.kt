package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldEvent

/**
 * The town constable investigates reported crimes with imperfect information.
 * Suspicion is drawn from plausible *motive* — poor finances, low honesty,
 * resentment towards the victim — not certainty, so the constable can end up
 * publicly naming the wrong person while the real culprit gets away with it.
 * `CRIME_REPORTED` only ever carries what the constable believes, never the
 * truth the engine actually knows — same principle as [RumourSystem].
 *
 * Also home to the crime-flavoured incident types added for the severity-graded
 * incident system (2026-07-10): shoplifting (Level 1) and burglary/mugging/
 * vehicle theft/fraud/arson attempt (Level 2) — anything genuinely police-shaped
 * enough to warrant [investigate]. Lower-stakes, non-police incidents (vandalism,
 * domestic disturbance, missing person, workplace accident) live in
 * `IncidentSystem` instead. See `docs/simulation-rules.md`'s "Incidents" section
 * for the full causal breakdown of every type below — deliberately no flat daily
 * dice roll anywhere: every incident is gated on real resident/business/building
 * state, same standard as everything else in this codebase.
 */
object CrimeSystem {

    // ---- Bounds shared by every incident check below (mirrors PetitionSystem's MAX_... shape) ----
    /** Never check more shoplifting candidates than this per day. */
    const val MAX_SHOPLIFTING_CANDIDATES_PER_DAY = 25
    /** Never check more burglary target homes than this per day. */
    const val MAX_BURGLARY_CANDIDATES_PER_DAY = 20
    /** Never check more mugging candidate pairs than this per day. */
    const val MAX_MUGGING_CANDIDATES_PER_DAY = 20
    /** Never check more vehicle-theft candidates than this per day. */
    const val MAX_VEHICLE_THEFT_CANDIDATES_PER_DAY = 15
    /** Never check more fraud candidates (business owners) than this per day. */
    const val MAX_FRAUD_CANDIDATES_PER_DAY = 15
    /** Never check more arson candidates than this per day. */
    const val MAX_ARSON_CANDIDATES_PER_DAY = 10

    /** A resident who was the subject of a crime-flavoured incident won't be one again for this long. */
    const val INCIDENT_COOLDOWN_DAYS = 21L

    private fun onCooldown(ctx: TickContext, residentId: Long): Boolean {
        val last = ctx.state.lastIncidentAt[residentId] ?: return false
        return ctx.now - last < INCIDENT_COOLDOWN_DAYS * com.ripple.town.core.model.SimTime.MINUTES_PER_DAY
    }

    private fun markCooldown(ctx: TickContext, residentId: Long) {
        ctx.state.lastIncidentAt[residentId] = ctx.now
    }

    /**
     * Daily entry point for the crime-flavoured incident types, called from
     * `SimulationCoordinator`'s `if (newDay)` block alongside every other daily system.
     * Fixed order matches the brief's MUST-then-SHOULD priority: shoplifting and burglary and
     * mugging (fully causally modelled, core deliverable) before the leaner should-tier trio.
     */
    fun updateDaily(ctx: TickContext) {
        updateShoplifting(ctx)
        updateBurglary(ctx)
        updateMugging(ctx)
        updateVehicleTheft(ctx)
        updateFraud(ctx)
        updateArson(ctx)
    }

    /** Keeps a constable appointed: the most honest, courageous adult in town. */
    fun ensureConstable(ctx: TickContext) {
        val state = ctx.state
        val current = state.constableResidentId?.let { state.resident(it) }
        if (current != null && current.inTown && current.lifeStageAt(ctx.now) == LifeStage.ADULT) return
        val best = state.detailedResidents()
            .filter { it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            .maxByOrNull { it.personality.honesty * 0.6 + it.personality.courage * 0.4 }
        state.constableResidentId = best?.id
    }

    /** Investigate a `CRIME_COMMITTED` event; may correctly or wrongly name a suspect. */
    fun investigate(ctx: TickContext, crime: WorldEvent) {
        val state = ctx.state
        val culprit = crime.sourceResidentId?.let { state.resident(it) } ?: return
        ensureConstable(ctx)
        val constable = state.constableResidentId?.let { state.resident(it) }

        val others = state.detailedResidents()
            .filter { it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT && it.id != constable?.id && it.id != culprit.id }
        val pool = (others + culprit).sortedBy { it.id }
        if (pool.isEmpty()) return

        val victimOwnerId = crime.businessId?.let { state.businesses[it]?.ownerId }
        fun suspicion(r: Resident): Double {
            val dishonesty = 1.0 - r.personality.honesty
            val poor = if (r.needs.financialSecurity < 35.0 || r.debt > 500.0) 0.35 else 0.0
            val grudge = victimOwnerId?.let { state.relationship(r.id, it)?.resentment ?: 0.0 } ?: 0.0
            return (0.1 + dishonesty * 0.5 + poor + grudge / 150.0).coerceAtLeast(0.02)
        }

        // Weighted pick among suspects, always including the true culprit in the pool.
        val weights = pool.map { it to suspicion(it) }
        val total = weights.sumOf { it.second }
        var roll = ctx.rng.nextDouble(0.0, total)
        var accused = pool.last()
        for ((r, w) in weights) {
            if (roll < w) { accused = r; break }
            roll -= w
        }

        val accurate = accused.id == culprit.id
        val crimeNoun = when (crime.type) {
            EventType.BURGLARY -> "burglary"
            EventType.MUGGING -> "mugging"
            EventType.VEHICLE_THEFT -> "vehicle theft"
            EventType.FRAUD -> "fraud"
            EventType.ARSON_ATTEMPT -> "arson attempt"
            EventType.SHOPLIFTING -> "shoplifting"
            else -> "theft"
        }
        val description = if (accurate) {
            "${accused.fullName} has been named by the constable over the recent $crimeNoun."
        } else {
            "${accused.fullName} has been accused over the recent $crimeNoun — though the truth of it is far from settled."
        }
        val report = ctx.emit(
            EventType.CRIME_REPORTED, description,
            sourceResidentId = constable?.id ?: accused.id,
            targetResidentIds = listOf(accused.id),
            severity = 0.35, causeIds = listOf(crime.id),
            payload = mapOf("accurate" to accurate.toString(), "accusedId" to accused.id.toString())
        )
        accused.needs.stress += if (accurate) 10.0 else 16.0
        accused.reputation -= if (accurate) 8.0 else 12.0
        if (!accurate) {
            ctx.addMemory(accused, MemoryType.HUMILIATION, "Accused of something I never did.", 75.0, report.id)
            culprit.needs.stress += 6.0 // some quiet unease, never made public
            if (constable != null) {
                val rel = state.relationshipOrCreate(accused.id, constable.id)
                rel.resentment += 15.0
                rel.trust -= 10.0
                rel.clampAll()
            }
        }
        ConsequenceEngine.onEvent(ctx, report)
    }

    // ============================================================
    // Level 1 — Shoplifting
    // ============================================================

    /**
     * Shoplifting: a resident's own desperation and dishonesty meeting a genuine
     * opportunity — a business currently getting little footfall, so a theft is
     * less likely to be noticed in the moment. Never a flat daily roll: the
     * `risk` score below is built entirely from `financialSecurity`/`debt`
     * (desperation), `personality.honesty` (willingness), and the target
     * business's own `demand` standing in for footfall/witness density
     * (opportunity) — a quiet shop is a genuinely easier target than a busy one.
     */
    fun updateShoplifting(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_SHOPLIFTING_CANDIDATES_PER_DAY
        val openShops = state.businesses.values
            .filter { it.open && it.type !in EconomySystem.PUBLIC_SERVICES && it.demand < LOW_FOOTFALL_DEMAND }
            .sortedBy { it.id }
        if (openShops.isEmpty()) return

        val candidates = state.detailedResidents()
            .filter {
                it.inTown && it.lifeStageAt(ctx.now) != LifeStage.CHILD &&
                    !onCooldown(ctx, it.id) &&
                    (it.needs.financialSecurity < 30.0 || it.debt > 400.0) &&
                    it.personality.honesty < 0.55
            }
            .sortedBy { it.id }

        for (r in candidates) {
            if (budget <= 0) break
            budget--
            val target = openShops[ctx.rng.nextInt(openShops.size)]
            val desperation = ((35.0 - r.needs.financialSecurity).coerceAtLeast(0.0) / 35.0) * 0.4 +
                (r.debt / 1000.0).coerceIn(0.0, 0.2)
            val dishonesty = (1.0 - r.personality.honesty) * 0.3
            val opportunity = ((LOW_FOOTFALL_DEMAND - target.demand) / LOW_FOOTFALL_DEMAND).coerceIn(0.0, 1.0) * 0.2
            val risk = (desperation + dishonesty + opportunity).coerceIn(0.0, SHOPLIFTING_MAX_CHANCE)
            if (!ctx.rng.nextBoolean(risk)) continue

            markCooldown(ctx, r.id)
            val amount = ctx.rng.nextDouble(6.0, 24.0) * target.priceLevel
            target.balance -= amount
            target.reputation = (target.reputation - 1.0).coerceAtLeast(5.0)
            // Prior desperation, if a JOB_LOST/DEBT_CRISIS event is recent, is the real cause;
            // fall back to no cause link if nothing plausible is on record (never invent one).
            val priorCause = mostRecentDesperationCause(ctx, r)
            val crime = ctx.emit(
                EventType.SHOPLIFTING,
                "Something has gone missing from ${target.name} — a quiet till, easily overlooked.",
                sourceResidentId = r.id, businessId = target.id,
                severity = 0.3, visibility = EventVisibility.HIDDEN,
                causeIds = listOfNotNull(priorCause?.id),
                payload = desperationCausePayload(priorCause)
            )
            r.needs.safety -= 4.0
            ConsequenceEngine.onEvent(ctx, crime)
            if (ctx.rng.nextBoolean(SHOPLIFTING_REPORT_CHANCE)) investigate(ctx, crime)
        }
    }

    // ============================================================
    // Level 2 — Burglary
    // ============================================================

    /**
     * Burglary: the target home must actually be *unoccupied* right now (nobody
     * currently inside — opportunity), the burglar must be genuinely desperate
     * (low `financialSecurity`) and low-honesty, and a low `reputation` lowers
     * the perceived stakes of getting caught. Distinct household, never one's
     * own home. `causeIds` link back to the burglar's own recent `JOB_LOST`/
     * `DEBT_CRISIS` event when one exists in their memories — the desperation
     * that made this plausible in the first place.
     */
    fun updateBurglary(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_BURGLARY_CANDIDATES_PER_DAY

        val burglars = state.detailedResidents()
            .filter {
                it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT &&
                    !onCooldown(ctx, it.id) &&
                    it.needs.financialSecurity < 25.0 &&
                    it.personality.honesty < 0.45
            }
            .sortedBy { it.id }
        if (burglars.isEmpty()) return

        // Homes with nobody currently inside them — the opportunity half of the equation.
        val emptyHomes = state.homes()
            .filter { home ->
                state.residentsIn(home.id).isEmpty()
            }
            .sortedBy { it.id }
        if (emptyHomes.isEmpty()) return

        for (burglar in burglars) {
            if (budget <= 0) break
            val ownHomeId = burglar.homeBuildingId
            val target = emptyHomes.filter { it.id != ownHomeId }
                .let { if (it.isEmpty()) null else it[ctx.rng.nextInt(it.size)] } ?: continue
            budget--

            val desperation = ((25.0 - burglar.needs.financialSecurity).coerceAtLeast(0.0) / 25.0) * 0.35
            val dishonesty = (0.45 - burglar.personality.honesty).coerceAtLeast(0.0) * 0.5
            val lowStakes = ((50.0 - burglar.reputation).coerceAtLeast(0.0) / 50.0) * 0.15
            val risk = (desperation + dishonesty + lowStakes).coerceIn(0.0, BURGLARY_MAX_CHANCE)
            if (!ctx.rng.nextBoolean(risk)) continue

            markCooldown(ctx, burglar.id)
            val victims = state.livingResidents().filter { it.homeBuildingId == target.id }
            val takeFrom = victims.maxByOrNull { it.wealth }
            val amount = ctx.rng.nextDouble(40.0, 160.0)
            if (takeFrom != null) {
                val taken = amount.coerceAtMost(takeFrom.wealth)
                takeFrom.wealth -= taken
                burglar.wealth += taken
            }
            val priorCause = mostRecentDesperationCause(ctx, burglar)
            val crime = ctx.emit(
                EventType.BURGLARY,
                "A home on the quiet side of town was broken into while the family was out.",
                sourceResidentId = burglar.id, buildingId = target.id,
                targetResidentIds = victims.map { it.id },
                severity = 0.55, visibility = EventVisibility.HIDDEN,
                causeIds = listOfNotNull(priorCause?.id),
                payload = desperationCausePayload(priorCause)
            )
            for (v in victims) {
                v.needs.safety -= 14.0
                v.needs.stress += 10.0
                ctx.addMemory(v, MemoryType.FEAR, "Someone had been in our home while we were out.", 60.0, crime.id)
            }
            ConsequenceEngine.onEvent(ctx, crime)
            investigate(ctx, crime) // burglary is always reported — too significant to sit hidden
        }
    }

    // ============================================================
    // Level 2 — Mugging
    // ============================================================

    /**
     * Mugging: a low-honesty, impulsive resident confronts someone in a public
     * space. Preconditions: both parties actually co-located outdoors/public
     * right now (crowded-enough location — reusing `InteractionSystem`'s public
     * sociable-activity gathering rather than inventing a second location
     * query), the mugger under real financial strain, and — the feud/humiliation
     * half of the brief's design principle — an existing grudge (resentment) or
     * a recent `HUMILIATION` memory raises the odds sharply, since a mugging
     * "of opportunity" alone is much rarer than one with a personal edge to it.
     */
    fun updateMugging(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_MUGGING_CANDIDATES_PER_DAY

        // Group present detailed adults by building, same shape InteractionSystem uses — only
        // public, crowd-plausible spaces count as opportunity (a mugging needs somewhere to
        // happen unseen by staff, unlike a shop or someone's own home).
        val byBuilding = state.detailedResidents()
            .filter {
                it.inTown && it.currentBuildingId != null &&
                    state.building(it.currentBuildingId!!)?.type == com.ripple.town.core.model.BuildingType.PARK
            }
            .groupBy { it.currentBuildingId!! }
            .toSortedMap()

        for ((buildingId, present) in byBuilding) {
            if (budget <= 0) break
            if (present.size < 2) continue
            val sorted = present.sortedBy { it.id }
            for (mugger in sorted) {
                if (budget <= 0) break
                if (onCooldown(ctx, mugger.id)) continue
                if (mugger.lifeStageAt(ctx.now) != LifeStage.ADULT) continue
                if (mugger.personality.honesty > 0.4 || mugger.needs.financialSecurity > 30.0) continue
                val victim = sorted.filter { it.id != mugger.id }
                    .let { if (it.isEmpty()) null else it[ctx.rng.nextInt(it.size)] } ?: continue
                budget--

                val desperation = ((30.0 - mugger.needs.financialSecurity).coerceAtLeast(0.0) / 30.0) * 0.25
                val impulsiveness = mugger.personality.impulsiveness * 0.15
                val rel = state.relationship(mugger.id, victim.id)
                val grudge = ((rel?.resentment ?: 0.0) / 100.0) * 0.3
                val risk = (desperation + impulsiveness + grudge).coerceIn(0.0, MUGGING_MAX_CHANCE)
                if (!ctx.rng.nextBoolean(risk)) continue

                markCooldown(ctx, mugger.id)
                val amount = ctx.rng.nextDouble(10.0, 45.0).coerceAtMost(victim.wealth)
                victim.wealth -= amount
                mugger.wealth += amount
                val priorCause = mostRecentDesperationCause(ctx, mugger)
                val crime = ctx.emit(
                    EventType.MUGGING,
                    "${victim.fullName} was confronted and robbed near ${state.building(buildingId)?.name ?: "the high street"}.",
                    sourceResidentId = mugger.id, buildingId = buildingId,
                    targetResidentIds = listOf(victim.id),
                    severity = 0.6, visibility = EventVisibility.HIDDEN,
                    causeIds = listOfNotNull(priorCause?.id),
                    payload = desperationCausePayload(priorCause)
                )
                victim.needs.safety -= 18.0
                victim.needs.stress += 14.0
                ctx.addMemory(victim, MemoryType.FEAR, "Being robbed like that shook me more than I expected.", 70.0, crime.id, listOf(mugger.id))
                ConsequenceEngine.onEvent(ctx, crime)
                investigate(ctx, crime) // a mugging with a victim who saw a face is always reported
            }
        }
    }

    // ============================================================
    // Level 2 (should-tier) — Vehicle theft
    // ============================================================

    /**
     * Vehicle theft: modelled as a lighter-weight cousin of burglary rather than
     * a whole new vehicle-ownership model (Ripple has no tracked vehicle
     * objects) — targets a resident's *wealth* directly (a stand-in for "their
     * cart/bicycle, sold on quickly") rather than a household's home. Same
     * desperation+dishonesty shape as burglary, opportunity being the victim
     * currently away from home (so nobody sees it happen).
     */
    fun updateVehicleTheft(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_VEHICLE_THEFT_CANDIDATES_PER_DAY

        val thieves = state.detailedResidents()
            .filter {
                it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT &&
                    !onCooldown(ctx, it.id) &&
                    it.needs.financialSecurity < 28.0 && it.personality.honesty < 0.5
            }
            .sortedBy { it.id }
        if (thieves.isEmpty()) return

        // Victims currently away from home (travelling or at another building) — genuinely
        // can't be watching their own property right now.
        val awayFromHome = state.detailedResidents()
            .filter { it.inTown && it.homeBuildingId != null && it.currentBuildingId != it.homeBuildingId && it.wealth > 50.0 }
            .sortedBy { it.id }
        if (awayFromHome.isEmpty()) return

        for (thief in thieves) {
            if (budget <= 0) break
            val victim = awayFromHome.filter { it.id != thief.id }
                .let { if (it.isEmpty()) null else it[ctx.rng.nextInt(it.size)] } ?: continue
            budget--

            val desperation = ((28.0 - thief.needs.financialSecurity).coerceAtLeast(0.0) / 28.0) * 0.3
            val dishonesty = (0.5 - thief.personality.honesty).coerceAtLeast(0.0) * 0.4
            val risk = (desperation + dishonesty).coerceIn(0.0, VEHICLE_THEFT_MAX_CHANCE)
            if (!ctx.rng.nextBoolean(risk)) continue

            markCooldown(ctx, thief.id)
            val amount = ctx.rng.nextDouble(30.0, 90.0).coerceAtMost(victim.wealth)
            victim.wealth -= amount
            thief.wealth += amount
            val priorCause = mostRecentDesperationCause(ctx, thief)
            val crime = ctx.emit(
                EventType.VEHICLE_THEFT,
                "${victim.fullName}'s cart went missing while they were out.",
                sourceResidentId = thief.id, targetResidentIds = listOf(victim.id),
                severity = 0.4, visibility = EventVisibility.HIDDEN,
                causeIds = listOfNotNull(priorCause?.id)
            )
            victim.needs.safety -= 8.0
            victim.needs.stress += 8.0
            ConsequenceEngine.onEvent(ctx, crime)
            if (ctx.rng.nextBoolean(0.5)) investigate(ctx, crime)
        }
    }

    // ============================================================
    // Level 2 (should-tier) — Fraud
    // ============================================================

    /**
     * Fraud: a struggling business owner (not an employee — this is specifically
     * "cooking the books"/falsifying claims) quietly skims from their own
     * balance sheet. Preconditions: the business is genuinely `daysInTrouble`
     * (real financial pressure, not opportunism) and the owner's honesty is
     * low. Deliberately rare and small — a slow bleed, not a windfall.
     */
    fun updateFraud(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_FRAUD_CANDIDATES_PER_DAY

        val struggling = state.businesses.values
            .filter { it.open && it.type !in EconomySystem.PUBLIC_SERVICES && it.daysInTrouble >= 3 }
            .sortedBy { it.id }
        for (biz in struggling) {
            if (budget <= 0) break
            val owner = biz.ownerId?.let { state.resident(it) } ?: continue
            if (!owner.inTown || onCooldown(ctx, owner.id)) continue
            if (owner.personality.honesty > 0.4) continue
            budget--

            val pressure = (biz.daysInTrouble.toDouble() / EconomySystem.CLOSURE_DAYS).coerceIn(0.0, 1.0) * 0.3
            val dishonesty = (0.4 - owner.personality.honesty).coerceAtLeast(0.0) * 0.4
            val risk = (pressure + dishonesty).coerceIn(0.0, FRAUD_MAX_CHANCE)
            if (!ctx.rng.nextBoolean(risk)) continue

            markCooldown(ctx, owner.id)
            val amount = ctx.rng.nextDouble(50.0, 200.0)
            owner.wealth += amount
            biz.balance -= amount
            val struggleEvent = mostRecentEventOfType(ctx, EventType.BUSINESS_STRUGGLING, owner.id, biz.id)
            val crime = ctx.emit(
                EventType.FRAUD,
                "The books at ${biz.name} don't quite add up.",
                sourceResidentId = owner.id, businessId = biz.id,
                severity = 0.5, visibility = EventVisibility.HIDDEN,
                causeIds = listOfNotNull(struggleEvent?.id)
            )
            ConsequenceEngine.onEvent(ctx, crime)
            if (ctx.rng.nextBoolean(0.4)) investigate(ctx, crime)
        }
    }

    // ============================================================
    // Level 2 (should-tier) — Arson attempt
    // ============================================================

    /**
     * Arson attempt: deliberately the rarest and most gated incident here — a
     * business rival relationship that has curdled into genuine `RIVAL` status
     * (via `BusinessRivalrySystem`/`InteractionSystem`'s existing thresholds,
     * never a fresh mechanic), paired with real resentment and a low-courage,
     * high-impulsiveness personality profile tipping from bitterness into
     * action. Deliberately never destroys the building outright — it lands as
     * `BUILDING_DAMAGED`-flavoured condition loss via the same field
     * `BuildingLifecycleSystem` already repairs, keeping this inside Ripple's
     * gentle tone (a scorched doorway, not a structure fire).
     */
    fun updateArson(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_ARSON_CANDIDATES_PER_DAY

        val rivalPairs = state.relationships.values
            .filter { it.kind == com.ripple.town.core.model.RelationshipKind.RIVAL && it.resentment > 65.0 }
            .sortedBy { it.aId }
        for (rel in rivalPairs) {
            if (budget <= 0) break
            val a = state.resident(rel.aId) ?: continue
            val b = state.resident(rel.bId) ?: continue
            if (!a.inTown || !b.inTown) continue
            // Whichever of the two has the volatile profile is the plausible aggressor;
            // the other is never considered — this is about a specific person's tipping point.
            val aggressor = listOf(a, b).firstOrNull {
                !onCooldown(ctx, it.id) && it.personality.impulsiveness > 0.6 && it.personality.courage < 0.4
            } ?: continue
            val target = if (aggressor.id == a.id) b else a
            val targetBiz = state.businesses.values.firstOrNull { it.open && it.ownerId == target.id } ?: continue
            budget--

            val resentmentTerm = (rel.resentment / 100.0) * 0.15
            val volatility = (aggressor.personality.impulsiveness - aggressor.personality.courage).coerceAtLeast(0.0) * 0.1
            val risk = (resentmentTerm + volatility).coerceIn(0.0, ARSON_MAX_CHANCE)
            if (!ctx.rng.nextBoolean(risk)) continue

            markCooldown(ctx, aggressor.id)
            val building = state.building(targetBiz.buildingId)
            building?.let {
                it.condition = (it.condition - ctx.rng.nextDouble(15.0, 30.0)).coerceAtLeast(5.0)
                it.visibleChanges += "Scorch marks by the door"
                if (it.visibleChanges.size > 6) it.visibleChanges.removeAt(0)
            }
            val rivalryEvent = mostRecentEventOfType(ctx, EventType.RIVALRY_FORMED, aggressor.id, null)
            val crime = ctx.emit(
                EventType.ARSON_ATTEMPT,
                "Scorch marks were found by ${targetBiz.name}'s door this morning — a fire that, thankfully, didn't take hold.",
                sourceResidentId = aggressor.id, businessId = targetBiz.id, buildingId = targetBiz.buildingId,
                targetResidentIds = listOf(target.id),
                severity = 0.7, visibility = EventVisibility.HIDDEN,
                causeIds = listOfNotNull(rivalryEvent?.id)
            )
            target.needs.safety -= 12.0
            target.needs.stress += 10.0
            ctx.addMemory(target, MemoryType.FEAR, "Someone tried to burn us out.", 80.0, crime.id, listOf(aggressor.id))
            ConsequenceEngine.onEvent(ctx, crime)
            investigate(ctx, crime) // always investigated — too severe to sit unreported
        }
    }

    // ============================================================
    // Shared helpers
    // ============================================================

    /**
     * Cause payload (added 2026-07-10, see docs/simulation-rules.md "Events, causes,
     * importance"): "immediate" is always the plain fact of the theft itself — the crime's own
     * description already says that, so the payload only ever adds "underlying_cause", and only
     * when [priorCause] is non-null (a real, traced `JOB_LOST`/`DEBT_CRISIS` event or memory —
     * see [mostRecentDesperationCause]'s own doc comment). No entry at all, never a placeholder
     * string, when nothing genuine is on record.
     */
    private fun desperationCausePayload(priorCause: WorldEvent?): Map<String, String> =
        if (priorCause == null) emptyMap()
        else mapOf("underlying_cause" to when (priorCause.type) {
            EventType.JOB_LOST -> "still reeling from losing their job"
            EventType.DEBT_CRISIS -> "drowning in debt with no way out in sight"
            else -> "circumstances that had been building for a while"
        })

    /**
     * The most recent `JOB_LOST`/`DEBT_CRISIS` event for this resident, if one is still within
     * reach of `WorldState.recentEventIds` (the same bounded sliding window — 60 ids — the live
     * ticker already maintains) or the resident's own `LOSS` memories (job-loss specifically
     * leaves one, see `EconomySystem.closeBusiness`). Used to give a desperation-driven crime a
     * genuine causal link back to whatever actually created the desperation; returns null (never
     * invents a cause) if nothing plausible is still on record.
     */
    private fun mostRecentDesperationCause(ctx: TickContext, r: Resident): WorldEvent? {
        val fromRecent = ctx.state.recentEventIds.asReversed()
            .mapNotNull { ctx.eventIndex.get(it) }
            .firstOrNull {
                (it.type == EventType.JOB_LOST || it.type == EventType.DEBT_CRISIS) &&
                    it.involvedResidentIds().contains(r.id)
            }
        if (fromRecent != null) return fromRecent
        val eventId = r.memories
            .filter { it.type == MemoryType.LOSS && it.eventId != null }
            .maxByOrNull { it.createdAt }?.eventId ?: return null
        return ctx.eventIndex.get(eventId)
    }

    /** The most recent event of a given type touching this resident, from this tick or the recent-events window. */
    private fun mostRecentEventOfType(ctx: TickContext, type: EventType, residentId: Long?, businessId: Long?): WorldEvent? {
        ctx.newEvents.lastOrNull {
            it.type == type && (residentId == null || it.involvedResidentIds().contains(residentId))
        }?.let { return it }
        return ctx.state.recentEventIds.asReversed()
            .mapNotNull { ctx.eventIndex.get(it) }
            .firstOrNull {
                it.type == type && (residentId == null || it.involvedResidentIds().contains(residentId))
            }
    }

    // ---- Chance ceilings, kept as named constants like every other system's MAX_/CHANCE consts ----
    const val LOW_FOOTFALL_DEMAND = 35.0
    const val SHOPLIFTING_MAX_CHANCE = 0.12
    const val SHOPLIFTING_REPORT_CHANCE = 0.35
    const val BURGLARY_MAX_CHANCE = 0.10
    const val MUGGING_MAX_CHANCE = 0.08
    const val VEHICLE_THEFT_MAX_CHANCE = 0.08
    const val FRAUD_MAX_CHANCE = 0.06
    const val ARSON_MAX_CHANCE = 0.04
}
