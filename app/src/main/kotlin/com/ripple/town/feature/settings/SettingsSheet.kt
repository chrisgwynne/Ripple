package com.ripple.town.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ripple.town.core.ui.SectionTitle
import com.ripple.town.data.SettingsRepository
import com.ripple.town.data.WorldRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val worldRepository: WorldRepository
) : ViewModel() {
    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.ripple.town.data.Settings())

    val world = worldRepository.worldUi

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }
    }
}

/** Deliberately small: Ripple is not a settings app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val world by viewModel.world.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            SectionTitle("Alerts")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Life alerts", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Quiet banners when something happens to people you follow.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = settings.notificationsEnabled, onCheckedChange = viewModel::setNotifications)
            }
            SectionTitle("This world")
            Text(
                "Town: ${world?.townName ?: settings.townName}\nSeed: ${world?.worldSeed ?: settings.worldSeed}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "The same seed always grows the same town.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SectionTitle("About")
            Text(
                "Ripple is an observation. The town lives whether you watch or not — " +
                    "it simply waits for you, then remembers everything that happened while you were away.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Prototype build. Health events are fictional simulation, not medical guidance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
