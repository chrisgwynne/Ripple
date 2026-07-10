package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.GoalStatus
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.model.isPublicSpace

/**
 * One concrete thing a resident could do next, with the utility terms that
 * scored it. The terms are kept so the UI can answer "Why this action?".
 */
data class ScoredAction(
    val kind: ActionKind,
    val targetBuildingId: Long?,
    val targetResidentId: Long? = null,
    val activity: Activity,
    val durationMinutes: Long,
    val reason: String,
    val needPressure: Double,
    val personalityFit: Double,
    val expectedReward: Double,
    val confidence: Double,
    val socialInfluence: Double,
    val opportunity: Double,
    val risk: Double,
    val cost: Double,
    val effort: Double,
    val moralResistance: Double
) {
    val score: Double =
        (needPressure * personalityFit * expectedReward * confidence * socialInfluence * opportunity) -
            risk - cost - effort - moralResistance
}

enum class ActionKind {
    EAT_HOME, EAT_OUT, SLEEP, GO_TO_WORK, GO_TO_SCHOOL, VISIT_FRIEND, SOCIALISE_PUBLIC,
    SHOP, EXERCISE, LEARN_SKILL, RELAX_HOME, REST_ILL, VISIT_CLINIC, WORK_ON_GOAL, WANDER
}

object DecisionSystem {

    /** Evaluate residents whose current activity has finished and choose their next action. */
    fun update(ctx: TickContext) {
        for (r in ctx.state.residentsOrdered()) {
            if (!r.inTown || r.detailLevel != DetailLevel.DETAILED) continue
            if (r.activity == Activity.TRAVELLING) continue
            if (ctx.now < r.activityEndsAt) continue
            decide(ctx, r)
        }
    }

    fun decide(ctx: TickContext, r: Resident) {
        val actions = candidateActions(ctx.state, r, ctx.now)
        if (actions.isEmpty()) {
            ctx.beginActivity(r, Activity.IDLE, 30, "Nothing pressing")
            return
        }
        val best = chooseBest(actions, ctx.rng)
        execute(ctx, r, best)
    }

    /**
     * Deterministic choice: highest score wins; randomness only breaks near-ties
     * (within 5% of the best score) and never drives a major decision alone.
     */
    fun chooseBest(actions: List<ScoredAction>, rng: SimRandom): ScoredAction {
        val sorted = actions.sortedByDescending { it.score }
        val best = sorted.first()
        val nearTies = sorted.filter { it.score >= best.score * 0.95 && best.score > 0 }
        return if (nearTies.size > 1) nearTies[rng.nextInt(nearTies.size)] else best
    }

    fun candidateActions(state: WorldState, r: Resident, now: Long): List<ScoredAction> {
        val out = mutableListOf<ScoredAction>()
        val n = r.needs
        val p = r.personality
        val stage = r.lifeStageAt(now)
        val hour = SimTime.hourOfDay(now)
        val weekend = SimTime.dayOfWeek(now) >= 5
        val home = r.homeBuildingId
        val employment = state.employmentOf(r)
        val poor = r.wealth < 60.0

        fun pressure(v: Double) = ((100.0 - v) / 100.0).coerceIn(0.0, 1.0)

        // --- Sleep
        val sleepy = pressure(n.energy)
        val night = hour >= 22 || hour < 6
        if (home != null && (sleepy > 0.55 || night)) {
            out += ScoredAction(
                ActionKind.SLEEP, home, null, Activity.SLEEPING,
                durationMinutes = if (night) (7 * 60L) else 90L,
                reason = if (night) "It's late and the day is done" else "Running on empty",
                needPressure = (sleepy + if (night) 0.7 else 0.0).coerceIn(0.05, 1.6),
                personalityFit = 1.0, expectedReward = 1.2, confidence = 1.0,
                socialInfluence = 1.0, opportunity = 1.0,
                risk = 0.0, cost = 0.0, effort = 0.02, moralResistance = 0.0
            )
        }

        // --- Eat
        val hungry = pressure(n.hunger)
        if (hungry > 0.35) {
            if (home != null) {
                out += ScoredAction(
                    ActionKind.EAT_HOME, home, null, Activity.EATING, 40L,
                    "Hungry — there's food at home",
                    needPressure = hungry * 1.3, personalityFit = 1.0,
                    expectedReward = 1.0, confidence = 1.0, socialInfluence = 1.0,
                    opportunity = 1.0, risk = 0.0, cost = 0.02, effort = 0.05, moralResistance = 0.0
                )
            }
            val eatery = openEatery(state, hour)
            if (eatery != null && !poor && stage != LifeStage.CHILD) {
                out += ScoredAction(
                    ActionKind.EAT_OUT, state.businesses[eatery]!!.buildingId, null, Activity.EATING, 50L,
                    "Hungry — a bite at ${state.businesses[eatery]!!.name}",
                    needPressure = hungry * 1.15 + pressure(n.social) * 0.3,
                    personalityFit = 0.7 + p.sociability * 0.6,
                    expectedReward = 1.05, confidence = 1.0, socialInfluence = 1.0,
                    opportunity = 1.0, risk = 0.0,
                    cost = 0.12 + if (r.wealth < 200) 0.25 else 0.0,
                    effort = 0.1, moralResistance = 0.0
                )
            }
        }

        // --- Work
        if (employment != null && !weekend && stage != LifeStage.CHILD) {
            val biz = state.businesses[employment.businessId]
            val withinShift = hour >= employment.shiftStartHour && hour < employment.shiftEndHour
            if (biz != null && biz.open && withinShift && r.activity != Activity.WORKING) {
                val minutesLeft = (employment.shiftEndHour - hour).toLong() * 60L
                out += ScoredAction(
                    ActionKind.GO_TO_WORK, biz.buildingId, null, Activity.WORKING, minutesLeft,
                    "Shift at ${biz.name}",
                    needPressure = 0.85 + pressure(n.financialSecurity) * 0.5,
                    personalityFit = 0.75 + p.discipline * 0.5,
                    expectedReward = 1.15, confidence = 1.0, socialInfluence = 1.0, opportunity = 1.0,
                    risk = 0.0, cost = 0.0,
                    effort = 0.1 + (pressure(n.energy) * 0.25) + (pressure(n.health) * 0.3),
                    moralResistance = 0.0
                )
            }
        }

        // --- School for children and teens on weekdays
        if ((stage == LifeStage.CHILD || stage == LifeStage.TEEN) && !weekend && hour in 8..14) {
            val school = state.buildings.values.firstOrNull { it.type == BuildingType.SCHOOL }
            if (school != null && r.activity != Activity.AT_SCHOOL) {
                out += ScoredAction(
                    ActionKind.GO_TO_SCHOOL, school.id, null, Activity.AT_SCHOOL,
                    ((15 - hour).coerceAtLeast(1)).toLong() * 60L,
                    "School day",
                    needPressure = 1.1, personalityFit = 0.8 + p.discipline * 0.3,
                    expectedReward = 1.0, confidence = 1.0, socialInfluence = 1.0, opportunity = 1.0,
                    risk = 0.0, cost = 0.0, effort = 0.15, moralResistance = 0.0
                )
            }
        }

        // --- Rest / clinic when unwell
        val conditions = r.activeConditions()
        if (conditions.isNotEmpty()) {
            val worst = conditions.maxOf { it.severity }
            if (home != null) {
                out += ScoredAction(
                    ActionKind.REST_ILL, home, null, Activity.RESTING_ILL, 4 * 60L,
                    "Feeling unwell — best to rest",
                    needPressure = (worst / 100.0) * 1.4 + pressure(n.health) * 0.5,
                    personalityFit = 0.7 + p.patience * 0.4,
                    expectedReward = 1.0, confidence = 1.0, socialInfluence = 1.0, opportunity = 1.0,
                    risk = 0.0, cost = 0.0, effort = 0.02,
                    moralResistance = if (state.employmentOf(r) != null) 0.15 else 0.0
                )
            }
            val clinic = state.buildings.values.firstOrNull { it.type == BuildingType.CLINIC }
            val knowsIll = conditions.any { !it.hidden }
            val warned = r.awareness.contains("health_risk")
            if (clinic != null && (knowsIll || warned) && hour in 8..17) {
                out += ScoredAction(
                    ActionKind.VISIT_CLINIC, clinic.id, null, Activity.AT_CLINIC, 90L,
                    if (warned && !knowsIll) "That warning has been playing on their mind"
                    else "This isn't shifting on its own — clinic time",
                    needPressure = (worst / 100.0) * 1.5 + (if (warned) 0.5 else 0.0),
                    personalityFit = 0.6 + p.discipline * 0.3 + (1.0 - p.courage) * 0.2,
                    expectedReward = 1.2, confidence = 0.9, socialInfluence = 1.0, opportunity = 1.0,
                    risk = 0.05, cost = 0.08, effort = 0.15,
                    moralResistance = 0.0
                )
            }
        }

        // --- Socialising
        val lonely = pressure(n.social)
        if (lonely > 0.3 && stage != LifeStage.CHILD) {
            val friend = bestFriendToVisit(state, r)
            if (friend != null) {
                val friendHome = friend.homeBuildingId
                if (friendHome != null) {
                    out += ScoredAction(
                        ActionKind.VISIT_FRIEND, friendHome, friend.id, Activity.VISITING, 90L,
                        "Calling on ${friend.firstName}",
                        needPressure = lonely * 1.2,
                        personalityFit = 0.5 + p.sociability * 0.8,
                        expectedReward = 0.8 + (state.relationship(r.id, friend.id)?.warmth() ?: 0.0) / 100.0,
                        confidence = 0.9, socialInfluence = 1.1, opportunity = 1.0,
                        risk = 0.02, cost = 0.02, effort = 0.15, moralResistance = 0.0
                    )
                }
            }
            val spot = openPublicSpot(state, hour)
            if (spot != null) {
                out += ScoredAction(
                    ActionKind.SOCIALISE_PUBLIC, spot.id, null, Activity.SOCIALISING, 80L,
                    "Some company at ${spot.name}",
                    needPressure = lonely,
                    personalityFit = 0.4 + p.sociability * 0.9,
                    expectedReward = 0.9, confidence = 0.9, socialInfluence = 1.15, opportunity = 1.0,
                    risk = 0.02, cost = if (spot.type == BuildingType.PARK) 0.0 else 0.1,
                    effort = 0.12, moralResistance = 0.0
                )
            }
        }

        // --- Shopping
        if (n.comfort < 55 && !poor && hour in 9..17 && stage == LifeStage.ADULT) {
            val shop = state.businesses.values
                .filter { it.open && it.type in SHOP_TYPES }
                .minByOrNull { it.id }
            if (shop != null) {
                out += ScoredAction(
                    ActionKind.SHOP, shop.buildingId, null, Activity.SHOPPING, 45L,
                    "Errands at ${shop.name}",
                    needPressure = pressure(n.comfort) * 0.9 + pressure(n.hunger) * 0.2,
                    personalityFit = 0.8, expectedReward = 0.85, confidence = 1.0,
                    socialInfluence = 1.0, opportunity = 1.0,
                    risk = 0.0, cost = 0.15, effort = 0.1, moralResistance = 0.0
                )
            }
        }

        // --- Exercise
        val park = state.buildings.values.firstOrNull { it.type == BuildingType.PARK }
        if (park != null && n.energy > 40 && hour in 6..19 && stage != LifeStage.ELDER) {
            out += ScoredAction(
                ActionKind.EXERCISE, park.id, null, Activity.EXERCISING, 60L,
                "A run around the park",
                needPressure = 0.25 + (n.stress / 100.0) * 0.5,
                personalityFit = 0.3 + p.discipline * 0.5 + (r.skill(SkillType.FITNESS) / 200.0),
                expectedReward = 0.8, confidence = 0.95, socialInfluence = 1.0, opportunity = 1.0,
                risk = 0.02, cost = 0.0, effort = 0.3, moralResistance = 0.0
            )
        }

        // --- Learning / goal work
        val activeGoal = r.goals.firstOrNull { it.status == GoalStatus.ACTIVE }
        if (activeGoal != null && home != null && hour in 8..21) {
            out += ScoredAction(
                ActionKind.WORK_ON_GOAL, goalVenue(state, r, activeGoal.type) ?: home, null,
                Activity.LEARNING, 80L,
                goalReason(activeGoal.type),
                needPressure = 0.4 + pressure(n.purpose) * 0.8,
                personalityFit = 0.4 + p.ambition * 0.7 + p.discipline * 0.2,
                expectedReward = 1.0 - activeGoal.risk * 0.3,
                confidence = 0.7 + activeGoal.progress * 0.3,
                socialInfluence = 1.0, opportunity = 1.0,
                risk = activeGoal.risk * 0.15, cost = 0.05, effort = 0.25, moralResistance = 0.0
            )
        }

        // --- Relax at home (always available fallback)
        if (home != null) {
            out += ScoredAction(
                ActionKind.RELAX_HOME, home, null, Activity.RELAXING, 60L,
                "A quiet hour at home",
                needPressure = 0.3 + (n.stress / 100.0) * 0.6,
                personalityFit = 0.6 + (1.0 - p.sociability) * 0.3,
                expectedReward = 0.75, confidence = 1.0, socialInfluence = 1.0, opportunity = 1.0,
                risk = 0.0, cost = 0.0, effort = 0.02, moralResistance = 0.0
            )
        } else {
            val spot = openPublicSpot(state, hour) ?: state.buildings.values.first { it.type == BuildingType.PARK }
            out += ScoredAction(
                ActionKind.WANDER, spot.id, null, Activity.IDLE, 45L,
                "Passing the time",
                needPressure = 0.25, personalityFit = 1.0, expectedReward = 0.5,
                confidence = 1.0, socialInfluence = 1.0, opportunity = 1.0,
                risk = 0.0, cost = 0.0, effort = 0.05, moralResistance = 0.0
            )
        }

        return out
    }

    private fun goalVenue(state: WorldState, r: Resident, type: GoalType): Long? = when (type) {
        GoalType.START_BUSINESS -> state.buildings.values.firstOrNull { it.type == BuildingType.VACANT }?.id
        GoalType.FIND_JOB -> state.buildings.values.firstOrNull { it.type == BuildingType.TOWN_HALL }?.id
        GoalType.LEAVE_FOR_EDUCATION -> r.homeBuildingId
        GoalType.LEARN_SKILL -> state.buildings.values.firstOrNull { it.type == BuildingType.BOOKSHOP }?.id
        else -> r.homeBuildingId
    }

    private fun goalReason(type: GoalType): String = when (type) {
        GoalType.FIND_JOB -> "Asking around about work"
        GoalType.START_BUSINESS -> "Working on the business plan"
        GoalType.LEARN_SKILL -> "Practising and studying"
        GoalType.FIND_PARTNER -> "Making an effort to get out more"
        GoalType.LEAVE_FOR_EDUCATION -> "Reading course brochures again"
        GoalType.PAY_OFF_DEBT -> "Going over the sums"
        GoalType.GET_HEALTHY -> "Taking recovery seriously"
        GoalType.REPAIR_RELATIONSHIP -> "Thinking about how to make things right"
        GoalType.MOVE_HOME -> "Scanning the housing notices"
        GoalType.RUN_FOR_OFFICE -> "Drafting ideas for the town"
        GoalType.RETIRE_WELL -> "Putting affairs in order"
    }

    private fun bestFriendToVisit(state: WorldState, r: Resident): Resident? {
        return state.relationshipsOf(r.id)
            .filter { it.warmth() > 25 }
            .sortedByDescending { it.warmth() }
            .asSequence()
            .mapNotNull { state.resident(it.other(r.id)) }
            .firstOrNull { it.inTown && it.detailLevel == DetailLevel.DETAILED && it.activity != Activity.SLEEPING && it.activity != Activity.WORKING }
    }

    private fun openEatery(state: WorldState, hour: Int): Long? {
        val types = if (hour >= 17) listOf(com.ripple.town.core.model.BusinessType.PUB, com.ripple.town.core.model.BusinessType.CAFE)
        else listOf(com.ripple.town.core.model.BusinessType.CAFE, com.ripple.town.core.model.BusinessType.BAKERY, com.ripple.town.core.model.BusinessType.PUB)
        return state.businesses.values
            .filter { it.open && it.type in types }
            .minByOrNull { it.id }?.id
    }

    private fun openPublicSpot(state: WorldState, hour: Int): com.ripple.town.core.model.Building? {
        val candidates = state.buildings.values
            .filter { it.type.isPublicSpace && !it.abandoned }
            .sortedBy { it.id }
        return when {
            hour in 17..22 -> candidates.firstOrNull { it.type == BuildingType.PUB } ?: candidates.firstOrNull()
            hour in 8..17 -> candidates.firstOrNull { it.type == BuildingType.CAFE }
                ?: candidates.firstOrNull { it.type == BuildingType.PARK }
            else -> candidates.firstOrNull { it.type == BuildingType.PARK }
        }
    }

    private fun execute(ctx: TickContext, r: Resident, action: ScoredAction) {
        // Purchases happen up front so money flows are deterministic.
        when (action.kind) {
            ActionKind.EAT_OUT -> spend(ctx, r, action.targetBuildingId, 6.0)
            ActionKind.SHOP -> spend(ctx, r, action.targetBuildingId, 12.0)
            ActionKind.SOCIALISE_PUBLIC -> {
                val b = action.targetBuildingId?.let { ctx.state.building(it) }
                if (b != null && b.type != BuildingType.PARK) spend(ctx, r, action.targetBuildingId, 5.0)
            }
            ActionKind.LEARN_SKILL, ActionKind.WORK_ON_GOAL -> {
                // Practice slowly improves the most relevant skill.
                val goal = r.goals.firstOrNull { it.status == GoalStatus.ACTIVE }
                val skill = goal?.targetSkill ?: relevantSkill(goal?.type)
                if (skill != null) {
                    r.skills[skill] = (r.skill(skill) + 0.4).coerceAtMost(100.0)
                }
            }
            else -> {}
        }
        val target = action.targetBuildingId
        if (target != null) {
            ctx.sendTo(r, target, action.activity, action.durationMinutes, action.reason)
        } else {
            ctx.beginActivity(r, action.activity, action.durationMinutes, action.reason)
        }
    }

    fun relevantSkill(type: GoalType?): SkillType? = when (type) {
        GoalType.START_BUSINESS -> SkillType.BUSINESS
        GoalType.LEARN_SKILL -> SkillType.CREATIVITY
        GoalType.RUN_FOR_OFFICE -> SkillType.POLITICS
        GoalType.LEAVE_FOR_EDUCATION -> SkillType.TEACHING
        else -> null
    }

    private fun spend(ctx: TickContext, r: Resident, buildingId: Long?, amount: Double) {
        if (r.wealth < amount) return
        r.wealth -= amount
        val biz = ctx.state.businesses.values.firstOrNull { it.buildingId == buildingId && it.open }
        if (biz != null) {
            biz.customersToday += 1
            biz.revenueToday += amount
            biz.balance += amount
        }
    }

    private val SHOP_TYPES = setOf(
        com.ripple.town.core.model.BusinessType.GROCER,
        com.ripple.town.core.model.BusinessType.HARDWARE,
        com.ripple.town.core.model.BusinessType.BOOKSHOP,
        com.ripple.town.core.model.BusinessType.TAILOR,
        com.ripple.town.core.model.BusinessType.BAKERY
    )
}
