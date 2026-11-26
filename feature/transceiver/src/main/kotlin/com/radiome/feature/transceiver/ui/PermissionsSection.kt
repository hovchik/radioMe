package com.radiome.feature.transceiver.ui

import com.radiome.feature.transceiver.i18n.tr
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.radiome.feature.transceiver.map.MapLocation
import com.radiome.feature.transceiver.map.OfflineMaps
import com.radiome.feature.transceiver.settings.AppPermission
import com.radiome.feature.transceiver.settings.PermissionAction
import com.radiome.feature.transceiver.settings.PermissionRow
import com.radiome.feature.transceiver.settings.PermissionStatus
import com.radiome.feature.transceiver.settings.TIRAMISU
import com.radiome.feature.transceiver.settings.permissionRows

/** Remembers which permission prompts RadioMe has shown, to tell "not asked yet" from "denied for good". */
internal object PermissionPrompts {
    private const val FILE = "radiome.permissions"

    fun markAsked(context: Context, permissions: Collection<String>) {
        val names = permissions.mapNotNull { manifestToApp(it)?.name }
        if (names.isEmpty()) return
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY, prefs.getStringSet(KEY, emptySet()).orEmpty() + names).apply()
    }

    fun asked(context: Context): Set<AppPermission> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
            .mapNotNull { name -> AppPermission.entries.firstOrNull { it.name == name } }.toSet()

    private fun manifestToApp(permission: String): AppPermission? = when (permission) {
        Manifest.permission.RECORD_AUDIO -> AppPermission.MICROPHONE
        "android.permission.POST_NOTIFICATIONS" -> AppPermission.NOTIFICATIONS
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> AppPermission.LOCATION
        else -> null
    }

    private const val KEY = "asked"
}

private fun AppPermission.manifestName(): String? = when (this) {
    AppPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
    AppPermission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
    AppPermission.PHOTOS -> null
    AppPermission.LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
    AppPermission.LOCATION_SERVICES, AppPermission.INTERNET -> null
}

/** What the system prompt asks for: location always asks for both, so Android 12+ offers precise and approximate. */
private fun AppPermission.requestNames(): Array<String> = when (this) {
    AppPermission.LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    else -> listOfNotNull(manifestName()).toTypedArray()
}

private fun Context.openLocationSettings() {
    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun Context.openSettingsFor(p: AppPermission) = when (p) {
    AppPermission.NOTIFICATIONS -> openNotificationSettings()
    AppPermission.LOCATION_SERVICES -> openLocationSettings()
    else -> openAppSettings()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Context.openNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Lists each permission RadioMe uses with its current state and a button to grant or revoke it. The state is read
 * again whenever the screen resumes, so a change made on the system settings page shows up on return.
 *
 * @param onBeforeRevokeMicrophone turns the radio off before the microphone is taken away.
 */
@Composable
fun PermissionsSection(onBeforeRevokeMicrophone: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val rows = remember(refresh) {
        val activity = context.findActivity()
        permissionRows(
            sdk = Build.VERSION.SDK_INT,
            micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            askedBefore = PermissionPrompts.asked(context),
            locationGranted = MapLocation.granted(context),
            preciseLocation = MapLocation.precise(context),
            locationServicesOn = MapLocation.servicesOn(context),
            mapDownloadsOn = OfflineMaps.onlineTiles(context),
            showRationale = AppPermission.entries.filter { p ->
                val name = p.manifestName()
                activity != null && name != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, name)
            }.toSet(),
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    var revoking by remember { mutableStateOf<AppPermission?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            PermissionRowView(row) {
                when (row.action) {
                    PermissionAction.REQUEST -> row.permission.requestNames().takeIf { it.isNotEmpty() }?.let {
                        PermissionPrompts.markAsked(context, it.toList())
                        launcher.launch(it)
                    }
                    PermissionAction.OPEN_SETTINGS -> context.openSettingsFor(row.permission)
                    PermissionAction.REVOKE -> revoking = row.permission
                    PermissionAction.NONE -> Unit
                }
            }
        }
    }

    revoking?.let { p ->
        RevokeDialog(
            permission = p,
            onDismiss = { revoking = null },
            onOpenSettings = {
                revoking = null
                context.openSettingsFor(p)
            },
            onRevokeNow = if (p == AppPermission.MICROPHONE && Build.VERSION.SDK_INT >= TIRAMISU) {
                {
                    revoking = null
                    onBeforeRevokeMicrophone()
                    // Android applies the revocation once the app's process ends, so end it now.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.revokeSelfPermissionOnKill(Manifest.permission.RECORD_AUDIO)
                    }
                    context.findActivity()?.finishAndRemoveTask()
                    Process.killProcess(Process.myPid())
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun PermissionRowView(row: PermissionRow, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr(row.permission.title), style = MaterialTheme.typography.bodyLarge)
            val (status, color) = when (row.status) {
                PermissionStatus.GRANTED -> tr("Allowed") to MaterialTheme.colorScheme.primary
                PermissionStatus.DENIED -> tr("Not allowed") to MaterialTheme.colorScheme.error
                PermissionStatus.NOT_NEEDED -> tr("Not needed") to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(status, style = MaterialTheme.typography.labelLarge, color = color)
            Text(tr(row.permission.purpose), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.note?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val label = when (row.action) {
            PermissionAction.REQUEST -> tr("Allow")
            PermissionAction.OPEN_SETTINGS -> if (row.status == PermissionStatus.GRANTED) tr("Manage") else tr("Open settings")
            PermissionAction.REVOKE -> tr("Revoke")
            PermissionAction.NONE -> null
        }
        label?.let { OutlinedButton(onClick = onAction) { Text(it) } }
    }
}

@Composable
private fun RevokeDialog(permission: AppPermission, onDismiss: () -> Unit, onOpenSettings: () -> Unit, onRevokeNow: (() -> Unit)?) {
    val text = when {
        permission == AppPermission.NOTIFICATIONS ->
            "Android's notification settings for RadioMe will open. Turn notifications off there. The radio keeps " +
                "working, but the ongoing notification and its Stop button won't be shown."
        onRevokeNow != null ->
            "RadioMe will turn the radio off and close, because Android applies the change when the app closes. " +
                "The next time you turn the radio on, RadioMe will ask for the microphone again."
        else ->
            "Android 12 and older don't let an app give up a permission by itself. On the settings page that opens, " +
                "tap Permissions, then ${permission.title}, then Don't allow. Android closes RadioMe when you do."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revoke ${permission.title.lowercase()} access?") },
        text = { Text(text) },
        confirmButton = {
            if (onRevokeNow != null) {
                TextButton(onClick = onRevokeNow) { Text(tr("Revoke and close")) }
            } else {
                TextButton(onClick = onOpenSettings) { Text(tr("Open settings")) }
            }
        },
        dismissButton = {
            Row {
                if (onRevokeNow != null) TextButton(onClick = onOpenSettings) { Text(tr("Open settings")) }
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
            }
        },
    )
}
