package com.ripple.town.feature.town

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ripple.town.core.model.SimSpeed
import com.ripple.town.core.model.TimeOfDay
import com.ripple.town.core.model.Weather
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.core.ui.SpriteProvider
import com.ripple.town.data.WorldUi
import kotlinx.coroutines.flow.collectLatest

/**
 * The default screen: the full-screen living town with a light HUD.
 * No permanent data panels — everything detailed lives in bottom sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TownScreen(
    sprites: SpriteProvider,
    onOpenSettings: () -> Unit,
    viewModel: TownViewModel = hiltViewModel()
) {
    val world by viewModel.world.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val sheet by viewModel.sheet.collectAsState()
    val catchUp by viewModel.catchUp.collectAsState()
    val death by viewModel.followedDeath.collectAsState()
    val recentEvents by viewModel.recentEvents.collectAsState()

    var alert by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.alerts.collectLatest {
            alert = it
            kotlinx.coroutines.delay(5_000)
            alert = null
        }
    }

    val w = world
    if (w == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = RippleColors.WarmGreen)
        }
        return
    }

    val camera = remember { TownCamera() }
    var canvasSize by remember { mutableStateOf(0f to 0f) }
    val jump by viewModel.jumpTo.collectAsState()
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().background(RippleColors.GrassDark)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val cw = with(density) { maxWidth.toPx() }
            val ch = with(density) { maxHeight.toPx() }
            canvasSize = cw to ch
            // First composition: centre on the followed resident.
            LaunchedEffect(Unit) {
                val followed = w.resident(w.followedResidentId)
                if (followed?.visibleOnMap == true) camera.centreOn(followed.x, followed.y, cw, ch)
                else camera.centreOn(w.map.width / 2f, w.map.height / 2f, cw, ch)
            }
            LaunchedEffect(jump) {
                jump?.let { (x, y) ->
                    camera.centreOn(x, y, cw, ch)
                    viewModel.consumeJump()
                }
            }
            TownRenderer(
                world = w,
                camera = camera,
                sprites = sprites,
                modifier = Modifier.fillMaxSize(),
                onTap = viewModel::onTap
            )
        }

        // ------- HUD (top) -------
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudChip("${w.dateLabel}  ·  ${w.clockLabel}")
                Spacer(Modifier.width(6.dp))
                HudChip("${weatherGlyph(w.weather, w.timeOfDay)} ${w.weather.label}")
                Spacer(Modifier.weight(1f))
                HudChip("Pop ${w.population}")
                Spacer(Modifier.width(6.dp))
                HudIconButton("⚙", onClick = onOpenSettings)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val followed = w.resident(w.followedResidentId)
                if (followed != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = RippleColors.Ink.copy(alpha = 0.72f),
                        modifier = Modifier.clickable { viewModel.jumpToResident(followed.id) }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape).background(RippleColors.Gold)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Following ${followed.name} · ${followed.activity.label}",
                                color = RippleColors.Cream,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                HudChip("✨ ${w.nudges}/${w.maxNudges}")
            }
            // Live event ticker
            val latest = recentEvents.firstOrNull()
            if (latest != null) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = RippleColors.Cream.copy(alpha = 0.88f),
                    modifier = Modifier.clickable { viewModel.openEvent(latest.id) }
                ) {
                    Text(
                        "◦ ${latest.description}",
                        style = MaterialTheme.typography.labelMedium,
                        color = RippleColors.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            alert?.let {
                Spacer(Modifier.height(6.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = RippleColors.Gold.copy(alpha = 0.92f)) {
                    Text(
                        it, style = MaterialTheme.typography.labelMedium, color = RippleColors.Ink,
                        maxLines = 2, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // ------- Speed controls (bottom-left, above nav) -------
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpeedButton("⏸", speed == SimSpeed.PAUSED) { viewModel.setSpeed(SimSpeed.PAUSED) }
            Spacer(Modifier.width(5.dp))
            SpeedButton("1×", speed == SimSpeed.NORMAL) { viewModel.setSpeed(SimSpeed.NORMAL) }
            Spacer(Modifier.width(5.dp))
            SpeedButton("3×", speed == SimSpeed.FAST) { viewModel.setSpeed(SimSpeed.FAST) }
            Spacer(Modifier.width(5.dp))
            SpeedButton("10×", speed == SimSpeed.VERY_FAST) { viewModel.setSpeed(SimSpeed.VERY_FAST) }
        }

        // ------- Catch-up overlay -------
        catchUp?.let { progress ->
            if (progress.running) {
                Box(
                    Modifier.fillMaxSize().background(RippleColors.Ink.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(shape = RoundedCornerShape(18.dp), color = RippleColors.Cream) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Time passed in ${w.townName}…", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = {
                                    if (progress.totalTicks == 0) 0f
                                    else progress.doneTicks.toFloat() / progress.totalTicks
                                },
                                color = RippleColors.WarmGreen,
                                modifier = Modifier.width(200.dp)
                            )
                        }
                    }
                }
            } else if (progress.summary != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = RoundedCornerShape(18.dp), color = RippleColors.Cream,
                        shadowElevation = 6.dp,
                        modifier = Modifier.padding(32.dp).clickable { viewModel.dismissCatchUp() }
                    ) {
                        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Welcome back", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(8.dp))
                            Text(progress.summary!!, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Tap to continue",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // ------- Sheets -------
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    sheet?.let { current ->
        ModalBottomSheet(
            onDismissRequest = viewModel::closeSheet,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            when (current) {
                is TownSheet.ResidentSheet -> ResidentSheetContent(
                    world = w, residentId = current.residentId, sprites = sprites, viewModel = viewModel
                )
                is TownSheet.BuildingSheet -> BuildingSheetContent(
                    world = w, buildingId = current.buildingId, viewModel = viewModel
                )
                is TownSheet.EventSheet -> EventSheetContent(
                    world = w, eventId = current.eventId, viewModel = viewModel
                )
                is TownSheet.InterventionSheet -> InterventionSheetContent(
                    world = w, residentId = current.residentId, viewModel = viewModel
                )
            }
        }
    }

    // ------- Death of the followed resident -------
    death?.let { d ->
        DeathSummaryDialog(
            death = d,
            onFollow = { id -> viewModel.follow(id); viewModel.dismissFollowedDeath() },
            onDismiss = viewModel::dismissFollowedDeath
        )
    }
}

@Composable
private fun HudChip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = RippleColors.Cream.copy(alpha = 0.88f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = RippleColors.Ink,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun HudIconButton(glyph: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = RippleColors.Cream.copy(alpha = 0.88f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.labelLarge,
            color = RippleColors.Ink,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
private fun SpeedButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) RippleColors.WarmGreen else RippleColors.Cream.copy(alpha = 0.85f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else RippleColors.Ink,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

fun weatherGlyph(weather: Weather, timeOfDay: TimeOfDay): String = when (weather) {
    Weather.CLEAR -> if (timeOfDay == TimeOfDay.NIGHT) "☾" else "☀"
    Weather.CLOUDY -> "☁"
    Weather.RAIN -> "☔"
    Weather.STORM -> "⛈"
    Weather.FOG -> "🌫"
    Weather.SNOW -> "❄"
}
