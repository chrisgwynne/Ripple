package com.ripple.town.feature.people

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ripple.town.core.ui.EmptyNote
import com.ripple.town.core.ui.PixelAvatar
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.core.ui.SectionTitle
import com.ripple.town.core.ui.SpriteProvider
import com.ripple.town.data.RelationUi
import com.ripple.town.data.ResidentUi
import com.ripple.town.data.WorldUi
import com.ripple.town.feature.town.poseFor
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen dialog: generational family tree (Canvas-drawn connectors) plus
 * a toggled relationship map (radial layout) for one resident. Reuses
 * `familyOf()` from PeopleScreen.kt for the immediate generation and extends
 * outward for grandparents/grandchildren where traceable; reuses the
 * already-capped-at-12-by-warmth `ResidentUi.relationships` for the map.
 *
 * Self-contained: opened via local `remember` state from PeopleScreen, no
 * navigation graph or RippleApp.kt changes.
 */
@Composable
fun FamilyTreeDialog(
    world: WorldUi,
    residentId: Long,
    sprites: SpriteProvider,
    onOpenResident: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val r = world.resident(residentId) ?: return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            FamilyTreeContent(world, r, sprites, onOpenResident, onDismiss)
        }
    }
}

@Composable
private fun FamilyTreeContent(
    world: WorldUi,
    r: ResidentUi,
    sprites: SpriteProvider,
    onOpenResident: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember(r.id) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${r.firstName}'s connections", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Family tree and relationship map",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Family tree") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Relationship map") })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> FamilyTreeTab(world, r, sprites, onOpenResident)
                else -> RelationshipMapTab(world, r, sprites, onOpenResident)
            }
        }
    }
}

// ------------------------------------------------------------ family tree

private const val NODE_SIZE_DP = 56
private const val NODE_SIZE_DP_SMALL = 44
private const val ROW_V_GAP_DP = 46
private const val COL_H_GAP_DP = 14

/** One generation row: a list of (resident, role label) slots, gaps allowed for symmetry. */
private data class TreeSlot(val resident: ResidentUi?, val role: String)

@Composable
private fun FamilyTreeTab(
    world: WorldUi,
    r: ResidentUi,
    sprites: SpriteProvider,
    onOpenResident: (Long) -> Unit
) {
    // Generation -2: grandparents (only the ones traceable through mother/father).
    val maternalGrandmother = world.resident(r.motherId)?.let { world.resident(it.motherId) }
    val maternalGrandfather = world.resident(r.motherId)?.let { world.resident(it.fatherId) }
    val paternalGrandmother = world.resident(r.fatherId)?.let { world.resident(it.motherId) }
    val paternalGrandfather = world.resident(r.fatherId)?.let { world.resident(it.fatherId) }
    val grandparents = listOf(
        TreeSlot(paternalGrandfather, "Grandfather"),
        TreeSlot(paternalGrandmother, "Grandmother"),
        TreeSlot(maternalGrandfather, "Grandfather"),
        TreeSlot(maternalGrandmother, "Grandmother")
    ).filter { it.resident != null }

    // Generation -1: parents.
    val mother = world.resident(r.motherId)
    val father = world.resident(r.fatherId)
    val parents = listOfNotNull(
        father?.let { TreeSlot(it, "Father") },
        mother?.let { TreeSlot(it, "Mother") }
    )

    // Generation 0: resident + partner.
    val partner = world.resident(r.partnerId)
    val self = listOfNotNull(
        TreeSlot(r, "—"),
        partner?.let { TreeSlot(it, "Partner") }
    )

    // Generation +1: children.
    val children = r.childIds.mapNotNull { world.resident(it) }.map { TreeSlot(it, "Child") }

    // Generation +2: grandchildren, traced through each child's own childIds.
    val grandchildren = children.flatMap { slot ->
        slot.resident?.childIds.orEmpty().mapNotNull { world.resident(it) }
    }.distinctBy { it.id }.map { TreeSlot(it, "Grandchild") }

    val hasAnyFamily = parents.isNotEmpty() || partner != null || children.isNotEmpty()
    if (!hasAnyFamily && grandparents.isEmpty() && grandchildren.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            EmptyNote("No traceable family for ${r.firstName} yet.")
        }
        return
    }

    Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        if (grandparents.isNotEmpty()) {
            GenerationRow("Grandparents", grandparents, sprites, world, onOpenResident, small = true)
            Connector(grandparents.size, parents.size.coerceAtLeast(1))
        }
        if (parents.isNotEmpty()) {
            GenerationRow("Parents", parents, sprites, world, onOpenResident)
            Connector(parents.size.coerceAtLeast(1), self.size)
        } else if (grandparents.isNotEmpty()) {
            // Grandparents known but the direct parent link wasn't traceable — still connect down.
            Connector(1, self.size)
        }
        GenerationRow(if (r.firstName.isNotBlank()) r.firstName else "This resident", self, sprites, world, onOpenResident, highlightId = r.id)
        if (children.isNotEmpty()) {
            Connector(self.size, children.size)
            GenerationRow("Children", children, sprites, world, onOpenResident)
        }
        if (grandchildren.isNotEmpty()) {
            Connector(children.size.coerceAtLeast(1), grandchildren.size)
            GenerationRow("Grandchildren", grandchildren, sprites, world, onOpenResident, small = true)
        }
    }
    if (grandparents.size < 4 || grandchildren.isEmpty()) {
        Text(
            "Showing only what the town remembers — untraceable grandparents or " +
                "grandchildren (e.g. founders born before the simulation, or lines that " +
                "simply haven't produced them yet) are left out rather than shown as gaps.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)
        )
    }
}

@Composable
private fun GenerationRow(
    label: String,
    slots: List<TreeSlot>,
    sprites: SpriteProvider,
    world: WorldUi,
    onOpenResident: (Long) -> Unit,
    small: Boolean = false,
    highlightId: Long? = null
) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp)
    )
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(COL_H_GAP_DP.dp)
    ) {
        slots.forEach { slot ->
            val res = slot.resident ?: return@forEach
            TreeNode(res, slot.role, sprites, small, highlight = res.id == highlightId) {
                onOpenResident(res.id)
            }
        }
    }
}

@Composable
private fun TreeNode(
    r: ResidentUi,
    role: String,
    sprites: SpriteProvider,
    small: Boolean,
    highlight: Boolean,
    onClick: () -> Unit
) {
    val size = if (small) NODE_SIZE_DP_SMALL else NODE_SIZE_DP
    Column(
        Modifier.width(84.dp).wrapContentSize(Alignment.TopCenter),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(size.dp)
                .clip(CircleShape)
                .then(
                    if (highlight)
                        Modifier.background(RippleColors.Gold.copy(alpha = 0.35f))
                    else Modifier
                )
                .padding(3.dp)
        ) {
            PixelAvatar(
                r.sprite, sprites, size = (size - 6).dp,
                pose = poseFor(r), lifeStage = r.lifeStage, occupation = r.occupation
            )
        }
        Text(
            r.firstName,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (role != "—") {
            Text(
                role + if (!r.alive) " · d." else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        } else if (!r.alive) {
            Text(
                "Deceased",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Simple centred connector: a short vertical stem plus a horizontal bar spanning the wider row, drawn via Canvas. */
@Composable
private fun Connector(fromCount: Int, toCount: Int) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(ROW_V_GAP_DP.dp)
    ) {
        val midX = size.width / 2f
        val stemTop = 0f
        val stemBottom = size.height
        val colour = RippleColors.SoftInk.copy(alpha = 0.5f)
        val stroke = 2.dp.toPx()
        // Simple trunk-and-spread: a vertical line down the centre, capped — good enough
        // to visually separate generations without modelling per-node x positions (the
        // per-row Compose layout already centres each generation independently).
        drawLine(
            colour,
            start = Offset(midX, stemTop),
            end = Offset(midX, stemBottom),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawCircle(colour, radius = stroke, center = Offset(midX, stemTop))
        drawCircle(colour, radius = stroke, center = Offset(midX, stemBottom))
    }
}

// -------------------------------------------------------- relationship map

private val KIND_COLOURS = mapOf(
    "Friend" to RippleColors.WarmGreen,
    "Close friend" to RippleColors.DeepGreen,
    "Rival" to RippleColors.BrickRed,
    "Secret affair" to RippleColors.Blush,
    "Former partner" to RippleColors.MutedBrown,
    "Acquaintance" to RippleColors.PaleBlue,
    "Stranger" to RippleColors.SoftInk
)
private val FAMILY_KIND_LABELS = setOf("Family", "Estranged family", "Partner", "Spouse")

@Composable
private fun RelationshipMapTab(
    world: WorldUi,
    r: ResidentUi,
    sprites: SpriteProvider,
    onOpenResident: (Long) -> Unit
) {
    // Non-family relationships only — family already has its own dedicated tree above.
    // ResidentUi.relationships is already sorted by warmth and capped at 12
    // (SnapshotBuilder.residentUi), so no re-sorting/re-capping is needed here.
    val nonFamily = r.relationships.filter { it.kindLabel !in FAMILY_KIND_LABELS }

    if (nonFamily.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            EmptyNote("${r.firstName} hasn't formed any notable relationships outside family yet.")
        }
        return
    }

    val diameterDp = 300
    Box(
        Modifier
            .fillMaxWidth()
            .height(diameterDp.dp)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        RadialConnectors(nonFamily, diameterDp)
        // Centre node.
        PixelAvatar(r.sprite, sprites, size = 60.dp, pose = poseFor(r), lifeStage = r.lifeStage, occupation = r.occupation)
        val density = LocalDensity.current
        val radiusPx = with(density) { (diameterDp * 0.42f).dp.toPx() }
        nonFamily.forEachIndexed { i, rel ->
            val angle = (2 * Math.PI * i / nonFamily.size) - Math.PI / 2
            val dxPx = (radiusPx * cos(angle)).toFloat()
            val dyPx = (radiusPx * sin(angle)).toFloat()
            val other = world.resident(rel.otherId)
            val colour = KIND_COLOURS[rel.kindLabel] ?: RippleColors.SoftInk
            Column(
                Modifier
                    .offset { IntOffset(dxPx.toInt(), dyPx.toInt()) }
                    .width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(colour.copy(alpha = 0.22f))
                        .then(
                            if (other != null) Modifier.clickable { onOpenResident(rel.otherId) } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (other != null) {
                        PixelAvatar(other.sprite, sprites, size = 40.dp, lifeStage = other.lifeStage, occupation = other.occupation)
                    }
                }
                Text(
                    rel.otherName.substringBefore(" "),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                Text(
                    rel.kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colour,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
    SectionTitle("Legend", Modifier.padding(horizontal = 20.dp))
    Row(
        Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        nonFamily.map { it.kindLabel }.distinct().forEach { kind ->
            val colour = KIND_COLOURS[kind] ?: RippleColors.SoftInk
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
                Spacer(Modifier.width(4.dp))
                Text(kind, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Text(
        "Closest ${nonFamily.size} relationship${if (nonFamily.size == 1) "" else "s"} by warmth, " +
            "excluding family (see the Family tree tab for those). ${r.firstName}'s data already " +
            "caps at 12 relationships town-wide.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
    )
}

/** Draws the spokes from centre to each relationship node behind the avatars. */
@Composable
private fun RadialConnectors(relations: List<RelationUi>, diameterDp: Int) {
    Canvas(Modifier.size(diameterDp.dp)) {
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        val radiusPx = diameterDp * 0.42f * density
        relations.forEachIndexed { i, rel ->
            val angle = (2 * Math.PI * i / relations.size) - Math.PI / 2
            val end = Offset(
                centre.x + (radiusPx * cos(angle)).toFloat(),
                centre.y + (radiusPx * sin(angle)).toFloat()
            )
            val colour = (KIND_COLOURS[rel.kindLabel] ?: RippleColors.SoftInk).copy(alpha = 0.45f)
            drawLine(
                colour,
                start = centre,
                end = end,
                strokeWidth = (1.2f + (rel.warmth.coerceIn(0.0, 100.0) / 100.0 * 2.2f).toFloat()).dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}
