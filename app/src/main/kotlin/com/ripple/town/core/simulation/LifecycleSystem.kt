package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.ArrivalReason
import com.ripple.town.core.model.DepartureReason
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.DistrictCharacter
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.Gender
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.Household
import com.ripple.town.core.model.HouseholdArrivalType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.MigrationRecord
import com.ripple.town.core.model.Needs
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.Relationship
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.RelationshipStatus
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimCalendar
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.SpriteConfig

/**
 * Births, deaths, ageing milestones, promotion of background residents into the
 * detailed simulation, and new arrivals.
 */
object LifecycleSystem {

    fun updateDaily(ctx: TickContext) {
        births(ctx)
        checkInwardMigration(ctx)
        InteractionSystem.processSeparations(ctx)
        InteractionSystem.dailyDecay(ctx)
        memoryDecay(ctx)
        election(ctx)
        // Monthly outward migration check -- runs on the first day of each in-game month.
        if (SimTime.dayOfMonth(ctx.now) == 1) {
            checkOutwardMigration(ctx)
        }
    }

    /**
     * Demand-driven inward migration: occasionally attract a new family when the town
     * has unfilled jobs AND vacant homes AND the municipal budget is healthy enough to
     * absorb new residents. Probability scales with the number of open job slots to
     * avoid flooding a town that has no work.
     */
    private fun checkInwardMigration(ctx: TickContext) {
        val state = ctx.state
        // Vacant homes: homes with no household.
        val vacantHomes = state.homes().count { h ->
            !h.abandoned && state.households.values.none { it.homeBuildingId == h.id }
        }
        if (vacantHomes == 0) return
        // Open job slots.
        val openJobs = state.businesses.values
            .filter { it.open }
            .sumOf { biz -> (biz.employeeCapacity - state.employeesOf(biz.id).size).coerceAtLeast(0) }
        if (openJobs == 0) return
        // Budget health check: don't attract migrants when the town is in deep deficit.
        if (state.municipalBudget.balance < -30_000.0) return
        // Probability: 1.5% base + 0.5% per open job slot, capped at 5%.
        val baseProbRaw = (0.015 + openJobs * 0.005).coerceAtMost(0.05)
        // Audit #39: district character affects migration attractiveness.
        // Each DECLINING/DERELICT district lowers prob by 5%; each PROSPEROUS/GENTRIFYING raises it by 3%.
        val districtChars = ctx.state.districts.values.map { it.character }
        val migrationMod = districtChars.sumOf { char ->
            when (char) {
                DistrictCharacter.DECLINING,
                DistrictCharacter.DERELICT   -> -0.05
                DistrictCharacter.PROSPEROUS,
                DistrictCharacter.GENTRIFYING -> 0.03
                else -> 0.0
            }
        }
        val prob = (baseProbRaw + migrationMod).coerceIn(0.005, 0.05)
        if (ctx.rng.nextBoolean(prob)) {
            newFamilyArrives(ctx, null)
        }
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
            // Spring gives a small birth-rate uplift -- more conceptions in the warmer months.
            val springFactor = if (SimCalendar.season(ctx.now) == SimCalendar.Season.SPRING) 1.15 else 1.0
            val chance = 0.0012 * (rel.affection / 100.0) * (1.0 / (1 + existingKids)) * springFactor
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
        CaregiverSystem.assignCaregiver(state, child)
        parentA.childIds += id
        parentB.childIds += id
        child.householdId?.let { state.households[it]?.memberIds?.add(id) }
        for (p in listOf(parentA, parentB)) {
            val rel = state.relationshipOrCreate(p.id, id)
            rel.kind = RelationshipKind.FAMILY
            rel.familiarity = 90.0; rel.affection = 85.0; rel.trust = 80.0; rel.dependency = 70.0
        }
        state.birthsToday += 1
        // Births happen at realistic daytime hours, not at the midnight tick that fires updateDaily.
        val dayStart = ctx.now - SimTime.minuteOfDay(ctx.now)
        val birthTime = HumanScheduler.realisticTimeToday(ScheduledActivity.BIRTH, dayStart, ctx.rng)
        val e = ctx.emit(
            EventType.PERSON_BORN,
            "${parentA.fullName} and ${parentB.fullName} have welcomed a baby: ${child.firstName}.",
            sourceResidentId = child.id,
            targetResidentIds = listOf(parentA.id, parentB.id),
            severity = 0.5,
            atTime = birthTime
        )
        ctx.addMemory(parentA, MemoryType.ACHIEVEMENT, "The day ${child.firstName} was born.", 90.0, e.id, listOf(child.id))
        ctx.addMemory(parentB, MemoryType.ACHIEVEMENT, "The day ${child.firstName} was born.", 90.0, e.id, listOf(child.id))
        EmotionSystem.spawnEmotion(ctx, parentA, com.ripple.town.core.model.EmotionType.PRIDE, 70.0, e.id, child.id)
        EmotionSystem.spawnEmotion(ctx, parentB, com.ripple.town.core.model.EmotionType.PRIDE, 70.0, e.id, child.id)
        PersonalityDevelopmentSystem.evaluateParenthood(ctx, parentA, e.id)
        PersonalityDevelopmentSystem.evaluateParenthood(ctx, parentB, e.id)
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

        // Employment ends immediately -- the dead do not work.
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
            // Breathing space: widowed partner should not immediately seek new companionship.
            partner.majorEventCooldownUntil = ctx.now + 180 * SimTime.MINUTES_PER_DAY
        }
        // Household shrinks.
        r.householdId?.let { hid -> state.households[hid]?.memberIds?.remove(r.id) }

        val age = r.ageAt(ctx.now)
        // Deaths should appear in the event log at a realistic hour -- not midnight when the tick fires.
        val dayStart = ctx.now - SimTime.minuteOfDay(ctx.now)
        val deathTime = HumanScheduler.realisticTimeToday(ScheduledActivity.DAYTIME_GENERAL, dayStart, ctx.rng)
        val death = ctx.emit(
            EventType.PERSON_DIED,
            "${r.fullName} has died, aged $age. Cause: $cause.",
            sourceResidentId = r.id,
            targetResidentIds = listOfNotNull(partner?.id) + r.childIds.filter { state.resident(it)?.alive == true },
            severity = 0.85,
            atTime = deathTime,
            payload = buildMap {
                put("cause", cause)
                put("age", age.toString())
                put("bornAt", r.bornAt.toString())
                put("immediate_cause", cause)
                if (causeIds.isNotEmpty()) put("underlying_cause", "a condition diagnosed earlier that never fully let go")
            },
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
            EmotionSystem.spawnEmotion(ctx, m, com.ripple.town.core.model.EmotionType.GRIEF, 75.0, death.id, r.id)
            if (m.id == partner?.id || m.id in r.childIds || m.id == r.motherId || m.id == r.fatherId) {
                EconomySystem.scheduleShock(ctx, m, death.id)
            }
        }

        passDownBeliefs(ctx, r, death.id)
        passDownHeirloom(ctx, r, death.id)

        ConsequenceEngine.onEvent(ctx, death)
        LegendSystem.considerSpawn(ctx, death)
        LegacySystem.onDeath(ctx, r, death)
    }

    /** How important a memory's formed belief must be to survive as a family story. */
    private const val BELIEF_IMPORTANCE_THRESHOLD = 65.0

    /** At most this many of the deceased's beliefs get handed down. */
    private const val MAX_INHERITED_BELIEFS = 2

    /** Inherited "family story" memories land at roughly this fraction of the original's intensity. */
    private const val INHERITED_BELIEF_INTENSITY_FACTOR = 0.45

    /** How important a positive memory must be for its owner to leave a physical heirloom behind. */
    private const val HEIRLOOM_IMPORTANCE_THRESHOLD = 75.0

    private val HEIRLOOM_MEMORY_TYPES = setOf(MemoryType.ACHIEVEMENT, MemoryType.INSPIRATION, MemoryType.ROMANCE)

    private fun passDownBeliefs(ctx: TickContext, deceased: Resident, deathEventId: Long) {
        val state = ctx.state
        val survivingChildren = deceased.childIds.mapNotNull { state.resident(it) }
            .filter { it.alive && it.detailLevel == DetailLevel.DETAILED }
        if (survivingChildren.isEmpty()) return

        val topBeliefs = deceased.memories
            .filter { it.beliefFormed != null && it.importance >= BELIEF_IMPORTANCE_THRESHOLD }
            .sortedWith(compareByDescending<com.ripple.town.core.model.Memory> { it.importance }
                .thenByDescending { it.emotionalIntensity })
            .take(MAX_INHERITED_BELIEFS)
        if (topBeliefs.isEmpty()) return

        for (child in survivingChildren) {
            for (memory in topBeliefs) {
                val belief = memory.beliefFormed ?: continue
                val story = "${deceased.firstName} used to say: \"$belief\"."
                ctx.addMemory(
                    child,
                    MemoryType.CHILDHOOD,
                    story,
                    memory.emotionalIntensity * INHERITED_BELIEF_INTENSITY_FACTOR,
                    deathEventId,
                    listOf(deceased.id),
                    belief
                )
            }
        }
    }

    private fun passDownHeirloom(ctx: TickContext, deceased: Resident, deathEventId: Long) {
        val state = ctx.state
        val proudMemory = deceased.memories
            .filter { it.type in HEIRLOOM_MEMORY_TYPES && it.importance >= HEIRLOOM_IMPORTANCE_THRESHOLD }
            .maxWithOrNull(compareBy<com.ripple.town.core.model.Memory> { it.importance }
                .thenBy { it.emotionalIntensity })
            ?: return

        val heir = deceased.childIds.mapNotNull { state.resident(it) }
            .filter { it.alive && it.inTown && it.detailLevel == DetailLevel.DETAILED }
            .let { candidates ->
                val adults = candidates.filter { it.lifeStageAt(ctx.now) == LifeStage.ADULT }
                if (adults.isNotEmpty()) ctx.rng.pick(adults) else ctx.rng.pickOrNull(candidates)
            }
            ?: deceased.partnerId?.let { state.resident(it) }?.takeIf { it.alive && it.inTown }
            ?: return

        val item = heirloomItemFor(deceased)
        heir.ideaSeeds += "heirloom:${deceased.firstName}'s $item"
        ctx.addMemory(
            heir,
            MemoryType.INSPIRATION,
            "${deceased.firstName} left me ${article(item)} $item.",
            proudMemory.emotionalIntensity * INHERITED_BELIEF_INTENSITY_FACTOR + 15.0,
            deathEventId,
            listOf(deceased.id)
        )
    }

    /** Flavour text for a lightweight heirloom, loosely themed to the deceased's trade. */
    private fun heirloomItemFor(deceased: Resident): String {
        val bestSkill = deceased.skills.entries.maxByOrNull { it.value }?.key
        return when (bestSkill) {
            SkillType.COOKING -> "worn recipe book"
            SkillType.CARPENTRY -> "well-used toolbox"
            SkillType.REPAIR -> "old pocket watch"
            SkillType.TEACHING -> "annotated notebook"
            SkillType.MEDICINE -> "medical bag"
            SkillType.BUSINESS -> "ledger and fountain pen"
            SkillType.POLITICS -> "campaign badge"
            SkillType.SOCIAL -> "address book"
            SkillType.FITNESS -> "trophy"
            SkillType.CREATIVITY -> "sketchbook"
            null -> "pocket watch"
        }
    }

    private fun article(noun: String): String =
        if (noun.firstOrNull()?.lowercaseChar()?.let { it in "aeiou" } == true) "an" else "a"

    // ---------------------------------------------------- promotion/arrival

    /** Background/connected residents step into the next detail tier when they start to matter. */
    fun promoteIfNeeded(ctx: TickContext, r: Resident, why: String) {
        if (r.detailLevel == DetailLevel.DETAILED) return
        if (r.detailLevel == DetailLevel.BACKGROUND) {
            r.detailLevel = DetailLevel.CONNECTED
            return
        }
        r.detailLevel = DetailLevel.DETAILED
        if (r.homeBuildingId == null) {
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

    /**
     * Handles new family arrivals -- typed by [HouseholdArrivalType], with an [ArrivalReason],
     * household composition matching the type, and a [MigrationRecord] entry.
     * Emits [EventType.FAMILY_ARRIVED] (Phase 6A).
     */
    fun newFamilyArrives(ctx: TickContext, causeEventId: Long?) {
        val state = ctx.state
        val surname = ctx.rng.pick(NameData.SURNAMES)
        val home = state.homes().firstOrNull { h -> state.households.values.none { it.homeBuildingId == h.id } }

        // --- Step 1: Pick household type based on current town conditions ---
        val openJobs = state.businesses.values
            .filter { it.open }
            .sumOf { biz -> (biz.employeeCapacity - state.employeesOf(biz.id).size).coerceAtLeast(0) }
        val manyOpenJobs = openJobs >= 3
        val elderlyPop = state.livingResidents().count { it.ageAt(ctx.now) >= 65 }
        val totalPop = state.livingResidents().size
        val elderlyFraction = if (totalPop > 0) elderlyPop.toDouble() / totalPop else 0.0

        val arrivalType: HouseholdArrivalType = when {
            ctx.rng.nextBoolean(0.1) -> HouseholdArrivalType.ENTREPRENEUR_FAMILY
            manyOpenJobs -> {
                val roll = ctx.rng.nextDouble(0.0, 1.0)
                when {
                    roll < 0.30 -> HouseholdArrivalType.SINGLE_WORKER
                    roll < 0.50 -> HouseholdArrivalType.YOUNG_COUPLE
                    roll < 0.70 -> HouseholdArrivalType.COUPLE_WITH_CHILDREN
                    roll < 0.85 -> HouseholdArrivalType.PROFESSIONAL_HOUSEHOLD
                    roll < 0.90 -> if (elderlyFraction > 0.2) HouseholdArrivalType.RETIRED_COUPLE else HouseholdArrivalType.SINGLE_PARENT
                    else -> HouseholdArrivalType.MULTIGENERATIONAL
                }
            }
            else -> {
                val roll = ctx.rng.nextDouble(0.0, 1.0)
                when {
                    roll < 0.20 -> HouseholdArrivalType.SINGLE_WORKER
                    roll < 0.35 -> HouseholdArrivalType.YOUNG_COUPLE
                    roll < 0.50 -> HouseholdArrivalType.COUPLE_WITH_CHILDREN
                    roll < 0.60 -> HouseholdArrivalType.SINGLE_PARENT
                    roll < 0.70 + (if (elderlyFraction > 0.2) 0.15 else 0.0) ->
                        HouseholdArrivalType.RETIRED_COUPLE
                    roll < 0.80 -> HouseholdArrivalType.PROFESSIONAL_HOUSEHOLD
                    else -> HouseholdArrivalType.MULTIGENERATIONAL
                }
            }
        }

        // --- Step 2: Pick arrival reason matching the type ---
        val arrivalReason: ArrivalReason = when (arrivalType) {
            HouseholdArrivalType.SINGLE_WORKER,
            HouseholdArrivalType.PROFESSIONAL_HOUSEHOLD -> {
                if (ctx.rng.nextBoolean(0.7)) ArrivalReason.JOB_OFFER else ArrivalReason.AFFORDABILITY
            }
            HouseholdArrivalType.YOUNG_COUPLE -> {
                val r = ctx.rng.nextDouble(0.0, 1.0)
                when {
                    r < 0.5 -> ArrivalReason.JOB_OFFER
                    r < 0.8 -> ArrivalReason.AFFORDABILITY
                    else -> ArrivalReason.AVAILABLE_HOUSING
                }
            }
            HouseholdArrivalType.COUPLE_WITH_CHILDREN -> {
                val r = ctx.rng.nextDouble(0.0, 1.0)
                when {
                    r < 0.4 -> ArrivalReason.FAMILY_REUNIFICATION
                    r < 0.8 -> ArrivalReason.JOB_OFFER
                    else -> ArrivalReason.AVAILABLE_HOUSING
                }
            }
            HouseholdArrivalType.SINGLE_PARENT -> {
                val r = ctx.rng.nextDouble(0.0, 1.0)
                when {
                    r < 0.4 -> ArrivalReason.FAMILY_REUNIFICATION
                    r < 0.7 -> ArrivalReason.AFFORDABILITY
                    else -> ArrivalReason.JOB_OFFER
                }
            }
            HouseholdArrivalType.RETIRED_COUPLE -> ArrivalReason.RETIREMENT
            HouseholdArrivalType.ENTREPRENEUR_FAMILY -> ArrivalReason.BUSINESS_OPPORTUNITY
            HouseholdArrivalType.MULTIGENERATIONAL -> {
                val r = ctx.rng.nextDouble(0.0, 1.0)
                when {
                    r < 0.5 -> ArrivalReason.FAMILY_REUNIFICATION
                    r < 0.75 -> ArrivalReason.JOB_OFFER
                    else -> ArrivalReason.AVAILABLE_HOUSING
                }
            }
            HouseholdArrivalType.STUDENT_HOUSEHOLD -> ArrivalReason.EDUCATION
            HouseholdArrivalType.DISPLACED_FAMILY -> ArrivalReason.DISPLACEMENT
        }

        // --- Step 3: Set household savings based on type ---
        val hhSavings = when (arrivalType) {
            HouseholdArrivalType.RETIRED_COUPLE -> ctx.rng.nextDouble(5_000.0, 15_000.0)
            HouseholdArrivalType.ENTREPRENEUR_FAMILY -> ctx.rng.nextDouble(8_000.0, 25_000.0)
            HouseholdArrivalType.PROFESSIONAL_HOUSEHOLD -> ctx.rng.nextDouble(2_000.0, 6_000.0)
            else -> ctx.rng.nextDouble(400.0, 1_500.0)
        }

        val hh = Household(
            id = state.nextHouseholdId++,
            name = "The $surname household",
            homeBuildingId = home?.id,
            savings = hhSavings
        )
        state.households[hh.id] = hh

        // --- Step 4: Generate residents matching the type ---
        val names = mutableListOf<String>()
        val newResidents = mutableListOf<Resident>()

        fun makeResident(age: Int, isAdult: Boolean, isElderAdult: Boolean = false, wealthOverride: Double? = null): Resident {
            val gender = if (ctx.rng.nextBoolean(0.06)) Gender.NONBINARY else if (ctx.rng.nextBoolean()) Gender.FEMALE else Gender.MALE
            val first = when (gender) {
                Gender.FEMALE -> ctx.rng.pick(NameData.FEMALE_FIRST)
                Gender.MALE -> ctx.rng.pick(NameData.MALE_FIRST)
                Gender.NONBINARY -> ctx.rng.pick(NameData.NEUTRAL_FIRST)
            }
            val wealth = wealthOverride ?: when {
                isElderAdult -> ctx.rng.nextDouble(3_000.0, 8_000.0)
                arrivalType == HouseholdArrivalType.PROFESSIONAL_HOUSEHOLD && isAdult ->
                    ctx.rng.nextDouble(800.0, 2_500.0)
                arrivalType == HouseholdArrivalType.ENTREPRENEUR_FAMILY && isAdult ->
                    ctx.rng.nextDouble(1_500.0, 5_000.0)
                isAdult -> ctx.rng.nextDouble(200.0, 900.0)
                else -> 0.0
            }
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
                wealth = wealth,
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
            newResidents += r
            return r
        }

        when (arrivalType) {
            HouseholdArrivalType.SINGLE_WORKER -> {
                val adult = makeResident(ctx.rng.nextInt(22, 36), isAdult = true)
                adult.goals += com.ripple.town.core.model.Goal(
                    id = state.nextGoalId++, ownerId = adult.id,
                    type = GoalType.FIND_JOB, motivation = "New town, new start -- work comes first.",
                    createdAt = ctx.now, causeEventId = causeEventId
                )
            }
            HouseholdArrivalType.YOUNG_COUPLE -> {
                val a = makeResident(ctx.rng.nextInt(20, 33), isAdult = true)
                val b = makeResident(ctx.rng.nextInt(20, 33), isAdult = true)
                val rel = state.relationshipOrCreate(a.id, b.id)
                rel.kind = RelationshipKind.PARTNER
                rel.familiarity = 80.0; rel.affection = 75.0; rel.trust = 70.0
                a.partnerId = b.id; b.partnerId = a.id
                a.relationshipStatus = RelationshipStatus.DATING
                b.relationshipStatus = RelationshipStatus.DATING
                for (r in listOf(a, b)) {
                    r.goals += com.ripple.town.core.model.Goal(
                        id = state.nextGoalId++, ownerId = r.id,
                        type = GoalType.FIND_JOB, motivation = "New town, new start -- work comes first.",
                        createdAt = ctx.now, causeEventId = causeEventId
                    )
                }
            }
            HouseholdArrivalType.COUPLE_WITH_CHILDREN -> {
                val a = makeResident(ctx.rng.nextInt(28, 46), isAdult = true)
                val b = makeResident(ctx.rng.nextInt(28, 46), isAdult = true)
                val rel = state.relationshipOrCreate(a.id, b.id)
                rel.kind = RelationshipKind.SPOUSE
                rel.familiarity = 90.0; rel.affection = 70.0; rel.trust = 75.0
                a.partnerId = b.id; b.partnerId = a.id
                a.relationshipStatus = RelationshipStatus.MARRIED
                b.relationshipStatus = RelationshipStatus.MARRIED
                for (r in listOf(a, b)) {
                    r.goals += com.ripple.town.core.model.Goal(
                        id = state.nextGoalId++, ownerId = r.id,
                        type = GoalType.FIND_JOB, motivation = "New town, new start -- work comes first.",
                        createdAt = ctx.now, causeEventId = causeEventId
                    )
                }
                val kidCount = ctx.rng.nextInt(1, 4)
                repeat(kidCount) {
                    val child = makeResident(ctx.rng.nextInt(2, 15), isAdult = false)
                    a.childIds += child.id; b.childIds += child.id
                    child.motherId = if (a.gender == Gender.FEMALE) a.id else b.id
                    child.fatherId = if (a.gender == Gender.MALE) a.id else if (b.gender == Gender.MALE) b.id else null
                    for (parent in listOf(a, b)) {
                        val pr = state.relationshipOrCreate(parent.id, child.id)
                        pr.kind = RelationshipKind.FAMILY
                        pr.familiarity = 90.0; pr.affection = 85.0; pr.trust = 80.0; pr.dependency = 70.0
                    }
                }
            }
            HouseholdArrivalType.SINGLE_PARENT -> {
                val parent = makeResident(ctx.rng.nextInt(25, 41), isAdult = true)
                parent.goals += com.ripple.town.core.model.Goal(
                    id = state.nextGoalId++, ownerId = parent.id,
                    type = GoalType.FIND_JOB, motivation = "New town, new start -- work comes first.",
                    createdAt = ctx.now, causeEventId = causeEventId
                )
                val kidCount = ctx.rng.nextInt(1, 3)
                repeat(kidCount) {
                    val child = makeResident(ctx.rng.nextInt(3, 13), isAdult = false)
                    parent.childIds += child.id
                    child.motherId = if (parent.gender == Gender.FEMALE) parent.id else null
                    child.fatherId = if (parent.gender == Gender.MALE) parent.id else null
                    val pr = state.relationshipOrCreate(parent.id, child.id)
                    pr.kind = RelationshipKind.FAMILY
                    pr.familiarity = 90.0; pr.affection = 85.0; pr.trust = 80.0; pr.dependency = 75.0
                }
            }
            HouseholdArrivalType.RETIRED_COUPLE -> {
                val a = makeResident(ctx.rng.nextInt(62, 76), isAdult = true, isElderAdult = true)
                val b = makeResident(ctx.rng.nextInt(62, 76), isAdult = true, isElderAdult = true)
                val rel = state.relationshipOrCreate(a.id, b.id)
                rel.kind = RelationshipKind.SPOUSE
                rel.familiarity = 95.0; rel.affection = 75.0; rel.trust = 85.0; rel.sharedHistory = 80.0
                a.partnerId = b.id; b.partnerId = a.id
                a.relationshipStatus = RelationshipStatus.MARRIED
                b.relationshipStatus = RelationshipStatus.MARRIED
            }
            HouseholdArrivalType.MULTIGENERATIONAL -> {
                val a = makeResident(ctx.rng.nextInt(28, 46), isAdult = true)
                val b = makeResident(ctx.rng.nextInt(28, 46), isAdult = true)
                val rel = state.relationshipOrCreate(a.id, b.id)
                rel.kind = RelationshipKind.SPOUSE
                rel.familiarity = 88.0; rel.affection = 70.0; rel.trust = 75.0
                a.partnerId = b.id; b.partnerId = a.id
                a.relationshipStatus = RelationshipStatus.MARRIED
                b.relationshipStatus = RelationshipStatus.MARRIED
                for (r in listOf(a, b)) {
                    r.goals += com.ripple.town.core.model.Goal(
                        id = state.nextGoalId++, ownerId = r.id,
                        type = GoalType.FIND_JOB, motivation = "New town, new start -- work comes first.",
                        createdAt = ctx.now, causeEventId = causeEventId
                    )
                }
                val elderCount = ctx.rng.nextInt(1, 3)
                repeat(elderCount) { makeResident(ctx.rng.nextInt(65, 81), isAdult = true, isElderAdult = true) }
                val kidCount = ctx.rng.nextInt(1, 3)
                repeat(kidCount) {
                    val child = makeResident(ctx.rng.nextInt(2, 15), isAdult = false)
                    a.childIds += child.id; b.childIds += child.id
                    child.motherId = if (a.gender == Gender.FEMALE) a.id else b.id
                    child.fatherId = if (a.gender == Gender.MALE) a.id else if (b.gender == Gender.MALE) b.id else null
                    for (parent in listOf(a, b)) {
                        val pr = state.relationshipOrCreate(parent.id, child.id)
                        pr.kind = RelationshipKind.FAMILY
                        pr.familiarity = 90.0; pr.affection = 85.0; pr.trust = 80.0; pr.dependency = 70.0
                    }
                }
            }
            HouseholdArrivalType.ENTREPRENEUR_FAMILY -> {
                val a = makeResident(ctx.rng.nextInt(30, 49), isAdult = true)
                val b = makeResident(ctx.rng.nextInt(30, 49), isAdult = true)
                val rel = state.relationshipOrCreate(a.id, b.id)
                rel.kind = RelationshipKind.SPOUSE
                rel.familiarity = 85.0; rel.affection = 72.0; rel.trust = 78.0
                a.partnerId = b.id; b.partnerId = a.id
                a.relationshipStatus = RelationshipStatus.MARRIED
                b.relationshipStatus = RelationshipStatus.MARRIED
                a.ideaSeeds += "heirloom:entrepreneurial spirit"
                a.goals += com.ripple.town.core.model.Goal(
                    id = state.nextGoalId++, ownerId = a.id,
                    type = GoalType.START_BUSINESS,
                    motivation = "We moved here to build something new.",
                    createdAt = ctx.now, causeEventId = causeEventId
                )
                b.goals += com.ripple.town.core.model.Goal(
                    id = state.nextGoalId++, ownerId = b.id,
                    type = GoalType.FIND_JOB, motivation = "New town, new start -- work comes first.",
                    createdAt = ctx.now, causeEventId = causeEventId
                )
            }
            HouseholdArrivalType.PROFESSIONAL_HOUSEHOLD -> {
                val adultCount = if (ctx.rng.nextBoolean()) 1 else 2
                repeat(adultCount) { i ->
                    val adult = makeResident(ctx.rng.nextInt(26, 41), isAdult = true)
                    adult.goals += com.ripple.town.core.model.Goal(
                        id = state.nextGoalId++, ownerId = adult.id,
                        type = GoalType.FIND_JOB, motivation = "New town, career first.",
                        createdAt = ctx.now, causeEventId = causeEventId
                    )
                    if (i == 1 && newResidents.size >= 2) {
                        val first = newResidents[newResidents.size - 2]
                        val pRel = state.relationshipOrCreate(first.id, adult.id)
                        pRel.kind = RelationshipKind.PARTNER
                        pRel.familiarity = 75.0; pRel.affection = 65.0; pRel.trust = 68.0
                        first.partnerId = adult.id; adult.partnerId = first.id
                        first.relationshipStatus = RelationshipStatus.DATING
                        adult.relationshipStatus = RelationshipStatus.DATING
                    }
                }
            }
            HouseholdArrivalType.STUDENT_HOUSEHOLD,
            HouseholdArrivalType.DISPLACED_FAMILY -> {
                val adultCount = ctx.rng.nextInt(1, 3)
                val kidCount = ctx.rng.nextInt(0, 3)
                repeat(adultCount) {
                    val adult = makeResident(ctx.rng.nextInt(18, 35), isAdult = true)
                    adult.goals += com.ripple.town.core.model.Goal(
                        id = state.nextGoalId++, ownerId = adult.id,
                        type = GoalType.FIND_JOB, motivation = "New town, new start -- work comes first.",
                        createdAt = ctx.now, causeEventId = causeEventId
                    )
                }
                repeat(kidCount) { makeResident(ctx.rng.nextInt(2, 15), isAdult = false) }
            }
        }

        // All household members know and care for each other
        val members = hh.memberIds.toList()
        for (i in members.indices) for (j in i + 1 until members.size) {
            if (state.relationship(members[i], members[j]) == null) {
                val rel = state.relationshipOrCreate(members[i], members[j])
                rel.kind = RelationshipKind.FAMILY
                rel.familiarity = 85.0; rel.affection = 70.0; rel.trust = 72.0; rel.sharedHistory = 60.0
            }
        }

        // --- Step 5: Record arrival ---
        val homeBuildingDistrictId = home?.districtId
        val record = MigrationRecord(
            tick = ctx.now,
            householdId = hh.id,
            arrivalType = arrivalType,
            arrivalReason = arrivalReason,
            districtId = homeBuildingDistrictId,
            memberCount = newResidents.size,
            isArrival = true
        )
        if (state.migrationHistory.size >= 500) state.migrationHistory.removeAt(0)
        state.migrationHistory.add(record)

        // --- Step 6: Emit FAMILY_ARRIVED event ---
        val typeLabel = when (arrivalType) {
            HouseholdArrivalType.SINGLE_WORKER -> "A new resident"
            HouseholdArrivalType.YOUNG_COUPLE -> "A young couple"
            HouseholdArrivalType.COUPLE_WITH_CHILDREN -> "A family"
            HouseholdArrivalType.SINGLE_PARENT -> "A single parent and ${if (newResidents.size > 2) "children" else "child"}"
            HouseholdArrivalType.RETIRED_COUPLE -> "A retired couple"
            HouseholdArrivalType.MULTIGENERATIONAL -> "A multigenerational family"
            HouseholdArrivalType.ENTREPRENEUR_FAMILY -> "An entrepreneurial family"
            HouseholdArrivalType.PROFESSIONAL_HOUSEHOLD -> "A professional household"
            HouseholdArrivalType.STUDENT_HOUSEHOLD -> "Student residents"
            HouseholdArrivalType.DISPLACED_FAMILY -> "A displaced family"
        }
        val reasonLabel = when (arrivalReason) {
            ArrivalReason.JOB_OFFER -> "seeking work"
            ArrivalReason.AFFORDABILITY -> "drawn by affordable living"
            ArrivalReason.FAMILY_REUNIFICATION -> "joining family here"
            ArrivalReason.AVAILABLE_HOUSING -> "looking for a new home"
            ArrivalReason.RETIREMENT -> "settling into retirement"
            ArrivalReason.BUSINESS_OPPORTUNITY -> "ready to start a business"
            ArrivalReason.EDUCATION -> "pursuing education"
            ArrivalReason.SAFETY -> "looking for safety"
            ArrivalReason.HEALTHCARE -> "seeking better healthcare"
            ArrivalReason.RELATIONSHIP -> "following a partner"
            ArrivalReason.DISPLACEMENT -> "displaced from their previous home"
            ArrivalReason.GOVERNMENT_PROGRAMME -> "through a resettlement programme"
        }
        val e = ctx.emit(
            EventType.FAMILY_ARRIVED,
            "$typeLabel has moved to ${state.townName} -- $reasonLabel. (The $surname household: ${names.joinToString(", ")})",
            targetResidentIds = members,
            buildingId = hh.homeBuildingId,
            severity = 0.45,
            causeIds = listOfNotNull(causeEventId),
            payload = buildMap {
                put("householdId", hh.id.toString())
                put("arrivalType", arrivalType.name)
                put("reason", arrivalReason.name)
            }
        )
        ConsequenceEngine.onEvent(ctx, e)
    }

    // ---------------------------------------------------------------- outward migration

    /**
     * Monthly pass: for each in-town CONNECTED or DETAILED resident, calculate departure
     * pressure from unemployment, unaffordable housing, crime, business failure, life
     * satisfaction, and relationship factors. Capped at 3 departures per month.
     */
    private fun checkOutwardMigration(ctx: TickContext) {
        val state = ctx.state
        var departuresThisMonth = 0

        val candidates = state.residents.values.filter { r ->
            r.alive && r.leftTownAt == null &&
            r.detailLevel != DetailLevel.BACKGROUND
        }.sortedBy { it.id }

        for (resident in candidates) {
            if (departuresThisMonth >= 3) break

            var pressure = 0.0
            var primaryReason = DepartureReason.BETTER_OPPORTUNITIES

            // 1. Unemployed with no open jobs available in town
            if (resident.employmentId == null && resident.lifeStageAt(ctx.now) == LifeStage.ADULT) {
                val hasBeenUnemployed = resident.occupation == "Unemployed" || resident.occupation == "Newly arrived"
                val openJobExists = state.businesses.values.any { biz ->
                    biz.open && state.employeesOf(biz.id).size < biz.employeeCapacity
                }
                if (hasBeenUnemployed && !openJobExists) {
                    pressure += 0.25
                    primaryReason = DepartureReason.UNEMPLOYMENT
                } else if (hasBeenUnemployed) {
                    pressure += 0.08
                }
            }

            // 2. Wealth < 100 AND home rent > 40% of current wealth
            val household = resident.householdId?.let { state.households[it] }
            if (resident.wealth < 100.0 && household != null) {
                val monthlyRent = household.monthlyRent
                if (monthlyRent > resident.wealth * 0.4) {
                    pressure += 0.20
                    if (primaryReason == DepartureReason.BETTER_OPPORTUNITIES) {
                        primaryReason = DepartureReason.UNAFFORDABLE_HOUSING
                    }
                }
            }

            // 3. Home district crime rate > 0.6
            val homeBuilding = resident.homeBuildingId?.let { state.buildings[it] }
            val homeDistrict = homeBuilding?.districtId?.let { state.districts[it] }
            if (homeDistrict != null && homeDistrict.crimeRate > 0.6) {
                pressure += 0.15
                if (pressure >= 0.35 && primaryReason == DepartureReason.BETTER_OPPORTUNITIES) {
                    primaryReason = DepartureReason.CRIME
                }
            }

            // 4. Business they owned just closed within last 30 days
            val ownedClosedBusiness = state.businesses.values.firstOrNull { biz ->
                biz.ownerId == resident.id && !biz.open &&
                biz.closedAt != null &&
                (ctx.now - biz.closedAt!!) <= 30L * SimTime.MINUTES_PER_DAY
            }
            if (ownedClosedBusiness != null) {
                pressure += 0.20
                primaryReason = DepartureReason.BUSINESS_FAILURE
            }

            // 5. Low life satisfaction + social isolation + adult age > 25
            val age = resident.ageAt(ctx.now)
            if (resident.lifeSatisfaction.overall() < 20.0 && age > 25 &&
                resident.lifeStageAt(ctx.now) == LifeStage.ADULT) {
                val hasNoFamilyInTown = state.relationshipsOf(resident.id)
                    .none { rel ->
                        (rel.kind == RelationshipKind.FAMILY || rel.kind == RelationshipKind.SPOUSE) &&
                        state.resident(rel.other(resident.id))?.inTown == true
                    }
                val hasNoPartner = resident.partnerId == null
                if (hasNoFamilyInTown || hasNoPartner) {
                    pressure += 0.15
                }
            }

            // 6. Partner has left town
            val partner = resident.partnerId?.let { state.resident(it) }
            if (partner != null && partner.leftTownAt != null) {
                pressure += 0.30
                primaryReason = DepartureReason.RELATIONSHIP
            }

            if (pressure <= 0.0) continue

            val actualProb = pressure * 0.008
            if (ctx.rng.nextBoolean(actualProb)) {
                departResident(ctx, resident, primaryReason, household)
                departuresThisMonth++
            }
        }
    }

    /**
     * Remove a resident from town: set [Resident.leftTownAt], free home occupancy,
     * end employment, shrink household. Emits [EventType.FAMILY_DEPARTED].
     */
    private fun departResident(
        ctx: TickContext,
        resident: Resident,
        reason: DepartureReason,
        household: Household?
    ) {
        val state = ctx.state

        resident.leftTownAt = ctx.now
        resident.activity = Activity.IDLE
        resident.travelToBuildingId = null
        resident.plannedActivity = null

        resident.homeBuildingId?.let { bid ->
            val building = state.buildings[bid]
            if (building != null && resident.id !in building.tenantHistory) {
                building.tenantHistory += resident.id
                if (building.tenantHistory.size > com.ripple.town.core.model.Building.MAX_TENANT_HISTORY) {
                    building.tenantHistory.removeAt(0)
                }
            }
        }

        val emp = state.employmentOf(resident)
        if (emp != null) {
            emp.endedAt = ctx.now
            resident.employmentId = null
        }

        val hh = household ?: resident.householdId?.let { state.households[it] }
        hh?.memberIds?.remove(resident.id)

        val departingHouseholdId = hh?.id ?: resident.householdId
        if (hh != null && hh.memberIds.isEmpty() && departingHouseholdId != null) {
            val record = MigrationRecord(
                tick = ctx.now,
                householdId = departingHouseholdId,
                departureReason = reason,
                memberCount = 1,
                isArrival = false
            )
            if (state.migrationHistory.size >= 500) state.migrationHistory.removeAt(0)
            state.migrationHistory.add(record)
        }

        val reasonLabel = when (reason) {
            DepartureReason.UNEMPLOYMENT -> "unable to find work"
            DepartureReason.UNAFFORDABLE_HOUSING -> "unable to afford the cost of living"
            DepartureReason.CRIME -> "driven away by crime in the area"
            DepartureReason.BUSINESS_FAILURE -> "after their business closed"
            DepartureReason.BETTER_OPPORTUNITIES -> "seeking better opportunities elsewhere"
            DepartureReason.RELATIONSHIP -> "following their partner"
            DepartureReason.EDUCATION_ELSEWHERE -> "to pursue education elsewhere"
            DepartureReason.POOR_SERVICES -> "due to poor local services"
            DepartureReason.RETIREMENT_MIGRATION -> "retiring to a new place"
            DepartureReason.DISASTER -> "displaced by a disaster"
            DepartureReason.FAMILY_ELSEWHERE -> "to be with family elsewhere"
        }
        val e = ctx.emit(
            EventType.FAMILY_DEPARTED,
            "${resident.fullName} has left ${state.townName} -- $reasonLabel.",
            sourceResidentId = resident.id,
            buildingId = resident.homeBuildingId,
            severity = 0.35,
            payload = buildMap {
                departingHouseholdId?.let { put("householdId", it.toString()) }
                put("reason", reason.name)
            }
        )
        ConsequenceEngine.onEvent(ctx, e)
    }

    /** A `LEAVE_FOR_EDUCATION` leaver (see [GoalSystem.leaveForEducation]) comes home, changed. */
    fun studentReturns(ctx: TickContext, r: Resident) {
        val state = ctx.state
        if (!r.alive || r.leftTownAt == null) return
        r.leftTownAt = null
        r.occupation = "Unemployed"

        val oldHousehold = r.householdId?.let { state.households[it] }
        val targetHousehold = when {
            oldHousehold?.homeBuildingId != null -> oldHousehold
            else -> listOfNotNull(r.motherId, r.fatherId)
                .mapNotNull { state.resident(it) }
                .firstOrNull { it.inTown }
                ?.householdId?.let { state.households[it] }
                ?: run {
                    val home = state.homes().firstOrNull { h ->
                        state.households.values.none { it.homeBuildingId == h.id && it.memberIds.isNotEmpty() }
                    } ?: return@run null
                    Household(
                        id = state.nextHouseholdId++, name = "${r.surname} household",
                        homeBuildingId = home.id
                    ).also { state.households[it.id] = it }
                }
        }
        if (targetHousehold != null && r.id !in targetHousehold.memberIds) {
            oldHousehold?.memberIds?.remove(r.id)
            targetHousehold.memberIds += r.id
            r.householdId = targetHousehold.id
        }
        val home = targetHousehold?.homeBuildingId
        r.homeBuildingId = home
        r.currentBuildingId = home

        val gained = ctx.rng.nextDouble(20.0, 40.0)
        r.skills[SkillType.TEACHING] = (r.skill(SkillType.TEACHING) + gained).coerceAtMost(100.0)
        val secondary = secondarySkillFor(r)
        r.skills[secondary] = (r.skill(secondary) + ctx.rng.nextDouble(10.0, 25.0)).coerceAtMost(100.0)

        val e = ctx.emit(
            EventType.RESIDENT_ARRIVED,
            "${r.fullName} has come back to ${state.townName}, changed by years away studying.",
            sourceResidentId = r.id, buildingId = home, severity = 0.4
        )
        ctx.addMemory(r, MemoryType.ACHIEVEMENT, "Coming home to ${state.townName}, different than when I left.", 70.0, e.id)
        GoalSystem.seedGoal(ctx, r, GoalType.FIND_JOB, "Home again, and it's time to find my feet.", e.id)
        for (pid in listOfNotNull(r.motherId, r.fatherId)) {
            val parent = state.resident(pid) ?: continue
            if (!parent.inTown) continue
            parent.needs.social += 12.0
            val rel = state.relationshipOrCreate(parent.id, r.id)
            rel.affection += 10.0; rel.familiarity += 15.0
            rel.clampAll()
            ctx.addMemory(parent, MemoryType.ACHIEVEMENT, "${r.firstName} came home, grown up.", 70.0, e.id, listOf(r.id))
        }
        ConsequenceEngine.onEvent(ctx, e)
    }

    /** Which subject stuck: whichever trait a resident leans into hardest. */
    private fun secondarySkillFor(r: Resident): SkillType {
        val p = r.personality
        return listOf(
            p.ambition to SkillType.BUSINESS,
            p.curiosity to SkillType.CREATIVITY,
            p.empathy to SkillType.MEDICINE,
            p.courage to SkillType.POLITICS,
            p.discipline to SkillType.REPAIR,
            p.kindness to SkillType.COOKING
        ).maxByOrNull { it.first }?.second ?: SkillType.SOCIAL
    }

    // ------------------------------------------------------------- election

    private fun election(ctx: TickContext) {
        val state = ctx.state
        if (state.nextElectionAt <= 0 || ctx.now < state.nextElectionAt) return
        val candidates = state.detailedResidents()
            .filter { it.inTown && it.politicalInterest > 0.35 && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            .sortedByDescending { it.politicalInterest * 50 + it.reputation + it.skill(com.ripple.town.core.model.SkillType.POLITICS) }
            .take(3)
        if (candidates.isEmpty()) {
            state.nextElectionAt = ctx.now + 360L * SimTime.MINUTES_PER_DAY
            return
        }
        val votes = VotingSystem.tally(ctx, candidates, state.candidacies)
        val topVoteCount = votes.values.maxOrNull() ?: 0
        val topCandidates = candidates.filter { votes[it.id] == topVoteCount }
        val winner = if (topCandidates.size > 1) ctx.rng.pick(topCandidates) else topCandidates.first()
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
                if (m.importance < 40 && m.emotionalIntensity <= 5) it.remove()
            }
        }
    }

    const val RETURNING_STUDENT_NOTE = "returning_student"
}
