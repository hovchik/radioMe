package com.radiome.feature.transceiver.settings

/** A permission RadioMe uses, as listed on the Settings screen. */
enum class AppPermission(val title: String, val purpose: String) {
    MICROPHONE("Microphone", "Hears the radio's audio so RadioMe can decode incoming bursts."),
    NOTIFICATIONS("Notifications", "Shows the ongoing \"Listening\" notification with its Stop button."),
    PHOTOS("Photos", "Lets you choose an image to send."),
}

enum class PermissionStatus { GRANTED, DENIED, NOT_NEEDED }

/** What the row's button does. */
enum class PermissionAction {
    /** Show the system permission prompt. */
    REQUEST,

    /** The prompt is no longer offered (denied twice, or not a runtime permission here): open the system settings page. */
    OPEN_SETTINGS,

    /** Ask the user to confirm, then revoke. */
    REVOKE,

    NONE,
}

data class PermissionRow(
    val permission: AppPermission,
    val status: PermissionStatus,
    val action: PermissionAction,
    val note: String?,
)

/**
 * Builds the permission rows from what the system reports.
 *
 * @param sdk Build.VERSION.SDK_INT.
 * @param micGranted whether RECORD_AUDIO is granted.
 * @param notificationsEnabled NotificationManagerCompat.areNotificationsEnabled(), which covers both the
 *   POST_NOTIFICATIONS grant (Android 13+) and the app notification switch (all versions).
 * @param askedBefore permissions whose prompt RadioMe has already shown.
 * @param showRationale permissions for which the system would still show its prompt after a denial.
 */
fun permissionRows(
    sdk: Int,
    micGranted: Boolean,
    notificationsEnabled: Boolean,
    askedBefore: Set<AppPermission>,
    showRationale: Set<AppPermission>,
): List<PermissionRow> {
    fun runtimeRow(p: AppPermission, granted: Boolean, grantNote: String?): PermissionRow = when {
        granted -> PermissionRow(p, PermissionStatus.GRANTED, PermissionAction.REVOKE, null)
        // Never asked, or the system will still show its prompt: ask in-app.
        p !in askedBefore || p in showRationale -> PermissionRow(p, PermissionStatus.DENIED, PermissionAction.REQUEST, grantNote)
        // Denied for good: Android won't show the prompt again, only the settings page can grant it.
        else -> PermissionRow(
            p, PermissionStatus.DENIED, PermissionAction.OPEN_SETTINGS,
            "Android won't ask again. Allow it on the app's system settings page.",
        )
    }

    val mic = runtimeRow(AppPermission.MICROPHONE, micGranted, "Needed to turn the radio on.")
    val notifications = if (sdk >= TIRAMISU) {
        runtimeRow(AppPermission.NOTIFICATIONS, notificationsEnabled, "Optional. Without it the radio still works.")
    } else {
        // Before Android 13 notifications are not a runtime permission, only a switch on the settings page.
        PermissionRow(
            AppPermission.NOTIFICATIONS,
            if (notificationsEnabled) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            PermissionAction.OPEN_SETTINGS,
            null,
        )
    }
    val photos = PermissionRow(
        AppPermission.PHOTOS, PermissionStatus.NOT_NEEDED, PermissionAction.NONE,
        "No access needed: you pick each image in Android's photo picker, and RadioMe sees only that image.",
    )
    return listOf(mic, notifications, photos)
}

/** Android 13, the first release with POST_NOTIFICATIONS and Context.revokeSelfPermissionOnKill. */
const val TIRAMISU = 33
