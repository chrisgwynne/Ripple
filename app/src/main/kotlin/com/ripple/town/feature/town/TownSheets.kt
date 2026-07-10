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
import androidx.compose.ui.unit.dp
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
import com.ripple.town.core.model.InterventionVerb
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
