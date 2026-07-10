package com.ripple.town.core.simulation

import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.Business
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.Weather

/**
 * Businesses earn from footfall, pay wages daily, and can struggle, shrink or
 * close. Residents pay living costs daily and rent monthly. Everything routes
 * through events so consequences can chain.
 */
object EconomySystem {

    fun update(ctx: TickContext) {
        hourlyFootfall(ctx)
        // Daily settlement shortly before midnight.
        if (SimTime.hourOfDay(ctx.now) == 23 && SimTime.minuteOfDay(ctx.now) >= (23 * 60 + 50)) {
            dailySettlement(ctx)
        }
    }

    private fun hourlyFootfall(ctx: TickContext) {
        if (SimTime.minuteOfDay(ctx.now) % 60 != 0) return
        val hour = SimTime.hourOfDay(ctx.now)
        if (hour < 8 || hour > 21) return
        val weatherFactor = when (ctx.state.weather) {
            Weather.CLEAR -> 1.0
            Weather.CLOUDY -> 0.9
            Weather.FOG -> 0.75
            Weather.RAIN -> 0.65
            Weather.SNOW -> 0.55
            Weather.STORM -> 0.35
        }
        // Background residents produce abstract footfall.
        for (biz in ctx.state.businesses.values.sortedBy { it.id }) {
            if (!biz.open || biz.type in PUBLIC_SERVICES) continue
            val expected = (biz.demand / 100.0) * weatherFactor * 2.2
            val customers = ctx.rng.nextGaussianLike(expected, 1.2, 0.0, 6.0).toInt()
            if (customers > 0) {
                val spendEach = baseSpend(biz.type) * biz.priceLevel
                biz.customersToday += customers
                biz.revenueToday += customers * spendEach
                biz.balance += customers * spendEach
            }
        }
    }

    fun dailySettlement(ctx: TickContext) {
        val state = ctx.state
        // Wages and business expenses
        for (biz in state.businesses.values.sortedBy { it.id }) {
            if (!biz.open) continue
            val staff = state.employeesOf(biz.id).sortedBy { it.id }
            var expenses = overheads(biz.type)
            for (emp in staff) {
                val worker = state.resident(emp.residentId) ?: continue
                val pay = if (emp.reducedHours) emp.dailySalary * 0.6 else emp.dailySalary
                if (biz.type in PUBLIC_SERVICES || biz.balance > pay) {
                    worker.wealth += pay
                    if (biz.type !in PUBLIC_SERVICES) expenses += pay
                } else {
                    // Can't make payroll — pressure builds
                    worker.needs.stress += 6.0
                    worker.needs.financialSecurity -= 4.0
                }
            }
            biz.expensesToday += expenses
            biz.balance -= expenses
            settleBusinessDay(ctx, biz)
        }

        // Residents: living costs, debt interest, rent on the 1st
        val firstOfMonth = SimTime.dayOfMonth(state.time) == 1
        for (r in state.residentsOrdered()) {
            if (!r.inTown || r.detailLevel != com.ripple.town.core.model.DetailLevel.DETAILED) continue
            val stage = r.lifeStageAt(state.time)
            if (stage == com.ripple.town.core.model.LifeStage.CHILD) continue
            r.wealth -= LIVING_COST_PER_DAY
            if (r.debt > 0) {
                r.debt *= 1.0005 // gentle daily interest
                val repayment = minOf(r.debt, maxOf(0.0, (r.wealth - 100.0) * 0.05))
                r.wealth -= repayment
                r.debt -= repayment
                if (r.debt < 1.0) {
                    r.debt = 0.0
                    val e = ctx.emit(
                        EventType.FINANCIAL_RELIEF,
                        "${r.fullName} has finally cleared their debts.",
                        sourceResidentId = r.id, severity = 0.3, visibility = EventVisibility.PRIVATE
                    )
                    ConsequenceEngine.onEvent(ctx, e)
                }
            }
            if (r.wealth < 0) {
                r.debt += -r.wealth
                r.wealth = 0.0
                if (r.debt > DEBT_CRISIS_THRESHOLD && !r.awareness.contains("debt_crisis")) {
                    r.awareness += "debt_crisis"
                    val e = ctx.emit(
                        EventType.DEBT_CRISIS,
                        "${r.fullName} is drowning in debt.",
                        sourceResidentId = r.id, severity = 0.5, visibility = EventVisibility.PRIVATE
                    )
                    ConsequenceEngine.onEvent(ctx, e)
                }
            }
        }
        if (firstOfMonth) {
            for (hh in state.households.values.sortedBy { it.id }) {
                val adults = hh.memberIds.mapNotNull { state.resident(it) }
                    .filter { it.inTown && it.lifeStageAt(state.time) != com.ripple.town.core.model.LifeStage.CHILD }
                if (adults.isEmpty()) continue
                val share = hh.monthlyRent / adults.size
                for (a in adults) {
                    a.wealth -= share
                    if (a.wealth < 0) { a.debt += -a.wealth; a.wealth = 0.0 }
                }
            }
        }
    }

    private fun settleBusinessDay(ctx: TickContext, biz: Business) {
        val state = ctx.state
        // Reputation follows served customers and owner condition.
        val served = biz.customersToday
        biz.reputation += when {
            served >= 8 -> 0.4
            served <= 1 -> -0.5
            else -> 0.0
        }
        biz.reputation = biz.reputation.coerceIn(5.0, 95.0)
        // Demand drifts towards reputation.
        biz.demand += (biz.reputation - biz.demand) * 0.04
        biz.demand = biz.demand.coerceIn(5.0, 95.0)

        if (biz.type !in PUBLIC_SERVICES) {
            if (biz.balance < 0) {
                biz.daysInTrouble += 1
                if (biz.daysInTrouble == STRUGGLE_NOTICE_DAYS) {
                    val e = ctx.emit(
                        EventType.BUSINESS_STRUGGLING,
                        "${biz.name} is struggling to stay afloat.",
                        sourceResidentId = biz.ownerId, businessId = biz.id,
                        buildingId = biz.buildingId, severity = 0.45
                    )
                    ConsequenceEngine.onEvent(ctx, e)
                }
                if (biz.daysInTrouble >= CLOSURE_DAYS) {
                    closeBusiness(ctx, biz, "after ${biz.daysInTrouble} days in the red")
                }
            } else {
                biz.daysInTrouble = 0
                // Prosperous businesses may expand.
                if (biz.balance > EXPANSION_BALANCE && ctx.rng.nextBoolean(0.04)) {
                    expandBusiness(ctx, biz)
                }
                // Hiring when busy and under capacity.
                val staff = state.employeesOf(biz.id).size
                if (biz.demand > 62 && staff < biz.employeeCapacity && biz.balance > 1_500 && ctx.rng.nextBoolean(0.3)) {
                    hireSomeone(ctx, biz)
                }
            }
        }
        biz.customersToday = 0
        biz.revenueToday = 0.0
        biz.expensesToday = 0.0
    }

    fun closeBusiness(ctx: TickContext, biz: Business, why: String, causeIds: List<Long> = emptyList()) {
        val state = ctx.state
        biz.open = false
        biz.closedAt = ctx.now
        val building = state.building(biz.buildingId)
        building?.abandoned = true
        building?.visibleChanges?.add("Shutters down — closed")
        val closure = ctx.emit(
            EventType.BUSINESS_CLOSED,
            "${biz.name} has closed its doors $why.",
            sourceResidentId = biz.ownerId, businessId = biz.id, buildingId = biz.buildingId,
            severity = 0.7, causeIds = causeIds
        )
        // Everyone employed there loses their job — direct causal children.
        for (emp in state.employeesOf(biz.id).sortedBy { it.id }) {
            emp.endedAt = ctx.now
            val worker = state.resident(emp.residentId) ?: continue
            worker.employmentId = null
            worker.occupation = "Unemployed"
            val jobLost = ctx.emit(
                EventType.JOB_LOST,
                "${worker.fullName} lost their job when ${biz.name} closed.",
                sourceResidentId = worker.id, businessId = biz.id,
                severity = 0.55, causeIds = listOf(closure.id)
            )
            ctx.addMemory(worker, MemoryType.LOSS, "The day ${biz.name} closed.", 55.0, jobLost.id)
            ConsequenceEngine.onEvent(ctx, jobLost)
        }
        // Owner takes a financial and emotional hit.
        val owner = biz.ownerId?.let { state.resident(it) }
        if (owner != null) {
            owner.needs.stress += 18.0
            owner.needs.purpose -= 15.0
            owner.reputation -= 6.0
            if (biz.balance < 0) owner.debt += -biz.balance
            ctx.addMemory(owner, MemoryType.LOSS, "Losing ${biz.name} broke something in me.", 80.0, closure.id)
        }
        ConsequenceEngine.onEvent(ctx, closure)
    }

    private fun expandBusiness(ctx: TickContext, biz: Business) {
        biz.employeeCapacity += 1
        biz.balance -= 800.0
        val building = ctx.state.building(biz.buildingId)
        building?.upgradeLevel = (building?.upgradeLevel ?: 0) + 1
        building?.visibleChanges?.add("Extension added")
        building?.value = (building?.value ?: 0.0) + 6_000.0
        val e = ctx.emit(
            EventType.BUSINESS_EXPANDED,
            "${biz.name} is expanding — trade has been good.",
            sourceResidentId = biz.ownerId, businessId = biz.id, buildingId = biz.buildingId,
            severity = 0.4
        )
        ConsequenceEngine.onEvent(ctx, e)
    }

    private fun hireSomeone(ctx: TickContext, biz: Business) {
        val state = ctx.state
        // Prefer detailed unemployed adults actively looking; else promote a background resident.
        val candidate = state.residentsOrdered()
            .filter {
                it.inTown && it.employmentId == null &&
                    it.lifeStageAt(state.time) == com.ripple.town.core.model.LifeStage.ADULT &&
                    it.ageAt(state.time) < 66
            }
            .sortedByDescending { r ->
                (if (r.detailLevel == com.ripple.town.core.model.DetailLevel.DETAILED) 10.0 else 0.0) +
                    (if (r.goals.any { it.type == com.ripple.town.core.model.GoalType.FIND_JOB && it.status == com.ripple.town.core.model.GoalStatus.ACTIVE }) 20.0 else 0.0) +
                    r.skill(relevantSkillFor(biz.type)) / 10.0
            }
            .firstOrNull() ?: return
        hire(ctx, candidate, biz, roleFor(biz.type), causeIds = emptyList())
    }

    fun hire(ctx: TickContext, worker: com.ripple.town.core.model.Resident, biz: Business, role: String, causeIds: List<Long>) {
        val state = ctx.state
        if (state.employeesOf(biz.id).size >= biz.employeeCapacity) return
        val id = state.nextEmploymentId++
        state.employments[id] = com.ripple.town.core.model.Employment(
            id = id, residentId = worker.id, businessId = biz.id, role = role,
            dailySalary = salaryFor(biz.type), startedAt = ctx.now
        )
        worker.employmentId = id
        worker.occupation = role
        LifecycleSystem.promoteIfNeeded(ctx, worker, "hired at ${biz.name}")
        val started = ctx.emit(
            EventType.JOB_STARTED,
            "${worker.fullName} has started work at ${biz.name} as ${role.lowercase()}.",
            sourceResidentId = worker.id, businessId = biz.id, severity = 0.35, causeIds = causeIds
        )
        worker.goals.filter { it.type == com.ripple.town.core.model.GoalType.FIND_JOB && it.status == com.ripple.town.core.model.GoalStatus.ACTIVE }
            .forEach { it.status = com.ripple.town.core.model.GoalStatus.COMPLETED; it.resolvedAt = ctx.now; it.progress = 1.0 }
        worker.needs.purpose += 15.0
        worker.needs.financialSecurity += 10.0
        ConsequenceEngine.onEvent(ctx, started)
    }

    fun baseSpend(type: BusinessType): Double = when (type) {
        BusinessType.BAKERY -> 4.5
        BusinessType.CAFE -> 6.0
        BusinessType.PUB -> 9.0
        BusinessType.GROCER -> 11.0
        BusinessType.HARDWARE -> 14.0
        BusinessType.BOOKSHOP -> 8.0
        BusinessType.TAILOR -> 18.0
        BusinessType.WORKSHOP -> 25.0
        BusinessType.FACTORY -> 40.0
        else -> 0.0
    }

    private fun overheads(type: BusinessType): Double = when (type) {
        BusinessType.FACTORY -> 120.0
        BusinessType.PUB -> 55.0
        BusinessType.CAFE -> 40.0
        BusinessType.GROCER -> 60.0
        BusinessType.BAKERY -> 38.0
        BusinessType.HARDWARE -> 45.0
        BusinessType.BOOKSHOP -> 28.0
        BusinessType.TAILOR -> 26.0
        BusinessType.WORKSHOP -> 30.0
        else -> 0.0
    }

    fun salaryFor(type: BusinessType): Double = when (type) {
        BusinessType.FACTORY -> 46.0
        BusinessType.CLINIC -> 52.0
        BusinessType.SCHOOL -> 54.0
        BusinessType.TOWN_HALL -> 50.0
        else -> 40.0
    }

    fun roleFor(type: BusinessType): String = when (type) {
        BusinessType.BAKERY -> "Bakery assistant"
        BusinessType.CAFE -> "Café worker"
        BusinessType.PUB -> "Bar worker"
        BusinessType.GROCER -> "Grocery assistant"
        BusinessType.HARDWARE -> "Shop assistant"
        BusinessType.BOOKSHOP -> "Bookseller"
        BusinessType.TAILOR -> "Seamster"
        BusinessType.WORKSHOP -> "Workshop hand"
        BusinessType.FACTORY -> "Joinery worker"
        BusinessType.CLINIC -> "Clinic assistant"
        BusinessType.SCHOOL -> "Classroom assistant"
        BusinessType.TOWN_HALL -> "Clerk"
    }

    private fun relevantSkillFor(type: BusinessType): com.ripple.town.core.model.SkillType = when (type) {
        BusinessType.BAKERY, BusinessType.CAFE -> com.ripple.town.core.model.SkillType.COOKING
        BusinessType.WORKSHOP, BusinessType.FACTORY -> com.ripple.town.core.model.SkillType.CARPENTRY
        BusinessType.HARDWARE -> com.ripple.town.core.model.SkillType.REPAIR
        BusinessType.CLINIC -> com.ripple.town.core.model.SkillType.MEDICINE
        BusinessType.SCHOOL -> com.ripple.town.core.model.SkillType.TEACHING
        BusinessType.TOWN_HALL -> com.ripple.town.core.model.SkillType.POLITICS
        else -> com.ripple.town.core.model.SkillType.SOCIAL
    }

    const val LIVING_COST_PER_DAY = 9.0
    const val DEBT_CRISIS_THRESHOLD = 2_000.0
    const val STRUGGLE_NOTICE_DAYS = 5
    const val CLOSURE_DAYS = 18
    const val EXPANSION_BALANCE = 9_000.0
    val PUBLIC_SERVICES = setOf(BusinessType.CLINIC, BusinessType.SCHOOL, BusinessType.TOWN_HALL)
}
