package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.Business
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.DelayedEffect
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.Goal
import com.ripple.town.core.model.GoalStatus
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType

/**
 * Compositional goal generation and progress. Goals form from *combinations*
 * of circumstance (need + skill + opportunity + memory), progress through the
 * decision system's WORK_ON_GOAL action, and resolve with real effects.
 */
object GoalSystem {

    fun seedGoal(ctx: TickContext, r: Resident, type: GoalType, motivation: String, causeEventId: Long? = null): Goal? {
        if (!r.inTown) return null
        if (r.goals.any { it.type == type && it.status == GoalStatus.ACTIVE }) return null
        if (r.goals.count { it.status == GoalStatus.ACTIVE } >= 3) return null
        val goal = Goal(
            id = ctx.state.nextGoalId++,
            ownerId = r.id,
            type = type,
            motivation = motivation,
            createdAt = ctx.now,
            risk = defaultRisk(type),
            causeEventId = causeEventId,
            targetSkill = DecisionSystem.relevantSkill(type)
        )
        r.goals += goal
        val e = ctx.emit(
            EventType.GOAL_FORMED,
            "${r.fullName} has a new ambition: ${type.label.lowercase()}.",
            sourceResidentId = r.id, severity = 0.2,
            visibility = com.ripple.town.core.model.EventVisibility.PRIVATE,
            causeIds = listOfNotNull(causeEventId)
        )
        return goal
    }

    /** Daily: form goals from circumstances, progress active ones, abandon hopeless ones. */
    fun updateDaily(ctx: TickContext) {
        for (r in ctx.state.detailedResidents().sortedBy { it.id }) {
            if (!r.inTown) continue
            generateFromCircumstance(ctx, r)
            progressGoals(ctx, r)
        }
    }

    private fun generateFromCircumstance(ctx: TickContext, r: Resident) {
        val state = ctx.state
        val stage = r.lifeStageAt(ctx.now)
        if (stage == LifeStage.CHILD) return
        val n = r.needs
        // Breathing space: after completing or abandoning a major life goal, residents don't
        // immediately leap into the next big thing.  FIND_JOB and PAY_OFF_DEBT are exempt —
        // financial pressure doesn't respect a grieving period.
        val inBreathingSpace = ctx.now < r.majorEventCooldownUntil

        // Need income + no job -> find one. Threshold nudged by childhood-to-adulthood influence:
        // a resident who grew up around real financial hardship reads "money is tight" a little
        // more urgently as an adult (see MemoryRecallSystem.childhoodInfluenceModifier — small,
        // bounded 0.9..1.1, 1.0/no-op when there's no matching childhood memory).
        val findJobThreshold = 45.0 * MemoryRecallSystem.childhoodInfluenceModifier(
            r, MemoryRecallSystem.ChildhoodSituation.FINANCIAL_HARDSHIP
        )
        if (state.employmentOf(r) == null && stage == LifeStage.ADULT && r.ageAt(ctx.now) < 66 &&
            n.financialSecurity < findJobThreshold
        ) {
            seedGoal(ctx, r, GoalType.FIND_JOB, "Money is tight and there's no wage coming in.")
        }

        // Unemployed + craft skill + a vacant building + (idea seed or good memory) -> start a business.
        // Per design (docs/simulation-rules.md#goals): "unemployed + carpentry > 55 + vacant granary +
        // idea seed + ambition" — gated on employment status, not on a financial-security threshold
        // (a resident can be unemployed with a comfortable cushion and still be the one who opens the shop).
        // Ambition bar nudged by childhood-to-adulthood influence: a resident who watched a family
        // business fail as a child needs a touch more ambition before taking the same leap
        // themselves (see MemoryRecallSystem.childhoodInfluenceModifier).
        val vacant = state.buildings.values.firstOrNull { it.type == BuildingType.VACANT && it.abandoned }
        val startBusinessAmbitionBar = 0.45 * MemoryRecallSystem.childhoodInfluenceModifier(
            r, MemoryRecallSystem.ChildhoodSituation.BUSINESS_FAILURE
        )
        if (!inBreathingSpace && vacant != null && stage == LifeStage.ADULT && state.employmentOf(r) == null &&
            (r.skill(SkillType.CARPENTRY) > 55 || r.skill(SkillType.COOKING) > 60 || r.skill(SkillType.BUSINESS) > 55) &&
            (r.ideaSeeds.isNotEmpty() || r.memories.any { it.type == MemoryType.INSPIRATION }) &&
            r.personality.ambition > startBusinessAmbitionBar
        ) {
            seedGoal(
                ctx, r, GoalType.START_BUSINESS,
                "The old granary stands empty, and those hands know a trade."
            )
        }

        // Lonely single adults look for company — but not right after losing someone or closing a business
        if (!inBreathingSpace && n.social < 30 && r.partnerId == null && stage == LifeStage.ADULT && r.personality.sociability > 0.35) {
            seedGoal(ctx, r, GoalType.FIND_PARTNER, "The evenings have grown very quiet.")
        }

        // Heavy debt
        if (r.debt > 800 && r.goals.none { it.type == GoalType.PAY_OFF_DEBT && it.status == GoalStatus.ACTIVE }) {
            seedGoal(ctx, r, GoalType.PAY_OFF_DEBT, "The letters from the lender keep coming.")
        }

        // Curious, ambitious teens think about education elsewhere
        if (stage == LifeStage.TEEN && r.personality.curiosity > 0.7 && r.personality.ambition > 0.6) {
            seedGoal(ctx, r, GoalType.LEAVE_FOR_EDUCATION, "There's a whole world past the valley.")
        }

        // Politically interested adults near an election
        if (state.nextElectionAt in (ctx.now..(ctx.now + 120 * SimTime.MINUTES_PER_DAY)) &&
            r.politicalInterest > 0.5 && stage == LifeStage.ADULT && r.id != state.mayorId
        ) {
            seedGoal(ctx, r, GoalType.RUN_FOR_OFFICE, "Somebody has to speak for this town.")
        }

        // Damaged relationship + open personality -> want to make amends
        if (stage == LifeStage.ADULT && r.personality.kindness > 0.35) {
            val damaged = state.relationshipsOf(r.id).firstOrNull { it.affection < 15.0 && it.familiarity > 30.0 }
            if (damaged != null) {
                seedGoal(ctx, r, GoalType.REPAIR_RELATIONSHIP, "Something was left unresolved between us.")
            }
        }

        // Approaching old age -> retire gracefully
        if (r.ageAt(ctx.now) >= 65 && stage == LifeStage.ADULT && state.employmentOf(r) != null) {
            seedGoal(ctx, r, GoalType.RETIRE_WELL, "There's less road left than there was.")
        }

        // Persistent despair drives some adults to leave entirely:
        //   - Long-term unemployed + very low wealth + can't see a path
        //   - OR high-crime district + high stress + low safety + a friend already left
        if (stage == LifeStage.ADULT && r.inTown) {
            val longUnemployed = state.employmentOf(r) == null &&
                r.ageAt(ctx.now) in 20..65 && r.wealth < 300.0 && n.financialSecurity < 20.0
            val districtCrime = r.homeBuildingId?.let { state.building(it) }
                ?.let { state.districtAt(it.origin.x, it.origin.y)?.crimeRate } ?: 0.5
            val crimeDisplaced = districtCrime > 0.75 && n.stress > 75.0 && n.safety < 25.0
            if ((longUnemployed || crimeDisplaced) && ctx.rng.nextBoolean(0.015)) {
                val reason = if (longUnemployed) "There's nothing left here for me."
                             else "It's not safe anymore. I have to go."
                seedGoal(ctx, r, GoalType.LEAVE_TOWN, reason)
            }
        }
    }

    private fun progressGoals(ctx: TickContext, r: Resident) {
        val state = ctx.state
        for (goal in r.goals.filter { it.status == GoalStatus.ACTIVE }) {
            when (goal.type) {
                GoalType.FIND_JOB -> {
                    goal.progress += 0.05
                    // Actively apply: any open business with room and money.
                    val opening = state.businesses.values
                        .filter { it.open && state.employeesOf(it.id).size < it.employeeCapacity &&
                            (it.balance > 1_000 || it.type in EconomySystem.PUBLIC_SERVICES) }
                        .minByOrNull { it.id }
                    if (opening != null && ctx.rng.nextBoolean(0.2 + r.skill(SkillType.SOCIAL) / 500.0)) {
                        EconomySystem.hire(ctx, r, opening, EconomySystem.roleFor(opening.type), listOfNotNull(goal.causeEventId))
                    }
                }
                GoalType.START_BUSINESS -> {
                    // Saving up and planning; needs capital before opening.
                    goal.progress += 0.02 + r.skill(SkillType.BUSINESS) / 2_500.0
                    if (goal.progress >= 1.0 && r.wealth >= STARTUP_CAPITAL) {
                        openBusiness(ctx, r, goal)
                    } else if (goal.progress >= 1.0) {
                        goal.progress = 0.95 // ready, waiting on money
                    }
                }
                GoalType.PAY_OFF_DEBT -> {
                    goal.progress = if (r.debt <= 0) 1.0 else (1.0 / (1.0 + r.debt / 500.0))
                    if (r.debt <= 0) complete(ctx, r, goal, "Debt cleared")
                }
                GoalType.FIND_PARTNER -> {
                    goal.progress += 0.03
                    if (r.partnerId != null) complete(ctx, r, goal, "Found someone")
                    if (goal.progress > 1.2) abandon(ctx, r, goal, "It never quite happened")
                }
                GoalType.GET_HEALTHY -> {
                    if (r.activeConditions().none { it.type.serious }) complete(ctx, r, goal, "Health restored")
                }
                GoalType.LEAVE_FOR_EDUCATION -> {
                    goal.progress += 0.008
                    if (r.ageAt(ctx.now) >= 18 && goal.progress >= 0.8) {
                        leaveForEducation(ctx, r, goal)
                    }
                }
                GoalType.MOVE_HOME -> {
                    val newHome = state.homes().firstOrNull { h ->
                        h.id != r.homeBuildingId && !h.abandoned &&
                            state.households.values.none { it.homeBuildingId == h.id && it.memberIds.isNotEmpty() }
                    }
                    if (newHome != null) {
                        moveHome(ctx, r, newHome.id, goal)
                    } else {
                        goal.progress += 0.05
                        if (goal.progress > 1.5) abandon(ctx, r, goal, "Nowhere suitable came free")
                    }
                }
                GoalType.RUN_FOR_OFFICE -> {
                    goal.progress += 0.02
                    r.skills[SkillType.POLITICS] = (r.skill(SkillType.POLITICS) + 0.2).coerceAtMost(100.0)
                    if (state.nextElectionAt < ctx.now) complete(ctx, r, goal, "The election came")
                }
                GoalType.LEARN_SKILL -> {
                    val skill = goal.targetSkill ?: SkillType.CREATIVITY
                    goal.progress = r.skill(skill) / 80.0
                    if (goal.progress >= 1.0) {
                        complete(ctx, r, goal, "Skill mastered")
                        ctx.emit(
                            EventType.SKILL_MILESTONE,
                            "${r.fullName} has become genuinely good at ${skill.label.lowercase()}.",
                            sourceResidentId = r.id, severity = 0.2
                        )
                    }
                }
                GoalType.REPAIR_RELATIONSHIP -> {
                    // Find the most damaged relationship; progress tracks how much it's healed.
                    val worstRel = state.relationshipsOf(r.id)
                        .filter { it.familiarity > 20.0 }
                        .minByOrNull { it.affection }
                    if (worstRel == null) {
                        complete(ctx, r, goal, "The rifts have already healed")
                    } else {
                        goal.progress = (worstRel.affection / 60.0).coerceIn(0.0, 1.0)
                        if (goal.progress >= 1.0) {
                            r.needs.social = (r.needs.social + 5.0).coerceAtMost(100.0)
                            ctx.addMemory(r, MemoryType.KINDNESS_GIVEN,
                                "Made things right with someone I'd hurt.",
                                intensity = 35.0, associated = listOf(worstRel.other(r.id)))
                            complete(ctx, r, goal, "The air between us cleared")
                        }
                    }
                }
                GoalType.RETIRE_WELL -> {
                    val notWorking = state.employmentOf(r) == null
                    val comfortable = r.needs.comfort > 60.0 && r.needs.financialSecurity > 55.0
                    goal.progress = ((r.needs.comfort + r.needs.financialSecurity) / 200.0).coerceIn(goal.progress, 1.0)
                    if (notWorking && comfortable) {
                        r.needs.comfort = (r.needs.comfort + 8.0).coerceAtMost(100.0)
                        r.needs.purpose = (r.needs.purpose + 5.0).coerceAtMost(100.0)
                        ctx.addMemory(r, MemoryType.ACHIEVEMENT,
                            "Found peace in the later years.",
                            intensity = 45.0)
                        complete(ctx, r, goal, "Settled into a quieter life")
                    }
                }
                GoalType.LEAVE_TOWN -> {
                    goal.progress += 0.015
                    if (goal.progress >= 1.0) leaveForGood(ctx, r, goal)
                }
            }
            // Abandonment under despair
            if (goal.status == GoalStatus.ACTIVE && r.needs.stress > 90 && ctx.rng.nextBoolean(0.05)) {
                abandon(ctx, r, goal, "Everything else crowded it out")
            }
        }
    }

    /** Candidate business types a fresh `START_BUSINESS` opening will consider, in preference
     *  order — WORKSHOP first (the pre-existing default, and always gate-viable per
     *  `EconomySystem.estimateFormationViability`'s contract-shaped carve-out), then the everyday
     *  retail sectors a resident with a craft/cooking/business skill could plausibly run. Economy
     *  Calibration Gate Phase 2 (2026-07-11) — see docs/simulation-rules.md "Business formation
     *  gate". */
    private val FORMATION_CANDIDATE_TYPES = listOf(
        BusinessType.WORKSHOP, BusinessType.CAFE, BusinessType.BAKERY, BusinessType.GROCER,
        BusinessType.HARDWARE, BusinessType.BOOKSHOP, BusinessType.TAILOR
    )

    /**
     * Business formation gate (Economy Calibration Gate Phase 2, 2026-07-11) — see
     * docs/simulation-rules.md "Business formation gate". Before this pass, ANY vacant building
     * was opened as a WORKSHOP the moment a resident's `START_BUSINESS` goal completed, with no
     * check on whether projected demand could actually support it. Now: try every vacant building
     * against every candidate type (via `EconomySystem.estimateFormationViability`, which reads
     * real catchment demand, local competition and a lean owner-only break-even), and open the
     * FIRST genuinely viable building/type combination found. If none are viable, the goal is not
     * silently lost — it stays active at a high-but-not-complete progress so the resident keeps
     * trying (a real retry, matching the pre-existing "ready, waiting on money" pattern one branch
     * up in `progressGoals`) rather than either opening a doomed business or abandoning the dream
     * outright.
     */
    private fun openBusiness(ctx: TickContext, r: Resident, goal: Goal) {
        val state = ctx.state
        val vacants = state.buildings.values.filter { it.type == BuildingType.VACANT && it.abandoned }.sortedBy { it.id }
        if (vacants.isEmpty()) {
            goal.progress = 0.95 // nowhere to open — keep the ambition alive, don't abandon it
            return
        }

        var chosenBuilding: com.ripple.town.core.model.Building? = null
        var chosenType: BusinessType? = null
        for (building in vacants) {
            for (type in FORMATION_CANDIDATE_TYPES) {
                val viability = EconomySystem.estimateFormationViability(ctx, building, type, STARTUP_CAPITAL)
                if (viability.viable) {
                    chosenBuilding = building
                    chosenType = type
                    break
                }
            }
            if (chosenBuilding != null) break
        }

        if (chosenBuilding == null || chosenType == null) {
            // No viable building/type combination anywhere in town right now — a real rejection,
            // not a silent no-op: the resident keeps the ambition (goal stays active, same "ready,
            // waiting" holding pattern the pre-existing capital-shortfall branch already uses) and
            // will re-check on a future day once demand/competition/vacancy has shifted.
            goal.progress = 0.95
            return
        }

        val building = chosenBuilding
        val type = chosenType
        building.type = when (type) {
            BusinessType.WORKSHOP -> BuildingType.WORKSHOP
            BusinessType.CAFE -> BuildingType.CAFE
            BusinessType.BAKERY -> BuildingType.BAKERY
            BusinessType.GROCER -> BuildingType.GROCER
            BusinessType.HARDWARE -> BuildingType.HARDWARE
            BusinessType.BOOKSHOP -> BuildingType.BOOKSHOP
            BusinessType.TAILOR -> BuildingType.TAILOR
            else -> BuildingType.WORKSHOP
        }
        building.abandoned = false
        building.ownerId = r.id
        building.condition = 70.0
        building.visibleChanges += "New sign painted, windows cleaned"
        r.wealth -= STARTUP_CAPITAL
        val name = "${r.surname}'s ${type.label}"
        val biz = Business(
            id = state.nextBusinessId++,
            buildingId = building.id,
            name = name,
            type = type,
            ownerId = r.id,
            // Was STARTUP_CAPITAL * 0.6 (240) against ~75/day overhead+owner-salary once staffed
            // (~3.2 days runway) and a below-reputation starting demand of 35 — the calibration
            // audit (docs/backlog.md, 2026-07-11 "Economy calibration audit" entry) found this
            // pairing was the strongest supported driver of a 66.7% one-year business closure
            // rate, consistent across 10 independently-seeded towns. Full STARTUP_CAPITAL as
            // opening balance (~5.3 days runway) and demand at parity with starting reputation
            // (no double penalty for being new) directly target the two candidates that audit
            // flagged, without touching CLOSURE_DAYS or any wage/living-cost constant it found
            // were NOT structurally broken.
            balance = STARTUP_CAPITAL,
            demand = 45.0,
            reputation = 45.0,
            // Staffing ramp (Economy Calibration Gate Phase 2, 2026-07-11): lean, owner-only start
            // — was already the case before this pass (only the owner is hired below), but
            // `employeeCapacity` is now also trimmed to 1 at opening (was 2) so the business can't
            // even hire a second person until it EXPANDS (see `EconomySystem.expandBusiness`,
            // which raises capacity by 1 as a real, earned outcome of sustained trade) — matching
            // the brief's "zero or one employee" starting shape literally, not just in practice.
            employeeCapacity = 1,
            openedAt = ctx.now
        )
        state.businesses[biz.id] = biz
        val emp = com.ripple.town.core.model.Employment(
            id = state.nextEmploymentId++, residentId = r.id, businessId = biz.id,
            role = "Owner", dailySalary = EconomySystem.salaryFor(type).coerceAtMost(45.0), startedAt = ctx.now
        )
        state.employments[emp.id] = emp
        r.employmentId = emp.id
        // WORKSHOP keeps the pre-existing "Workshop owner" wording (BuildingType.WORKSHOP's own
        // label, "Workshop" — BusinessType.WORKSHOP's label is the more specific "Furniture
        // workshop", used for the business/shop NAME above but not this occupation string, to
        // avoid changing this common-case default's wording as a side effect of the Phase 2
        // formation-gate work).
        r.occupation = if (type == BusinessType.WORKSHOP) "Workshop owner" else "${type.label} owner"
        r.needs.purpose += 25.0
        complete(ctx, r, goal, "The doors are open")
        val dayStart = ctx.now - SimTime.minuteOfDay(ctx.now)
        val openTime = HumanScheduler.realisticTimeToday(ScheduledActivity.BUSINESS_OPEN, dayStart, ctx.rng)
        HumanScheduler.recordFired(ScheduledActivity.BUSINESS_OPEN, ctx.now, ctx.state.activityCooldowns)
        val e = ctx.emit(
            EventType.BUSINESS_OPENED,
            "$name has opened in the old granary.",
            sourceResidentId = r.id, businessId = biz.id, buildingId = building.id,
            severity = 0.6, causeIds = listOfNotNull(goal.causeEventId),
            atTime = openTime
        )
        ctx.addMemory(r, MemoryType.ACHIEVEMENT, "Turning the key on my own ${type.label.lowercase()}.", 85.0, e.id)
        ConsequenceEngine.onEvent(ctx, e)
    }

    private fun leaveForEducation(ctx: TickContext, r: Resident, goal: Goal) {
        r.leftTownAt = ctx.now
        r.occupation = "Away studying"
        r.currentBuildingId = null
        r.travelToBuildingId = null
        complete(ctx, r, goal, "Left for college")
        val e = ctx.emit(
            EventType.RESIDENT_LEFT_TOWN,
            "${r.fullName} has left ${ctx.state.townName} to study.",
            sourceResidentId = r.id, severity = 0.5,
            causeIds = listOfNotNull(goal.causeEventId)
        )
        // Parents feel it.
        for (pid in listOfNotNull(r.motherId, r.fatherId)) {
            val parent = ctx.state.resident(pid) ?: continue
            parent.needs.social -= 10.0
            ctx.addMemory(parent, MemoryType.LOSS, "${r.firstName} left for college. The house feels bigger.", 60.0, e.id, listOf(r.id))
        }
        ConsequenceEngine.onEvent(ctx, e)
        // They don't vanish from the town's story — they come back, eventually, changed.
        val day = SimTime.MINUTES_PER_DAY
        ctx.state.delayedEffects += DelayedEffect(
            id = ctx.state.nextEffectId++, sourceEventId = e.id,
            targetResidentId = r.id, type = DelayedEffectType.GOAL_SEED, strength = 1.0,
            earliestAt = ctx.now + 640 * day, latestAt = ctx.now + 1400 * day,
            note = LifecycleSystem.RETURNING_STUDENT_NOTE
        )
    }

    private fun leaveForGood(ctx: TickContext, r: Resident, goal: Goal) {
        val state = ctx.state
        // Vacate home slot.
        val hh = r.householdId?.let { state.households[it] }
        hh?.memberIds?.remove(r.id)
        if (hh != null && hh.memberIds.isEmpty()) state.households.remove(hh.id)
        r.householdId = null
        r.homeBuildingId = null
        // End employment.
        state.employmentOf(r)?.let { emp ->
            emp.endedAt = ctx.now
            r.employmentId = null
            r.occupation = ""
        }
        r.currentBuildingId = null
        r.travelToBuildingId = null
        r.leftTownAt = ctx.now
        complete(ctx, r, goal, "Left for good")
        val e = ctx.emit(
            EventType.RESIDENT_LEFT_TOWN,
            "${r.fullName} has left ${state.townName} and is not coming back.",
            sourceResidentId = r.id, severity = 0.55,
            causeIds = listOfNotNull(goal.causeEventId)
        )
        for (pid in listOfNotNull(r.motherId, r.fatherId)) {
            val parent = state.resident(pid) ?: continue
            if (parent.inTown) {
                parent.needs.social -= 12.0
                ctx.addMemory(parent, MemoryType.LOSS, "${r.firstName} has left town. I don't know when we'll next meet.", 70.0, e.id, listOf(r.id))
            }
        }
        ConsequenceEngine.onEvent(ctx, e)
    }

    private fun moveHome(ctx: TickContext, r: Resident, newHomeId: Long, goal: Goal) {
        val state = ctx.state
        val old = r.householdId?.let { state.households[it] }
        old?.memberIds?.remove(r.id)
        val hh = com.ripple.town.core.model.Household(
            id = state.nextHouseholdId++,
            name = "${r.surname} household",
            homeBuildingId = newHomeId,
            monthlyRent = 260.0
        )
        hh.memberIds += r.id
        state.households[hh.id] = hh
        r.householdId = hh.id
        r.homeBuildingId = newHomeId
        complete(ctx, r, goal, "Moved out")
        val e = ctx.emit(
            EventType.RESIDENT_MOVED,
            "${r.fullName} has moved to ${state.building(newHomeId)?.name ?: "a new house"}.",
            sourceResidentId = r.id, buildingId = newHomeId, severity = 0.3,
            causeIds = listOfNotNull(goal.causeEventId)
        )
        ConsequenceEngine.onEvent(ctx, e)
    }

    private fun complete(ctx: TickContext, r: Resident, goal: Goal, note: String) {
        goal.status = GoalStatus.COMPLETED
        goal.resolvedAt = ctx.now
        goal.progress = 1.0
        val cooldownDays = when (goal.type) {
            GoalType.START_BUSINESS -> 60L
            GoalType.FIND_PARTNER, GoalType.MOVE_HOME -> 30L
            else -> 0L
        }
        if (cooldownDays > 0) {
            r.majorEventCooldownUntil = maxOf(r.majorEventCooldownUntil, ctx.now + cooldownDays * SimTime.MINUTES_PER_DAY)
        }
        ctx.emit(
            EventType.GOAL_COMPLETED,
            "${r.fullName}: ${goal.type.label.lowercase()} — $note.",
            sourceResidentId = r.id, severity = 0.25,
            visibility = com.ripple.town.core.model.EventVisibility.PRIVATE,
            causeIds = listOfNotNull(goal.causeEventId)
        )
    }

    private fun abandon(ctx: TickContext, r: Resident, goal: Goal, note: String) {
        goal.status = GoalStatus.ABANDONED
        goal.resolvedAt = ctx.now
        val cooldownDays = when (goal.type) {
            GoalType.START_BUSINESS, GoalType.LEAVE_FOR_EDUCATION -> 30L
            GoalType.FIND_PARTNER -> 20L
            else -> 0L
        }
        if (cooldownDays > 0) {
            r.majorEventCooldownUntil = maxOf(r.majorEventCooldownUntil, ctx.now + cooldownDays * SimTime.MINUTES_PER_DAY)
        }
        ctx.emit(
            EventType.GOAL_ABANDONED,
            "${r.fullName} has quietly let go of a plan: ${goal.type.label.lowercase()}. $note.",
            sourceResidentId = r.id, severity = 0.15,
            visibility = com.ripple.town.core.model.EventVisibility.PRIVATE
        )
    }

    private fun defaultRisk(type: GoalType): Double = when (type) {
        GoalType.START_BUSINESS -> 0.5
        GoalType.LEAVE_FOR_EDUCATION -> 0.35
        GoalType.RUN_FOR_OFFICE -> 0.4
        GoalType.MOVE_HOME -> 0.3
        else -> 0.2
    }

    const val STARTUP_CAPITAL = 400.0
}
