package com.ripple.town.feature.town

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.isHome
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.StoryCategory
import com.ripple.town.core.model.Weather
import com.ripple.town.core.ui.CauseConnector
import com.ripple.town.core.ui.EmptyNote
import com.ripple.town.core.ui.PixelAvatar
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.core.ui.SectionTitle
import com.ripple.town.core.ui.SpriteProvider
import com.ripple.town.core.ui.StatBar
import com.ripple.town.core.database.InterventionEntity
import com.ripple.town.core.model.InterventionVerb
import com.ripple.town.core.simulation.InterventionEngine
import com.ripple.town.data.DeathSummary
import com.ripple.town.data.EventUi
import com.ripple.town.data.TownStatsUi
import com.ripple.town.data.WorldUi

// ResidentSheetContent moved to ResidentProfileScreen.kt (hero header + tabs redesign).

// ------------------------------------------------------------ building sheet

@Composable
fun BuildingSheetContent(world: WorldUi, buildingId: Long, viewModel: TownViewModel) {
    val b = world.building(buildingId) ?: return
    val buildingEvents by viewModel.buildingEvents.collectAsState()
    val owner = world.resident(b.ownerId)
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(b.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            buildingStatusPhrase(world, b),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (owner != null) {
            SectionTitle("Owner")
            OwnerCard(owner = owner, b = b, onClick = { viewModel.openResident(owner.id) })
        } else {
            b.ownerName?.let { Text("Owned by $it", style = MaterialTheme.typography.bodyMedium) }
        }

        if (b.businessName != null) {
            SectionTitle("Business")
            BusinessHealthSection(b, owner)
        }

        SectionTitle("Building")
        StatBar("Condition", b.condition)
        StatBar("Noise", b.noise, good = false)
        Text("Value: ${b.value.toInt()} coins", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Currently used as: " + (if (b.businessName != null) "a business" else if (b.type.isHome) "a home" else b.typeLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (b.districtName != null) {
            Text(
                "District: ${b.districtName}" + if (b.districtCharacter != null) " · ${b.districtCharacter}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (b.constructedAt != null) {
            Text(
                "Built ${SimTime.formatDateLong(b.constructedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (b.abandoned) {
            SectionTitle("What could happen here")
            if (b.type == BuildingType.VACANT) {
                Text(
                    "An ambitious resident with the right skills could one day buy this building and open a new business here.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    "Standing empty for now — no buyer or new use has been decided yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        val occupants = b.occupantIds.mapNotNull { world.resident(it) }
        if (occupants.isNotEmpty()) {
            SectionTitle("Inside right now")
            occupants.forEach { r ->
                Text(
                    "• ${r.name} — ${r.activity.label.lowercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { viewModel.openResident(r.id) }.padding(vertical = 2.dp)
                )
            }
        }

        val employees = b.businessEmployeeIds.mapNotNull { world.resident(it) }
        if (employees.isNotEmpty()) {
            SectionTitle("Employees")
            employees.forEach { r ->
                EmployeeRow(r, onClick = { viewModel.openResident(r.id) })
            }
        }

        val nearby = nearbyBusinesses(world, b)
        if (nearby.isNotEmpty()) {
            SectionTitle("Nearby businesses")
            nearby.forEach { nb ->
                Text(
                    "• ${nb.businessName ?: nb.name} (${nb.typeLabel})" +
                        if (nb.businessOpen == false) " — closed" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { viewModel.openBuilding(nb.id) }.padding(vertical = 2.dp)
                )
            }
        }

        val crimeEvents = buildingEvents.filter {
            it.type == EventType.CRIME_COMMITTED || it.type == EventType.CRIME_REPORTED
        }
        if (crimeEvents.isNotEmpty()) {
            SectionTitle("Crime here")
            crimeEvents.take(5).forEach { e ->
                Text(
                    "• ${e.description} (${e.timeLabel})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RippleColors.DeepBrick,
                    modifier = Modifier.clickable { viewModel.openEvent(e.id) }.padding(vertical = 2.dp)
                )
            }
        }

        val timeline = buildTimeline(b, buildingEvents)
        if (timeline.isNotEmpty()) {
            SectionTitle("Changes over time")
            timeline.forEach { entry -> TimelineRow(entry, onClick = { entry.eventId?.let { viewModel.openEvent(it) } }) }
        }

        SectionTitle("Recent events here")
        if (buildingEvents.isEmpty()) {
            EmptyNote("Nothing of note lately.")
        } else {
            val grouped = groupRoutineEvents(buildingEvents, world.time, b.businessOpen == true)
            grouped.summaryLines.forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodyMedium)
            }
            grouped.notable.forEach { e ->
                Column(Modifier.clickable { viewModel.openEvent(e.id) }.padding(vertical = 4.dp)) {
                    Text(e.description, style = MaterialTheme.typography.bodyMedium)
                    Text(e.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Real-thresholds status phrase for the header — no free-form text, just templated reads of real fields. */
private fun buildingStatusPhrase(world: WorldUi, b: com.ripple.town.data.BuildingUi): String {
    val open = b.businessOpen
    return when {
        b.abandoned && b.businessClosedAt != null -> {
            val days = SimTime.dayIndex(world.time) - SimTime.dayIndex(b.businessClosedAt)
            val since = when {
                days <= 0 -> "today"
                days == 1L -> "1 day"
                else -> "$days days"
            }
            "${b.typeLabel} · permanently closed — empty since $since"
        }
        b.abandoned -> "${b.typeLabel} · standing empty"
        open == false -> "${b.typeLabel} · closed down"
        open == true -> {
            val customers = b.occupantIds.size
            val trouble = b.businessDaysInTrouble ?: 0
            when {
                customers >= 4 -> "${b.typeLabel} · Busy — $customers customers inside"
                trouble > 0 -> "${b.typeLabel} · Struggling — losses for $trouble consecutive day${if (trouble == 1) "" else "s"}"
                b.condition < 30 -> "${b.typeLabel} · Open, but run-down"
                customers > 0 -> "${b.typeLabel} · Open — $customers inside"
                else -> "${b.typeLabel} · Open, quiet right now"
            }
        }
        else -> b.typeLabel
    }
}

@Composable
private fun OwnerCard(owner: com.ripple.town.data.ResidentUi, b: com.ripple.town.data.BuildingUi, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(start = 4.dp)) {
                Text(owner.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${owner.age} years old · ${owner.mood.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ownerBusinessImpactPhrase(b)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Short templated "how this business affects them" phrase, derived from real Business fields only. */
private fun ownerBusinessImpactPhrase(b: com.ripple.town.data.BuildingUi): String? {
    val trouble = b.businessDaysInTrouble ?: return null
    val reputation = b.businessReputation
    val demand = b.businessDemand
    return when {
        trouble > 3 -> "Under real financial strain from the business's losses."
        trouble > 0 -> "Feeling the pinch as the business struggles."
        reputation != null && reputation > 70 && demand != null && demand > 65 -> "Clearly proud — the business is thriving."
        reputation != null && reputation > 70 -> "Takes pride in the business's good name."
        else -> null
    }
}

@Composable
private fun BusinessHealthSection(b: com.ripple.town.data.BuildingUi, owner: com.ripple.town.data.ResidentUi?) {
    Text(
        "${b.businessName} — " + (if (b.businessOpen == true) "trading" else "no longer trading"),
        style = MaterialTheme.typography.bodyMedium
    )
    b.businessBalance?.let {
        Text(
            "Books: ${if (it >= 0) "healthy" else "in the red"} (${it.toInt()} coins)",
            style = MaterialTheme.typography.bodyMedium,
            color = if (it < 0) RippleColors.DeepBrick else RippleColors.DeepGreen
        )
    }
    val revenue = b.businessRevenueToday
    val expenses = b.businessExpensesToday
    if (revenue != null && expenses != null) {
        val profit = revenue - expenses
        Text(
            "Today: ${revenue.toInt()} coins in, ${expenses.toInt()} coins out (${if (profit >= 0) "+" else ""}${profit.toInt()})",
            style = MaterialTheme.typography.bodyMedium,
            color = if (profit < 0) RippleColors.DeepBrick else RippleColors.DeepGreen
        )
    }
    b.businessCustomersToday?.let {
        Text("Customers today: $it", style = MaterialTheme.typography.bodyMedium)
    }
    val trouble = b.businessDaysInTrouble
    if (trouble != null) {
        Text(
            if (trouble > 0) "$trouble consecutive day${if (trouble == 1) "" else "s"} in the red" else "Not currently in financial trouble",
            style = MaterialTheme.typography.bodyMedium,
            color = if (trouble > 0) RippleColors.DeepBrick else RippleColors.DeepGreen
        )
    }
    val employeeCount = b.employeeNames.size
    val capacity = b.businessEmployeeCapacity
    Text(
        "Staff: ${if (b.employeeNames.isEmpty()) "none" else b.employeeNames.joinToString()}" +
            (capacity?.let { " ($employeeCount/$it positions filled)" } ?: ""),
        style = MaterialTheme.typography.bodyMedium
    )
    b.businessDemand?.let { StatBar("Demand (footfall)", it) }
    b.businessReputation?.let { StatBar("Reputation", it) }
    b.businessPriceLevel?.let {
        val pct = ((it - 1.0) * 100).toInt()
        Text(
            "Prices: " + when {
                pct > 5 -> "$pct% above standard"
                pct < -5 -> "${-pct}% below standard"
                else -> "standard"
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
    val debt = owner?.debt
    if (debt != null && debt > 0) {
        Text(
            "Owner's outstanding debt: ${debt.toInt()} coins",
            style = MaterialTheme.typography.bodyMedium,
            color = RippleColors.DeepBrick
        )
    }
}

@Composable
private fun EmployeeRow(r: com.ripple.town.data.ResidentUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "• ${r.name} — ${r.occupation.ifBlank { "staff" }} · ${r.mood.label.lowercase()}",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Cheap nearby-business lookup: Manhattan tile distance between building origins, capped small. */
private fun nearbyBusinesses(world: WorldUi, b: com.ripple.town.data.BuildingUi): List<com.ripple.town.data.BuildingUi> {
    val maxDistance = 12
    return world.buildings
        .asSequence()
        .filter { it.id != b.id && it.businessName != null }
        .map { it to (kotlin.math.abs(it.x - b.x) + kotlin.math.abs(it.y - b.y)) }
        .filter { (_, dist) -> dist <= maxDistance }
        .sortedBy { (_, dist) -> dist }
        .take(4)
        .map { (bldg, _) -> bldg }
        .toList()
}

/** One entry in the combined visible-changes + building-relevant-events timeline. */
private data class TimelineEntry(val timeLabel: String, val description: String, val eventId: Long?, val major: Boolean)

/**
 * Combines building-level cosmetic history (`visibleChanges`) with real timestamped building
 * events into one chronological list. visibleChanges entries embed a date prefix of the form
 * "d Mon • Year N — description"; entries from saves before this format have no separator and
 * are shown as "Earlier". RESIDENT_ARRIVED / RESIDENT_MOVED provide the tenant history.
 */
private fun buildTimeline(b: com.ripple.town.data.BuildingUi, events: List<EventUi>): List<TimelineEntry> {
    val relevantTypes = setOf(
        EventType.BUILDING_CONSTRUCTED, EventType.BUILDING_DAMAGED, EventType.BUILDING_REPAIRED,
        EventType.BUILDING_EXPANDED, EventType.BUILDING_ABANDONED, EventType.BUSINESS_OPENED,
        EventType.BUSINESS_CLOSED, EventType.BUSINESS_EXPANDED, EventType.BUSINESS_SUCCESSION,
        EventType.HOME_PURCHASED, EventType.RESIDENT_ARRIVED, EventType.RESIDENT_MOVED
    )
    val eventEntries = events
        .filter { it.type in relevantTypes }
        .sortedByDescending { it.time }
        .take(10)
        .map { TimelineEntry(it.timeLabel, it.description, it.id, it.importance >= 45.0) }
    val changeEntries = b.visibleChanges.map { raw ->
        val sep = raw.indexOf(" — ")
        if (sep > 0) TimelineEntry(raw.substring(0, sep), raw.substring(sep + 3), null, false)
        else TimelineEntry("Earlier", raw, null, false)
    }
    return changeEntries + eventEntries
}

@Composable
private fun TimelineRow(entry: TimelineEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (entry.eventId != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(if (entry.major) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (entry.major) RippleColors.Gold else RippleColors.SoftInk)
            )
            Box(
                Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(entry.description, style = MaterialTheme.typography.bodyMedium)
            Text(entry.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Grouped-vs-individual split of recent building events. */
private data class GroupedEvents(val summaryLines: List<String>, val notable: List<EventUi>)

/**
 * Groups routine/frequent events from the current in-game day into count summaries
 * (e.g. "3 residents visited today") when the business is open and busy, falling back to
 * individual rows for older/rarer events. Categorisation mirrors the EventType buckets
 * `NewspaperGenerator`/`HistoryScreen` already use — no new event types invented.
 */
private fun groupRoutineEvents(events: List<EventUi>, now: Long, isOpenAndBusy: Boolean): GroupedEvents {
    val todayIndex = SimTime.dayIndex(now)
    val today = events.filter { SimTime.dayIndex(it.time) == todayIndex }
    val older = events.filterNot { SimTime.dayIndex(it.time) == todayIndex }

    if (!isOpenAndBusy || today.size < 3) {
        return GroupedEvents(emptyList(), events.take(8))
    }

    val visitTypes = setOf(EventType.MEETING, EventType.COMMUNITY_EVENT)
    val conversationTypes = setOf(EventType.ARGUMENT, EventType.APOLOGY, EventType.RECONCILIATION)
    val crimeTypes = setOf(EventType.CRIME_COMMITTED, EventType.CRIME_REPORTED, EventType.SHOPLIFTING, EventType.VANDALISM)

    val visits = today.count { it.type in visitTypes }
    val conversations = today.count { it.type in conversationTypes }
    val crimes = today.count { it.type in crimeTypes }
    val bucketed = visitTypes + conversationTypes + crimeTypes
    val routineCount = visits + conversations + crimes
    val leftoverToday = today.filter { it.type !in bucketed }

    val summary = buildList {
        if (visits > 0) add("$visits resident${if (visits == 1) "" else "s"} visited today")
        if (conversations > 0) add("$conversations conversation${if (conversations == 1) "" else "s"} happened today")
        if (crimes > 0) add("$crimes incident${if (crimes == 1) "" else "s"} reported today")
    }

    // If nothing was actually bucketed, there's no real grouping to show — fall back honestly.
    if (routineCount == 0) return GroupedEvents(emptyList(), events.take(8))

    val notable = (leftoverToday + older).sortedByDescending { it.importance }.take(6)
    return GroupedEvents(summary, notable)
}

// --------------------------------------------------------------- event sheet

@Composable
fun EventSheetContent(world: WorldUi, eventId: Long, sprites: SpriteProvider, viewModel: TownViewModel) {
    val chain by viewModel.causeChain.collectAsState()
    val forward by viewModel.forwardChain.collectAsState()
    val event = chain.firstOrNull()?.firstOrNull { it.id == eventId }
        ?: chain.firstOrNull()?.firstOrNull()
    // Phase 4 backlog item: NarrativeTextProvider. A richer, templated elaboration of the
    // event's own terse description, fetched lazily behind an expand toggle — not shown by
    // default, so the sheet still reads at a glance the way it always has. Re-fetched whenever
    // the underlying event changes (LaunchedEffect keyed on eventId, cleared on navigation to a
    // different event) via TownViewModel.requestElaboration, the same one-shot
    // suspend-call-from-Composable pattern requestChronicle already established.
    var expanded by remember(eventId) { mutableStateOf(false) }
    var elaboration by remember(eventId) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(eventId, expanded) {
        if (expanded && elaboration == null) {
            viewModel.requestElaboration(eventId) { elaboration = it ?: "" }
        }
    }
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (event == null) { EmptyNote("The record is missing."); return }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                event.typeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            SeverityTierBadge(event.severity, event.importance)
        }
        Text(event.description, style = MaterialTheme.typography.titleLarge)
        Text(event.timeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide detail ▲" else "More detail ▼")
        }
        if (expanded && !elaboration.isNullOrBlank()) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(10.dp)) {
                    Text(elaboration!!, style = MaterialTheme.typography.bodyMedium)
                    // Light-touch memory reinforcement (task 5, added 2026-07-10): if any involved
                    // resident already has a prior memory naming this same place, surface it here as
                    // a read-time enrichment of the elaboration text — not a new memory mechanic
                    // (Memory only ever decays, see priorMemoryEcho's doc comment below), and never
                    // fabricated: shown only when a real matching Memory exists on that resident.
                    val recall = event.involvedResidentIds.asSequence()
                        .mapNotNull { world.resident(it) }
                        .mapNotNull { r -> priorMemoryEcho(world, event, r) }
                        .firstOrNull()
                    if (recall != null) {
                        Text(
                            recall,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
        val involved = event.involvedResidentIds.mapNotNull { world.resident(it) }
        if (involved.isNotEmpty()) {
            SectionTitle("People involved")
            involved.forEachIndexed { i, r ->
                EventResidentCard(
                    resident = r,
                    role = roleLabel(event, i),
                    sprites = sprites,
                    isFavourite = r.id in world.favouriteIds,
                    onOpen = { viewModel.openResident(r.id) },
                    onToggleFavourite = { viewModel.toggleFavourite(r.id) }
                )
            }
        }
        // Location (task 3, added 2026-07-10): Ripple's homes are hand-authored with the
        // street baked directly into the building name ("8 Rowan Street" etc — see
        // WorldGenerator.slots()), so that name already reads as "name, street" on its own.
        // Business buildings ("Bell's Bakery") carry no separate street field anywhere in the
        // simulation layer — there is genuinely nothing truthful to append for them, so this
        // stays a plain name read rather than inventing a "High Street" suffix.
        world.building(event.buildingId)?.let {
            Text("Where: ${it.name}", style = MaterialTheme.typography.bodyMedium)
        }
        if (chain.size > 1) {
            SectionTitle("Why did this happen?")
            chain.drop(1).forEachIndexed { i, level ->
                CauseConnector(Modifier.padding(start = 4.dp))
                level.forEach { cause ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("← ${cause.description}", style = MaterialTheme.typography.bodyMedium)
                            Text(cause.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else if (event.hasCauses) {
            EmptyNote("Its causes run deeper than the town remembers.")
        } else {
            EmptyNote("As far as anyone can tell, this simply happened.")
        }
        if (forward.isNotEmpty()) {
            SectionTitle("What this led to")
            forward.forEach { level ->
                CauseConnector(Modifier.padding(start = 4.dp))
                level.forEach { consequence ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { viewModel.openEvent(consequence.id) }
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("→ ${consequence.description}", style = MaterialTheme.typography.bodyMedium)
                            Text(consequence.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact resident card (task 2, added 2026-07-10): replaces the old plain-text "• Name" bullet
 * row with an avatar + name/age/occupation/mood + a short role label, mirroring the row shape
 * `PeopleScreen.PersonRow`/`FamilyTreeScreen` already use. Reuses [PixelAvatar]/[SpriteProvider]
 * and `poseFor` (from `TownRenderer.kt`, same package) — no new sprite-rendering path. Tapping
 * still opens the resident's profile via the same `viewModel.openResident` call the old row used.
 */
@Composable
private fun EventResidentCard(
    resident: com.ripple.town.data.ResidentUi,
    role: String,
    sprites: SpriteProvider,
    isFavourite: Boolean,
    onOpen: () -> Unit,
    onToggleFavourite: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.padding(10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PixelAvatar(resident.sprite, sprites, size = 40.dp, pose = poseFor(resident), lifeStage = resident.lifeStage, occupation = resident.occupation)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(resident.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surface) {
                        Text(
                            role,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                val occupationLabel = resident.occupation.ifBlank { "no occupation" }
                Text(
                    "Age ${resident.age} · $occupationLabel · ${resident.mood.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isFavourite) {
                TextButton(onClick = onToggleFavourite) { Text("☆") }
            }
        }
    }
}

/**
 * Role label (task 2): derived only from data the event actually carries — never guessed beyond
 * what's traceable. `EventUi.involvedResidentIds` is built source-then-targets (see
 * `WorldRepository.toUi`), so index 0 is the event's actual source/initiator whenever one exists.
 * A handful of event types have an unambiguous, event-type-derivable role for that source (e.g.
 * the business owner in a closure); everyone else — including every target — reads as the
 * generic "Involved", which is honest rather than invented ("Witness"/"Employee" for a specific
 * person would need data — which employee, which witness — this UI layer doesn't have).
 */
private fun roleLabel(event: EventUi, index: Int): String {
    if (index != 0) return "Involved"
    return when (event.type) {
        EventType.BUSINESS_CLOSED, EventType.BUSINESS_STRUGGLING, EventType.BUSINESS_OPENED,
        EventType.BUSINESS_EXPANDED, EventType.BUSINESS_SUCCESSION -> "Owner"
        EventType.JOB_LOST, EventType.JOB_STARTED, EventType.JOB_QUIT, EventType.HOURS_REDUCED -> "Employee"
        EventType.CRIME_COMMITTED, EventType.SHOPLIFTING, EventType.BURGLARY, EventType.MUGGING,
        EventType.VEHICLE_THEFT, EventType.FRAUD, EventType.ARSON_ATTEMPT, EventType.VANDALISM -> "Suspected"
        EventType.PERSON_DIED -> "Deceased"
        else -> "Involved"
    }
}

/**
 * Severity tiers (task 4): `WorldEvent.severity` (0..1) and `ImportanceScorer` already exist;
 * this is the small pure bucketing function the brief asks for, not a new scoring system.
 * "Historic" reuses `ImportanceScorer.HISTORY_THRESHOLD` (30.0) — the exact bar the History
 * timeline itself already gates on, rather than inventing a new number. The other four bands
 * split the remaining severity range (0..1) evenly; severity (not importance) drives them since
 * importance already factors in reach/causal-boost and would double-count that here.
 */
private fun severityTierLabel(severity: Double, importance: Double): String = when {
    importance >= com.ripple.town.core.simulation.ImportanceScorer.HISTORY_THRESHOLD -> "Historic"
    severity >= 0.7 -> "Critical"
    severity >= 0.5 -> "Major"
    severity >= 0.25 -> "Moderate"
    else -> "Minor"
}

@Composable
private fun SeverityTierBadge(severity: Double, importance: Double) {
    val label = severityTierLabel(severity, importance)
    val color = when (label) {
        "Historic", "Critical" -> RippleColors.DeepBrick
        "Major" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * Memory reinforcement (task 5, light touch, added 2026-07-10): looks for a prior [MemoryUi] on
 * `resident` that plausibly references this same place — `Memory` itself has no resurfacing
 * mechanic (`lastRecalledAt`/`decayPerYear` in `core/model/Goal.kt` only ever decay towards
 * fading and eventual removal in `TickContext.addMemory`; there is no "resurface" path anywhere
 * in the engine) — building a real resurfacing engine is a genuinely large feature and explicitly
 * out of scope here (see docs/backlog.md's 2026-07-10 entry). This is purely a read-time text
 * lookup over the resident's existing `memories` list already carried on `ResidentUi` — no
 * simulation state changes, no new mechanic. Matches only on the building's own name appearing in
 * a memory's description (the one signal actually available client-side); returns null rather
 * than guessing when nothing matches.
 */
private fun priorMemoryEcho(world: WorldUi, event: EventUi, resident: com.ripple.town.data.ResidentUi): String? {
    val buildingName = world.building(event.buildingId)?.name ?: return null
    val echo = resident.memories
        .filter { it.description.contains(buildingName, ignoreCase = true) }
        .maxByOrNull { it.intensity }
        ?: return null
    return "${resident.firstName} remembers this place: \"${echo.description}\""
}

// -------------------------------------------------------- intervention sheet

@Composable
fun InterventionSheetContent(world: WorldUi, residentId: Long, viewModel: TownViewModel) {
    val r = world.resident(residentId) ?: return
    val message by viewModel.interventionMessage.collectAsState()
    val history by viewModel.residentInterventions.collectAsState()
    var pendingIntroduce by remember { mutableIntStateOf(0) }

    // Cooldown: residentId had an intervention within 24h sim time
    val cooldownMinutes = InterventionEngine.PER_PERSON_COOLDOWN_HOURS * SimTime.MINUTES_PER_HOUR
    val lastAt = world.lastInterventionAt[residentId]
    val cooldownRemaining = if (lastAt != null) (cooldownMinutes - (world.time - lastAt)).coerceAtLeast(0L) else 0L
    val onCooldown = cooldownRemaining > 0L

    // Nudge recharge: how many sim hours until next nudge regenerates
    val regenMinutes = InterventionEngine.REGEN_HOURS * SimTime.MINUTES_PER_HOUR
    val rechargeRemaining = if (world.nudges < world.maxNudges)
        (regenMinutes - world.nudgeRegenProgressMinutes).coerceAtLeast(0L) else 0L

    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("A quiet nudge", style = MaterialTheme.typography.headlineSmall)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Influence remaining: ${world.nudges}/${world.maxNudges} · ${r.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (rechargeRemaining > 0L) {
                val h = (rechargeRemaining / SimTime.MINUTES_PER_HOUR).toInt()
                val m = ((rechargeRemaining % SimTime.MINUTES_PER_HOUR) / 10L).toInt() * 10
                Text(
                    "⟳ ${h}h ${m}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onCooldown) {
            val h = (cooldownRemaining / SimTime.MINUTES_PER_HOUR).toInt()
            Text(
                "On cooldown — ${r.firstName} can be nudged again in ~${h}h",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        Text(
            "You never control anyone. You only tilt circumstances — the town decides what follows, and you may not learn the consequences for a long time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        message?.let {
            Surface(shape = MaterialTheme.shapes.small, color = RippleColors.Gold.copy(alpha = 0.3f)) {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
        if (pendingIntroduce == 0) {
            listOf(
                InterventionVerb.DELAY, InterventionVerb.DIVERT, InterventionVerb.ENCOURAGE,
                InterventionVerb.DISTRACT, InterventionVerb.INSPIRE, InterventionVerb.WARN,
                InterventionVerb.REVEAL, InterventionVerb.CONCEAL, InterventionVerb.INTRODUCE
            ).forEach { verb ->
                OutlinedButton(
                    onClick = {
                        if (verb == InterventionVerb.INTRODUCE) pendingIntroduce = 1
                        else viewModel.applyIntervention(verb, r.id)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(verb.label, style = MaterialTheme.typography.titleSmall)
                        Text(verb.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            SectionTitle("Introduce ${r.firstName} to…")
            world.residents
                .filter { it.id != r.id && it.alive && it.inTown && it.detailed }
                .sortedBy { it.name }
                .take(20)
                .forEach { other ->
                    Text(
                        "• ${other.name} (${other.occupation})",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.applyIntervention(InterventionVerb.INTRODUCE, r.id, other.id)
                                pendingIntroduce = 0
                            }
                            .padding(vertical = 6.dp)
                    )
                }
            TextButton(onClick = { pendingIntroduce = 0 }) { Text("Back") }
        }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("Past nudges")
            history.forEach { entry -> InterventionHistoryRow(entry) }
        }
    }
}

@Composable
private fun InterventionHistoryRow(entry: InterventionEntity) {
    val verb = runCatching { InterventionVerb.valueOf(entry.verb) }.getOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            verb?.label ?: entry.verb,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(72.dp)
        )
        Text(
            entry.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            SimTime.formatDate(entry.appliedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------- town overview

@Composable
fun TownOverviewSheetContent(world: WorldUi, viewModel: TownViewModel? = null) {
    val stats = world.townStats
    var tab by remember { mutableIntStateOf(0) }
    // Story feed (see buildTodaysStory below) needs a real events list; recentEvents is
    // the same 30-deep StateFlow the HUD banners already use, only available when a
    // viewModel is supplied — same optionality guard as the Chronicle tab below.
    val recentEvents: List<EventUi> = if (viewModel != null) {
        viewModel.recentEvents.collectAsState().value
    } else {
        emptyList()
    }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text(world.townName, style = MaterialTheme.typography.headlineSmall)
        Text(
            "${world.dateLabel} · ${world.clockLabel}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        // The Chronicle tab is only offered when a viewModel is supplied (it needs
        // chronicleEvents). Kept optional rather than a required param so any other/older
        // caller of this composable doesn't break.
        if (viewModel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("At a glance") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Chronicle") })
            }
            Spacer(Modifier.height(4.dp))
        }
        if (viewModel == null || tab == 0) {
            // Today's story leads — see buildTodaysStory doc comment for why this reads
            // EventUi.description directly rather than going through
            // TemplateNarrativeTextProvider.elaborate().
            val story = remember(recentEvents, stats.averageWellbeing) {
                buildTodaysStory(world, stats, recentEvents)
            }
            if (story.isNotEmpty()) {
                SectionTitle("Today's story")
                story.forEach { line ->
                    val targetEventId = line.eventId
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (targetEventId != null && viewModel != null)
                                    Modifier.clickable { viewModel.openEvent(targetEventId) }
                                else Modifier
                            )
                            .padding(vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            SectionTitle("At a glance")
            Text("Population: ${stats.population}", style = MaterialTheme.typography.bodyMedium)
            Text("In work: ${stats.employedCount}", style = MaterialTheme.typography.bodyMedium)
            SectionTitle("Wellbeing")
            StatBar("Wellbeing (low stress)", stats.averageWellbeing)
            StatBar("Health", stats.averageHealth)
            SectionTitle("Economy")
            Text("Average savings: ${stats.averageWealth.toInt()} coins", style = MaterialTheme.typography.bodyMedium)
            if (world.emergenceRecords.isNotEmpty()) {
                SectionTitle("Town stories")
                world.emergenceRecords.forEach { record ->
                    val typeLabel = com.ripple.town.core.model.EmergenceType.values()
                        .firstOrNull { it.name == record.type }?.label ?: record.type
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .then(
                                if (viewModel != null)
                                    Modifier.clickable { viewModel.openResident(record.residentId) }
                                else Modifier
                            )
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(typeLabel, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(record.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "These are town-wide averages across everyone currently living here. " +
                    "Crime and environment aren't tracked by the simulation yet, so they " +
                    "aren't shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (viewModel != null) {
            TownChronicleTab(viewModel)
        }
    }
}

/** A single "Today's story" line: display text plus the source event, if any, so the row
 * can be tapped through to the full event sheet (mirrors how Chronicle rows already work).
 */
private data class StoryLine(val text: String, val eventId: Long? = null)

/**
 * Builds the "Today's story" lead section: a handful of short, emoji-prefixed lines
 * summarising the town's current mood plus the most notable recent happenings — leading
 * the sheet ahead of the raw stats block below it, per this pass's brief.
 *
 * Text-generation choice, documented: this formats [EventUi.description] directly rather
 * than routing every candidate line through `TemplateNarrativeTextProvider.elaborate()`.
 * `elaborate()` is a one-shot `suspend` call keyed to a single event id (confirmed via
 * `TownViewModel.requestElaboration` / `EventSheetContent`'s `LaunchedEffect(eventId,
 * expanded)` pattern) — appropriate for elaborating ONE event a user has explicitly
 * expanded, not for synchronously building a capped list of 5-7 lines every time this
 * sheet opens, which would mean 5-7 separate engine-dispatcher round-trips just to render
 * a summary. `EventUi.description` is already a complete, readable sentence (it's what
 * the event banners, Chronicle rows and event sheet headline all show as-is) — direct
 * formatting was the practical choice here, elaboration stays reserved for the "more
 * detail" expand affordance on a single event.
 */
private fun buildTodaysStory(world: WorldUi, stats: TownStatsUi, recentEvents: List<EventUi>): List<StoryLine> {
    val lines = mutableListOf<StoryLine>()

    // Town mood, derived from the same averageWellbeing already computed for the stats
    // block below — not a new metric, just read earlier and phrased as a line.
    val moodEmoji: String
    val moodText: String
    when {
        stats.averageWellbeing >= 70 -> { moodEmoji = "😊"; moodText = "The town feels settled and content today." }
        stats.averageWellbeing >= 45 -> { moodEmoji = "😐"; moodText = "Life carries on at its usual pace." }
        else -> { moodEmoji = "😟"; moodText = "There's a heaviness in the air across town." }
    }
    lines += StoryLine("$moodEmoji $moodText")

    // Weather forecast line — reuses the same glyph the HUD chip already shows.
    lines += StoryLine("${weatherGlyph(world.weather, world.timeOfDay)} ${weatherForecastCopy(world.weather)}")

    // Notable recent events, most-recent first, one line per category so the list doesn't
    // read as five variations on the same story. Skips very low-importance noise (below
    // the Chronicle's own floor) so this stays "notable", not exhaustive.
    val seenCategories = mutableSetOf<StoryCategory>()
    recentEvents
        .sortedByDescending { it.time }
        .filter { it.importance >= 4.0 }
        .forEach { e ->
            if (lines.size >= 7) return@forEach
            val category = e.type?.let { storyCategoryFor(it) } ?: StoryCategory.TOWN_NEWS
            if (category in seenCategories) return@forEach
            val emoji = emojiFor(category, e.type)
            lines += StoryLine("$emoji ${e.description}", e.id)
            seenCategories += category
        }

    return lines.take(7)
}

private fun weatherForecastCopy(weather: Weather): String = when (weather) {
    Weather.CLEAR -> "Clear skies expected to hold."
    Weather.CLOUDY -> "Overcast, but dry."
    Weather.RAIN -> "Rain moving through — bring a coat."
    Weather.STORM -> "A storm is rolling in."
    Weather.FOG -> "Fog is settling over the streets."
    Weather.SNOW -> "Snow is falling across the town."
}

/**
 * Local mirror of `NewspaperGenerator.categoryFor(EventType)` — that function is
 * `private` to `NewspaperGenerator.kt`, so it can't be called directly from here, but
 * `StoryCategory` itself is a shared, public `core/model` enum (see `Goal.kt`). Reusing
 * the enum and matching its mapping (rather than inventing a third parallel taxonomy) is
 * the brief's own explicit instruction; duplicating this small `when` is the pragmatic
 * middle ground between that and making a private helper public across an unrelated file
 * for one caller.
 */
private fun storyCategoryFor(type: EventType): StoryCategory = when (type) {
    EventType.PERSON_BORN -> StoryCategory.BIRTHS
    EventType.PERSON_DIED -> StoryCategory.DEATHS
    EventType.MARRIAGE, EventType.ENGAGEMENT -> StoryCategory.WEDDINGS
    EventType.BUSINESS_OPENED, EventType.BUSINESS_CLOSED, EventType.BUSINESS_EXPANDED,
    EventType.BUSINESS_STRUGGLING, EventType.JOB_STARTED, EventType.JOB_LOST,
    EventType.PRICES_SHIFTED -> StoryCategory.BUSINESS
    EventType.CRIME_COMMITTED, EventType.CRIME_REPORTED,
    EventType.SHOPLIFTING, EventType.BURGLARY, EventType.MUGGING,
    EventType.VEHICLE_THEFT, EventType.FRAUD, EventType.ARSON_ATTEMPT,
    EventType.VANDALISM -> StoryCategory.CRIME
    EventType.ILLNESS_DIAGNOSED, EventType.ILLNESS_RECOVERED, EventType.WORKPLACE_ACCIDENT,
    EventType.INJURY -> StoryCategory.HEALTH
    EventType.WEATHER_DAMAGE -> StoryCategory.WEATHER
    EventType.ELECTION_WON, EventType.ELECTION_CALLED,
    EventType.PETITION_STARTED, EventType.PETITION_RESOLVED, EventType.PROTEST_DISRUPTION,
    EventType.TOWN_MILESTONE -> StoryCategory.TOWN_NEWS
    EventType.MEETING, EventType.FRIENDSHIP_FORMED, EventType.COMMUNITY_EVENT,
    EventType.RUMOUR_SPREAD, EventType.BUILDING_REPAIRED, EventType.SKILL_MILESTONE,
    EventType.APOLOGY, EventType.MISSING_PERSON_REPORTED, EventType.MISSING_PERSON_FOUND,
    EventType.DOMESTIC_DISTURBANCE -> StoryCategory.HUMAN_INTEREST
    else -> StoryCategory.TOWN_NEWS
}

private fun emojiFor(category: StoryCategory, type: EventType?): String = when (category) {
    StoryCategory.HEADLINE -> "✨"
    StoryCategory.BIRTHS -> "👶"
    StoryCategory.DEATHS -> "🕯"
    StoryCategory.WEDDINGS -> if (type == EventType.ENGAGEMENT) "💍" else "💒"
    StoryCategory.BUSINESS -> "💼"
    StoryCategory.CRIME -> "🚨"
    StoryCategory.HEALTH -> "🏥"
    StoryCategory.WEATHER -> "🌦️"
    StoryCategory.NOTICES -> "📌"
    StoryCategory.TOWN_NEWS -> "🏛️"
    StoryCategory.HUMAN_INTEREST -> "👥"
}

/**
 * Town Chronicle: a continuously-growing, low-stakes running feed — distinct from the weekly
 * newspaper (curated issues) and from History (day-grouped, importance >= HISTORY_THRESHOLD
 * only). Reads [TownViewModel.chronicleEvents] (a StateFlow, already time-ordered newest-last
 * from the DAO) and filters client-side to a floor well below the History bar so it captures
 * the ambient, everyday stuff — then caps the visible scrollback so this doesn't grow unbounded
 * on screen even though the backing StateFlow already caps at 120 events.
 *
 * Scoped down deliberately: no dedicated top-level screen/nav destination (this lives inside
 * the existing Town Overview sheet, reached the same way that sheet always was — HUD tap), no
 * chronicle-specific persistence beyond the event log WorldEvent/EventDao already is, no
 * chronicle-specific filtering UI (category chips, search, etc.).
 *
 * Newspaper-feel pass (2026-07-10): rows keep the exact same time-ordered "HH:MM —
 * description" format as before — that base format is untouched — but each row now also
 * shows a small category icon/label ahead of the time, using the same [storyCategoryFor]
 * mapping the "Today's story" section above reuses (itself a mirror of
 * `NewspaperGenerator.categoryFor`, since that function is private to a different file).
 * This is light differentiation, not a re-sort: entries stay in the single time-ordered
 * list newest-first, exactly as before, so a reader can still follow the town
 * chronologically — the category tag is a scannable accent, not a restructuring into
 * per-category sections (which would break that chronological read and wasn't asked for).
 */
@Composable
private fun TownChronicleTab(viewModel: TownViewModel) {
    val events by viewModel.chronicleEvents.collectAsState()
    // Much lower than History's HISTORY_THRESHOLD (30.0) — this tab exists specifically to
    // surface the minor/ambient events History and the newspaper both filter out.
    val floor = 4.0
    val entries = events.filter { it.importance >= floor }.sortedByDescending { it.time }.take(80)
    if (entries.isEmpty()) {
        EmptyNote("Nothing much has happened yet — check back soon.")
        return
    }
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        entries.forEach { e ->
            val category = e.type?.let { storyCategoryFor(it) } ?: StoryCategory.TOWN_NEWS
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openEvent(e.id) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    emojiFor(category, e.type),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(20.dp)
                )
                Text(
                    SimTime.formatClock(e.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp)
                )
                Text(e.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------- death card

@Composable
fun DeathSummaryDialog(
    death: DeathSummary,
    onFollow: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${death.name} has died") },
        text = {
            Column {
                Text("Aged ${death.age}. Cause: ${death.cause}.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(death.lifeSummary, style = MaterialTheme.typography.bodyMedium)
                death.era?.let { era ->
                    SectionTitle("Their era")
                    Text(
                        if (era.notableTownEventCount > 0)
                            "${era.years} years in the town's history — ${era.notableTownEventCount} notable town events, ${era.relationshipsFormed} close relationships formed."
                        else
                            "${era.years} years in the town's history.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (era.witnessed.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Lived through:", style = MaterialTheme.typography.labelMedium)
                        era.witnessed.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (era.definingMemories.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Will be remembered for:", style = MaterialTheme.typography.labelMedium)
                        era.definingMemories.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (death.familyLeft.isNotEmpty()) {
                    SectionTitle("Family left behind")
                    death.familyLeft.forEach { (_, label) -> Text("• $label", style = MaterialTheme.typography.bodySmall) }
                }
                if (death.suggestions.isNotEmpty()) {
                    SectionTitle("Lives you might follow")
                    death.suggestions.forEach { (id, label) ->
                        TextButton(onClick = { onFollow(id) }) { Text(label) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Keep watching the town") }
        }
    )
}
