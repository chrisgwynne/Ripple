package com.ripple.town.feature.news

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.ripple.town.core.database.NewspaperIssueEntity
import com.ripple.town.core.database.NewspaperStoryEntity
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.StoryCategory
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.data.WorldRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewsViewModel @Inject constructor(
    private val repository: WorldRepository
) : ViewModel() {
    val issues: StateFlow<List<NewspaperIssueEntity>> = repository.newspaperIssues()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Current sim time, used only to estimate when the next issue is due for the empty state. */
    val worldTime: StateFlow<Long?> = repository.worldUi
        .map { it?.time }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selectedIssueId = MutableStateFlow<Long?>(null)
    val selectedIssueId: StateFlow<Long?> = _selectedIssueId.asStateFlow()

    private val _stories = MutableStateFlow<List<NewspaperStoryEntity>>(emptyList())
    val stories: StateFlow<List<NewspaperStoryEntity>> = _stories.asStateFlow()

    fun select(issueId: Long) {
        _selectedIssueId.value = issueId
        viewModelScope.launch { _stories.value = repository.storiesOf(issueId) }
    }
}

/** The town's weekly paper, generated from public simulation events. */
@Composable
fun NewsScreen(viewModel: NewsViewModel = hiltViewModel()) {
    val issues by viewModel.issues.collectAsState()
    val selectedId by viewModel.selectedIssueId.collectAsState()
    val stories by viewModel.stories.collectAsState()
    val worldTime by viewModel.worldTime.collectAsState()

    val current = issues.firstOrNull { it.id == selectedId } ?: issues.firstOrNull()
    androidx.compose.runtime.LaunchedEffect(issues.firstOrNull()?.id, selectedId) {
        val target = selectedId ?: issues.firstOrNull()?.id
        if (target != null && (stories.isEmpty() || stories.firstOrNull()?.issueId != target)) {
            viewModel.select(target)
        }
    }

    if (current == null) {
        // Day-one empty state. If we know the current sim time, say roughly when the
        // first issue is due (first issue fires the morning after the world starts —
        // see NewspaperGenerator.isDue); otherwise fall back to the generic message.
        val etaText = worldTime?.let { now ->
            val dayStart = now - (now % SimTime.MINUTES_PER_DAY)
            val firstIssueAt = if (now < dayStart + SimTime.MINUTES_PER_DAY) dayStart + SimTime.MINUTES_PER_DAY else dayStart
            if (now < firstIssueAt) "Expect the first edition ${SimTime.formatDate(firstIssueAt)}, 8 o'clock sharp."
            else "It should be along any morning now — the presses run at 8 o'clock."
        } ?: "Come back tomorrow morning."

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "The first issue is still at the printers.\n$etaText",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val sortedIssues = issues.sortedBy { it.issueNumber }
    val currentIndex = sortedIssues.indexOfFirst { it.id == current.id }
    val previousIssue = sortedIssues.getOrNull(currentIndex - 1)
    val nextIssue = sortedIssues.getOrNull(currentIndex + 1)

    LazyColumn(Modifier.fillMaxSize().background(RippleColors.Parchment)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                // Masthead
                Text(
                    current.masthead,
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(2.dp))

                // Prev/next issue control, straddling the issue date so browsing the
                // archive doesn't require scrolling all the way to the bottom chip row.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { previousIssue?.let { viewModel.select(it.id) } },
                        enabled = previousIssue != null
                    ) {
                        Icon(
                            Icons.Filled.ChevronLeft,
                            contentDescription = "Previous issue",
                            tint = if (previousIssue != null) RippleColors.Ink else RippleColors.Ink.copy(alpha = 0.25f)
                        )
                    }
                    Text(
                        "No. ${current.issueNumber} — ${SimTime.formatDate(current.publishedAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    IconButton(
                        onClick = { nextIssue?.let { viewModel.select(it.id) } },
                        enabled = nextIssue != null
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = "Next issue",
                            tint = if (nextIssue != null) RippleColors.Ink else RippleColors.Ink.copy(alpha = 0.25f)
                        )
                    }
                }
                Divider(Modifier.padding(top = 2.dp), color = RippleColors.Ink, thickness = 2.dp)
            }
        }
        val grouped = stories.groupBy { it.category }
        val order = StoryCategory.entries.map { it.name }
        for (categoryName in order) {
            val catStories = grouped[categoryName] ?: continue
            val isHeadline = categoryName == StoryCategory.HEADLINE.name
            if (!isHeadline) {
                item(key = "hdr$categoryName") {
                    Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 4.dp)) {
                        Divider(color = RippleColors.Ink.copy(alpha = 0.3f))
                        Text(
                            StoryCategory.valueOf(categoryName).label.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = RippleColors.DeepBrick,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            items(catStories, key = { "story${it.id}" }) { story ->
                if (isHeadline) {
                    // Front-page treatment: distinct tinted block, bigger type, sits
                    // right under the masthead so it reads as the paper's lead story.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(RippleColors.Cream)
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            story.headline,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(story.body, style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(story.headline, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            story.body,
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = if (story.category == StoryCategory.NOTICES.name) FontStyle.Italic else FontStyle.Normal
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(20.dp)) {
                Divider(color = RippleColors.Ink.copy(alpha = 0.4f))
                Text(
                    "FROM THE ARCHIVE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sortedIssues, key = { it.id }) { issue ->
                        FilterChip(
                            selected = issue.id == current.id,
                            onClick = { viewModel.select(issue.id) },
                            label = { Text("No. ${issue.issueNumber} · ${SimTime.formatDate(issue.publishedAt)}") }
                        )
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}
