package com.ripple.town.feature.town

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
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
import com.ripple.town.core.simulation.ImportanceScorer
import com.ripple.town.core.ui.PixelAvatar
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.core.ui.SpriteProvider
import com.ripple.town.data.EventUi
import com.ripple.town.data.WorldUi
import kotlinx.coroutines.delay
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

    // Event banners: at most 2 shown at once, newest first, each auto-expiring
    // independently ~4s after it first appears (mirrors the `alert` pattern above).
    val eventBanners = remember { mutableStateListOf<EventUi>() }
    LaunchedEffect(recentEvents) {
        val latest = recentEvents.firstOrNull() ?: return@LaunchedEffect
        if (eventBanners.none { it.id == latest.id }) {
            eventBanners.add(0, latest)
            while (eventBanners.size > 2) eventBanners.removeAt(eventBanners.lastIndex)
        }
    }

    // Follow moments: the core promise of the app ("watch their world continue
    // without you") surfaced as a single, more prominent vignette card — distinct
    // from the generic event-banner ticker above, which shows ANY town event.
    // A "moment" is an event where the *followed* resident is source or target
    // AND it clears the same notability bar used everywhere else in the app for
    // "this mattered" (ImportanceScorer.HISTORY_THRESHOLD — see EraSummary/history
    // timeline). At most one card at a time, with a cooldown so two moments can't
    // fire back-to-back — both enforced by only ever tracking a single
    // `followMoment` slot and only replacing it once the previous one has cleared.
    var followMoment by remember { mutableStateOf<EventUi?>(null) }
    var followMomentCooldownUntil by remember { mutableStateOf(0L) }
    LaunchedEffect(recentEvents, world?.followedResidentId) {
        val followedId = world?.followedResidentId ?: return@LaunchedEffect
        val latest = recentEvents.firstOrNull() ?: return@LaunchedEffect
        if (followMoment?.id == latest.id) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (followMoment == null &&
            now >= followMomentCooldownUntil &&
            latest.importance >= ImportanceScorer.HISTORY_THRESHOLD &&
            followedId in latest.involvedResidentIds
        ) {
            followMoment = latest
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
                    camera.isFollowing = true
                    viewModel.consumeJump()
                }
            }
            TownRenderer(
                world = w,
                camera = camera,
                sprites = sprites,
                modifier = Modifier.fillMaxSize(),
                onTap = viewModel::onTap,
                recentEvents = recentEvents
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
                HudVectorIconButton(Icons.Filled.Insights, contentDescription = "Town overview", onClick = viewModel::openTownOverview)
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
            // Follow moment: a single, more prominent vignette card for something
            // notable the followed resident just did/experienced. Longer dwell time
            // than the plain banners below — these are meant to feel special, not
            // like ticker noise — and clears the cooldown window on dismiss so the
            // next moment can't immediately replace it.
            followMoment?.let { moment ->
                key(moment.id) {
                    var visible by remember { mutableStateOf(true) }
                    val followedResident = w.resident(w.followedResidentId)
                    LaunchedEffect(moment.id) {
                        delay(8_000)
                        visible = false
                        delay(300)
                        followMoment = null
                        followMomentCooldownUntil = System.currentTimeMillis() + 15_000
                    }
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 })
                    ) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = RippleColors.Gold.copy(alpha = 0.95f),
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.openEvent(moment.id) }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (followedResident != null) {
                                        PixelAvatar(
                                            followedResident.sprite, sprites, size = 40.dp,
                                            pose = poseFor(followedResident),
                                            lifeStage = followedResident.lifeStage,
                                            occupation = followedResident.occupation,
                                            background = RippleColors.Cream
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "A moment worth watching",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = RippleColors.Ink.copy(alpha = 0.65f)
                                        )
                                        Text(
                                            moment.description,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = RippleColors.Ink,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Live event banners: newest first, max 2, each fades/slides in and
            // auto-dismisses independently a few seconds after it appears. Skips
            // whichever event is currently showing as the (more prominent) follow
            // moment card above, so the same happening isn't shown twice at once.
            eventBanners.filter { it.id != followMoment?.id }.forEach { banner ->
                key(banner.id) {
                    var visible by remember { mutableStateOf(true) }
                    LaunchedEffect(banner.id) {
                        delay(4_000)
                        visible = false
                        delay(300) // let the exit animation finish before dropping it
                        eventBanners.remove(banner)
                    }
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 })
                    ) {
                        Column {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = RippleColors.Cream.copy(alpha = 0.88f),
                                modifier = Modifier.clickable { viewModel.openEvent(banner.id) }
                            ) {
                                Text(
                                    "◦ ${banner.description}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = RippleColors.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
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

        // ------- Return-to-follow control (bottom-centre, only when panned away) -------
        val followedId = w.followedResidentId
        val followedName = w.resident(followedId)?.name
        if (!camera.isFollowing && followedId != null && followedName != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = RippleColors.Ink.copy(alpha = 0.78f),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .clickable { viewModel.jumpToResident(followedId) }
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("◎", color = RippleColors.Gold, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Return to $followedName",
                        color = RippleColors.Cream,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // ------- Speed controls (bottom-left, above nav) -------
        // Collapsed: a single pill showing the current speed. Tapping it expands
        // to the full set of options; picking one (or tapping the pill again)
        // collapses it back.
        var speedExpanded by remember { mutableStateOf(false) }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 10.dp)
        ) {
            AnimatedVisibility(visible = speedExpanded, enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeedButton("⏸", speed == SimSpeed.PAUSED) {
                        viewModel.setSpeed(SimSpeed.PAUSED); speedExpanded = false
                    }
                    Spacer(Modifier.width(5.dp))
                    SpeedButton("1×", speed == SimSpeed.NORMAL) {
                        viewModel.setSpeed(SimSpeed.NORMAL); speedExpanded = false
                    }
                    Spacer(Modifier.width(5.dp))
                    SpeedButton("3×", speed == SimSpeed.FAST) {
                        viewModel.setSpeed(SimSpeed.FAST); speedExpanded = false
                    }
                    Spacer(Modifier.width(5.dp))
                    SpeedButton("10×", speed == SimSpeed.VERY_FAST) {
                        viewModel.setSpeed(SimSpeed.VERY_FAST); speedExpanded = false
                    }
                }
            }
            if (!speedExpanded) {
                SpeedButton(speedPillLabel(speed), selected = true) { speedExpanded = true }
            }
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
    // Default height: the brief wants sheets opening around 55-60% of screen height, not
    // fully collapsed (a sliver of content) or fully expanded (edge to edge). Material3
    // 1.3.1 (this project's resolved compose-bom version — no newer override exists in
    // libs.versions.toml) has `SheetValue.PartiallyExpanded` as a real detent
    // (`skipPartiallyExpanded = false` already opts into it), but exposes no public API
    // to set its height as an explicit fraction of the screen — that arrived later
    // (custom-detent / positional-value-provider `SheetState` constructors, ~1.4.0-alpha).
    // The closest achievable equivalent without bumping the BOM: `PartiallyExpanded`
    // wraps to the sheet CONTENT's own measured height, so giving the content a
    // `heightIn(min = ...)` of ~57% of the screen forces that detent to land there. This
    // is an approximation, not a true fraction lock — a very short sheet (e.g. a near-
    // empty building with no events) will still size to content if content is taller than
    // the min, and users can drag past it either way; stated here rather than overclaimed.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val targetSheetHeight = (configuration.screenHeightDp.dp * 0.57f)
    sheet?.let { current ->
        ModalBottomSheet(
            onDismissRequest = viewModel::closeSheet,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Box(Modifier.heightIn(min = targetSheetHeight)) {
                when (current) {
                    is TownSheet.ResidentSheet -> ResidentSheetContent(
                        world = w, residentId = current.residentId, sprites = sprites, viewModel = viewModel
                    )
                    is TownSheet.BuildingSheet -> BuildingSheetContent(
                        world = w, buildingId = current.buildingId, viewModel = viewModel
                    )
                    is TownSheet.EventSheet -> EventSheetContent(
                        world = w, eventId = current.eventId, sprites = sprites, viewModel = viewModel
                    )
                    is TownSheet.InterventionSheet -> InterventionSheetContent(
                        world = w, residentId = current.residentId, viewModel = viewModel
                    )
                    TownSheet.TownOverviewSheet -> TownOverviewSheetContent(world = w, viewModel = viewModel)
                }
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
private fun HudVectorIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = RippleColors.Cream.copy(alpha = 0.88f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = RippleColors.Ink,
            modifier = Modifier.padding(8.dp).size(20.dp)
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

/** Compact label for the collapsed speed pill, e.g. "▶ 1×" or "⏸ Paused". */
private fun speedPillLabel(speed: SimSpeed): String = when (speed) {
    SimSpeed.PAUSED -> "⏸ Paused"
    SimSpeed.NORMAL -> "▶ 1×"
    SimSpeed.FAST -> "▶ 3×"
    SimSpeed.VERY_FAST -> "▶ 10×"
}

fun weatherGlyph(weather: Weather, timeOfDay: TimeOfDay): String = when (weather) {
    Weather.CLEAR -> if (timeOfDay == TimeOfDay.NIGHT) "☾" else "☀"
    Weather.CLOUDY -> "☁"
    Weather.RAIN -> "☔"
    Weather.STORM -> "⛈"
    Weather.FOG -> "🌫"
    Weather.SNOW -> "❄"
}
