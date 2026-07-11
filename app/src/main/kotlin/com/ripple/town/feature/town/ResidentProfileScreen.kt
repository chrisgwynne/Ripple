package com.ripple.town.feature.town

import android.content.Intent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Premium Edition resident profile (2026-07-10 session; compacted 2026-07-10 follow-up pass).
 * Replaces the original flat `ResidentSheetContent` tab strip with a compact hero header + a
 * "living summary" + a set of collapsible cards (Summary/Relationships/Needs/Skills/
 * Household/Story/Timeline) instead of a `ScrollableTabRow`. Every data point below is read
 * from something the simulation already computes (`ResidentUi`, `WorldRepository
 * .eventsForResident`, the existing template text/dialogue providers) — see the accompanying
 * docs/backlog.md entry for the full inventory of what was reused vs. genuinely new, and for
 * what was deliberately skipped because it would require fabricating data or unverifiable
 * custom rendering.
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
    // now surfaced directly inside the living-summary card.
    val situation = situationFor(r)
    var dialogueLine by remember(residentId, situation) { mutableStateOf<String?>(null) }
    LaunchedEffect(residentId, situation) {
        dialogueLine = null
        if (situation != null) {
            viewModel.requestDialogueLine(residentId, situation) { dialogueLine = it }
        }
    }

    // Which card is expanded — only one open by default (Summary), like an accordion. Reset
    // per resident so switching profiles doesn't carry over an unrelated expanded section.
    var expandedCard by remember(residentId) { mutableStateOf<ProfileCard?>(ProfileCard.SUMMARY) }

    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 20.dp)
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
    ) {
        HeroHeader(
            r = r,
            sprites = sprites,
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

        Spacer(Modifier.height(8.dp))

        LivingSummaryCard(r, dialogueLine)

        Spacer(Modifier.height(10.dp))

        val household = world.residents.filter {
            it.householdId != null && it.householdId == r.householdId && it.id != r.id
        }

        ExpandableCard(
            title = "Summary",
            expanded = expandedCard == ProfileCard.SUMMARY,
            onToggle = { expandedCard = toggle(expandedCard, ProfileCard.SUMMARY) }
        ) {
            SummaryCardBody(world, r, viewModel)
        }
        ExpandableCard(
            title = "Relationships",
            subtitle = if (r.relationships.isNotEmpty()) "${r.relationships.size}" else null,
            expanded = expandedCard == ProfileCard.RELATIONSHIPS,
            onToggle = { expandedCard = toggle(expandedCard, ProfileCard.RELATIONSHIPS) }
        ) {
            RelationshipsTab(world, r, viewModel, onOpenTree = { showTree = true })
        }
        ExpandableCard(
            title = "Needs",
            expanded = expandedCard == ProfileCard.NEEDS,
            onToggle = { expandedCard = toggle(expandedCard, ProfileCard.NEEDS) }
        ) {
            NeedsCardBody(r)
        }
        if (r.skills.isNotEmpty()) {
            ExpandableCard(
                title = "Skills",
                expanded = expandedCard == ProfileCard.SKILLS,
                onToggle = { expandedCard = toggle(expandedCard, ProfileCard.SKILLS) }
            ) {
                r.skills.entries.sortedByDescending { it.value }.forEach { (label, value) ->
                    StatBar(label, value)
                }
            }
        }
        if (household.isNotEmpty()) {
            ExpandableCard(
                title = "Household",
                subtitle = "${household.size}",
                expanded = expandedCard == ProfileCard.HOUSEHOLD,
                onToggle = { expandedCard = toggle(expandedCard, ProfileCard.HOUSEHOLD) }
            ) {
                household.forEach { member ->
                    Text(
                        "• ${member.name} (${member.age})",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openResident(member.id) }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
        ExpandableCard(
            title = "Story",
            subtitle = if (r.memories.isNotEmpty()) "${r.memories.size}" else null,
            expanded = expandedCard == ProfileCard.STORY,
            onToggle = { expandedCard = toggle(expandedCard, ProfileCard.STORY) }
        ) {
            MemoriesTab(r)
        }
        ExpandableCard(
            title = "Timeline",
            expanded = expandedCard == ProfileCard.TIMELINE,
            onToggle = { expandedCard = toggle(expandedCard, ProfileCard.TIMELINE) }
        ) {
            TimelineTab(residentEvents, viewModel)
        }
        ExpandableCard(
            title = "Personality",
            expanded = expandedCard == ProfileCard.PERSONALITY,
            onToggle = { expandedCard = toggle(expandedCard, ProfileCard.PERSONALITY) }
        ) {
            PersonalityTab(r)
        }
        if (r.aspirations.isNotEmpty() || r.identityFacetLabels.isNotEmpty()) {
            ExpandableCard(
                title = "Life arc",
                subtitle = if (r.identityFacetLabels.isNotEmpty()) r.identityFacetLabels.take(2).joinToString(" · ") else null,
                expanded = expandedCard == ProfileCard.LIFE_ARC,
                onToggle = { expandedCard = toggle(expandedCard, ProfileCard.LIFE_ARC) }
            ) {
                LifeArcCardBody(r)
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

/** Which of the accordion cards is currently expanded (single-open accordion). */
private enum class ProfileCard { SUMMARY, RELATIONSHIPS, NEEDS, SKILLS, HOUSEHOLD, STORY, TIMELINE, PERSONALITY, LIFE_ARC }

/** Tapping an already-open card's header collapses it; tapping a closed one opens it (and closes the rest). */
private fun toggle(current: ProfileCard?, target: ProfileCard): ProfileCard? =
    if (current == target) null else target

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

/**
 * Short, readable financial-situation phrase derived from the existing `financialSecurity`
 * need, `wealth` and `debt` fields — a display-layer helper, not a new tracked field. Debt is
 * checked first since owing money is the clearest signal of trouble regardless of the
 * financialSecurity score.
 */
private fun financialSituationPhrase(r: ResidentUi): String = when {
    r.debt > 0 && r.financialSecurity < 25 -> "in real financial trouble"
    r.financialSecurity < 25 -> "bills are becoming difficult"
    r.financialSecurity < 55 -> "managing, but money is tight"
    else -> "financially comfortable"
}

// ------------------------------------------------------------------ hero header (compact)

@Composable
private fun HeroHeader(
    r: ResidentUi,
    sprites: SpriteProvider,
    isFollowed: Boolean,
    isFavourite: Boolean,
    onFollow: () -> Unit,
    onToggleFavourite: () -> Unit,
    onNudge: () -> Unit,
    onShareSaga: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PixelAvatar(r.sprite, sprites, size = 48.dp, pose = poseFor(r), lifeStage = r.lifeStage, occupation = r.occupation)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.titleLarge)
            Text(
                if (!r.alive) "Died aged ${r.age} — ${r.causeOfDeath ?: ""}"
                else if (!r.inTown) "Away — ${r.occupation}"
                else "${r.age} · ${r.occupation}" + (r.employerName?.let { " at $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // Compact equal-width action row instead of the previous loose FilterChip row.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        CompactActionButton(
            label = if (isFollowed) "Following" else "Follow",
            active = isFollowed,
            onClick = { if (!isFollowed) onFollow() },
            modifier = Modifier.weight(1f)
        )
        CompactActionButton(
            label = if (isFavourite) "★ Fav" else "☆ Fav",
            active = isFavourite,
            onClick = onToggleFavourite,
            modifier = Modifier.weight(1f)
        )
        if (r.alive && r.inTown) {
            CompactActionButton(label = "✨ Nudge", active = false, onClick = onNudge, modifier = Modifier.weight(1f))
        }
        CompactActionButton(label = "📜 Share", active = false, onClick = onShareSaga, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CompactActionButton(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = if (active) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    }
    Card(colors = colors, modifier = modifier.clickable(onClick = onClick)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

// ------------------------------------------------------------------ living summary

/**
 * The "living summary": current activity + mood, current thought (the existing
 * `TemplateDialogueProvider` quote line), the top active goal (used as the "next planned
 * activity" stand-in — `ResidentUi`/`Resident` expose no dedicated "next planned activity"
 * concept, so rather than inventing one this reuses the existing goal list, already ordered
 * with the most relevant goal first), the "why this?" activity reason, and a short readable
 * financial-situation phrase.
 */
@Composable
private fun LivingSummaryCard(r: ResidentUi, dialogueLine: String?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (r.alive && r.inTown) {
                Text(
                    "${r.activity.label} · feeling ${r.mood.label.lowercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RippleColors.SoftInk
                )
                if (r.activityReason.isNotBlank()) {
                    Text(
                        "Why: ${r.activityReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            } else if (!r.alive) {
                Text("Passed away", style = MaterialTheme.typography.bodyMedium, color = RippleColors.SoftInk)
            } else {
                Text("Currently away from town", style = MaterialTheme.typography.bodyMedium, color = RippleColors.SoftInk)
            }

            if (!dialogueLine.isNullOrBlank()) {
                Text(
                    "“$dialogueLine”",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (r.activeGoalLabels.isNotEmpty()) {
                Text(
                    if (r.activeGoalLabels.size == 1) "Working towards:" else "Goals (${r.activeGoalLabels.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                r.activeGoalLabels.forEach { goal ->
                    Text(
                        "· $goal",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                    )
                }
            }

            if (r.alive) {
                Text(
                    "Money: ${financialSituationPhrase(r)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ generic expandable card

/**
 * Shared accordion-card container used by every section below. `animateContentSize()` on the
 * body column gives a smooth low-risk expand/collapse (no custom easing/physics), and the
 * chevron icon reflects state. Content is only composed when expanded is true isn't required
 * here since these bodies are cheap list renders reading already-loaded `ResidentUi` data —
 * kept simple rather than adding lazy-composition machinery for content this small.
 */
@Composable
private fun ExpandableCard(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = 10.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Column(Modifier.padding(bottom = 10.dp)) {
                    content()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ summary card body

/**
 * Body of the "Summary" card: home, workplace, relationship status, means, top relationships,
 * top personality traits, and health notes — a condensed merge of what the old Overview tab
 * showed, reusing the same fields.
 */
@Composable
private fun SummaryCardBody(world: WorldUi, r: ResidentUi, viewModel: TownViewModel) {
    SummaryLine("Home", r.homeName ?: "No fixed home in town")
    SummaryLine("Workplace", r.employerName ?: (if (r.occupation.isNotBlank()) r.occupation else "Unemployed"))
    SummaryLine("Status", r.relationshipStatusLabel)
    SummaryLine("Means", wealthBandLabel(r.wealth) + if (r.debt > 0) " (in debt)" else "")

    val topRelationships = r.relationships.take(3)
    if (topRelationships.isNotEmpty()) {
        SectionTitle("Closest relationships")
        topRelationships.forEach { rel -> RelationshipInsightRow(rel, viewModel) }
    }

    val topTraits = traitList(r.personality).sortedByDescending { it.second }.take(3)
    if (topTraits.isNotEmpty()) {
        SectionTitle("Personality at a glance")
        Text(
            topTraits.joinToString(" · ") { it.first },
            style = MaterialTheme.typography.bodyMedium,
            color = RippleColors.SoftInk
        )
    }

    if (r.conditionLabels.isNotEmpty()) {
        SectionTitle("Health notes")
        Text(r.conditionLabels.joinToString(), style = MaterialTheme.typography.bodyMedium, color = RippleColors.DeepBrick)
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * "AI-generated relationship insight": a one-line templated sentence for a single
 * relationship — a warmth dot + name + kind + trust bar, reusing the same rows the old
 * Overview tab rendered.
 */
@Composable
private fun RelationshipInsightRow(rel: RelationUi, viewModel: TownViewModel) {
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
 * Low-risk "connection colour" affordance: a small coloured dot per relationship row
 * (green/amber/red by warmth), not a proportional-edge-width graph — that already exists in
 * `FamilyTreeScreen.kt`'s relationship map and is linked to, not duplicated.
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

// ------------------------------------------------------------------ relationships card body

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

// ------------------------------------------------------------------ needs card body

/**
 * A single need's descriptive state: icon, short label, and the raw 0..100 value it was
 * derived from (kept alongside so the numeric view and the trend comparison can reuse the same
 * bucketing pass instead of recomputing).
 */
private data class NeedState(val need: String, val icon: String, val label: String, val value: Double)

/**
 * Descriptive-state-first needs card (2026-07-10 rework). Raw 0..100 numbers are no longer the
 * primary display — each need is bucketed onto a small icon+label ladder instead, grouped into
 * Physical vs Emotional per the redesign brief. Numbers are still available behind the existing
 * "Show numbers" toggle (unchanged mechanism from the prior pass), and each need row shows a
 * trend arrow plus, for notably bad states, up to 3 short "why" contributors built from data
 * already on `ResidentUi` (conditions, goals, memories, employment, debt) — templated from real
 * fields, not free-form text.
 *
 * Trend approximation: `ResidentUi`/the snapshot pipeline exposes no prior-tick value for a
 * resident's needs (this was checked in `WorldSnapshot.kt` — `ResidentUi` only carries the
 * current tick's numbers). The cheapest *honest* approximation available without touching the
 * simulation/snapshot layer is: remember the values from the last time this resident's sheet
 * was opened (keyed by resident id, in local Compose `remember` state) and diff against that on
 * the next open. This is NOT a true multi-day trend — it only reflects "did this move since I
 * last looked at this resident", which could be seconds or days of sim time depending on how
 * often the player checks in. Documented here and in the trend-arrow helper below.
 */
@Composable
private fun NeedsCardBody(r: ResidentUi) {
    var showBars by remember { mutableStateOf(false) }

    // "Since I last looked" snapshot — see trend-approximation note above. Keyed by resident id
    // so switching profiles doesn't compare unrelated residents' numbers against each other.
    // Deliberately captured once per (composable-instance, resident id) via `remember` rather
    // than re-synced on every value change (a naive `LaunchedEffect` keyed on the live values
    // would fire on every tick's recomposition and immediately overwrite `prior` with the
    // current value, collapsing every arrow to "stable" within a frame). The snapshot is only
    // ever written on first composition for a given resident id, so it holds steady for the
    // whole time this sheet stays open on this resident — i.e. genuinely "since I last opened
    // this profile", not "since last frame".
    val prior = remember(r.id) { r }

    val physical = listOf(
        needState("Hunger", hungerState(r.hunger), r.hunger),
        needState("Energy", energyState(r.energy), r.energy),
        needState("Health", healthState(r.health), r.health),
        needState("Comfort", comfortState(r.comfort), r.comfort),
        needState("Safety", safetyState(r.safety), r.safety)
    )
    val emotional = listOf(
        needState("Stress", stressState(r.stress), r.stress),
        needState("Purpose", purposeState(r.purposeNeed), r.purposeNeed),
        needState("Social", socialState(r.social), r.social),
        needState("Financial security", financialState(r.financialSecurity), r.financialSecurity)
    )

    SectionTitle("Physical")
    physical.forEach { NeedRow(it, prior, r) }

    SectionTitle("Emotional")
    emotional.forEach { NeedRow(it, prior, r) }

    TextButton(onClick = { showBars = !showBars }, modifier = Modifier.animateContentSize()) {
        Text(if (showBars) "Hide numbers" else "Show numbers")
    }

    if (showBars) {
        Column(Modifier.animateContentSize()) {
            StatBar("Hunger", r.hunger)
            StatBar("Energy", r.energy)
            StatBar("Health", r.health)
            StatBar("Safety", r.safety)
            StatBar("Social", r.social)
            StatBar("Comfort", r.comfort)
            StatBar("Purpose", r.purposeNeed)
            StatBar("Stress", r.stress, good = false)
            StatBar("Financial security", r.financialSecurity)
        }
    }
}

@Composable
private fun NeedRow(state: NeedState, prior: ResidentUi?, current: ResidentUi) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("${state.icon} ${state.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                trendArrow(state.need, prior, current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val contributors = if (state.isNotable) needContributors(state.need, current) else emptyList()
        if (contributors.isNotEmpty()) {
            Text(
                contributors.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

/** True when this need's state is notable enough to be worth showing "why" contributors for. */
private val NeedState.isNotable: Boolean
    get() = when (need) {
        "Stress" -> value > 60
        "Purpose" -> value < 40
        "Financial security" -> value < 30
        "Social" -> value < 25
        "Health" -> value < 30
        else -> false
    }

private fun needState(need: String, labeled: Pair<String, String>, value: Double) =
    NeedState(need, labeled.first, labeled.second, value)

// ---- descriptive-state ladders (thresholds are display-layer judgement calls, not tuned sim balance) ----

/** Stress is inverted: low value = good. 5 buckets across 0..100. */
private fun stressState(value: Double): Pair<String, String> = when {
    value < 20 -> "😌" to "Calm"
    value < 40 -> "🙂" to "Content"
    value < 60 -> "😐" to "Concerned"
    value < 80 -> "😟" to "Stressed"
    else -> "😫" to "Overwhelmed"
}

/** Purpose: high = good. `purposeNeed` runs 0..100 like every other need, so 6 buckets fit
 * comfortably without over- or under-splitting the range. */
private fun purposeState(value: Double): Pair<String, String> = when {
    value >= 85 -> "✨" to "Inspired"
    value >= 65 -> "💼" to "Fulfilled"
    value >= 45 -> "🙂" to "Content"
    value >= 25 -> "🤔" to "Searching"
    value >= 10 -> "⬇" to "Lost"
    else -> "💔" to "Broken"
}

private fun financialState(value: Double): Pair<String, String> = when {
    value >= 80 -> "💰" to "Wealthy"
    value >= 60 -> "🟢" to "Comfortable"
    value >= 40 -> "🟡" to "Stable"
    value >= 20 -> "🟠" to "Struggling"
    else -> "🔴" to "Critical"
}

private fun socialState(value: Double): Pair<String, String> = when {
    value >= 80 -> "🥳" to "Thriving"
    value >= 60 -> "😊" to "Connected"
    value >= 40 -> "😐" to "Average"
    value >= 20 -> "😔" to "Lonely"
    else -> "💔" to "Isolated"
}

private fun healthState(value: Double): Pair<String, String> = when {
    value >= 80 -> "❤️" to "Excellent"
    value >= 60 -> "💚" to "Good"
    value >= 40 -> "💛" to "Fair"
    value >= 20 -> "🧡" to "Poor"
    else -> "❤️‍🩹" to "Critical"
}

/** Simple 2-3 word labels for the remaining physical needs — a full 5-icon ladder is overkill
 * here since these are transient/routine needs rather than emotional states worth dwelling on. */
private fun hungerState(value: Double): Pair<String, String> = when {
    value >= 60 -> "🍽" to "Well fed"
    value >= 30 -> "🍽" to "Getting hungry"
    else -> "🍽" to "Needs food"
}

private fun energyState(value: Double): Pair<String, String> = when {
    value >= 60 -> "⚡" to "Energised"
    value >= 30 -> "⚡" to "Tired"
    else -> "⚡" to "Exhausted"
}

private fun comfortState(value: Double): Pair<String, String> = when {
    value >= 60 -> "🛋" to "Comfortable"
    value >= 30 -> "🛋" to "Getting by"
    else -> "🛋" to "Uncomfortable"
}

private fun safetyState(value: Double): Pair<String, String> = when {
    value >= 60 -> "🛡" to "Feels safe"
    value >= 30 -> "🛡" to "Uneasy"
    else -> "🛡" to "Feels unsafe"
}

/**
 * Trend arrow for a single need, comparing against the "last time this sheet was opened"
 * snapshot described on `NeedsCardBody`. Returns a stable dash when there's no prior snapshot
 * yet (first time viewing this resident this session) rather than a misleading arrow.
 */
private fun trendArrow(need: String, prior: ResidentUi?, current: ResidentUi): String {
    if (prior == null) return "–"
    val (before, after) = when (need) {
        "Hunger" -> prior.hunger to current.hunger
        "Energy" -> prior.energy to current.energy
        "Health" -> prior.health to current.health
        "Comfort" -> prior.comfort to current.comfort
        "Safety" -> prior.safety to current.safety
        "Stress" -> prior.stress to current.stress
        "Purpose" -> prior.purposeNeed to current.purposeNeed
        "Social" -> prior.social to current.social
        "Financial security" -> prior.financialSecurity to current.financialSecurity
        else -> return "–"
    }
    val delta = after - before
    return when {
        delta > 1.0 -> "⬆"
        delta < -1.0 -> "⬇"
        else -> "➡"
    }
}

/**
 * Up to 3 short "why" contributors for a notably good/bad need state, built from fields already
 * on `ResidentUi` — conditions, active goals, recent memories, employment, debt — the same
 * "structured facts -> short phrase" approach `ChronicleBuilder`/`TemplateNarrativeTextProvider`
 * use elsewhere. Most relevant/recent first; not free-form generated text.
 */
private fun needContributors(need: String, r: ResidentUi): List<String> {
    val out = mutableListOf<String>()
    when (need) {
        "Stress" -> {
            if (r.employerName == null && (r.occupation.isBlank() || r.occupation == "Unemployed")) out += "Unemployed"
            if (r.debt > 0) out += "In debt"
            r.memories.firstOrNull { it.typeLabel.contains("betrayed", true) || it.typeLabel.contains("lost", true) || it.typeLabel.contains("humiliated", true) || it.typeLabel.contains("argument", true) }
                ?.let { out += it.description }
            if (r.conditionLabels.isNotEmpty()) out += r.conditionLabels.first()
        }
        "Purpose" -> {
            if (r.employerName == null && (r.occupation.isBlank() || r.occupation == "Unemployed")) out += "No work to focus on"
            if (r.activeGoalLabels.isEmpty()) out += "No active goals"
            r.memories.firstOrNull { it.typeLabel.contains("lost", true) }?.let { out += it.description }
        }
        "Financial security" -> {
            if (r.debt > 0) out += "Carrying debt"
            out += financialSituationPhrase(r).replaceFirstChar { it.uppercase() }
            if (r.employerName == null && r.occupation == "Unemployed") out += "Unemployed"
        }
        "Social" -> {
            if (r.relationships.isEmpty()) out += "No close relationships yet"
            r.memories.firstOrNull { it.typeLabel.contains("let down", true) || it.typeLabel.contains("argument", true) }?.let { out += it.description }
        }
        "Health" -> {
            if (r.conditionLabels.isNotEmpty()) out += r.conditionLabels.take(2)
        }
    }
    return out.distinct().take(3)
}

// ------------------------------------------------------------------ timeline card body

/** Chronological life events for this resident, grouped by year like `HistoryScreen.kt`, each
 * formatted as "HH:MM — description" using the event's own human-readable `description` text
 * (already produced by `NewspaperGenerator`/event emission — not new copy). */
@Composable
private fun TimelineTab(events: List<EventUi>, viewModel: TownViewModel) {
    if (events.isEmpty()) { EmptyNote("A quiet life, so far."); return }
    val byYear = events.groupBy { SimTime.year(it.time) }
    byYear.keys.sortedDescending().forEach { year ->
        Text(
            "Year $year",
            style = MaterialTheme.typography.titleSmall,
            color = RippleColors.DeepGreen,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )
        byYear[year]!!.sortedByDescending { it.time }.forEach { e ->
            Text(
                "${e.timeLabel} — ${e.description}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openEvent(e.id) }
                    .padding(vertical = 4.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ story (memories) card body

/** Renamed from "Memories" to "Story" per the redesign brief — label change only, the
 * underlying importance-sorted memory list is unchanged. */
@Composable
private fun MemoriesTab(r: ResidentUi) {
    if (r.memories.isEmpty()) { EmptyNote("Nothing has marked them deeply yet."); return }
    // Already importance-ranked (SnapshotBuilder sorts by importance and caps at 10) — shown
    // in that order rather than re-sorted, so the most defining memories read first.
    r.memories.forEach { m ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text("“${m.description}”", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${m.typeLabel} · ${SimTime.formatDate(m.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------ personality card body

private fun traitList(p: com.ripple.town.core.model.Personality): List<Pair<String, Double>> = listOf(
    "Kindness" to p.kindness, "Ambition" to p.ambition, "Curiosity" to p.curiosity,
    "Sociability" to p.sociability, "Patience" to p.patience, "Honesty" to p.honesty,
    "Courage" to p.courage, "Discipline" to p.discipline, "Empathy" to p.empathy,
    "Impulsiveness" to p.impulsiveness
)

/**
 * Labeled horizontal bars, not a radar chart — the safe alternative called for in the original
 * brief. `Personality` values are 0.0..1.0, so each is scaled to the 0..100 range `StatBar`
 * expects. A radar/spider chart was considered but skipped: with no device to verify polygon
 * math on, a wrong-but-plausible-looking chart is a worse outcome than a set of bars this
 * app's existing `StatBar` already renders correctly everywhere else.
 */
@Composable
private fun PersonalityTab(r: ResidentUi) {
    traitList(r.personality).forEach { (label, value) ->
        StatBar(label, value * 100.0)
    }
    Text(
        "Traits are fixed character values, 0-100. They don't fluctuate day to day the way " +
            "needs do — they shape how ${r.firstName} tends to react, not how they currently feel.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

// ------------------------------------------------------------------ life arc card body

@Composable
private fun LifeArcCardBody(r: ResidentUi) {
    if (r.identityFacetLabels.isNotEmpty()) {
        SectionTitle("Who they are")
        Text(
            r.identityFacetLabels.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
    }
    if (r.aspirations.isNotEmpty()) {
        SectionTitle("Aspirations")
        r.aspirations.forEach { (typeLabel, statusLabel, progress) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(typeLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (statusLabel != "Fulfilled") {
                    StatBar("", progress, modifier = Modifier.width(80.dp))
                } else {
                    Text("✓", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
    }
    if (r.lifeSatisfactionBreakdown.isNotEmpty()) {
        SectionTitle("Life satisfaction")
        r.lifeSatisfactionBreakdown.forEach { (dimension, value) ->
            StatBar(dimension, value)
        }
    }
}
