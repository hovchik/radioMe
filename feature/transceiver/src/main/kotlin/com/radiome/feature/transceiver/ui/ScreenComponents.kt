package com.radiome.feature.transceiver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radiome.feature.transceiver.settings.Health

// The visual language of the Settings screen, for every other tab: a status card on top whose colour says how
// things stand, one card per topic with a round icon, coloured status dots and pills, and short help lines.

/** Card colour for a status summary, matching the Settings overview. */
@Composable
fun healthContainer(h: Health): Color = when (h) {
    Health.PROBLEM -> MaterialTheme.colorScheme.errorContainer
    Health.ATTENTION -> MaterialTheme.colorScheme.secondaryContainer
    Health.OK -> MaterialTheme.colorScheme.primaryContainer
    Health.IDLE -> MaterialTheme.colorScheme.surfaceContainerHigh
}

/** The round icon in front of card titles. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 36.dp,
) {
    Surface(shape = CircleShape, color = container, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.56f))
        }
    }
}

/**
 * The card at the top of a screen that says how things stand: coloured by [health], an icon, a title and one
 * line, an optional control on the right, and whatever details follow.
 */
@Composable
fun StatusHeroCard(
    health: Health,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = healthContainer(health)), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon,
                    container = healthColor(health).copy(alpha = 0.18f),
                    content = if (health == Health.IDLE) MaterialTheme.colorScheme.onSurfaceVariant else healthColor(health),
                    size = 40.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                trailing?.let {
                    Spacer(Modifier.width(8.dp))
                    it()
                }
            }
            content?.invoke(this)
        }
    }
}

/** One number with its name under it, in a row of a few. */
@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Equal-width [MetricTile]s side by side. */
@Composable
fun MetricRow(metrics: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.forEach { (label, value) -> MetricTile(label, value, Modifier.weight(1f)) }
    }
}

/** A one-line notice with an icon and an optional action: "The radio is off. [Turn on]". */
@Composable
fun NoticeCard(
    icon: ImageVector,
    text: String,
    health: Health,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val container = when (health) {
        Health.PROBLEM -> MaterialTheme.colorScheme.errorContainer
        Health.ATTENTION -> MaterialTheme.colorScheme.tertiaryContainer
        Health.OK -> MaterialTheme.colorScheme.primaryContainer
        Health.IDLE -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = when (health) {
        Health.PROBLEM -> MaterialTheme.colorScheme.onErrorContainer
        Health.ATTENTION -> MaterialTheme.colorScheme.onTertiaryContainer
        Health.OK -> MaterialTheme.colorScheme.onPrimaryContainer
        Health.IDLE -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer), modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** A heading between cards, with an optional control on the right. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** What a list shows when it has nothing yet: an icon, a title and what to do. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBadge(icon, size = 56.dp, container = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A draft attachment (file, voice note) as a tile: icon, name and size, and a control on the right. */
@Composable
fun AttachmentTile(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
}

/** Material symbols the core icon set lacks, drawn from their 24 dp path data. */
object RadioIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

    val image = icon(
        "Image",
        "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
    )
    val file = icon(
        "File",
        "M6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6H6zM13,9V3.5L18.5,9H13z",
    )
    val mic = icon(
        "Mic",
        "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1" +
            "S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
    )
    val schedule = icon(
        "Schedule",
        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 " +
            "8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
    )
    val radio = icon(
        "Radio",
        "M3.24,6.15C2.51,6.43 2,7.17 2,8v12c0,1.1 0.89,2 2,2h16c1.11,0 2,-0.9 2,-2L22,8c0,-1.11 -0.89,-2 -2,-2L8.3,6l8.26,-3.34" +
            "L15.88,1 3.24,6.15zM7,20c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM20,12h-2v-2h-2v2L4,12L4,8h16v4z",
    )
}
