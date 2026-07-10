package com.ripple.town.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ripple.town.core.ui.SectionTitle
import com.ripple.town.data.SettingsRepository
import com.ripple.town.data.WorldRepository
import com.ripple.town.work.NotificationCheckWorker
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

    /**
     * Persists the push-notification opt-in. Called only after the caller has
     * already resolved (or deliberately skipped, e.g. turning the toggle off) the
     * OS permission — see [SettingsSheet]'s toggle handler, which drives the actual
     * [ActivityResultContracts.RequestPermission] launcher, since that's a
     * Composable-scoped API this ViewModel can't own directly.
     */
    fun setPushNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPushNotificationsEnabled(enabled) }
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
    val context = LocalContext.current

    // Standard rememberLauncherForActivityResult(RequestPermission()) flow. Only ever
    // launched from the toggle handler below (an explicit user tap), never on
    // composition/launch — that's what makes this "opt-in" rather than an
    // unprompted launch-time request.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Persist the opt-in regardless of the OS grant result: if denied, the toggle
        // stays visually on but NotificationHelper.canPostNotifications() will simply
        // suppress delivery until the user grants it via system settings — the app
        // never re-prompts on its own (Android also blocks a second in-app prompt
        // after one denial without the user first visiting system settings).
        viewModel.setPushNotificationsEnabled(true)
        if (granted) NotificationCheckWorker.enqueue(context)
    }

    val onTogglePush: (Boolean) -> Unit = onTogglePush@{ enabled ->
        if (!enabled) {
            viewModel.setPushNotificationsEnabled(false)
            NotificationCheckWorker.cancel(context)
            return@onTogglePush
        }
        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val alreadyGranted = !needsRuntimePermission || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            viewModel.setPushNotificationsEnabled(true)
            NotificationCheckWorker.enqueue(context)
        } else {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Push notifications", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Real phone notifications for followed/favourite residents, even " +
                            "when Ripple is closed. Checked on app open and roughly every " +
                            "15 minutes in the background — never continuous.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = settings.pushNotificationsEnabled, onCheckedChange = onTogglePush)
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
