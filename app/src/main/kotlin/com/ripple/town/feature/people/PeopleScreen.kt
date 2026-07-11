package com.ripple.town.feature.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.ui.PixelAvatar
import com.ripple.town.core.ui.SpriteProvider
import com.ripple.town.data.EventUi
import com.ripple.town.data.ResidentUi
import com.ripple.town.data.WorldUi
import com.ripple.town.feature.town.TownViewModel
import com.ripple.town.feature.town.poseFor
import kotlin.math.hypot

/**
 * People: a population browser for the whole town, filtered and sorted from one
 * control row, rendered as a single de-duplicated list. A compact pinned card
 * surfaces whoever is currently followed; the full family-tree/relationship-map
 * view lives on that resident's own profile screen now, not here (see
 * `ResidentProfileScreen`/`FamilyTreeDialog` — reachable via "Open Profile").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    sprites: SpriteProvider,
    viewModel: TownViewModel,
    onOpenResident: (Long) -> Unit
) {
    val world by viewModel.world.collectAsState()
    val w = world ?: return
    val recentEvents by viewModel.recentEvents.collectAsState()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PeopleFilter.ALL) }
    var sort by remember { mutableStateOf(PeopleSort.RECENTLY_ACTIVE) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val followed = w.resident(w.followedResidentId)
    val familyIds = followed?.let { familyOf(w, it).map { (r, _) -> r.id }.toSet() } ?: emptySet()
    val friendIds = followed?.relationships
        ?.filter { it.kindLabel in listOf("Friend", "Close friend") }
        ?.map { it.otherId }?.toSet() ?: emptySet()
    // Coworkers: same employer name as the followed resident, or sharing the followed
    // resident's current workplace building. `employerName`/`currentBuildingId` are the only
    // employment signals already on ResidentUi — no new field needed.
    val coworkerIds = followed?.let { f ->
        w.residents.filter { r ->
            r.id != f.id && r.alive && (
                (f.employerName != null && r.employerName == f.employerName) ||
                    (f.currentBuildingId != null && r.currentBuildingId == f.currentBuildingId && r.occupation.isNotBlank())
                )
        }.map { it.id }.toSet()
    } ?: emptySet()

    // Recently seen / recently updated both approximate "something happened to this
    // resident lately" from the same cheap source: the last 30 town-wide events already
    // streamed to TownViewModel.recentEvents, cross-referenced by involvedResidentIds. There
    // is no dedicated "last seen"/"last updated" timestamp per resident anywhere in
    // WorldRepository/WorldSnapshot, so this is a best-effort proxy, not a real activity log.
    val eventIdsByResident: Map<Long, List<EventUi>> = remember(recentEvents) {
        val map = mutableMapOf<Long, MutableList<EventUi>>()
        recentEvents.forEach { e -> e.involvedResidentIds.forEach { id -> map.getOrPut(id) { mutableListOf() }.add(e) } }
        map
    }
    val recentlySeenIds = eventIdsByResident.keys
    val updatedIds = eventIdsByResident.filterValues { evts -> evts.any { it.type?.name in UPDATE_EVENT_TYPES } }.keys

    fun distanceTo(r: ResidentUi): Float =
        followed?.let { hypot((r.x - it.x).toDouble(), (r.y - it.y).toDouble()).toFloat() } ?: Float.MAX_VALUE

    val nearbyIds = if (followed != null) {
        w.residents.filter { it.id != followed.id && it.inTown && it.alive && distanceTo(it) <= NEARBY_TILE_RADIUS }
            .map { it.id }.toSet()
    } else emptySet()

    fun matchesFilter(r: ResidentUi): Boolean = when (filter) {
        PeopleFilter.ALL -> true
        PeopleFilter.FOLLOWING -> r.id == w.followedResidentId
        PeopleFilter.FAVOURITES -> r.id in w.favouriteIds
        PeopleFilter.FAMILY -> r.id in familyIds
        PeopleFilter.FRIENDS -> r.id in friendIds
        PeopleFilter.COWORKERS -> r.id in coworkerIds
        PeopleFilter.CHILDREN -> r.lifeStage == LifeStage.CHILD || r.lifeStage == LifeStage.TEEN
        PeopleFilter.ELDERLY -> r.lifeStage == LifeStage.ELDER
        PeopleFilter.NEARBY -> r.id in nearbyIds
        PeopleFilter.RECENTLY_SEEN -> r.id in recentlySeenIds
        PeopleFilter.RECENTLY_UPDATED -> r.id in updatedIds
    }

    val base = w.residents.filter { it.detailed }
    val sorted = when (sort) {
        PeopleSort.RECENTLY_ACTIVE -> base.sortedByDescending { eventIdsByResident[it.id]?.maxOfOrNull { e -> e.time } ?: -1L }
        PeopleSort.ALPHABETICAL -> base.sortedBy { it.name }
        PeopleSort.AGE -> base.sortedByDescending { it.age }
        PeopleSort.OCCUPATION -> base.sortedBy { it.occupation.ifBlank { "zzz" } }
        PeopleSort.HOUSEHOLD -> base.sortedBy { it.homeName ?: "zzz" }
        PeopleSort.DISTANCE -> base.sortedBy { distanceTo(it) }
        PeopleSort.MOOD -> base.sortedByDescending { it.mood.ordinal }
        PeopleSort.HEALTH -> base.sortedByDescending { it.health }
        PeopleSort.FOLLOWING -> base.sortedWith(compareByDescending<ResidentUi> { it.id == w.followedResidentId }.thenBy { it.name })
        PeopleSort.FAVOURITES -> base.sortedWith(compareByDescending<ResidentUi> { it.id in w.favouriteIds }.thenBy { it.name })
    }

    val population = sorted.filter { matchesFilter(it) }

    val searchResults = if (query.isBlank()) emptyList() else
        w.residents.filter { it.detailed && it.name.contains(query, ignoreCase = true) }.take(20)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                "People of ${w.townName}",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search residents…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(PeopleFilter.entries, key = { it.name }) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedButton(onClick = { sortMenuOpen = true }) {
                    Text("Sort: ${sort.label}")
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    PeopleSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            onClick = { sort = s; sortMenuOpen = false }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (searchResults.isNotEmpty()) {
            item { Text("Search results", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp)) }
            items(searchResults, key = { "s${it.id}" }) { r ->
                PersonRow(r, sprites, w, followed, eventIdsByResident[r.id].orEmpty()) { onOpenResident(r.id) }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (followed != null) {
            item {
                FollowedCard(followed, sprites, onOpenProfile = { onOpenResident(followed.id) })
                Spacer(Modifier.height(12.dp))
            }
        }
        items(population, key = { "p${it.id}" }) { r ->
            PersonRow(r, sprites, w, followed, eventIdsByResident[r.id].orEmpty()) { onOpenResident(r.id) }
        }
        if (population.isEmpty()) {
            item {
                Text(
                    "No one matches this filter yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Tile-distance threshold for the "Nearby" filter — reasonable walking-distance proximity. */
private const val NEARBY_TILE_RADIUS = 12f

/**
 * Event types that read as "something changed" for a resident — new job, new relationship,
 * birth, death, moved house, marriage, illness, arrest/crime, argument — reusing the existing
 * [com.ripple.town.core.model.EventType] catalogue rather than inventing a new classification.
 * Shared by the "Recently Updated" filter and the per-row update badge/dot.
 */
private val UPDATE_EVENT_TYPES = setOf(
    "JOB_STARTED", "JOB_LOST", "JOB_QUIT", "RELATIONSHIP_STARTED", "ENGAGEMENT", "MARRIAGE",
    "SEPARATION", "DIVORCE", "PERSON_BORN", "PERSON_DIED", "RESIDENT_MOVED", "HOME_PURCHASED",
    "ILLNESS_STARTED", "ILLNESS_DIAGNOSED", "CRIME_COMMITTED", "ARGUMENT", "DOMESTIC_DISTURBANCE"
)

enum class PeopleFilter(val label: String) {
    ALL("All"), FOLLOWING("Following"), FAVOURITES("Favourites"), FAMILY("Family"),
    FRIENDS("Friends"), COWORKERS("Coworkers"), CHILDREN("Children"), ELDERLY("Elderly"),
    NEARBY("Nearby"), RECENTLY_SEEN("Recently Seen"), RECENTLY_UPDATED("Recently Updated")
}

enum class PeopleSort(val label: String) {
    RECENTLY_ACTIVE("Recently active"), ALPHABETICAL("Alphabetical"), AGE("Age"),
    OCCUPATION("Occupation"), HOUSEHOLD("Household"), DISTANCE("Distance"),
    MOOD("Mood"), HEALTH("Health"), FOLLOWING("Following"), FAVOURITES("Favourites")
}

/** Compact pinned card for whoever is currently followed — no embedded family tree here by design. */
@Composable
private fun FollowedCard(
    followed: ResidentUi,
    sprites: SpriteProvider,
    onOpenProfile: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenProfile)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelAvatar(followed.sprite, sprites, size = 56.dp, pose = poseFor(followed), lifeStage = followed.lifeStage, occupation = followed.occupation)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(followed.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${followed.age} · ${followed.employerName ?: followed.occupation} · ${followed.activity.label}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Mood: ${followed.mood.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenProfile) {
                Text("Open Profile")
            }
        }
    }
}

@Composable
private fun PersonRow(
    r: ResidentUi,
    sprites: SpriteProvider,
    world: WorldUi,
    followed: ResidentUi?,
    recentEventsForResident: List<EventUi>,
    onClick: () -> Unit
) {
    val relationBadge = relationshipBadge(r, followed)
    val updateBadge = updateBadgeFor(recentEventsForResident, UPDATE_EVENT_TYPES)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PixelAvatar(r.sprite, sprites, size = 40.dp, lifeStage = r.lifeStage, occupation = r.occupation)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, style = MaterialTheme.typography.titleSmall)
                if (relationBadge != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(relationBadge, style = MaterialTheme.typography.titleSmall)
                }
                if (updateBadge != null) {
                    Spacer(Modifier.width(4.dp))
                    Text("•", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                }
            }
            Text(
                if (!r.alive) "Died aged ${r.age}"
                else if (!r.inTown) "Away"
                else "${r.age} · ${r.occupation} · ${r.activity.label} · ${moodGlyph(r)} ${r.mood.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (r.alive && r.inTown) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "♥ ${healthLabel(r.health)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (r.health < 50) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        wealthLabel(r.wealth, r.debt),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (r.debt > 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (updateBadge != null) {
                Text(
                    updateBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (r.id == world.followedResidentId) {
            Text("●", color = MaterialTheme.colorScheme.primary)
        } else if (r.id in world.favouriteIds) {
            Text("★", color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private fun healthLabel(h: Double): String = when {
    h < 25 -> "Critical"
    h < 50 -> "Poor"
    h < 70 -> "Unwell"
    h < 85 -> "Good"
    else -> "Healthy"
}

private fun wealthLabel(wealth: Double, debt: Double): String = when {
    debt > 0 -> "Debt £${debt.toInt()}"
    wealth >= 1000 -> "£${"%.1f".format(wealth / 1000)}k"
    else -> "£${wealth.toInt()}"
}

/** Short emoji/glyph mood indicator alongside the existing text label. */
private fun moodGlyph(r: ResidentUi): String = when (r.mood.label) {
    "Despairing" -> "😭" // 😭
    "Low" -> "😞" // 😞
    "Flat" -> "😐" // 😐
    "Content" -> "🙂" // 🙂
    "Happy" -> "😊" // 😊
    "Joyful" -> "😄" // 😄
    else -> ""
}

/**
 * Small relationship glyph shown on a row IF that resident has a relationship with the
 * currently-followed resident — simple text/emoji, no new icon assets. Family relation
 * (via familyOf) takes priority over the warmth-ranked relationship list for the "partner"
 * case since partner already appears in both.
 */
private fun relationshipBadge(r: ResidentUi, followed: ResidentUi?): String? {
    if (followed == null || r.id == followed.id) return null
    if (r.id == followed.partnerId) return "❤️" // heart
    val relation = followed.relationships.firstOrNull { it.otherId == r.id } ?: return null
    return when (relation.kindLabel) {
        "Close friend" -> "⭐" // star
        "Friend" -> "👥" // people
        "Rival" -> "⚡" // bolt
        "Family", "Spouse" -> "👪" // family
        else -> if (r.employerName != null && r.employerName == followed.employerName) "💼" else null // briefcase
    }
}

/** Cheap "something changed" label from the last few recent events already involving this resident. */
private fun updateBadgeFor(events: List<EventUi>, updateTypes: Set<String>): String? =
    events.filter { it.type?.name in updateTypes }.maxByOrNull { it.time }?.typeLabel

/** Family members with role labels, walking one generation each way + partner/siblings. */
fun familyOf(world: WorldUi, r: ResidentUi): List<Pair<ResidentUi, String>> {
    val out = mutableListOf<Pair<ResidentUi, String>>()
    world.resident(r.partnerId)?.let { out += it to "Partner" }
    world.resident(r.motherId)?.let { out += it to "Mother" }
    world.resident(r.fatherId)?.let { out += it to "Father" }
    r.childIds.mapNotNull { world.resident(it) }.forEach { out += it to "Child" }
    // Siblings: share a parent.
    world.residents
        .filter {
            it.id != r.id &&
                ((it.motherId != null && it.motherId == r.motherId) || (it.fatherId != null && it.fatherId == r.fatherId))
        }
        .forEach { out += it to "Sibling" }
    return out.distinctBy { it.first.id }
}
