package app.tapwell

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

internal class HabitWidgetSettings(context: Context) {
    private val prefs = context.getSharedPreferences("habit_widgets", Context.MODE_PRIVATE)

    fun selected(id: Int): List<String>? = prefs.getString("habits_$id", null)?.let { text ->
        val array = JSONArray(text)
        (0 until array.length()).map { array.getString(it) }
    }

    fun cellWidth(id: Int): Float = prefs.getFloat("width_$id", 80f)
    fun cellHeight(id: Int): Float = prefs.getFloat("height_$id", 96f)

    fun save(id: Int, selected: List<String>, options: Bundle) {
        val edit = prefs.edit().putString("habits_$id", JSONArray(selected.distinct()).toString())
        if (!prefs.contains("width_$id")) {
            val columns = options.getInt("semAppWidgetColumnSpan", 1).coerceIn(1, 6)
            val rows = options.getInt("semAppWidgetRowSpan", 1).coerceIn(1, 6)
            edit.putFloat("width_$id", (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 64).coerceAtLeast(48) + 16f) / columns)
                .putFloat("height_$id", (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 80).coerceAtLeast(48) + 16f) / rows)
        }
        edit.apply()
    }

    fun delete(id: Int) {
        prefs.edit().remove("habits_$id").remove("width_$id").remove("height_$id").apply()
    }

    fun restore(oldId: Int, newId: Int) {
        if (oldId == newId) return
        val selected = selected(oldId) ?: return
        prefs.edit().putString("habits_$newId", JSONArray(selected).toString())
            .putFloat("width_$newId", cellWidth(oldId)).putFloat("height_$newId", cellHeight(oldId)).apply()
        delete(oldId)
    }
}

abstract class TapwellWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(
                AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
                AppWidgetManager.ACTION_APPWIDGET_RESTORED, Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED, TapwellWidgets.REFRESH
            )) {
            val pending = goAsync()
            TapwellWidgets.scope.launch {
                try {
                    TapwellWidgets.updateAll(context)
                } catch (error: Exception) {
                    Log.e("TapwellWidgets", "Could not update widgets", error)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val settings = HabitWidgetSettings(context)
        appWidgetIds.forEach(settings::delete)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val settings = HabitWidgetSettings(context)
        oldWidgetIds.zip(newWidgetIds).forEach { (old, new) -> settings.restore(old, new) }
    }
}

class HabitWidgetProvider : TapwellWidgetProvider()
class ActivityWidgetProvider : TapwellWidgetProvider()

internal object TapwellWidgets {
    const val REFRESH = "app.tapwell.REFRESH_WIDGETS"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updates = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private var refresh: Runnable? = null

    fun scheduleUpdate(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            refresh?.let(handler::removeCallbacks)
            refresh = Runnable {
                scope.launch {
                    try {
                        updateAll(app)
                    } catch (error: Exception) {
                        Log.e("TapwellWidgets", "Could not refresh widgets", error)
                    }
                }
            }.also { handler.postDelayed(it, 300) }
        }
    }

    suspend fun updateAll(context: Context) = updates.withLock {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val habits = manager.getAppWidgetIds(ComponentName(app, HabitWidgetProvider::class.java))
        val activity = manager.getAppWidgetIds(ComponentName(app, ActivityWidgetProvider::class.java))
        if (habits.isEmpty() && activity.isEmpty()) return@withLock
        for (id in habits) {
            val options = manager.getAppWidgetOptions(id)
            val sizes = sizes(options)
            manager.updateAppWidget(id, RemoteViews(sizes.associateWith { habitViews(app, id, it, options) }))
        }
        if (activity.isNotEmpty()) {
            val data = WidgetActivityData.read(app)
            for (id in activity) {
                val sizes = sizes(manager.getAppWidgetOptions(id))
                manager.updateAppWidget(id, RemoteViews(sizes.associateWith { activityViews(app, id, it, data) }))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sizes(options: Bundle): List<SizeF> {
        val exact = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        val valid = exact.orEmpty().filter { it.width > 0 && it.height > 0 }.distinct().take(16)
        if (valid.isNotEmpty()) return valid
        return listOf(
            SizeF(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 64).coerceAtLeast(48).toFloat(),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 80).coerceAtLeast(48).toFloat()),
            SizeF(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 64).coerceAtLeast(48).toFloat(),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80).coerceAtLeast(48).toFloat())
        ).distinct()
    }

    private fun habitViews(context: Context, id: Int, size: SizeF, options: Bundle): RemoteViews {
        val settings = HabitWidgetSettings(context)
        val store = Store.get(context)
        val selected = settings.selected(id).orEmpty().mapNotNull(store::habit)
        val columns = options.getInt("semAppWidgetColumnSpan", 0)
        val rows = options.getInt("semAppWidgetRowSpan", 0)
        val grid = if (columns in 1..6 && rows in 1..6) HabitWidgetGrid(columns, rows)
            else habitWidgetGrid(size.width, size.height, settings.cellWidth(id), settings.cellHeight(id))
        val header = grid.rows > 1 && size.width >= 120
        val pageHeight = (size.height - 12 - if (header) 32 else 0).coerceAtLeast(36f)
        val configure = PendingIntent.getActivity(context, id,
            Intent(context, HabitWidgetConfiguration::class.java)
                .setData(Uri.parse("tapwell-widget://configure/$id"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return RemoteViews(context.packageName, R.layout.widget_habits).apply {
            setViewVisibility(R.id.widget_header, if (header) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.widget_edit, configure)
            setOnClickPendingIntent(R.id.widget_empty, configure)
            setViewVisibility(R.id.widget_empty, if (selected.isEmpty()) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_pages, if (selected.isEmpty()) View.GONE else View.VISIBLE)
            if (selected.isNotEmpty()) {
                val template = PendingIntent.getActivity(context, id,
                    Intent(context, WidgetHabitActivity::class.java)
                        .setData(Uri.parse("tapwell-widget://log/$id"))
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                setPendingIntentTemplate(R.id.widget_pages, template)
                setInt(R.id.widget_pages, "setNumColumns", grid.columns)
                val collection = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                val now = System.currentTimeMillis()
                val counts = store.activityCounts(localDay(now, ZoneId.systemDefault()))
                val tileHeight = ((pageHeight - (grid.rows - 1) * 4) / grid.rows).coerceAtLeast(28f)
                for ((index, habit) in selected.withIndex()) {
                    val habitSettings = store.habitSettings(habit)
                    val logged = counts[habit.id]?.get(ActivityCount.HABIT_DONE) ?: 0
                    val text = if (habit == Habit.WATER) "${store.habitState(habit, now).loggedMl} / ${habitSettings.goalMl} mL"
                        else "$logged logged"
                    val tile = RemoteViews(context.packageName, R.layout.widget_habit_tile).apply {
                        setViewLayoutHeight(R.id.widget_habit_tile, tileHeight, TypedValue.COMPLEX_UNIT_DIP)
                        setTextViewText(R.id.widget_habit_title, habit.title)
                        setTextViewText(R.id.widget_habit_count, text)
                        setContentDescription(R.id.widget_habit_tile,
                            "${habit.title}, $text. ${if (habitSettings.needsNfc) "Scan tag to log." else "Tap to log."} Swipe up or down for more habits.")
                        setOnClickFillInIntent(R.id.widget_habit_tile, Intent().putExtra("habit", habit.id))
                    }
                    collection.addItem(index.toLong(), tile)
                }
                setRemoteAdapter(R.id.widget_pages, collection.build())
            }
        }
    }

    private fun activityViews(context: Context, id: Int, size: SizeF, data: WidgetActivityData): RemoteViews {
        val compact = size.height < 140 || size.width < 120
        val open = PendingIntent.getActivity(context, id,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("tapwell-widget://activity/$id"))
                .putExtra("open_tab", "STATS"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val refresh = PendingIntent.getBroadcast(context, id,
            Intent(context, ActivityWidgetProvider::class.java).setAction(REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return RemoteViews(context.packageName, R.layout.widget_activity).apply {
            setOnClickPendingIntent(R.id.widget_root, open)
            setOnClickPendingIntent(R.id.widget_refresh, refresh)
            setViewVisibility(R.id.widget_refresh, if (size.width < 100) View.GONE else View.VISIBLE)
            setTextViewText(R.id.widget_title, if (size.width < 120) "Today" else "Activity · Today")
            val loggedLabel = if (data.logged == 1) "habit" else "habits"
            val openedLabel = if (data.opened == 1) "app" else "apps"
            setTextViewText(R.id.widget_summary, if (size.width < 120) "${data.logged} logged"
                else "${data.logged} $loggedLabel logged · ${data.opened} $openedLabel opened\n${data.usageSummary}")
            setViewVisibility(R.id.widget_chart, if (compact) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widget_limits, if (compact) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widget_updated, if (size.height < 100) View.GONE else View.VISIBLE)
            if (!compact) {
                setImageViewBitmap(R.id.widget_chart, activityChart(context, data, size.width))
                setContentDescription(R.id.widget_chart, data.chartDescription)
                setTextViewText(R.id.widget_limits, data.limits)
            }
            setTextViewText(R.id.widget_updated, "Updated ${data.updated} · Tap to open")
        }
    }

    private fun activityChart(context: Context, data: WidgetActivityData, width: Float): Bitmap {
        val bitmap = Bitmap.createBitmap((width * 2).toInt().coerceIn(240, 720), 220, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val accent = context.getColor(R.color.widget_accent)
        val secondary = context.getColor(R.color.widget_secondary)
        val text = context.getColor(R.color.widget_text)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; color = text }
        canvas.drawText("7 days · Logged / Opened", 4f, 20f, paint)
        val peak = data.loggedDays.plus(data.openedDays).maxOrNull()?.coerceAtLeast(1) ?: 1
        val spacing = bitmap.width / 7f
        for (index in 0..6) {
            val start = index * spacing + spacing * 0.15f
            val barWidth = spacing * 0.27f
            paint.color = accent
            val loggedHeight = 140f * data.loggedDays[index] / peak
            canvas.drawRoundRect(start, 180f - loggedHeight, start + barWidth, 180f, 4f, 4f, paint)
            paint.color = secondary
            val openedHeight = 140f * data.openedDays[index] / peak
            canvas.drawRoundRect(start + barWidth + 3, 180f - openedHeight, start + 2 * barWidth + 3, 180f, 4f, 4f, paint)
            paint.color = text
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(data.dates[index].format(DateTimeFormatter.ofPattern("EE")), index * spacing + spacing / 2, 206f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        return bitmap
    }
}

private data class WidgetActivityData(
    val dates: List<LocalDate>, val loggedDays: List<Int>, val openedDays: List<Int>,
    val usageSummary: String, val limits: String, val updated: String
) {
    val logged: Int get() = loggedDays.last()
    val opened: Int get() = openedDays.last()
    val chartDescription: String get() = dates.indices.joinToString(". ") {
        "${dates[it]}: ${loggedDays[it]} habits logged, ${openedDays[it]} apps opened"
    }

    companion object {
        fun read(context: Context): WidgetActivityData {
            val store = Store.get(context)
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val today = localDay(now, zone)
            val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val counts = dates.map(store::activityCounts)
            val logged = counts.map { day -> day.values.sumOf { it[ActivityCount.HABIT_DONE] ?: 0 } }
            val opened = counts.map { day -> day.values.sumOf { it[ActivityCount.FOCUS_OPENED] ?: 0 } }
            val targets = store.locks(now)
            val usage = FocusUsage.usageHistory(context, targets.map(::focusPackage).toSet(), today, now)?.get(today)
            val summary = when {
                !FocusUsage.allowed(context) -> "Allow Usage access in Tapwell for screen time"
                usage == null -> "Screen time unavailable"
                else -> "${timeLeft(usage.values.sum())} in Focus apps"
            }
            val limits = targets.sortedBy { targetLabel(context, it) }.mapNotNull { target ->
                val cap = store.dailyLimit(target).takeIf { it > 0 } ?: return@mapNotNull null
                val used = usage?.get(focusPackage(target))
                val detail = if (used == null) "$cap min limit" else {
                    val left = cap * MINUTE - used
                    "${timeLeft(used)} used · ${if (left > 0) "${timeLeft(left)} left" else "limit reached"}"
                }
                "${targetLabel(context, target)} · $detail"
            }.take(3).joinToString("\n")
            return WidgetActivityData(dates, logged, opened, summary, limits,
                java.time.Instant.ofEpochMilli(now).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")))
        }
    }
}
