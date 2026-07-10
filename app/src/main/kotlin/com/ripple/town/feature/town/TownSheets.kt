package com.ripple.town.feature.town

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.core.app.ShareCompat
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.ui.CauseConnector
import com.ripple.town.core.ui.EmptyNote
import com.ripple.town.core.ui.PixelAvatar
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.core.ui.SectionTitle
import com.ripple.town.core.ui.SpriteProvider
import com.ripple.town.core.ui.StatBar
import com.ripple.town.core.model.InterventionVerb
import com.ripple.town.data.DeathSummary
import com.ripple.town.data.EventUi
import com.ripple.town.data.WorldUi

// ------------------------------------------------------------ resident sheet

@Composable
fun ResidentSheetContent(
    world: WorldUi,
    residentId: Long,
    sprites: SpriteProvider,
    viewModel: TownViewModel
) {
    val r = world.resident(residentId) ?: return
    val residentEvents by viewModel.residentEvents.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val isFollowed = world.followedResidentId == r.id
    val isFavourite = r.id in world.favouriteIds
    val context = LocalContext.current

    // Phase 4 backlog item: DialogueProvider. A short, personality-flavoured line for the
    // resident's *current* activity/mood — only fetched (and only shown) when that activity maps
    // onto one of TemplateDialogueProvider's supported situation strings; everything else (idle
    // browsing, travelling, etc.) shows nothing rather than forcing a generic line onto every
    // resident sheet. Re-fetched whenever the resident or their situation changes.
    val situation = situationFor(r)
    var dialogueLine by remember(residentId, situation) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(residentId, situation) {
        dialogueLine = null
        if (situation != null) {
            viewModel.requestDialogueLine(residentId, situation) { dialogueLine = it }
        }
    }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelAvatar(r.sprite, sprites, size = 54.dp, pose = poseFor(r), lifeStage = r.lifeStage, occupation = r.occupation)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (!r.alive) "Died aged ${r.age} — ${r.causeOfDeath ?: ""}"
                    else if (!r.inTown) "Away — ${r.occupation}"
                    else "${r.age} · ${r.occupation}" + (r.employerName?.let { " at $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (r.alive && r.inTown) {
                    Text(
                        "${r.activity.label} · feeling ${r.mood.label.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = RippleColors.SoftInk
                    )
                }
                if (!dialogueLine.isNullOrBlank()) {
                    Text(
                        "“$dialogueLine”",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = isFollowed,
                onClick = { if (!isFollowed) viewModel.follow(r.id) },
                label = { Text(if (isFollowed) "Following" else "Follow") }
            )
            FilterChip(
                selected = isFavourite,
                onClick = { viewModel.toggleFavourite(r.id) },
                label = { Text(if (isFavourite) "★ Favourite" else "☆ Favourite") }
            )
            if (r.alive && r.inTown) {
                FilterChip(
                    selected = false,
                    onClick = { viewModel.openIntervention(r.id) },
                    label = { Text("✨ Nudge") }
                )
            }
            // Phase 4 backlog item: shareable town chronicles. Builds the text off-thread
            // (WorldRepository.buildChronicle, via ChronicleBuilder) then launches the
            // standard Android share sheet — text/plain, ShareCompat.IntentBuilder, the
            // same well-established pattern the task brief asked for. This is the app's
            // first ACTION_SEND usage; no FileProvider/manifest changes were needed since
            // this is a plain text share, not a file attachment.
            FilterChip(
                selected = false,
                onClick = {
                    viewModel.requestChronicle(r.id) { chronicle ->
                        if (chronicle.isNullOrBlank()) return@requestChronicle
                        val intent = ShareCompat.IntentBuilder(context)
                            .setType("text/plain")
                            .setSubject("${r.name}'s family chronicle — ${world.townName}")
                            .setText(chronicle)
                            .intent
                        context.startActivity(Intent.createChooser(intent, "Share ${r.firstName}'s chronicle"))
                    }
                },
                label = { Text("📜 Share saga") }
            )
        }
        if (r.alive && r.inTown && r.activityReason.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "Why this? ${r.activityReason}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val tabs = listOf("Life", "Relationships", "Memories", "Skills", "History")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, label ->
                FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
            }
        }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> LifeTab(world, r)
                1 -> RelationshipsTab(world, r, viewModel)
                2 -> MemoriesTab(r)
                3 -> SkillsTab(r)
                4 -> HistoryTab(residentEvents, viewModel)
            }
        }
    }
}

/**
 * Phase 4 backlog item: DialogueProvider. Maps a resident's current
 * [com.ripple.town.core.model.Activity]/mood onto one of `TemplateDialogueProvider`'s closed set
 * of supported situation strings ("grieving", "celebrating", "working", "arguing",
 * "socialising", "worried", "idle"), or `null` for activities not worth a quoted line at all
 * (travelling, sleeping, at school, etc. — showing a line for absolutely everything would make
 * it feel like empty filler rather than a genuine moment).
 */
private fun situationFor(r: com.ripple.town.data.ResidentUi): String? {
    if (!r.alive || !r.inTown) return null
    return when (r.activity) {
        com.ripple.town.core.model.Activity.MOURNING -> "grieving"
        com.ripple.town.core.model.Activity.CELEBRATING -> "celebrating"
        com.ripple.town.core.model.Activity.WORKING -> "working"
        com.ripple.town.core.model.Activity.ARGUING -> "arguing"
        com.ripple.town.core.model.Activity.SOCIALISING,
        com.ripple.town.core.model.Activity.VISITING -> "socialising"
        else -> if (r.stress > 70 || r.financialSecurity < 25) "worried" else null
    }
}

@Composable
private fun LifeTab(world: WorldUi, r: com.ripple.town.data.ResidentUi) {
    SectionTitle("Needs")
    StatBar("Hunger", r.hunger)
    StatBar("Energy", r.energy)
    StatBar("Health", r.health)
    StatBar("Social", r.social)
    StatBar("Stress", r.stress, good = false)
    StatBar("Purpose", r.purposeNeed)
    StatBar("Comfort", r.comfort)
    StatBar("Financial security", r.financialSecurity)
    SectionTitle("Situation")
    Text("Home: ${r.homeName ?: "No fixed home in town"}", style = MaterialTheme.typography.bodyMedium)
    Text("Status: ${r.relationshipStatusLabel}", style = MaterialTheme.typography.bodyMedium)
    Text("Savings: ${r.wealth.toInt()} coins" + (if (r.debt > 0) " · Debt: ${r.debt.toInt()}" else ""), style = MaterialTheme.typography.bodyMedium)
    if (r.conditionLabels.isNotEmpty()) {
        Text("Health notes: ${r.conditionLabels.joinToString()}", style = MaterialTheme.typography.bodyMedium, color = RippleColors.DeepBrick)
    }
    if (r.activeGoalLabels.isNotEmpty()) {
        SectionTitle("Current goals")
        r.activeGoalLabels.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
    val household = world.residents.filter { it.householdId != null && it.householdId == r.householdId && it.id != r.id }
    if (household.isNotEmpty()) {
        SectionTitle("Household")
        household.forEach { Text("• ${it.name} (${it.age})", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun RelationshipsTab(world: WorldUi, r: com.ripple.town.data.ResidentUi, viewModel: TownViewModel) {
    if (r.relationships.isEmpty()) { EmptyNote("No one knows them well yet."); return }
    r.relationships.forEach { rel ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { viewModel.openResident(rel.otherId) }
                .padding(vertical = 6.dp)
        ) {
            Row {
                Text(rel.otherName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(rel.kindLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatBar("Trust", rel.trust)
            StatBar("Affection", rel.affection)
            if (rel.resentment > 10) StatBar("Resentment", rel.resentment, good = false)
        }
    }
}

@Composable
private fun MemoriesTab(r: com.ripple.town.data.ResidentUi) {
    if (r.memories.isEmpty()) { EmptyNote("Nothing has marked them deeply yet."); return }
    r.memories.forEach { m ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Text("“${m.description}”", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${m.typeLabel} · ${SimTime.formatDate(m.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SkillsTab(r: com.ripple.town.data.ResidentUi) {
    if (r.skills.isEmpty()) { EmptyNote("No practised skills yet."); return }
    r.skills.entries.sortedByDescending { it.value }.forEach { (label, value) ->
        StatBar(label, value)
    }
}

@Composable
private fun HistoryTab(events: List<EventUi>, viewModel: TownViewModel) {
    if (events.isEmpty()) { EmptyNote("A quiet life, so far."); return }
    events.forEach { e ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { viewModel.openEvent(e.id) }
                .padding(vertical = 6.dp)
        ) {
            Text(e.description, style = MaterialTheme.typography.bodyMedium)
            Text(e.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------ building sheet

@Composable
fun BuildingSheetContent(world: WorldUi, buildingId: Long, viewModel: TownViewModel) {
    val b = world.building(buildingId) ?: return
    val buildingEvents by viewModel.buildingEvents.collectAsState()
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(b.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            b.typeLabel + (if (b.abandoned) " · standing empty" else "") +
                (b.businessOpen?.let { if (!it) " · closed down" else "" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        b.ownerName?.let { Text("Owned by $it", style = MaterialTheme.typography.bodyMedium) }
        SectionTitle("Condition")
        StatBar("Condition", b.condition)
        StatBar("Noise", b.noise, good = false)
        Text("Value: ${b.value.toInt()} coins", style = MaterialTheme.typography.bodyMedium)
        if (b.businessName != null) {
            SectionTitle("Business")
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
            if (b.employeeNames.isNotEmpty()) {
                Text("Staff: ${b.employeeNames.joinToString()}", style = MaterialTheme.typography.bodyMedium)
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
        if (b.visibleChanges.isNotEmpty()) {
            SectionTitle("Changes over time")
            b.visibleChanges.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        SectionTitle("Recent events here")
        if (buildingEvents.isEmpty()) EmptyNote("Nothing of note lately.")
        buildingEvents.take(8).forEach { e ->
            Column(Modifier.clickable { viewModel.openEvent(e.id) }.padding(vertical = 4.dp)) {
                Text(e.description, style = MaterialTheme.typography.bodyMedium)
                Text(e.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --------------------------------------------------------------- event sheet

@Composable
fun EventSheetContent(world: WorldUi, eventId: Long, viewModel: TownViewModel) {
    val chain by viewModel.causeChain.collectAsState()
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
        Text(event.typeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(event.description, style = MaterialTheme.typography.titleLarge)
        Text(event.timeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide detail ▲" else "More detail ▼")
        }
        if (expanded && !elaboration.isNullOrBlank()) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    elaboration!!,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
        val involved = event.involvedResidentIds.mapNotNull { world.resident(it) }
        if (involved.isNotEmpty()) {
            SectionTitle("People involved")
            involved.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.openResident(r.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("• ${r.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (r.id !in world.favouriteIds) {
                        TextButton(onClick = { viewModel.toggleFavourite(r.id) }) { Text("☆ Favourite") }
                    }
                }
            }
        }
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
    }
}

// -------------------------------------------------------- intervention sheet

@Composable
fun InterventionSheetContent(world: WorldUi, residentId: Long, viewModel: TownViewModel) {
    val r = world.resident(residentId) ?: return
    val message by viewModel.interventionMessage.collectAsState()
    var pendingIntroduce by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("A quiet nudge", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Influence remaining: ${world.nudges}/${world.maxNudges} · ${r.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    }
}

// ------------------------------------------------------------- town overview

@Composable
fun TownOverviewSheetContent(world: WorldUi, viewModel: TownViewModel? = null) {
    val stats = world.townStats
    var tab by remember { mutableIntStateOf(0) }
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
            SectionTitle("At a glance")
            Text("Population: ${stats.population}", style = MaterialTheme.typography.bodyMedium)
            Text("In work: ${stats.employedCount}", style = MaterialTheme.typography.bodyMedium)
            SectionTitle("Wellbeing")
            StatBar("Wellbeing (low stress)", stats.averageWellbeing)
            StatBar("Health", stats.averageHealth)
            SectionTitle("Economy")
            Text("Average savings: ${stats.averageWealth.toInt()} coins", style = MaterialTheme.typography.bodyMedium)
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
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    SimTime.formatClock(e.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp)
                )
                Text(e.description, style = MaterialTheme.typography.bodySmall)
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
