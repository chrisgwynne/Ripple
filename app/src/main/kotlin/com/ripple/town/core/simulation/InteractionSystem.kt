package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Relationship
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.RelationshipStatus
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType

/**
 * Resolves social contact between residents who share a location, and the
 * slower romantic arcs (dating, engagement, marriage, separation, divorce).
 *
 * Bounded: at most [MAX_INTERACTIONS_PER_TICK] pair interactions per tick.
 */
object InteractionSystem {

    const val MAX_INTERACTIONS_PER_TICK = 8

    /** Bounded trust swing a family's reputation can lend (or cost) a first meeting. */
    private const val FIRST_MEETING_TRUST_SWING = 3.0

    fun update(ctx: TickContext) {
        var budget = MAX_INTERACTIONS_PER_TICK
        val byBuilding = ctx.state.detailedResidents()
            .filter { it.inTown && it.currentBuildingId != null && it.activity in SOCIABLE_ACTIVITIES }
            .groupBy { it.currentBuildingId!! }
            .toSortedMap()

        for ((buildingId, present) in byBuilding) {
            if (budget <= 0) break
            if (present.size < 2) continue
            val sorted = present.sortedBy { it.id }
            // Sample a couple of pairs per busy building.
            val pairCount = minOf(2, sorted.size / 2, budget)
            repeat(pairCount) {
                val a = sorted[ctx.rng.nextInt(sorted.size)]
                val b = sorted[ctx.rng.nextInt(sorted.size)]
                if (a.id != b.id) {
                    interact(ctx, a, b, buildingId)
                    budget--
                }
            }
        }

        // Daily romance/relationship-arc pass at 21:00
        if (SimTime.hourOfDay(ctx.now) == 21 && SimTime.minuteOfDay(ctx.now) % 60 < SimTime.MINUTES_PER_TICK) {
            romanticArcs(ctx)
        }
    }

    fun interact(ctx: TickContext, a: Resident, b: Resident, buildingId: Long?) {
        val rel = ctx.state.relationshipOrCreate(a.id, b.id)
        val firstMeeting = rel.familiarity < 5.0
        rel.familiarity += 3.0
        rel.sharedHistory += 0.4
        rel.lastInteractionAt = ctx.now

        val compatibility = compatibility(a, b)
        val tension = tension(a, b, rel)

        when {
            tension > 0.55 && ctx.rng.nextBoolean(tension * 0.5) -> argue(ctx, a, b, rel, buildingId)
            else -> {
                // A pleasant or neutral exchange.
                val lift = 1.2 + compatibility * 2.2
                rel.affection += lift
                rel.trust += 0.8 + compatibility
                rel.respect += 0.4
                a.needs.social += 4.0
                b.needs.social += 4.0
                // A well-regarded family's name travels ahead of them: a small trust
                // head start (or handicap) on a genuinely first meeting only — once two
                // people actually know each other, their own history takes over.
                if (firstMeeting) {
                    rel.trust += FamilyReputationSystem.standingModifier(ctx.state, a, FIRST_MEETING_TRUST_SWING)
                    rel.trust += FamilyReputationSystem.standingModifier(ctx.state, b, FIRST_MEETING_TRUST_SWING)
                }
                val social = (a.skill(SkillType.SOCIAL) + b.skill(SkillType.SOCIAL)) / 2.0
                if (social < 100.0) {
                    a.skills[SkillType.SOCIAL] = (a.skill(SkillType.SOCIAL) + 0.1).coerceAtMost(100.0)
                    b.skills[SkillType.SOCIAL] = (b.skill(SkillType.SOCIAL) + 0.1).coerceAtMost(100.0)
                }
                // Helping when one is struggling builds trust and leaves a memory.
                val struggling = listOf(a, b).firstOrNull { it.needs.stress > 65 }
                val helper = if (struggling == a) b else a
                if (struggling != null && helper.personality.kindness > 0.55 && ctx.rng.nextBoolean(0.4)) {
                    rel.trust += 4.0
                    rel.dependency += 2.0
                    struggling.needs.stress -= 6.0
                    ctx.addMemory(
                        struggling, MemoryType.KINDNESS_RECEIVED,
                        "${helper.firstName} listened when things were heavy.",
                        intensity = 45.0, associated = listOf(helper.id)
                    )
                }
                // Spark? Only for single adults with mutual availability.
                maybeSpark(ctx, a, b, rel)
            }
        }

        if (firstMeeting) {
            ctx.emit(
                EventType.MEETING,
                "${a.fullName} and ${b.fullName} got talking at ${ctx.state.building(buildingId ?: -1)?.name ?: "the street"}.",
                sourceResidentId = a.id, targetResidentIds = listOf(b.id),
                buildingId = buildingId, severity = 0.1, visibility = EventVisibility.PRIVATE
            )
        }

        updateKind(ctx, rel, a, b)
        rel.clampAll()
    }

    fun compatibility(a: Resident, b: Resident): Double {
        val pa = a.personality; val pb = b.personality
        val closeness = 1.0 - (
            kotlin.math.abs(pa.sociability - pb.sociability) +
                kotlin.math.abs(pa.curiosity - pb.curiosity) +
                kotlin.math.abs(pa.honesty - pb.honesty)
            ) / 3.0
        val warmth = (pa.kindness + pb.kindness + pa.empathy + pb.empathy) / 4.0
        return (closeness * 0.5 + warmth * 0.5).coerceIn(0.0, 1.0)
    }

    private fun tension(a: Resident, b: Resident, rel: Relationship): Double {
        val stress = (a.needs.stress + b.needs.stress) / 200.0
        val resent = rel.resentment / 100.0
        val impuls = (a.personality.impulsiveness + b.personality.impulsiveness) / 2.0
        val patience = (a.personality.patience + b.personality.patience) / 2.0
        return (stress * 0.5 + resent * 0.6 + impuls * 0.2 - patience * 0.3).coerceIn(0.0, 1.0)
    }

    fun argue(ctx: TickContext, a: Resident, b: Resident, rel: Relationship, buildingId: Long?) {
        rel.resentment += 7.0
        rel.affection -= 4.0
        rel.trust -= 3.0
        a.needs.stress += 8.0
        b.needs.stress += 8.0
        a.activity = Activity.ARGUING
        b.activity = Activity.ARGUING
        a.activityEndsAt = ctx.now + 20
        b.activityEndsAt = ctx.now + 20
        val event = ctx.emit(
            EventType.ARGUMENT,
            "${a.fullName} and ${b.fullName} had a heated argument.",
            sourceResidentId = a.id, targetResidentIds = listOf(b.id),
            buildingId = buildingId, severity = 0.35, visibility = EventVisibility.PRIVATE
        )
        ctx.addMemory(a, MemoryType.ARGUMENT, "That row with ${b.firstName} stuck with me.", 40.0, event.id, listOf(b.id))
        ctx.addMemory(b, MemoryType.ARGUMENT, "That row with ${a.firstName} stuck with me.", 40.0, event.id, listOf(a.id))
        ConsequenceEngine.onEvent(ctx, event)
    }

    private fun maybeSpark(ctx: TickContext, a: Resident, b: Resident, rel: Relationship) {
        val now = ctx.now
        if (a.lifeStageAt(now) != LifeStage.ADULT || b.lifeStageAt(now) != LifeStage.ADULT) return
        if (isFamily(rel.kind)) return
        val ageGap = kotlin.math.abs(a.ageAt(now) - b.ageAt(now))
        if (ageGap > 15) return
        if (a.relationshipStatus in AVAILABLE && b.relationshipStatus in AVAILABLE &&
            a.partnerId == null && b.partnerId == null
        ) {
            val chance = (compatibility(a, b) * 0.25 + rel.warmth() / 400.0).coerceIn(0.0, 0.3)
            if (ctx.rng.nextBoolean(chance)) {
                rel.attraction += ctx.rng.nextDouble(4.0, 10.0)
            }
            return
        }
        // A committed resident in a strained partnership can still feel a rare spark —
        // fertile ground for temptation, never a guarantee of anything by itself.
        val vuln = maxOf(
            if (a.partnerId != null && a.partnerId != b.id) vulnerability(ctx, a) else 0.0,
            if (b.partnerId != null && b.partnerId != a.id) vulnerability(ctx, b) else 0.0
        )
        if (vuln <= 0.15) return
        val chance = (compatibility(a, b) * 0.1 + vuln * 0.08).coerceIn(0.0, 0.05)
        if (ctx.rng.nextBoolean(chance)) {
            rel.attraction += ctx.rng.nextDouble(2.0, 6.0)
        }
    }

    private fun romanticArcs(ctx: TickContext) {
        val state = ctx.state
        // Consider starting relationships
        for (rel in state.relationships.values.sortedBy { Relationship.keyOf(it.aId, it.bId) }) {
            val a = state.resident(rel.aId) ?: continue
            val b = state.resident(rel.bId) ?: continue
            if (!a.inTown || !b.inTown) continue

            if (rel.kind !in ROMANTIC_KINDS && rel.attraction > 45 && rel.affection > 45 &&
                a.partnerId == null && b.partnerId == null && !isFamily(rel.kind)
            ) {
                if (ctx.rng.nextBoolean(0.35)) {
                    rel.kind = RelationshipKind.PARTNER
                    a.relationshipStatus = RelationshipStatus.DATING; a.partnerId = b.id
                    b.relationshipStatus = RelationshipStatus.DATING; b.partnerId = a.id
                    val e = ctx.emit(
                        EventType.RELATIONSHIP_STARTED,
                        "${a.fullName} and ${b.fullName} have started seeing each other.",
                        sourceResidentId = a.id, targetResidentIds = listOf(b.id),
                        severity = 0.4, visibility = EventVisibility.PRIVATE
                    )
                    ctx.addMemory(a, MemoryType.ROMANCE, "It began with ${b.firstName}.", 60.0, e.id, listOf(b.id))
                    ctx.addMemory(b, MemoryType.ROMANCE, "It began with ${a.firstName}.", 60.0, e.id, listOf(a.id))
                    ConsequenceEngine.onEvent(ctx, e)
                }
            }

            // Dating -> engagement -> marriage
            if (rel.kind == RelationshipKind.PARTNER &&
                a.relationshipStatus == RelationshipStatus.DATING && rel.affection > 65 && rel.trust > 60
            ) {
                if (ctx.rng.nextBoolean(0.06)) {
                    a.relationshipStatus = RelationshipStatus.ENGAGED
                    b.relationshipStatus = RelationshipStatus.ENGAGED
                    val e = ctx.emit(
                        EventType.ENGAGEMENT,
                        "${a.fullName} and ${b.fullName} are engaged.",
                        sourceResidentId = a.id, targetResidentIds = listOf(b.id), severity = 0.5
                    )
                    ConsequenceEngine.onEvent(ctx, e)
                }
            } else if (rel.kind == RelationshipKind.PARTNER &&
                a.relationshipStatus == RelationshipStatus.ENGAGED && ctx.rng.nextBoolean(0.08)
            ) {
                rel.kind = RelationshipKind.SPOUSE
                a.relationshipStatus = RelationshipStatus.MARRIED
                b.relationshipStatus = RelationshipStatus.MARRIED
                rel.dependency += 15.0
                val e = ctx.emit(
                    EventType.MARRIAGE,
                    "${a.fullName} and ${b.fullName} were married at the town hall.",
                    sourceResidentId = a.id, targetResidentIds = listOf(b.id), severity = 0.6
                )
                ctx.addMemory(a, MemoryType.ROMANCE, "Our wedding day.", 85.0, e.id, listOf(b.id))
                ctx.addMemory(b, MemoryType.ROMANCE, "Our wedding day.", 85.0, e.id, listOf(a.id))
                ConsequenceEngine.onEvent(ctx, e)
            }

            // Break-ups and separations under sustained resentment
            if (rel.kind == RelationshipKind.PARTNER && rel.resentment > 60 && rel.affection < 30) {
                if (ctx.rng.nextBoolean(0.3)) {
                    endPartnership(ctx, a, b, rel, married = false)
                }
            }
            if (rel.kind == RelationshipKind.SPOUSE && rel.resentment > 72 && rel.affection < 25) {
                if (ctx.rng.nextBoolean(0.18)) {
                    endPartnership(ctx, a, b, rel, married = true)
                }
            }
            // Affairs: a secret closeness that grows outside an existing partnership.
            if (rel.kind == RelationshipKind.AFFAIR) {
                progressAffair(ctx, a, b, rel)
            } else if (rel.kind !in FIXED_KINDS && !isFamily(rel.kind) &&
                ((a.partnerId != null && a.partnerId != b.id) || (b.partnerId != null && b.partnerId != a.id))
            ) {
                maybeBeginAffair(ctx, a, b, rel)
            }

            // Reconciliation is possible while separated
            if (a.relationshipStatus == RelationshipStatus.SEPARATED && rel.involves(b.id) &&
                rel.resentment < 35 && rel.affection > 45 && ctx.rng.nextBoolean(0.1)
            ) {
                a.relationshipStatus = RelationshipStatus.MARRIED
                b.relationshipStatus = RelationshipStatus.MARRIED
                a.partnerId = b.id; b.partnerId = a.id
                rel.kind = RelationshipKind.SPOUSE
                val e = ctx.emit(
                    EventType.RECONCILIATION,
                    "${a.fullName} and ${b.fullName} have reconciled.",
                    sourceResidentId = a.id, targetResidentIds = listOf(b.id), severity = 0.45,
                    visibility = EventVisibility.PRIVATE
                )
                ConsequenceEngine.onEvent(ctx, e)
            }
            rel.clampAll()
        }
    }

    fun endPartnership(ctx: TickContext, a: Resident, b: Resident, rel: Relationship, married: Boolean) {
        if (married) {
            a.relationshipStatus = RelationshipStatus.SEPARATED
            b.relationshipStatus = RelationshipStatus.SEPARATED
            val e = ctx.emit(
                EventType.SEPARATION,
                "${a.fullName} and ${b.fullName} have separated.",
                sourceResidentId = a.id, targetResidentIds = listOf(b.id), severity = 0.6
            )
            ConsequenceEngine.onEvent(ctx, e)
        } else {
            rel.kind = RelationshipKind.FORMER_PARTNER
            a.relationshipStatus = RelationshipStatus.SINGLE; a.partnerId = null
            b.relationshipStatus = RelationshipStatus.SINGLE; b.partnerId = null
            val e = ctx.emit(
                EventType.BREAK_UP,
                "${a.fullName} and ${b.fullName} have gone their separate ways.",
                sourceResidentId = a.id, targetResidentIds = listOf(b.id), severity = 0.45,
                visibility = EventVisibility.PRIVATE
            )
            ConsequenceEngine.onEvent(ctx, e)
        }
        ctx.addMemory(a, MemoryType.LOSS, "Things ended with ${b.firstName}.", 65.0, associated = listOf(b.id))
        ctx.addMemory(b, MemoryType.LOSS, "Things ended with ${a.firstName}.", 65.0, associated = listOf(a.id))
    }

    /**
     * A committed resident's resistance to temptation, eroded by a strained
     * partnership and dampened by a vigilant one. The vigilance term stands in
     * for jealousy as a *modifier* on existing dimensions (dependency,
     * resentment) rather than a tracked value of its own — a suspicious
     * partner makes wandering feel riskier without ever guaranteeing it stops.
     */
    private fun vulnerability(ctx: TickContext, r: Resident): Double {
        val partner = r.partnerId?.let { ctx.state.resident(it) } ?: return 0.0
        val spouseRel = ctx.state.relationship(r.id, partner.id) ?: return 0.0
        val strain = ((100.0 - spouseRel.affection) + spouseRel.resentment) / 200.0
        val vigilance = spouseRel.dependency / 100.0 * 0.4 + spouseRel.resentment / 100.0 * 0.3
        return (strain - vigilance * 0.5).coerceIn(0.0, 1.0)
    }

    private fun maybeBeginAffair(ctx: TickContext, a: Resident, b: Resident, rel: Relationship) {
        val now = ctx.now
        if (a.lifeStageAt(now) != LifeStage.ADULT || b.lifeStageAt(now) != LifeStage.ADULT) return
        if (rel.attraction < 30.0 || rel.affection < 35.0) return
        val vulnA = if (a.partnerId != null && a.partnerId != b.id) vulnerability(ctx, a) else 0.0
        val vulnB = if (b.partnerId != null && b.partnerId != a.id) vulnerability(ctx, b) else 0.0
        if (vulnA <= 0.0 && vulnB <= 0.0) return
        val temptation = (
            compatibility(a, b) * 0.25 + rel.attraction / 200.0 +
                (a.personality.impulsiveness + b.personality.impulsiveness) / 2.0 * 0.15 -
                (a.personality.honesty + b.personality.honesty) / 2.0 * 0.2 +
                maxOf(vulnA, vulnB) * 0.35
            ).coerceIn(0.0, 0.06)
        if (!ctx.rng.nextBoolean(temptation)) return
        rel.kind = RelationshipKind.AFFAIR
        rel.attraction += ctx.rng.nextDouble(6.0, 14.0)
        // Hidden: nobody in town knows yet, but it stands ready as a cause once discovered.
        val e = ctx.emit(
            EventType.AFFAIR_BEGAN,
            "${a.fullName} and ${b.fullName} have grown close in a way they haven't mentioned at home.",
            sourceResidentId = a.id, targetResidentIds = listOf(b.id),
            severity = 0.3, visibility = EventVisibility.HIDDEN
        )
        ctx.addMemory(a, MemoryType.ROMANCE, "What started with ${b.firstName} wasn't supposed to happen.", 55.0, e.id, listOf(b.id))
        ctx.addMemory(b, MemoryType.ROMANCE, "What started with ${a.firstName} wasn't supposed to happen.", 55.0, e.id, listOf(a.id))
    }

    private fun progressAffair(ctx: TickContext, a: Resident, b: Resident, rel: Relationship) {
        // It can fizzle out on its own.
        if (rel.affection < 20.0 || ctx.rng.nextBoolean(0.02)) {
            rel.kind = RelationshipKind.ACQUAINTANCE
            rel.clampAll()
            return
        }
        val candidates = listOfNotNull(
            a.partnerId?.let { spouseId -> Triple(a, spouseId, b) },
            b.partnerId?.let { spouseId -> Triple(b, spouseId, a) }
        )
        for ((cheater, spouseId, lover) in candidates) {
            val spouse = ctx.state.resident(spouseId) ?: continue
            val spouseRel = ctx.state.relationship(cheater.id, spouse.id) ?: continue
            val vigilance = (spouseRel.dependency / 100.0 * 0.5 + spouseRel.resentment / 100.0 * 0.3).coerceIn(0.0, 0.8)
            val exposure = (rel.sharedHistory / 100.0 * 0.4 + vigilance).coerceIn(0.0, 0.9)
            val chance = (0.002 + exposure * 0.01).coerceIn(0.0, 0.15)
            if (ctx.rng.nextBoolean(chance)) {
                discoverAffair(ctx, cheater, spouse, rel, lover)
                return
            }
        }
    }

    /** Also used by the Reveal intervention when it lands on an ongoing affair. */
    fun discoverAffair(
        ctx: TickContext, cheater: Resident, spouse: Resident, affairRel: Relationship, lover: Resident,
        causeIds: List<Long> = emptyList()
    ) {
        val e = ctx.emit(
            EventType.AFFAIR_DISCOVERED,
            "${spouse.fullName} has learned the truth about ${cheater.fullName} and ${lover.fullName}.",
            sourceResidentId = cheater.id, targetResidentIds = listOf(spouse.id, lover.id),
            severity = 0.75, visibility = EventVisibility.PRIVATE, causeIds = causeIds
        )
        ctx.addMemory(spouse, MemoryType.BETRAYAL,
            "Finding out about ${cheater.firstName} and ${lover.firstName} broke something.", 90.0, e.id, listOf(cheater.id, lover.id))
        ctx.addMemory(cheater, MemoryType.HUMILIATION,
            "Being found out with ${lover.firstName} was the worst of it.", 70.0, e.id, listOf(spouse.id, lover.id))
        affairRel.kind = RelationshipKind.ACQUAINTANCE
        affairRel.attraction -= 20.0
        affairRel.clampAll()
        ConsequenceEngine.onEvent(ctx, e)
    }

    /** Divorce is processed daily for separated spouses whose rift persists. */
    fun processSeparations(ctx: TickContext) {
        val state = ctx.state
        for (rel in state.relationships.values.filter { it.kind == RelationshipKind.SPOUSE }.sortedBy { it.aId }) {
            val a = state.resident(rel.aId) ?: continue
            val b = state.resident(rel.bId) ?: continue
            if (a.relationshipStatus == RelationshipStatus.SEPARATED &&
                b.relationshipStatus == RelationshipStatus.SEPARATED &&
                rel.resentment > 60 && ctx.rng.nextBoolean(0.05)
            ) {
                rel.kind = RelationshipKind.FORMER_PARTNER
                a.relationshipStatus = RelationshipStatus.DIVORCED; a.partnerId = null
                b.relationshipStatus = RelationshipStatus.DIVORCED; b.partnerId = null
                val e = ctx.emit(
                    EventType.DIVORCE,
                    "The marriage of ${a.fullName} and ${b.fullName} has formally ended.",
                    sourceResidentId = a.id, targetResidentIds = listOf(b.id), severity = 0.65
                )
                ConsequenceEngine.onEvent(ctx, e)
                // One of them looks for a new home.
                val mover = if (a.personality.courage > b.personality.courage) a else b
                GoalSystem.seedGoal(ctx, mover, com.ripple.town.core.model.GoalType.MOVE_HOME,
                    "Staying under the same roof is impossible now.", e.id)
            }
        }
    }

    private fun updateKind(ctx: TickContext, rel: Relationship, a: Resident, b: Resident) {
        if (rel.kind in FIXED_KINDS) return
        val warmth = rel.warmth()
        val newKind = when {
            rel.resentment > 55 && rel.affection < 30 -> RelationshipKind.RIVAL
            warmth > 55 && rel.familiarity > 60 -> RelationshipKind.CLOSE_FRIEND
            warmth > 32 && rel.familiarity > 30 -> RelationshipKind.FRIEND
            rel.familiarity > 12 -> RelationshipKind.ACQUAINTANCE
            else -> RelationshipKind.STRANGER
        }
        if (newKind != rel.kind) {
            val old = rel.kind
            rel.kind = newKind
            if (newKind == RelationshipKind.FRIEND && old in listOf(RelationshipKind.STRANGER, RelationshipKind.ACQUAINTANCE)) {
                val e = ctx.emit(
                    EventType.FRIENDSHIP_FORMED,
                    "${a.fullName} and ${b.fullName} have become friends.",
                    sourceResidentId = a.id, targetResidentIds = listOf(b.id),
                    severity = 0.25, visibility = EventVisibility.PRIVATE
                )
                ConsequenceEngine.onEvent(ctx, e)
            }
            if (newKind == RelationshipKind.RIVAL && old != RelationshipKind.RIVAL) {
                ctx.emit(
                    EventType.RIVALRY_FORMED,
                    "There is bad blood between ${a.fullName} and ${b.fullName} now.",
                    sourceResidentId = a.id, targetResidentIds = listOf(b.id),
                    severity = 0.3, visibility = EventVisibility.PRIVATE
                )
            }
            if (old in listOf(RelationshipKind.FRIEND, RelationshipKind.CLOSE_FRIEND) &&
                newKind in listOf(RelationshipKind.RIVAL, RelationshipKind.STRANGER, RelationshipKind.ACQUAINTANCE)
            ) {
                ctx.emit(
                    EventType.FRIENDSHIP_ENDED,
                    "${a.fullName} and ${b.fullName} have drifted apart.",
                    sourceResidentId = a.id, targetResidentIds = listOf(b.id),
                    severity = 0.2, visibility = EventVisibility.PRIVATE
                )
            }
        }
    }

    /** Slow decay applied daily: absence lowers affection; shared history barely fades. */
    fun dailyDecay(ctx: TickContext) {
        for (rel in ctx.state.relationships.values) {
            val idleDays = (ctx.now - rel.lastInteractionAt) / SimTime.MINUTES_PER_DAY
            if (idleDays > 7) {
                rel.affection -= 0.15
                rel.familiarity -= 0.1
            }
            rel.resentment -= 0.1 // grudges cool very slowly on their own
            rel.sharedHistory -= 0.01
            rel.clampAll()
        }
    }

    private fun isFamily(kind: RelationshipKind) =
        kind == RelationshipKind.FAMILY || kind == RelationshipKind.ESTRANGED_FAMILY

    private val SOCIABLE_ACTIVITIES = setOf(
        Activity.SOCIALISING, Activity.VISITING, Activity.EATING, Activity.SHOPPING,
        Activity.COMMUNITY, Activity.WORKING, Activity.RELAXING, Activity.IDLE, Activity.CELEBRATING
    )
    private val AVAILABLE = setOf(
        RelationshipStatus.SINGLE, RelationshipStatus.DIVORCED, RelationshipStatus.WIDOWED
    )
    private val ROMANTIC_KINDS = setOf(RelationshipKind.PARTNER, RelationshipKind.SPOUSE)
    private val FIXED_KINDS = setOf(
        RelationshipKind.FAMILY, RelationshipKind.ESTRANGED_FAMILY, RelationshipKind.PARTNER,
        RelationshipKind.SPOUSE, RelationshipKind.FORMER_PARTNER, RelationshipKind.AFFAIR
    )
}
