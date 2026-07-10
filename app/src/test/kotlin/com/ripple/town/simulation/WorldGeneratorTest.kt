package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.HealthConditionType
import com.ripple.town.core.model.isHome
import com.ripple.town.core.database.DbJson
import com.ripple.town.core.model.WorldState
import org.junit.Test

class WorldGeneratorTest {

    @Test
    fun `same seed produces identical initial towns`() {
        val a = TestWorld.newState(seed = 7L)
        val b = TestWorld.newState(seed = 7L)
        val jsonA = DbJson.json.encodeToString(WorldState.serializer(), a.copy(createdAtRealMs = 0L))
        val jsonB = DbJson.json.encodeToString(WorldState.serializer(), b.copy(createdAtRealMs = 0L))
        assertThat(jsonA).isEqualTo(jsonB)
    }

    @Test
    fun `different seeds produce different towns`() {
        val a = TestWorld.newState(seed = 1L)
        val b = TestWorld.newState(seed = 2L)
        val namesA = a.residents.values.map { it.fullName }.sorted()
        val namesB = b.residents.values.map { it.fullName }.sorted()
        // Detailed cast is authored, but background population must differ.
        assertThat(namesA).isNotEqualTo(namesB)
        val traitsA = a.residents.values.sortedBy { it.id }.map { it.personality.kindness }
        val traitsB = b.residents.values.sortedBy { it.id }.map { it.personality.kindness }
        assertThat(traitsA).isNotEqualTo(traitsB)
    }

    @Test
    fun `town has the required shape`() {
        val state = TestWorld.newState()
        val detailed = state.residents.values.filter { it.detailLevel == DetailLevel.DETAILED }
        assertThat(detailed).hasSize(30)
        assertThat(state.residents.values.filter { it.detailLevel == DetailLevel.BACKGROUND })
            .hasSize(60)
        val types = state.buildings.values.groupBy { it.type }
        assertThat(state.buildings.values.count { it.type.isHome }).isEqualTo(12)
        assertThat(types[BuildingType.SCHOOL]).hasSize(1)
        assertThat(types[BuildingType.CLINIC]).hasSize(1)
        assertThat(types[BuildingType.TOWN_HALL]).hasSize(1)
        assertThat(types[BuildingType.PARK]).hasSize(1)
        assertThat(types[BuildingType.PUB]).hasSize(1)
        assertThat(types[BuildingType.FACTORY]).hasSize(1)
        assertThat(types[BuildingType.CEMETERY]).hasSize(1)
        assertThat(types[BuildingType.VACANT]).hasSize(1)
        // Eight private businesses seeded (clinic/school/town hall are services).
        val commercial = state.businesses.values.filterNot {
            it.type.name in listOf("CLINIC", "SCHOOL", "TOWN_HALL")
        }
        assertThat(commercial).hasSize(8)
    }

    @Test
    fun `scenario seeds are planted`() {
        val state = TestWorld.newState()
        // 1. A struggling bakery
        val bakery = state.businesses.values.first { it.name == "Bell's Bakery" }
        assertThat(bakery.balance).isLessThan(500.0)
        // 8. A hidden health condition
        val arthur = TestWorld.resident(state, "Arthur Pemberton")
        assertThat(arthur.conditions.single().type).isEqualTo(HealthConditionType.WEAK_HEART)
        assertThat(arthur.conditions.single().hidden).isTrue()
        // 4. A teen thinking of leaving
        val kit = TestWorld.resident(state, "Kit Hartley")
        assertThat(kit.goals.map { it.type }).contains(GoalType.LEAVE_FOR_EDUCATION)
        // 5. An exhausted clinic worker
        assertThat(TestWorld.resident(state, "Sylvie Crane").needs.stress).isGreaterThan(60.0)
        // 9. A noisy business near homes
        assertThat(state.buildings.values.first { it.type == BuildingType.FACTORY }.noise)
            .isGreaterThan(40.0)
        // 10. A pending new-family thread
        assertThat(state.delayedEffects.map { it.note }).contains("new_family_arrival")
        // Followed resident is introduced
        val followed = state.resident(state.followedResidentId!!)!!
        assertThat(followed.fullName).isEqualTo("Mara Vale")
        // Buildings never overlap each other or water
        val map = state.map
        val claimed = mutableSetOf<Pair<Int, Int>>()
        for (b in state.buildings.values) {
            for (x in b.origin.x until b.origin.x + b.width) {
                for (y in b.origin.y until b.origin.y + b.height) {
                    assertThat(claimed.add(x to y)).isTrue()
                    assertThat(map.tileAt(x, y)).isNotEqualTo(com.ripple.town.core.model.TileType.WATER)
                }
            }
        }
    }
}
