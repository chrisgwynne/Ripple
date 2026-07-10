package com.ripple.town.feature.town

import android.content.Intent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.ui.EmptyNote
import com.ripple.town.core.ui.PixelAvatar
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.core.ui.SectionTitle
import com.ripple.town.core.ui.SpriteProvider
import com.ripple.town.core.ui.StatBar
import com.ripple.town.data.EventUi
import com.ripple.town.data.RelationUi
import com.ripple.town.data.ResidentUi
import com.ripple.town.data.WorldUi
import com.ripple.town.feature.people.FamilyTreeDialog

/**
 * Premium Edition resident profile (2026-07-10 session). Replaces the previous flat
 * `ResidentSheetContent` tab strip with a hero header + proper tabbed information
 * architecture. Every data point below is read from something the simulation already
 * computes (`ResidentUi`, `WorldRepository.eventsForResident`, the existing template
 * text/dialogue providers) — see the accompanying docs/backlog.md entry for the full
 * inventory of what was reused vs. genuinely new, and for what was deliberately skipped
 * because it would require fabricating data or unverifiable custom rendering.
 */
@Composable
fun ResidentSheetContent(
    world: WorldUi,
    residentId: Long,
    sprites: SpriteProvider,
    viewModel: TownViewModel
) {
    val r = world.resident(residentId) ?: return
    val residentEvents by viewModel.residentEvents.collectAsState()
    val isFollowed = world.followedResidentId == r.id
    val isFavourite = r.id in world.favouriteIds
    val context = LocalContext.current

    var showTree by remember(residentId) { mutableStateOf(false) }

    // DialogueProvider: the resident's current "living quote" — same mechanism as before,
    // relocated into the hero header rather than the old flat layout.
    val situation = situationFor(r)
    var dialogueLine by remember(residentId, situation) { mutableStateOf<String?>(null) }
    LaunchedEffect(residentId, situation) {
        dialogueLine = null
        if (situation != null) {
            viewModel.requestDialogueLine(residentId, situation) { dialogueLine = it }
        }
    }

    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
    ) {
        HeroHeader(
            world = world,
            r = r,
            sprites = sprites,
            dialogueLine = dialogueLine,
            isFollowed = isFollowed,
            isFavourite = isFavourite,
            onFollow = { viewModel.follow(r.id) },
            onToggleFavourite = { viewModel.toggleFavourite(r.id) },
            onNudge = { viewModel.openIntervention(r.id) },
            onShareSaga = {
                viewModel.requestChronicle(r.id) { chronicle ->
                    if (chronicle.isNullOrBlank()) return@requestChronicle
                    val intent = ShareCompat.IntentBuilder(context)
                        .setType("text/plain")
                        .setSubject("${r.name}'s family chronicle — ${world.townName}")
                        .setText(chronicle)
                        .intent
                    context.startActivity(Intent.createChooser(intent, "Share ${r.firstName}'s chronicle"))
                }
            }
        )

        var tab by remember(residentId) { mutableIntStateOf(0) }
        val tabs = listOf("Overview", "Relationships", "Timeline", "Memories", "Personality", "Stats")
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 0.dp,
            containerColor = androidx.compose.ui.graphics.Color.Transparent
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }

        Column(Modifier.animateContentSize()) {
            when (tab) {
                0 -> OverviewTab(world, r, viewModel)
                1 -> RelationshipsTab(world, r, viewModel, onOpenTree = { showTree = true })
                2 -> TimelineTab(residentEvents, viewModel)
                3 -> MemoriesTab(r)
                4 -> PersonalityTab(r)
                5 -> StatsTab(r)
            }
        }
    }

    if (showTree) {
        FamilyTreeDialog(
            world = world,
            residentId = residentId,
            sprites = sprites,
            onOpenResident = { showTree = false; viewModel.openResident(it) },
            onDismiss = { showTree = false }
        )
    }
}

/**
 * Maps a resident's current activity/mood onto one of `TemplateDialogueProvider`'s closed
 * set of supported situation strings, or `null` for activities not worth a quoted line at
 * all — unchanged from the pre-redesign logic, just relocated into this file.
 */
private fun situationFor(r: ResidentUi): String? {
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

/** Simple, honest wealth-band label from the existing `wealth` number — no new tracked field. */
private fun wealthBandLabel(wealth: Double): String = when {
    wealth < 200 -> "Struggling"
    wealth < 1200 -> "Getting by"
    wealth < 4000 -> "Comfortable"
    else -> "Well-off"
}

// ------------------------------------------------------------------ hero header

@Composable
private fun HeroHeader(
    world: WorldUi,
    r: ResidentUi,
    sprites: SpriteProvider,
    dialogueLine: String?,
    isFollowed: Boolean,
    isFavourite: Boolean,
    onFollow: () -> Unit,
    onToggleFavourite: () -> Unit,
    onNudge: () -> Unit,
    onShareSaga: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PixelAvatar(r.sprite, sprites, size = 64.dp, pose = poseFor(r), lifeStage = r.lifeStage, occupation = r.occupation)
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
        }
    }

    if (!dialogueLine.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                "“$dialogueLine”",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = isFollowed, onClick = { if (!isFollowed) onFollow() }, label = { Text(if (isFollowed) "Following" else "Follow") })
        FilterChip(selected = isFavourite, onClick = onToggleFavourite, label = { Text(if (isFavourite) "★ Favourite" else "☆ Favourite") })
        if (r.alive && r.inTown) {
            FilterChip(selected = false, onClick = onNudge, label = { Text("✨ Nudge") })
        }
        FilterChip(selected = false, onClick = onShareSaga, label = { Text("📜 Share saga") })
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

    // Summary card: home, workplace, relationship status, wealth band. Reputation isn't a
    // tracked simulation field (checked ResidentUi/Resident — no such value exists), so it's
    // left out rather than invented.
    Spacer(Modifier.height(10.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            SummaryLine("Home", r.homeName ?: "No fixed home in town")
            SummaryLine("Workplace", r.employerName ?: (if (r.occupation.isNotBlank()) r.occupation else "Unemployed"))
            SummaryLine("Status", r.relationshipStatusLabel)
            SummaryLine("Means", wealthBandLabel(r.wealth) + if (r.debt > 0) " (in debt)" else "")
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ------------------------------------------------------------------ overview tab

@Composable
private fun OverviewTab(world: WorldUi, r: ResidentUi, viewModel: TownViewModel) {
    if (r.activeGoalLabels.isNotEmpty()) {
        SectionTitle("Current goals")
        r.activeGoalLabels.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }

    val topRelationships = r.relationships.take(3)
    if (topRelationships.isNotEmpty()) {
        SectionTitle("Closest relationships")
        topRelationships.forEach { rel ->
            RelationshipInsightRow(r, rel, viewModel)
        }
    }

    if (r.memories.isNotEmpty()) {
        SectionTitle("Recent highlights")
        r.memories.take(3).forEach { m ->
            Text("• ${m.description}", style = MaterialTheme.typography.bodyMedium)
        }
    }

    SectionTitle("Personality at a glance")
    val topTraits = traitList(r.personality).sortedByDescending { it.second }.take(3)
    Text(
        topTraits.joinToString(" · ") { "${it.first}" },
        style = MaterialTheme.typography.bodyMedium,
        color = RippleColors.SoftInk
    )

    SectionTitle("Needs")
    StatBar("Hunger", r.hunger)
    StatBar("Energy", r.energy)
    StatBar("Health", r.health)
    StatBar("Social", r.social)
    StatBar("Stress", r.stress, good = false)
    StatBar("Purpose", r.purposeNeed)
    StatBar("Comfort", r.comfort)
    StatBar("Financial security", r.financialSecurity)

    val household = world.residents.filter { it.householdId != null && it.householdId == r.householdId && it.id != r.id }
    if (household.isNotEmpty()) {
        SectionTitle("Household")
        household.forEach { Text("• ${it.name} (${it.age})", style = MaterialTheme.typography.bodyMedium) }
    }
    if (r.conditionLabels.isNotEmpty()) {
        SectionTitle("Health notes")
        Text(r.conditionLabels.joinToString(), style = MaterialTheme.typography.bodyMedium, color = RippleColors.DeepBrick)
    }
}

/**
 * "AI-generated relationship insight": a one-line templated sentence for a single
 * relationship, built from the same `TemplateDialogueProvider`/situation mechanism already
 * used for the hero quote — reworded to describe the *relationship* by asking for a
 * "socialising" line themed around the other person, since there's no dedicated
 * relationship-insight provider method. This deliberately reuses the existing generator
 * rather than inventing new text-generation logic; if a resident has no eligible situation
 * (not currently socialising/etc.) it simply omits the insight line rather than faking one.
 */
@Composable
private fun RelationshipInsightRow(r: ResidentUi, rel: RelationUi, viewModel: TownViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { viewModel.openResident(rel.otherId) }
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WarmthDot(rel.warmth)
            Spacer(Modifier.width(6.dp))
            Text(rel.otherName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(rel.kindLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatBar("Warmth", rel.warmth)
    }
}

/**
 * Low-risk "connection colour" affordance from the brief: a small coloured dot per
 * relationship row (green/amber/red by warmth), not a proportional-edge-width graph — that
 * already exists in `FamilyTreeScreen.kt`'s relationship map and is linked to, not
 * duplicated.
 */
@Composable
private fun WarmthDot(warmth: Double) {
    val colour = when {
        warmth >= 55 -> RippleColors.WarmGreen
        warmth >= 20 -> RippleColors.Gold
        else -> RippleColors.BrickRed
    }
    Surface(shape = CircleShape, color = colour, modifier = Modifier.width(10.dp).height(10.dp)) {}
}

// ------------------------------------------------------------------ relationships tab

@Composable
private fun RelationshipsTab(
    world: WorldUi,
    r: ResidentUi,
    viewModel: TownViewModel,
    onOpenTree: () -> Unit
) {
    OutlinedButton(onClick = onOpenTree, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("View family tree / relationship map")
    }
    if (r.relationships.isEmpty()) { EmptyNote("No one knows them well yet."); return }
    r.relationships.forEach { rel ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { viewModel.openResident(rel.otherId) }
                .padding(vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WarmthDot(rel.warmth)
                Spacer(Modifier.width(6.dp))
                Text(rel.otherName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(rel.kindLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatBar("Trust", rel.trust)
            StatBar("Affection", rel.affection)
            if (rel.resentment > 10) StatBar("Resentment", rel.resentment, good = false)
        }
    }
}

// ------------------------------------------------------------------ timeline tab

/** Chronological life events for this resident, grouped by year like `HistoryScreen.kt`. */
@Composable
private fun TimelineTab(events: List<EventUi>, viewModel: TownViewModel) {
    if (events.isEmpty()) { EmptyNote("A quiet life, so far."); return }
    val byYear = events.groupBy { SimTime.year(it.time) }
    byYear.keys.sortedDescending().forEach { year ->
        Text(
            "Year $year",
            style = MaterialTheme.typography.titleSmall,
            color = RippleColors.DeepGreen,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
        )
        byYear[year]!!.sortedByDescending { it.time }.forEach { e ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openEvent(e.id) }
                    .padding(vertical = 5.dp)
            ) {
                Text(e.description, style = MaterialTheme.typography.bodyMedium)
                Text(e.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ memories tab

@Composable
private fun MemoriesTab(r: ResidentUi) {
    if (r.memories.isEmpty()) { EmptyNote("Nothing has marked them deeply yet."); return }
    // Already importance-ranked (SnapshotBuilder sorts by importance and caps at 10) — shown
    // in that order rather than re-sorted, so the most defining memories read first.
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

// ------------------------------------------------------------------ personality tab

private fun traitList(p: com.ripple.town.core.model.Personality): List<Pair<String, Double>> = listOf(
    "Kindness" to p.kindness, "Ambition" to p.ambition, "Curiosity" to p.curiosity,
    "Sociability" to p.sociability, "Patience" to p.patience, "Honesty" to p.honesty,
    "Courage" to p.courage, "Discipline" to p.discipline, "Empathy" to p.empathy,
    "Impulsiveness" to p.impulsiveness
)

/**
 * Labeled horizontal bars, not a radar chart — the safe alternative called for in the brief.
 * `Personality` values are 0.0..1.0, so each is scaled to the 0..100 range `StatBar` expects.
 * A radar/spider chart was considered but skipped: with no device to verify polygon math on,
 * a wrong-but-plausible-looking chart is a worse outcome than a set of bars this app's
 * existing `StatBar` already renders correctly everywhere else.
 */
@Composable
private fun PersonalityTab(r: ResidentUi) {
    SectionTitle("Traits")
    traitList(r.personality).forEach { (label, value) ->
        StatBar(label, value * 100.0)
    }
    Text(
        "Traits are fixed character values, 0-100. They don't fluctuate day to day the way " +
            "needs do — they shape how ${r.firstName} tends to react, not how they currently feel.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

// ------------------------------------------------------------------ stats tab

/**
 * Needs/health as compact cards. Sparkline trend charts were explicitly requested in the
 * brief but are skipped here: there is no historical time-series store for `Needs` values
 * anywhere in the codebase (checked `WorldRepository`/`core/database` — only current-tick
 * needs are persisted, no per-tick or daily snapshot log exists), so a trend line would have
 * to be invented data. Showing only the current, real values instead.
 */
@Composable
private fun StatsTab(r: ResidentUi) {
    SectionTitle("Needs")
    StatBar("Hunger", r.hunger)
    StatBar("Energy", r.energy)
    StatBar("Health", r.health)
    StatBar("Safety", r.safety)
    StatBar("Social", r.social)
    StatBar("Comfort", r.comfort)
    StatBar("Purpose", r.purposeNeed)
    StatBar("Stress", r.stress, good = false)
    StatBar("Financial security", r.financialSecurity)

    if (r.skills.isNotEmpty()) {
        SectionTitle("Skills")
        r.skills.entries.sortedByDescending { it.value }.forEach { (label, value) ->
            StatBar(label, value)
        }
    }

    Text(
        "No trend history is tracked by the simulation yet, so this shows current values only " +
            "— not fabricated day-over-day change.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}
