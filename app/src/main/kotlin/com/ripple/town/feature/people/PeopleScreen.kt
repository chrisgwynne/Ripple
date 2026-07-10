package com.ripple.town.feature.people

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.ripple.town.core.ui.PixelAvatar
import com.ripple.town.core.ui.SectionTitle
import com.ripple.town.core.ui.SpriteProvider
import com.ripple.town.data.ResidentUi
import com.ripple.town.data.WorldUi
import com.ripple.town.feature.town.TownViewModel
import com.ripple.town.feature.town.poseFor

/**
 * People: who you follow, who you've marked, families and connections.
 * Tapping anyone opens their sheet over the town.
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
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PeopleFilter.ALL) }
    var treeResidentId by remember { mutableStateOf<Long?>(null) }

    val followed = w.resident(w.followedResidentId)
    val favourites = w.favouriteIds.mapNotNull { w.resident(it) }
    val family = followed?.let { familyOf(w, it) } ?: emptyList()
    val friends = followed?.relationships
        ?.filter { it.kindLabel in listOf("Friend", "Close friend") }
        ?.mapNotNull { w.resident(it.otherId) } ?: emptyList()
    val rivals = followed?.relationships
        ?.filter { it.kindLabel == "Rival" || it.resentment > 50 }
        ?.mapNotNull { w.resident(it.otherId) } ?: emptyList()
    val discovered = w.discoveredIds.mapNotNull { w.resident(it) }

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
        }
        if (searchResults.isNotEmpty()) {
            item { SectionTitle("Search results") }
            items(searchResults, key = { "s${it.id}" }) { r ->
                PersonRow(r, sprites, w, familyOf(w, r), onOpenTree = { treeResidentId = it }) { onOpenResident(r.id) }
            }
        }
        if (followed != null && filter in listOf(PeopleFilter.ALL, PeopleFilter.FOLLOWED)) {
            item { SectionTitle("Following") }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenResident(followed.id) }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelAvatar(followed.sprite, sprites, size = 56.dp, pose = poseFor(followed), lifeStage = followed.lifeStage, occupation = followed.occupation)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(followed.name, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${followed.age} · ${followed.occupation} · ${followed.activity.label}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Mood: ${followed.mood.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (family.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Family: " + family.joinToString(", ") { (r, role) -> "${r.firstName} ($role)" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { treeResidentId = followed.id }) {
                            Text("View family tree & relationships")
                        }
                    }
                }
            }
        }
        if (favourites.isNotEmpty() && filter in listOf(PeopleFilter.ALL, PeopleFilter.FAVOURITES)) {
            item { SectionTitle("Favourites") }
            items(favourites, key = { "f${it.id}" }) { r ->
                PersonRow(r, sprites, w, familyOf(w, r), onOpenTree = { treeResidentId = it }) { onOpenResident(r.id) }
            }
        }
        if (family.isNotEmpty() && filter in listOf(PeopleFilter.ALL, PeopleFilter.FAMILY)) {
            item { SectionTitle("${followed?.firstName}'s family") }
            items(family, key = { "fam${it.first.id}" }) { (r, role) ->
                PersonRow(r, sprites, w, familyOf(w, r), subtitle = role, onOpenTree = { treeResidentId = it }) { onOpenResident(r.id) }
            }
        }
        if (friends.isNotEmpty() && filter in listOf(PeopleFilter.ALL, PeopleFilter.FRIENDS)) {
            item { SectionTitle("${followed?.firstName}'s friends") }
            items(friends, key = { "fr${it.id}" }) { r ->
                PersonRow(r, sprites, w, familyOf(w, r), onOpenTree = { treeResidentId = it }) { onOpenResident(r.id) }
            }
        }
        if (rivals.isNotEmpty() && filter in listOf(PeopleFilter.ALL, PeopleFilter.FRIENDS)) {
            item { SectionTitle("Frictions") }
            items(rivals, key = { "rv${it.id}" }) { r ->
                PersonRow(r, sprites, w, familyOf(w, r), onOpenTree = { treeResidentId = it }) { onOpenResident(r.id) }
            }
        }
        if (discovered.isNotEmpty() && filter in listOf(PeopleFilter.ALL, PeopleFilter.DISCOVERED)) {
            item { SectionTitle("Recently discovered") }
            items(discovered.takeLast(10).reversed(), key = { "d${it.id}" }) { r ->
                PersonRow(r, sprites, w, familyOf(w, r), onOpenTree = { treeResidentId = it }) { onOpenResident(r.id) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    val treeId = treeResidentId
    if (treeId != null) {
        FamilyTreeDialog(
            world = w,
            residentId = treeId,
            sprites = sprites,
            onOpenResident = { treeResidentId = null; onOpenResident(it) },
            onDismiss = { treeResidentId = null }
        )
    }
}

enum class PeopleFilter(val label: String) {
    ALL("All"), FOLLOWED("Followed"), FAVOURITES("Favourites"), FAMILY("Family"),
    FRIENDS("Friends"), DISCOVERED("Discovered")
}

@Composable
private fun PersonRow(
    r: ResidentUi,
    sprites: SpriteProvider,
    world: WorldUi,
    family: List<Pair<ResidentUi, String>> = emptyList(),
    subtitle: String? = null,
    onOpenTree: (Long) -> Unit = {},
    onClick: () -> Unit
) {
    var expanded by remember(r.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PixelAvatar(r.sprite, sprites, size = 40.dp, lifeStage = r.lifeStage, occupation = r.occupation)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle ?: (if (!r.alive) "Died aged ${r.age}" else if (!r.inTown) "Away" else "${r.age} · ${r.occupation}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (family.isNotEmpty()) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Hide family" else "Show family"
                    )
                }
            }
            if (r.id == world.followedResidentId) {
                Text("●", color = MaterialTheme.colorScheme.primary)
            } else if (r.id in world.favouriteIds) {
                Text("★", color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (family.isNotEmpty()) {
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.fillMaxWidth().padding(start = 50.dp, bottom = 8.dp)) {
                    family.forEach { (member, role) ->
                        Text(
                            "$role: ${member.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onOpenTree(r.id) }) {
                        Text("View family tree & relationships", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

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
