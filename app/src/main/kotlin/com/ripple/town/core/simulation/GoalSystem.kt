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

        // Need income + no job -> find one
        if (state.employmentOf(r) == null && stage == LifeStage.ADULT && r.ageAt(ctx.now) < 66 &&
            n.financialSecurity < 45
        ) {
            seedGoal(ctx, r, GoalType.FIND_JOB, "Money is tight and there's no wage coming in.")
        }

        // Need income + craft skill + a vacant building + (idea seed or good memory) -> start a business
        val vacant = state.buildings.values.firstOrNull { it.type == BuildingType.VACANT && it.abandoned }
        if (vacant != null && stage == LifeStage.ADULT &&
            (r.skill(SkillType.CARPENTRY) > 55 || r.skill(SkillType.COOKING) > 60 || r.skill(SkillType.BUSINESS) > 55) &&
            (r.ideaSeeds.isNotEmpty() || r.memories.any { it.type == MemoryType.INSPIRATION }) &&
            r.personality.ambition > 0.45 && n.financialSecurity < 60
        ) {
            seedGoal(
                ctx, r, GoalType.START_BUSINESS,
                "The old granary stands empty, and those hands know a trade."
            )
        }

        // Lonely single adults look for company
        if (n.social < 30 && r.partnerId == null && stage == LifeStage.ADULT && r.personality.sociability > 0.35) {
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
                GoalType.REPAIR_RELATIONSHIP, GoalType.RETIRE_WELL -> {
                    goal.progress += 0.02
                    if (goal.progress >= 1.0) complete(ctx, r, goal, "Done quietly")
                }
            }
            // Abandonment under despair
            if (goal.status == GoalStatus.ACTIVE && r.needs.stress > 90 && ctx.rng.nextBoolean(0.05)) {
                abandon(ctx, r, goal, "Everything else crowded it out")
            }
        }
    }

    private fun openBusiness(ctx: TickContext, r: Resident, goal: Goal) {
        val state = ctx.state
        val building = state.buildings.values.firstOrNull { it.type == BuildingType.VACANT && it.abandoned }
            ?: return
        building.type = BuildingType.WORKSHOP
        building.abandoned = false
        building.ownerId = r.id
        building.condition = 70.0
        building.visibleChanges += "New sign painted, windows cleaned"
        r.wealth -= STARTUP_CAPITAL
        val name = "${r.surname}'s Workshop"
        val biz = Business(
            id = state.nextBusinessId++,
            buildingId = building.id,
            name = name,
            type = BusinessType.WORKSHOP,
            ownerId = r.id,
            balance = STARTUP_CAPITAL * 0.6,
            demand = 35.0,
            reputation = 45.0,
            employeeCapacity = 2,
            openedAt = ctx.now
        )
        state.businesses[biz.id] = biz
        val emp = com.ripple.town.core.model.Employment(
            id = state.nextEmploymentId++, residentId = r.id, businessId = biz.id,
            role = "Owner", dailySalary = 45.0, startedAt = ctx.now
        )
        state.employments[emp.id] = emp
        r.employmentId = emp.id
        r.occupation = "Workshop owner"
        r.needs.purpose += 25.0
        complete(ctx, r, goal, "The doors are open")
        val e = ctx.emit(
            EventType.BUSINESS_OPENED,
            "$name has opened in the old granary.",
            sourceResidentId = r.id, businessId = biz.id, buildingId = building.id,
            severity = 0.6, causeIds = listOfNotNull(goal.causeEventId)
        )
        ctx.addMemory(r, MemoryType.ACHIEVEMENT, "Turning the key on my own workshop.", 85.0, e.id)
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
