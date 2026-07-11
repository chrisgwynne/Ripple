package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingState
import com.ripple.town.core.model.District
import com.ripple.town.core.model.DistrictCharacter
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.isHome

/**
 * Reclassifies each district's [DistrictCharacter] once per in-game week from
 * four aggregate signals: vacancy rate, wealth index, crime rate, and employment rate.
 *
 * The classification is deterministic from those signals — the same state always
 * produces the same character, so it can be recomputed cheaply without storing history.
 * A DISTRICT_CHARACTER_CHANGED event fires whenever the classification changes.
 */
object DistrictCharacterSystem {

    const val UPDATE_INTERVAL_DAYS = 7L

    fun updateWeekly(ctx: TickContext) {
        val state = ctx.state
        val townAvgWealth = state.livingResidents().filter { it.inTown }.map { it.wealth }.average0()
        for (district in state.districts.values) {
            updateDistrict(ctx, district, townAvgWealth)
        }
    }

    private fun updateDistrict(ctx: TickContext, district: District, townAvgWealth: Double) {
        val state = ctx.state

        // Residents who live in this district.
        val districtBuildings = state.buildings.values.filter { b ->
            district.containsTile(b.origin.x, b.origin.y) &&
            b.buildingState != BuildingState.DEMOLISHED
        }
        if (districtBuildings.isEmpty()) return

        // Vacancy rate: fraction of non-civic buildings that are vacant/derelict/condemned.
        val nonCivic = districtBuildings.filter {
            it.type != com.ripple.town.core.model.BuildingType.PARK &&
            it.type != com.ripple.town.core.model.BuildingType.CEMETERY
        }
        val vacantCount = nonCivic.count {
            it.buildingState == BuildingState.VACANT ||
            it.buildingState == BuildingState.DERELICT ||
            it.buildingState == BuildingState.CONDEMNED
        }
        val vacancyRate = if (nonCivic.isEmpty()) 0.0 else vacantCount.toDouble() / nonCivic.size
        district.vacancyRate = vacancyRate

        // Residents in this district.
        val homeIds = districtBuildings.filter { it.type.isHome }.map { it.id }.toSet()
        val districtResidents = state.livingResidents().filter { r ->
            r.inTown && r.homeBuildingId in homeIds
        }
        val pop = districtResidents.size
        district.population = pop

        // Wealth: average resident wealth relative to town average (townAvgWealth hoisted to caller).
        val districtAvgWealth = districtResidents.map { it.wealth }.average0()
        district.wealthIndex = if (townAvgWealth > 0) districtAvgWealth / townAvgWealth else 1.0

        // Employment rate among working-age adults.
        val workingAge = districtResidents.count { r ->
            r.lifeStageAt(state.time) == LifeStage.ADULT && r.ageAt(state.time) < 66
        }
        val employed = districtResidents.count { r ->
            state.employmentOf(r) != null
        }
        val employmentRate = if (workingAge == 0) 1.0 else employed.toDouble() / workingAge

        // Prosperity index: 0..100 composite.
        val prosperityRaw = (district.wealthIndex - 1.0) * 20.0 +
            (employmentRate - 0.5) * 30.0 +
            (1.0 - vacancyRate) * 20.0 +
            (1.0 - district.crimeRate) * 30.0
        district.prosperityIndex = (50.0 + prosperityRaw).coerceIn(0.0, 100.0)

        val newCharacter = classify(vacancyRate, district.wealthIndex, district.crimeRate, employmentRate, pop)
        if (newCharacter != district.character) {
            val old = district.character
            district.character = newCharacter
            ctx.emit(
                EventType.DISTRICT_CHARACTER_CHANGED,
                "${district.name} has shifted from ${old.label.lowercase()} to ${newCharacter.label.lowercase()}.",
                severity = 0.4, visibility = EventVisibility.PUBLIC
            )
        }
    }

    private fun classify(
        vacancyRate: Double,
        wealthIndex: Double,
        crimeRate: Double,
        employmentRate: Double,
        pop: Int
    ): DistrictCharacter {
        if (vacancyRate > 0.5) return DistrictCharacter.DERELICT
        if (vacancyRate > 0.3) return DistrictCharacter.DECLINING
        if (crimeRate > 0.75) return DistrictCharacter.HIGH_CRIME
        if (wealthIndex > 1.3 && vacancyRate < 0.05 && crimeRate < 0.4)
            return DistrictCharacter.PROSPEROUS
        // GENTRIFYING before STABLE: rising wealth (1.15–1.3) + moderate vacancy (0.05–0.15) is
        // gentrification in progress; the old STABLE ordering would shadow this entirely when
        // employment is high, making GENTRIFYING unreachable in the most common real-world case.
        if (wealthIndex > 1.15 && vacancyRate < 0.15)
            return DistrictCharacter.GENTRIFYING
        if (vacancyRate < 0.05 && wealthIndex > 0.9 && employmentRate > 0.75)
            return DistrictCharacter.STABLE
        if (pop > 0 && vacancyRate < 0.1 && employmentRate < 0.5)
            return DistrictCharacter.OVERCROWDED
        if (vacancyRate < 0.1 && wealthIndex < 0.85 && wealthIndex > 0.6)
            return DistrictCharacter.FAMILY_SUBURB
        if (vacancyRate > 0.1 && wealthIndex > 1.1)
            return DistrictCharacter.REGENERATING
        return DistrictCharacter.STABLE
    }
}

private fun List<Double>.average0(): Double = if (isEmpty()) 0.0 else average()
