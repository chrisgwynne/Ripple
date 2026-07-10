package com.ripple.town.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ripple.town.core.database.TownStatisticEntity
import com.ripple.town.core.model.SimTime
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

/** The town's chronicle: major events, milestones and the current-year summary. */
@Composable
fun HistoryScreen(
    onOpenEvent: (Long) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsState()
    val stats by viewModel.statistics.collectAsState()
    val interventions by viewModel.interventions.collectAsState()

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
        }
        if (events.isEmpty()) {
            item {
                Text(
                    "History is still being written. Let the town live a little.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
        val byYear = events.groupBy { SimTime.year(it.time) }
        byYear.keys.sortedDescending().forEach { year ->
            item(key = "year$year") {
                Text(
                    "Year $year",
                    style = MaterialTheme.typography.titleLarge,
                    color = RippleColors.DeepGreen,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }
            items(byYear[year]!!, key = { "ev${it.id}" }) { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenEvent(e.id) }
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
                                .height(38.dp)
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
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun importanceColour(importance: Double) = when {
    importance >= 60 -> RippleColors.BrickRed
    importance >= 45 -> RippleColors.Gold
    else -> RippleColors.WarmGreen
}
