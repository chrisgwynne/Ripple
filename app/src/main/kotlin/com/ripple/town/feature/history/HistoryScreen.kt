package com.ripple.town.feature.history

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ripple.town.core.database.TownStatisticEntity
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.StoryCategory
import com.ripple.town.core.simulation.ImportanceScorer
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.data.EventUi
import com.ripple.town.data.WorldRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    repository: WorldRepository
) : ViewModel() {
    val events: StateFlow<List<EventUi>> = repository.historyEvents(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val statistics: StateFlow<List<TownStatisticEntity>> = repository.statistics(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val interventions = repository.interventions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Importance at/above which an event gets the more prominent "major" card treatment. */
private const val MAJOR_EVENT_THRESHOLD = ImportanceScorer.HISTORY_THRESHOLD * 2

/** Filter buckets for the History timeline. "All" plus a handful of StoryCategory groups. */
private enum class HistoryFilter(val label: String, val category: StoryCategory?) {
    ALL("All", null),
    PEOPLE("People", StoryCategory.HUMAN_INTEREST),
    BUSINESS("Business", StoryCategory.BUSINESS),
    CRIME("Crime", StoryCategory.CRIME),
    HEALTH("Health", StoryCategory.HEALTH),
    TOWN("Town & politics", StoryCategory.TOWN_NEWS)
}

/**
 * Buckets an EventType into a StoryCategory for the History filter row. Mirrors
 * NewspaperGenerator.categoryFor's grouping (kept in sync manually — that function
 * is private to NewspaperGenerator) but covers the fuller set of EventTypes that
 * can appear in the history feed (relationships, family, goals, etc. fold into
 * PEOPLE via HUMAN_INTEREST).
 */
private fun historyCategoryFor(type: EventType): StoryCategory = when (type) {
    EventType.BUSINESS_OPENED, EventType.BUSINESS_CLOSED, EventType.BUSINESS_EXPANDED,
    EventType.BUSINESS_STRUGGLING, EventType.JOB_STARTED, EventType.JOB_LOST,
    EventType.JOB_QUIT, EventType.HOURS_REDUCED, EventType.DEBT_CRISIS,
    EventType.FINANCIAL_RELIEF -> StoryCategory.BUSINESS

    EventType.CRIME_COMMITTED, EventType.CRIME_REPORTED -> StoryCategory.CRIME

    EventType.ILLNESS_STARTED, EventType.ILLNESS_DIAGNOSED, EventType.ILLNESS_RECOVERED,
    EventType.INJURY -> StoryCategory.HEALTH

    EventType.WEATHER_DAMAGE -> StoryCategory.WEATHER

    EventType.ELECTION_WON, EventType.ELECTION_CALLED,
    EventType.PETITION_STARTED, EventType.PETITION_RESOLVED,
    EventType.TOWN_MILESTONE, EventType.BUILDING_CONSTRUCTED,
    EventType.BUILDING_DAMAGED, EventType.BUILDING_REPAIRED,
    EventType.BUILDING_EXPANDED, EventType.BUILDING_ABANDONED -> StoryCategory.TOWN_NEWS

    EventType.PERSON_BORN -> StoryCategory.BIRTHS
    EventType.PERSON_DIED -> StoryCategory.DEATHS
    EventType.MARRIAGE, EventType.ENGAGEMENT -> StoryCategory.WEDDINGS

    // Everyone else — relationships, family, goals, meetings, secrets, nudges —
    // reads as "around town" human interest, which the People filter surfaces.
    else -> StoryCategory.HUMAN_INTEREST
}

/** The town's chronicle: a real vertical timeline of major and minor events, day by day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenEvent: (Long) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsState()
    val stats by viewModel.statistics.collectAsState()
    val interventions by viewModel.interventions.collectAsState()
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }

    val filtered = if (filter.category == null) events
    else events.filter { e -> e.type != null && historyCategoryFor(e.type) == filter.category }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(
                "Town chronicle",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            val latest = stats.firstOrNull()
            if (latest != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("The town today", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Population ${latest.population} · ${latest.openBusinesses} businesses trading · " +
                                "${latest.employedAdults}/${latest.adultCount} adults in work",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Average spirits: ${latest.averageWellbeing.toInt()}/100" +
                                if (interventions.isNotEmpty()) " · ${interventions.size} quiet nudge${if (interventions.size == 1) "" else "s"} recorded" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(HistoryFilter.entries, key = { it.name }) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    if (events.isEmpty()) "History is still being written. Let the town live a little."
                    else "Nothing in this category yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }

        // Real vertical timeline: grouped by day, newest day first, newest event
        // within a day first. Day groups are nested under year headers so a long
        // history still reads as a scannable chronicle rather than a flat list.
        val byYear = filtered.groupBy { SimTime.year(it.time) }
        byYear.keys.sortedDescending().forEach { year ->
            item(key = "year$year") {
                Text(
                    "Year $year",
                    style = MaterialTheme.typography.titleLarge,
                    color = RippleColors.DeepGreen,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }
            val yearEvents = byYear[year]!!
            val byDay = yearEvents.groupBy { SimTime.dayIndex(it.time) }
            byDay.keys.sortedDescending().forEach { dayIdx ->
                val dayEvents = byDay[dayIdx]!!
                val sample = dayEvents.first()
                item(key = "day$year-$dayIdx") {
                    Text(
                        "${SimTime.dayOfMonth(sample.time)} ${SimTime.MONTH_SHORT[SimTime.monthIndex(sample.time)]}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }
                items(dayEvents, key = { "ev${it.id}" }) { e ->
                    if (e.importance >= MAJOR_EVENT_THRESHOLD) {
                        MajorEventCard(e, onClick = { onOpenEvent(e.id) })
                    } else {
                        MinorEventRow(e, onClick = { onOpenEvent(e.id) })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

/** Compact one-line row for ordinary (below the major threshold) events. */
@Composable
private fun MinorEventRow(e: EventUi, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(importanceColour(e.importance))
            )
            Box(
                Modifier
                    .width(2.dp)
                    .height(30.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(e.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${e.typeLabel} · ${e.timeLabel}" + if (e.hasCauses) "  ·  why? →" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** More prominent, expandable-feeling card for high-importance events. */
@Composable
private fun MajorEventCard(e: EventUi, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(importanceColour(e.importance))
            )
            Box(
                Modifier
                    .width(2.dp)
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            )
        }
        Spacer(Modifier.width(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    e.typeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(e.description, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    e.timeLabel + if (e.hasCauses) "  ·  why? →" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun importanceColour(importance: Double) = when {
    importance >= 60 -> RippleColors.BrickRed
    importance >= 45 -> RippleColors.Gold
    else -> RippleColors.WarmGreen
}
