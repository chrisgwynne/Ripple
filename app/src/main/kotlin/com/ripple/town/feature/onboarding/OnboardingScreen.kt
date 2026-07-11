package com.ripple.town.feature.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ripple.town.core.model.SimSpeed
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.data.WorldRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: WorldRepository
) : ViewModel() {

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    fun generate(townName: String, speed: SimSpeed) {
        if (_generating.value) return
        _generating.value = true
        viewModelScope.launch {
            // A short beat so the generation moment lands.
            delay(600)
            repository.createWorld(townName.ifBlank { "Ashcombe" }, speed)
            delay(900)
            _done.value = true
        }
    }
}

/** Five steps: premise → pace → meet your town → you can nudge → generate. No accounts, no forms. */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    var step by remember { mutableIntStateOf(0) }
    var speed by remember { mutableStateOf(SimSpeed.NORMAL) }
    var townName by remember { mutableStateOf("Ashcombe") }
    val generating by viewModel.generating.collectAsState()
    val done by viewModel.done.collectAsState()

    LaunchedEffect(done) { if (done) onFinished() }

    Box(
        Modifier.fillMaxSize().background(RippleColors.Cream),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(32.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (step > 0) {
                StepDots(current = step - 1, total = 4)
                Spacer(Modifier.height(20.dp))
            }
            when (step) {
                0 -> {
                    RippleMark()
                    Spacer(Modifier.height(20.dp))
                    Text("Ripple", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Watch a living town change.\nFollow any life.\nSmall choices may echo for generations.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = { step = 1 },
                        colors = ButtonDefaults.buttonColors(containerColor = RippleColors.WarmGreen)
                    ) { Text("Begin") }
                }
                1 -> {
                    Text("How should the world move?", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "You can change this at any time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    PaceOption("Calm", "An unhurried town. One real minute is an hour there.", speed == SimSpeed.NORMAL) { speed = SimSpeed.NORMAL }
                    PaceOption("Standard", "Life moves along. Days pass as you watch.", speed == SimSpeed.FAST) { speed = SimSpeed.FAST }
                    PaceOption("Fast", "Seasons roll by. Best for watching generations.", speed == SimSpeed.VERY_FAST) { speed = SimSpeed.VERY_FAST }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { step = 2 },
                        colors = ButtonDefaults.buttonColors(containerColor = RippleColors.WarmGreen)
                    ) { Text("Next") }
                }
                2 -> {
                    Text("Meet your town", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap any resident to follow their life. You'll see their needs, relationships, and goals.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    ResidentTilePreview()
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { step = 3 },
                        colors = ButtonDefaults.buttonColors(containerColor = RippleColors.WarmGreen)
                    ) { Text("Got it →") }
                }
                3 -> {
                    Text("You can nudge", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "You start with 3 nudges. Tap a resident and choose an action — send them to the pub, prompt them to make amends, or push them toward a goal. Small choices echo.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    NudgeExamples()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "You earn nudges as time passes.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { step = 4 },
                        colors = ButtonDefaults.buttonColors(containerColor = RippleColors.WarmGreen)
                    ) { Text("Got it →") }
                }
                4 -> {
                    if (!generating) {
                        Text("Name your town", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = townName,
                            onValueChange = { if (it.length <= 20) townName = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { viewModel.generate(townName, speed) },
                            colors = ButtonDefaults.buttonColors(containerColor = RippleColors.WarmGreen)
                        ) { Text("Bring it to life") }
                    } else {
                        GenerationAnimation(townName)
                    }
                }
            }
        }
    }
}

/** Progress dots indicating which of the 5 onboarding steps is active. */
@Composable
private fun StepDots(current: Int, total: Int = 5) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            Box(
                Modifier
                    .size(if (index == current) 10.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == current) RippleColors.WarmGreen
                        else RippleColors.WarmGreen.copy(alpha = 0.28f)
                    )
            )
        }
    }
}

/** Illustrated resident tile preview card for the "Meet your town" step. */
@Composable
private fun ResidentTilePreview() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                color = RippleColors.PaleBlue.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
            .border(
                width = 1.dp,
                color = RippleColors.PaleBlue,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Avatar placeholder
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(RippleColors.SkyBlue.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Text("👤", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Mabel Hurst", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Baker  ·  😊 Content",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Goal: open her own shop",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Three non-interactive nudge option pills for the "You can nudge" step. */
@Composable
private fun NudgeExamples() {
    val options = listOf("Visit a friend", "Seek help", "Rest")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { label ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = RippleColors.WarmGreen.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = RippleColors.WarmGreen.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PaceOption(title: String, blurb: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) RippleColors.WarmGreen.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GenerationAnimation(townName: String) {
    var phase by remember { mutableIntStateOf(0) }
    val phases = listOf(
        "Laying the streets…",
        "Raising the roofs…",
        "Filling the larders…",
        "Waking the residents…",
        "Starting the clocks…"
    )
    LaunchedEffect(Unit) {
        while (phase < phases.size - 1) {
            delay(320)
            phase++
        }
    }
    val progress by animateFloatAsState(
        targetValue = (phase + 1f) / phases.size,
        animationSpec = tween(300),
        label = "gen"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RippleMark()
        Spacer(Modifier.height(18.dp))
        Text("Generating ${townName.ifBlank { "Ashcombe" }}", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(phases[phase], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress },
            color = RippleColors.WarmGreen,
            modifier = Modifier.width(200.dp)
        )
    }
}

/** Concentric ripple rings mark. */
@Composable
private fun RippleMark() {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(RippleColors.PaleBlue.copy(alpha = 0.4f)))
        Box(Modifier.size(56.dp).clip(CircleShape).background(RippleColors.PaleBlue.copy(alpha = 0.6f)))
        Box(Modifier.size(30.dp).clip(CircleShape).background(RippleColors.SkyBlue))
    }
}
