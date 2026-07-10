package com.ripple.town.core.database

import com.ripple.town.core.model.Building
import com.ripple.town.core.model.Business
import com.ripple.town.core.model.DelayedEffect
import com.ripple.town.core.model.Employment
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.Goal
import com.ripple.town.core.model.HealthCondition
import com.ripple.town.core.model.Household
import com.ripple.town.core.model.Intervention
import com.ripple.town.core.model.Memory
import com.ripple.town.core.model.Needs
import com.ripple.town.core.model.NewspaperIssue
import com.ripple.town.core.model.NewspaperStory
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.Relationship
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.SpriteConfig
import com.ripple.town.core.model.TownStatistic
import com.ripple.town.core.model.WorldEvent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object DbJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

/** Ids stored as ",1,2,3," so `LIKE '%,x,%'` matches exactly. */
fun List<Long>.toCsv(): String = if (isEmpty()) "" else joinToString(",", prefix = ",", postfix = ",")
fun String.csvToLongs(): List<Long> = split(',').filter { it.isNotBlank() }.map { it.toLong() }

fun WorldEvent.toEntity(): WorldEventEntity = WorldEventEntity(
    id = id, worldId = worldId, time = time, type = type.name,
    sourceResidentId = sourceResidentId,
    targetResidentIdsCsv = targetResidentIds.toCsv(),
    buildingId = buildingId, businessId = businessId,
    severity = severity, visibility = visibility.name, description = description,
    payloadJson = DbJson.json.encodeToString(
        MapSerializer(String.serializer(), String.serializer()), payload
    ),
    consequenceDepth = consequenceDepth, importance = importance
)

fun WorldEventEntity.toDomain(causeIds: List<Long> = emptyList()): WorldEvent = WorldEvent(
    id = id, worldId = worldId, time = time, type = EventType.valueOf(type),
    sourceResidentId = sourceResidentId,
    targetResidentIds = targetResidentIdsCsv.csvToLongs(),
    buildingId = buildingId, businessId = businessId,
    severity = severity, visibility = EventVisibility.valueOf(visibility),
    description = description,
    payload = DbJson.json.decodeFromString(
        MapSerializer(String.serializer(), String.serializer()), payloadJson
    ),
    causeIds = causeIds, consequenceDepth = consequenceDepth, importance = importance
)

fun WorldEvent.causeEntities(): List<EventCauseEntity> =
    causeIds.map { EventCauseEntity(eventId = id, causeEventId = it) }

fun Resident.toEntity(worldId: Long): ResidentEntity = ResidentEntity(
    id = id, worldId = worldId, firstName = firstName, surname = surname,
    gender = gender.name, bornAt = bornAt, alive = alive, diedAt = diedAt,
    causeOfDeath = causeOfDeath, leftTownAt = leftTownAt,
    detailLevel = detailLevel.name, homeBuildingId = homeBuildingId,
    householdId = householdId, occupation = occupation, employmentId = employmentId,
    relationshipStatus = relationshipStatus.name, partnerId = partnerId,
    motherId = motherId, fatherId = fatherId,
    wealth = wealth, debt = debt, reputation = reputation, politicalInterest = politicalInterest,
    needsJson = DbJson.json.encodeToString(Needs.serializer(), needs),
    personalityJson = DbJson.json.encodeToString(Personality.serializer(), personality),
    spriteJson = DbJson.json.encodeToString(SpriteConfig.serializer(), sprite),
    childIdsCsv = childIds.toCsv()
)

fun Household.toEntity(worldId: Long): HouseholdEntity = HouseholdEntity(
    id = id, worldId = worldId, name = name, homeBuildingId = homeBuildingId,
    savings = savings, monthlyRent = monthlyRent, memberIdsCsv = memberIds.toCsv()
)

fun Relationship.toEntity(): RelationshipEntity = RelationshipEntity(
    aId = aId, bId = bId, kind = kind.name, familiarity = familiarity, trust = trust,
    affection = affection, attraction = attraction, respect = respect,
    resentment = resentment, dependency = dependency, sharedHistory = sharedHistory,
    lastInteractionAt = lastInteractionAt
)

fun Building.toEntity(worldId: Long): BuildingEntity = BuildingEntity(
    id = id, worldId = worldId, name = name, type = type.name,
    originX = origin.x, originY = origin.y, width = width, height = height,
    doorX = door.x, doorY = door.y, ownerId = ownerId, condition = condition,
    noise = noise, value = value, capacity = capacity, constructedAt = constructedAt,
    upgradeLevel = upgradeLevel, abandoned = abandoned,
    visibleChangesJson = DbJson.json.encodeToString(ListSerializer(String.serializer()), visibleChanges)
)

fun Business.toEntity(): BusinessEntity = BusinessEntity(
    id = id, buildingId = buildingId, name = name, type = type.name, ownerId = ownerId,
    balance = balance, reputation = reputation, demand = demand, priceLevel = priceLevel,
    employeeCapacity = employeeCapacity, open = open, openedAt = openedAt,
    closedAt = closedAt, daysInTrouble = daysInTrouble
)

fun Employment.toEntity(): EmploymentEntity = EmploymentEntity(
    id = id, residentId = residentId, businessId = businessId, role = role,
    dailySalary = dailySalary, startedAt = startedAt, endedAt = endedAt,
    shiftStartHour = shiftStartHour, shiftEndHour = shiftEndHour, reducedHours = reducedHours
)

fun HealthCondition.toEntity(): HealthConditionEntity = HealthConditionEntity(
    id = id, residentId = residentId, type = type.name, severity = severity,
    startedAt = startedAt, hidden = hidden, diagnosedAt = diagnosedAt, recoveredAt = recoveredAt
)

fun Memory.toEntity(): MemoryEntity = MemoryEntity(
    id = id, residentId = residentId, eventId = eventId, type = type.name,
    description = description, emotionalIntensity = emotionalIntensity,
    accuracy = accuracy, importance = importance, createdAt = createdAt,
    lastRecalledAt = lastRecalledAt, decayPerYear = decayPerYear,
    associatedResidentIdsCsv = associatedResidentIds.toCsv(), beliefFormed = beliefFormed
)

fun Goal.toEntity(): GoalEntity = GoalEntity(
    id = id, ownerId = ownerId, type = type.name, motivation = motivation,
    createdAt = createdAt, progress = progress, risk = risk, status = status.name,
    resolvedAt = resolvedAt, targetResidentId = targetResidentId,
    targetSkill = targetSkill?.name, causeEventId = causeEventId
)

fun DelayedEffect.toEntity(): DelayedEffectEntity = DelayedEffectEntity(
    id = id, sourceEventId = sourceEventId, targetResidentId = targetResidentId,
    secondaryResidentId = secondaryResidentId, targetBusinessId = targetBusinessId,
    type = type.name, strength = strength, earliestAt = earliestAt, latestAt = latestAt,
    condition = condition.name, decayPerDay = decayPerDay, applied = applied,
    cancelled = cancelled, note = note
)

fun Intervention.toEntity(): InterventionEntity = InterventionEntity(
    id = id, verb = verb.name, targetResidentId = targetResidentId,
    secondaryResidentId = secondaryResidentId, targetBuildingId = targetBuildingId,
    appliedAt = appliedAt, note = note, eventId = eventId
)

fun NewspaperIssue.toEntity(): NewspaperIssueEntity = NewspaperIssueEntity(
    id = id, issueNumber = issueNumber, publishedAt = publishedAt, masthead = masthead
)

fun NewspaperStory.toEntity(): NewspaperStoryEntity = NewspaperStoryEntity(
    id = id, issueId = issueId, category = category.name, headline = headline,
    body = body, eventId = eventId, orderInIssue = orderInIssue
)

fun TownStatistic.toEntity(): TownStatisticEntity = TownStatisticEntity(
    dayIndex = dayIndex, population = population, detailedPopulation = detailedPopulation,
    employedAdults = employedAdults, adultCount = adultCount, openBusinesses = openBusinesses,
    averageWellbeing = averageWellbeing, averageWealth = averageWealth,
    births = births, deaths = deaths
)

fun skillCatalog(): List<SkillEntity> = SkillType.entries.map { SkillEntity(it.name, it.label) }

fun Resident.skillEntities(): List<ResidentSkillEntity> =
    skills.map { (k, v) -> ResidentSkillEntity(residentId = id, skillName = k.name, value = v) }
