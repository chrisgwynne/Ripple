package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.Gender
import com.ripple.town.core.model.Household
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Needs
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.Relationship
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.RelationshipStatus
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SpriteConfig

/**
 * Births, deaths, ageing milestones, promotion of background residents into the
 * detailed simulation, and new arrivals.
 */
object LifecycleSystem {

    fun updateDaily(ctx: TickContext) {
        births(ctx)
        InteractionSystem.processSeparations(ctx)
        InteractionSystem.dailyDecay(ctx)
        memoryDecay(ctx)
        election(ctx)
    }

    // ---------------------------------------------------------------- birth

    private fun births(ctx: TickContext) {
        val state = ctx.state
        val couples = state.relationships.values
            .filter { it.kind == RelationshipKind.SPOUSE || it.kind == RelationshipKind.PARTNER }
            .sortedBy { Relationship.keyOf(it.aId, it.bId) }
        for (rel in couples) {
            val a = state.resident(rel.aId) ?: continue
            val b = state.resident(rel.bId) ?: continue
            if (!a.inTown || !b.inTown) continue
            if (a.detailLevel != DetailLevel.DETAILED || b.detailLevel != DetailLevel.DETAILED) continue
            val ageA = a.ageAt(ctx.now); val ageB = b.ageAt(ctx.now)
            if (ageA !in 20..44 || ageB !in 20..44) continue
            if (rel.affection < 55) continue
            val existingKids = a.childIds.count { state.resident(it)?.alive == true }
            val chance = 0.0012 * (rel.affection / 100.0) * (1.0 / (1 + existingKids))
            if (!ctx.rng.nextBoolean(chance)) continue
            bear(ctx, a, b)
        }
    }

    private fun bear(ctx: TickContext, parentA: Resident, parentB: Resident) {
        val state = ctx.state
        val id = state.nextResidentId++
        val gender = when (ctx.rng.nextInt(20)) {
            in 0..9 -> Gender.FEMALE
            in 10..18 -> Gender.MALE
            else -> Gender.NONBINARY
        }
        val name = when (gender) {
            Gender.FEMALE -> ctx.rng.pick(NameData.FEMALE_FIRST)
            Gender.MALE -> ctx.rng.pick(NameData.MALE_FIRST)
            Gender.NONBINARY -> ctx.rng.pick(NameData.NEUTRAL_FIRST)
        }
        val child = Resident(
            id = id,
            firstName = name,
            surname = parentA.surname,
            gender = gender,
            bornAt = ctx.now,
            homeBuildingId = parentA.homeBuildingId,
            householdId = parentA.householdId,
            detailLevel = DetailLevel.DETAILED,
            sprite = SpriteConfig(
                skinTone = (if (ctx.rng.nextBoolean()) parentA.sprite.skinTone else parentB.sprite.skinTone),
                hairStyle = ctx.rng.nextInt(4),
                hairColor = (if (ctx.rng.nextBoolean()) parentA.sprite.hairColor else parentB.sprite.hairColor),
                shirtColor = ctx.rng.nextInt(8),
                trouserColor = ctx.rng.nextInt(5)
            ),
            occupation = "Infant",
            wealth = 0.0,
            personality = inheritPersonality(ctx.rng, parentA.personality, parentB.personality),
            needs = Needs(hunger = 70.0, energy = 80.0, health = 92.0, stress = 5.0),
            motherId = if (parentA.gender == Gender.FEMALE) parentA.id else parentB.id,
            fatherId = if (parentA.gender == Gender.MALE) parentA.id else if (parentB.gender == Gender.MALE) parentB.id else null,
            currentBuildingId = parentA.homeBuildingId
        )
        state.residents[id] = child
        parentA.childIds += id
        parentB.childIds += id
        child.householdId?.let { state.households[it]?.memberIds?.add(id) }
        for (p in listOf(parentA, parentB)) {
            val rel = state.relationshipOrCreate(p.id, id)
            rel.kind = RelationshipKind.FAMILY
            rel.familiarity = 90.0; rel.affection = 85.0; rel.trust = 80.0; rel.dependency = 70.0
        }
        state.birthsToday += 1
        val e = ctx.emit(
            EventType.PERSON_BORN,
            "${parentA.fullName} and ${parentB.fullName} have welcomed a baby: ${child.firstName}.",
            sourceResidentId = child.id,
            targetResidentIds = listOf(parentA.id, parentB.id),
            severity = 0.5
        )
        ctx.addMemory(parentA, MemoryType.ACHIEVEMENT, "The day ${child.firstName} was born.", 90.0, e.id, listOf(child.id))
        ctx.addMemory(parentB, MemoryType.ACHIEVEMENT, "The day ${child.firstName} was born.", 90.0, e.id, listOf(child.id))
        ConsequenceEngine.onEvent(ctx, e)
    }

    /** Children inherit weighted tendencies, not copies. */
    fun inheritPersonality(rng: SimRandom, a: Personality, b: Personality): Personality {
        fun mix(x: Double, y: Double): Double {
            val base = (x + y) / 2.0
            return rng.nextGaussianLike(base, 0.25, 0.05, 0.95)
        }
        return Personality(
            kindness = mix(a.kindness, b.kindness),
            ambition = mix(a.ambition, b.ambition),
            curiosity = mix(a.curiosity, b.curiosity),
            sociability = mix(a.sociability, b.sociability),
            patience = mix(a.patience, b.patience),
            honesty = mix(a.honesty, b.honesty),
            courage = mix(a.courage, b.courage),
            discipline = mix(a.discipline, b.discipline),
            empathy = mix(a.empathy, b.empathy),
            impulsiveness = mix(a.impulsiveness, b.impulsiveness)
        )
    }

    // ---------------------------------------------------------------- death

    fun die(ctx: TickContext, r: Resident, cause: String, causeIds: List<Long> = emptyList()) {
        val state = ctx.state
        if (!r.alive) return
        r.alive = false
        r.diedAt = ctx.now
        r.causeOfDeath = cause
        r.activity = Activity.IDLE
        r.currentBuildingId = state.buildings.values.firstOrNull { it.type == com.ripple.town.core.model.BuildingType.CEMETERY }?.id
        r.travelToBuildingId = null
        r.plannedActivity = null

        // Employment ends immediately — the dead do not work.
        val emp = state.employmentOf(r)
        if (emp != null) {
            emp.endedAt = ctx.now
            r.employmentId = null
        }
        // Business ownership passes on or lapses.
        for (biz in state.businesses.values.filter { it.ownerId == r.id }) {
            val heir = r.childIds.mapNotNull { state.resident(it) }
                .firstOrNull { it.alive && it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
                ?: r.partnerId?.let { state.resident(it) }?.takeIf { it.alive && it.inTown }
            biz.ownerId = heir?.id
        }
        // Inheritance: estate splits between partner and children.
        val estate = (r.wealth - r.debt).coerceAtLeast(0.0)
        val heirs = (listOfNotNull(r.partnerId?.let { state.resident(it) }) +
            r.childIds.mapNotNull { state.resident(it) }).filter { it.alive }
        if (estate > 0 && heirs.isNotEmpty()) {
            val share = estate / heirs.size
            heirs.forEach { it.wealth += share }
        }
        r.wealth = 0.0

        // Partner is widowed.
        val partner = r.partnerId?.let { state.resident(it) }
        if (partner != null && partner.alive) {
            partner.relationshipStatus = RelationshipStatus.WIDOWED
            partner.partnerId = null
            partner.needs.stress += 25.0
        }
        // Household shrinks.
        r.householdId?.let { hid -> state.households[hid]?.memberIds?.remove(r.id) }

        val age = r.ageAt(ctx.now)
        val death = ctx.emit(
            EventType.PERSON_DIED,
            "${r.fullName} has died, aged $age. Cause: $cause.",
            sourceResidentId = r.id,
            targetResidentIds = listOfNotNull(partner?.id) + r.childIds.filter { state.resident(it)?.alive == true },
            severity = 0.85,
            payload = mapOf("cause" to cause, "age" to age.toString()),
            causeIds = causeIds
        )
        state.deathsToday += 1

        // The bereaved grieve and remember.
        val bereaved = state.relationshipsOf(r.id)
            .filter { it.warmth() > 30 || it.kind == RelationshipKind.FAMILY || it.kind == RelationshipKind.SPOUSE }
            .mapNotNull { state.resident(it.other(r.id)) }
            .filter { it.alive && it.inTown && it.detailLevel == DetailLevel.DETAILED }
        for (m in bereaved) {
            m.needs.stress += 12.0
            m.needs.social -= 10.0
            ctx.beginActivity(m, Activity.MOURNING, 12 * 60, "Grieving for ${r.firstName}")
            ctx.addMemory(m, MemoryType.LOSS, "We lost ${r.firstName}.", 80.0, death.id, listOf(r.id))
        }
        ConsequenceEngine.onEvent(ctx, death)
    }

    // ---------------------------------------------------- promotion/arrival

    /** Background residents step into the detailed simulation when they start to matter. */
    fun promoteIfNeeded(ctx: TickContext, r: Resident, why: String) {
        if (r.detailLevel == DetailLevel.DETAILED) return
        r.detailLevel = DetailLevel.DETAILED
        if (r.homeBuildingId == null) {
            // Move into the emptiest home with space.
            val state = ctx.state
            val home = state.homes()
                .filter { !it.abandoned }
                .minByOrNull { h -> state.households.values.count { it.homeBuildingId == h.id } * 10 + h.id }
            if (home != null) {
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
                r.currentBuildingId = home.id
            }
        }
        if (r.id !in ctx.state.discoveredResidentIds) ctx.state.discoveredResidentIds += r.id
    }

    /** Handles the seeded "new family arrives" thread and any later arrivals. */
    fun newFamilyArrives(ctx: TickContext, causeEventId: Long?) {
        val state = ctx.state
        val surname = ctx.rng.pick(NameData.SURNAMES)
        val home = state.homes().firstOrNull { h -> state.households.values.none { it.homeBuildingId == h.id } }
        val hh = Household(
            id = state.nextHouseholdId++,
            name = "The $surname household",
            homeBuildingId = home?.id,
            savings = ctx.rng.nextDouble(400.0, 1_500.0)
        )
        state.households[hh.id] = hh
        val adultCount = ctx.rng.nextInt(1, 3)
        val kidCount = ctx.rng.nextInt(0, 3)
        val names = mutableListOf<String>()
        repeat(adultCount + kidCount) { i ->
            val isAdult = i < adultCount
            val gender = if (ctx.rng.nextBoolean(0.06)) Gender.NONBINARY else if (ctx.rng.nextBoolean()) Gender.FEMALE else Gender.MALE
            val first = when (gender) {
                Gender.FEMALE -> ctx.rng.pick(NameData.FEMALE_FIRST)
                Gender.MALE -> ctx.rng.pick(NameData.MALE_FIRST)
                Gender.NONBINARY -> ctx.rng.pick(NameData.NEUTRAL_FIRST)
            }
            val age = if (isAdult) ctx.rng.nextInt(24, 46) else ctx.rng.nextInt(2, 15)
            val id = state.nextResidentId++
            val r = Resident(
                id = id, firstName = first, surname = surname, gender = gender,
                bornAt = ctx.now - age.toLong() * SimTime.MINUTES_PER_YEAR,
                homeBuildingId = hh.homeBuildingId, householdId = hh.id,
                detailLevel = DetailLevel.DETAILED,
                sprite = SpriteConfig(
                    skinTone = ctx.rng.nextInt(4), hairStyle = ctx.rng.nextInt(4),
                    hairColor = ctx.rng.nextInt(5), shirtColor = ctx.rng.nextInt(8),
                    trouserColor = ctx.rng.nextInt(5)
                ),
                occupation = if (isAdult) "Newly arrived" else "Pupil",
                wealth = if (isAdult) ctx.rng.nextDouble(200.0, 900.0) else 0.0,
                personality = Personality(
                    kindness = ctx.rng.nextDouble(0.3, 0.8), ambition = ctx.rng.nextDouble(0.3, 0.8),
                    curiosity = ctx.rng.nextDouble(0.3, 0.8), sociability = ctx.rng.nextDouble(0.3, 0.8),
                    patience = ctx.rng.nextDouble(0.3, 0.8), honesty = ctx.rng.nextDouble(0.4, 0.9),
                    courage = ctx.rng.nextDouble(0.3, 0.8), discipline = ctx.rng.nextDouble(0.3, 0.8),
                    empathy = ctx.rng.nextDouble(0.3, 0.8), impulsiveness = ctx.rng.nextDouble(0.2, 0.7)
                ),
                currentBuildingId = hh.homeBuildingId
            )
            state.residents[id] = r
            hh.memberIds += id
            names += first
            if (isAdult) {
                r.goals += com.ripple.town.core.model.Goal(
                    id = state.nextGoalId++, ownerId = id,
                    type = com.ripple.town.core.model.GoalType.FIND_JOB,
                    motivation = "New town, new start — work comes first.",
                    createdAt = ctx.now, causeEventId = causeEventId
                )
            }
        }
        // Family members know and love each other.
        val members = hh.memberIds.toList()
        for (i in members.indices) for (j in i + 1 until members.size) {
            val rel = state.relationshipOrCreate(members[i], members[j])
            rel.kind = RelationshipKind.FAMILY
            rel.familiarity = 85.0; rel.affection = 70.0; rel.trust = 72.0; rel.sharedHistory = 60.0
        }
        val e = ctx.emit(
            EventType.RESIDENT_ARRIVED,
            "The $surname family (${names.joinToString(", ")}) has moved to ${state.townName}.",
            targetResidentIds = members,
            buildingId = hh.homeBuildingId,
            severity = 0.45,
            causeIds = listOfNotNull(causeEventId)
        )
        ConsequenceEngine.onEvent(ctx, e)
    }

    // ------------------------------------------------------------- election

    private fun election(ctx: TickContext) {
        val state = ctx.state
        if (state.nextElectionAt <= 0 || ctx.now < state.nextElectionAt) return
        // Candidates: politically interested adults with standing.
        val candidates = state.detailedResidents()
            .filter { it.inTown && it.politicalInterest > 0.35 && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            .sortedByDescending { it.politicalInterest * 50 + it.reputation + it.skill(com.ripple.town.core.model.SkillType.POLITICS) }
            .take(3)
        if (candidates.isEmpty()) {
            state.nextElectionAt = ctx.now + 360L * SimTime.MINUTES_PER_DAY
            return
        }
        val scored = candidates.map { c ->
            c to (c.reputation + c.skill(com.ripple.town.core.model.SkillType.POLITICS) * 0.6 +
                ctx.rng.nextDouble(0.0, 15.0))
        }
        val winner = scored.maxByOrNull { it.second }!!.first
        val previous = state.mayorId?.let { state.resident(it) }
        state.mayorId = winner.id
        winner.occupation = "Mayor"
        winner.needs.purpose += 20.0
        state.nextElectionAt = ctx.now + 720L * SimTime.MINUTES_PER_DAY
        val e = ctx.emit(
            EventType.ELECTION_WON,
            "${winner.fullName} has won the town election" +
                (previous?.takeIf { it.id != winner.id }?.let { ", succeeding ${it.fullName}" } ?: "") + ".",
            sourceResidentId = winner.id,
            targetResidentIds = candidates.map { it.id } - winner.id,
            severity = 0.7
        )
        ctx.addMemory(winner, MemoryType.ACHIEVEMENT, "Election night.", 85.0, e.id)
        ConsequenceEngine.onEvent(ctx, e)
    }

    // -------------------------------------------------------------- memory

    private fun memoryDecay(ctx: TickContext) {
        val perDay = 1.0 / SimTime.DAYS_PER_YEAR
        for (r in ctx.state.detailedResidents()) {
            val it = r.memories.iterator()
            while (it.hasNext()) {
                val m = it.next()
                m.emotionalIntensity -= m.decayPerYear * perDay
                m.accuracy -= (m.decayPerYear * 0.5) * perDay
                // Important memories persist for life; minor ones fade away entirely.
                if (m.importance < 40 && m.emotionalIntensity <= 5) it.remove()
            }
        }
    }
}
