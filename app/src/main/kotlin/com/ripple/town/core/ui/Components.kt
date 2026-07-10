package com.ripple.town.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.SpriteConfig

/** Small pixel-art portrait rendered from a resident's sprite config. */
@Composable
fun PixelAvatar(
    config: SpriteConfig,
    sprites: SpriteProvider,
    size: Dp = 44.dp,
    pose: Pose = Pose.STAND,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    lifeStage: LifeStage = LifeStage.ADULT,
    occupation: String = ""
) {
    val bmp = remember(config, pose, lifeStage, occupation) { sprites.resident(config, pose, 0, lifeStage, occupation) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.78f)) {
            drawIntoCanvas { canvas ->
                val paint = Paint().apply { filterQuality = FilterQuality.None }
                val scale = this.size.height / bmp.height
                val w = bmp.width * scale
                canvas.save()
                canvas.translate((this.size.width - w) / 2f, 0f)
                canvas.scale(scale, scale)
                canvas.drawImageRect(
                    bmp,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(bmp.width, bmp.height),
                    paint = paint
                )
                canvas.restore()
            }
        }
    }
}

/** 0..100 bar for needs/relationship values. */
@Composable
fun StatBar(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    good: Boolean = true,
    max: Double = 100.0
) {
    val fraction = (value / max).coerceIn(0.0, 1.0).toFloat()
    val colour = when {
        !good -> if (fraction > 0.6f) RippleColors.DeepBrick else RippleColors.MutedBrown
        fraction > 0.55f -> RippleColors.WarmGreen
        fraction > 0.3f -> RippleColors.Gold
        else -> RippleColors.BrickRed
    }
    Column(modifier = modifier.padding(vertical = 3.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("${value.toInt()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colour)
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 8.dp)
    )
}

/** Little dotted connector for cause chains. */
@Composable
fun CauseConnector(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 24.dp, height = 22.dp)) {
        val x = size.width / 2
        var y = 2f
        while (y < size.height) {
            drawCircle(RippleColors.SoftInk, radius = 1.6f, center = Offset(x, y))
            y += 7f
        }
    }
}
