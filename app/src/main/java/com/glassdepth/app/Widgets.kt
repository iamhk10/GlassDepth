package com.glassdepth.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.RemoteViews

/**
 * Base for all glass widgets. It draws the frosted glass bitmap for the widget's exact size
 * and lets each widget add its own text/controls on top.
 */
abstract class GlassWidgetProvider : AppWidgetProvider() {

    abstract fun buildViews(ctx: Context, id: Int, size: WidgetSize, card: Bitmap): RemoteViews

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) update(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        update(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) Prefs.clearWidget(context, id)
    }

    protected fun update(context: Context, mgr: AppWidgetManager, id: Int) {
        val size = GlassRenderer.sizeFor(context, id)
        val card = GlassRenderer.glassCard(context, size, Prefs.widgetStyle(context, id))
        mgr.updateAppWidget(id, buildViews(context, id, size, card))
    }

    protected fun open(ctx: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

/** Time is drawn by TextClock, so the system ticks it. The widget itself never wakes the phone. */
class ClockWidget : GlassWidgetProvider() {
    override fun buildViews(ctx: Context, id: Int, size: WidgetSize, card: Bitmap): RemoteViews {
        val large = size.wDp >= 180 && size.hDp >= 90
        val rv = RemoteViews(
            ctx.packageName,
            if (large) R.layout.widget_clock_large else R.layout.widget_clock_small
        )
        rv.setImageViewBitmap(R.id.glass_bg, card)
        rv.setOnClickPendingIntent(R.id.root, open(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        return rv
    }
}

class DateWidget : GlassWidgetProvider() {
    override fun buildViews(ctx: Context, id: Int, size: WidgetSize, card: Bitmap): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_date)
        rv.setImageViewBitmap(R.id.glass_bg, card)
        rv.setOnClickPendingIntent(
            R.id.root,
            open(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
        )
        return rv
    }
}

class BatteryWidget : GlassWidgetProvider() {

    override fun buildViews(ctx: Context, id: Int, size: WidgetSize, card: Bitmap): RemoteViews {
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val rv = RemoteViews(ctx.packageName, R.layout.widget_battery)
        rv.setImageViewBitmap(R.id.glass_bg, card)
        rv.setTextViewText(R.id.pct, "$pct%")
        rv.setTextViewText(
            R.id.status,
            ctx.getString(if (charging) R.string.charging else R.string.on_battery)
        )
        rv.setProgressBar(R.id.bar, 100, pct, false)
        rv.setOnClickPendingIntent(R.id.root, open(ctx, Intent(Intent.ACTION_POWER_USAGE_SUMMARY)))
        return rv
    }

    // These four broadcasts are allowed in the manifest, so battery changes cost almost nothing.
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_BATTERY_OKAY -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, BatteryWidget::class.java))
                for (id in ids) update(context, mgr, id)
            }
        }
    }
}

/** Re-draws every glass widget, e.g. after a new wallpaper photo is saved. */
object WidgetUpdater {
    private val providers = listOf(
        ClockWidget::class.java,
        DateWidget::class.java,
        BatteryWidget::class.java
    )

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (cls in providers) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isEmpty()) continue
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setClass(ctx, cls)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            ctx.sendBroadcast(intent)
        }
    }
}
