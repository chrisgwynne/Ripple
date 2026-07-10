package com.ripple.town.feature.news

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewsViewModel @Inject constructor(
    private val repository: WorldRepository
) : ViewModel() {
    val issues: StateFlow<List<NewspaperIssueEntity>> = repository.newspaperIssues()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    val current = issues.firstOrNull { it.id == selectedId } ?: issues.firstOrNull()
    androidx.compose.runtime.LaunchedEffect(issues.firstOrNull()?.id, selectedId) {
        val target = selectedId ?: issues.firstOrNull()?.id
        if (target != null && (stories.isEmpty() || stories.firstOrNull()?.issueId != target)) {
            viewModel.select(target)
        }
    }

    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "The first issue is still at the printers.\nCome back tomorrow morning.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().background(RippleColors.Parchment)) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    current.masthead,
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    "No. ${current.issueNumber} — ${SimTime.formatDate(current.publishedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Divider(Modifier.padding(top = 8.dp), color = RippleColors.Ink)
            }
        }
        val grouped = stories.groupBy { it.category }
        val order = StoryCategory.entries.map { it.name }
        for (categoryName in order) {
            val catStories = grouped[categoryName] ?: continue
            val isHeadline = categoryName == StoryCategory.HEADLINE.name
            if (!isHeadline) {
                item(key = "hdr$categoryName") {
                    Text(
                        StoryCategory.valueOf(categoryName).label.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = RippleColors.DeepBrick,
                        modifier = Modifier.padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 2.dp)
                    )
                }
            }
            items(catStories, key = { "story${it.id}" }) { story ->
                Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text(
                        story.headline,
                        style = if (isHeadline) MaterialTheme.typography.headlineMedium
                        else MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        story.body,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = if (story.category == StoryCategory.NOTICES.name) FontStyle.Italic else FontStyle.Normal
                    )
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
                    items(issues, key = { it.id }) { issue ->
                        FilterChip(
                            selected = issue.id == current.id,
                            onClick = { viewModel.select(issue.id) },
                            label = { Text("No. ${issue.issueNumber}") }
                        )
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}
