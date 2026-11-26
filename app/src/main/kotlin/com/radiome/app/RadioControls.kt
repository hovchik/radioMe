package com.radiome.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.radiome.core.audio.TransceiverService

/**
 * Home-screen widget and quick-settings tile: show whether the radio is on and turn it on or off.
 *
 * Turning it on always opens the app first: Android only lets a microphone service start while the app is visible,
 * so the widget and tile open the activity with [EXTRA_START], which then starts listening. Turning it off goes
 * straight to the service.
 */
object RadioControls {
    const val EXTRA_START = "com.radiome.app.START_LISTENING"

    @Volatile var running: Boolean = false
        private set

    /** Called when the link starts or stops; refreshes the widget and the tile. */
    fun onRunningChanged(context: Context, on: Boolean) {
        running = on
        RadioWidget.updateAll(context)
        TileService.requestListeningState(context, ComponentName(context, RadioTile::class.java))
    }

    fun startIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 10,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_START, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
        context, 11,
        Intent(context, TransceiverService::class.java).setAction(TransceiverService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class RadioWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, RadioWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val on = RadioControls.running
            return RemoteViews(context.packageName, R.layout.widget_radio).apply {
                setTextViewText(R.id.widget_status, context.getString(if (on) R.string.widget_on else R.string.widget_off))
                setTextViewText(R.id.widget_button, context.getString(if (on) R.string.action_stop else R.string.action_listen))
                setOnClickPendingIntent(R.id.widget_button, if (on) RadioControls.stopIntent(context) else RadioControls.startIntent(context))
                val open = PendingIntent.getActivity(
                    context, 12, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                setOnClickPendingIntent(R.id.widget_status, open)
            }
        }
    }
}

class RadioTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val on = RadioControls.running
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(if (on) R.string.widget_on else R.string.widget_off)
        tile.icon = Icon.createWithResource(this, android.R.drawable.stat_sys_headset)
        tile.updateTile()
    }

    override fun onClick() {
        if (RadioControls.running) {
            TransceiverService.stop(this)
            return
        }
        val pi = RadioControls.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(Intent(this, MainActivity::class.java).putExtra(RadioControls.EXTRA_START, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
