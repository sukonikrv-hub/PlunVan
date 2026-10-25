package ua.planner

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

class WTask(val d: String, val t: String, val ti: String, val pr: String, val dn: Boolean, val c: String)

/** Дані та вигляд віджетів: задачі лежать у SharedPreferences ("wt"), їх оновлюють сайт і фонова синхронізація. */
object WData {
    private fun sp(c: Context) = c.getSharedPreferences("p", 0)

    fun tasks(c: Context): List<WTask> {
        val a = try { JSONArray(sp(c).getString("wt", "[]")) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<WTask>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(WTask(o.optString("d"), o.optString("t"), o.optString("ti"), o.optString("pr", "normal"), o.optBoolean("dn"), o.optString("c")))
        }
        return out
    }

    private fun meta(c: Context): JSONObject =
        try { JSONObject(sp(c).getString("wmeta", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun dark(c: Context) = meta(c).optString("th") == "dark"
    private fun h12(c: Context) = meta(c).optBoolean("h12")

    fun color(c: Context, k: String): Int {
        val def = mapOf("low" to "#ffe45c", "normal" to "#ffa94d", "high" to "#ff5a5a", "ok" to "#7fdc5a")
        val s = meta(c).optJSONObject("c")?.optString(k) ?: ""
        return try { Color.parseColor(if (Regex("^#[0-9a-fA-F]{6}$").matches(s)) s else def[k]!!) } catch (e: Exception) { Color.parseColor(def[k]!!) }
    }

    fun soft(col: Int): Int {
        fun m(x: Int) = x + (255 - x) * 55 / 100
        return Color.rgb(m(Color.red(col)), m(Color.green(col)), m(Color.blue(col)))
    }

    fun luminant(col: Int) = (0.299 * Color.red(col) + 0.587 * Color.green(col) + 0.114 * Color.blue(col)) / 255
    fun text(c: Context) = if (dark(c)) Color.WHITE else Color.parseColor("#1E2C39")
    fun dim(c: Context) = Color.parseColor(if (dark(c)) "#9AA5B1" else "#6B7785")
    fun empty(c: Context) = Color.parseColor(if (dark(c)) "#3A424B" else "#F0EEEB")
    fun bg(c: Context) = if (dark(c)) R.drawable.widget_bg_dark else R.drawable.widget_bg

    fun time(c: Context, t: String): String {
        if (t.isEmpty() || !h12(c)) return t
        val h = t.substringBefore(":").toIntOrNull() ?: return t
        return "${if (h % 12 == 0) 12 else h % 12}:${t.substringAfter(":")} ${if (h < 12) "AM" else "PM"}"
    }

    fun dayName(d: LocalDate) = arrayOf("пн", "вт", "ср", "чт", "пт", "сб", "нд")[d.dayOfWeek.value - 1]
    fun two(n: Int) = n.toString().padStart(2, '0')
    fun dm(d: LocalDate) = two(d.dayOfMonth) + "." + two(d.monthValue)

    fun save(c: Context, tasks: JSONArray, meta: JSONObject?) {
        val e = sp(c).edit().putString("wt", tasks.toString())
        if (meta != null) e.putString("wmeta", meta.toString())
        e.apply()
        refreshAll(c)
    }

    fun clear(c: Context) {
        sp(c).edit().remove("wt").apply()
        refreshAll(c)
    }

    fun refreshAll(c: Context) {
        try { MonthWidget.update(c) } catch (e: Exception) { }
        try { ListWidget.updateAll(c, TodayWidget::class.java, "today") } catch (e: Exception) { }
        try { ListWidget.updateAll(c, WeekWidget::class.java, "week") } catch (e: Exception) { }
    }

    fun openApp(c: Context, mutable: Boolean = false, code: Int = 70): PendingIntent {
        val fl = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else if (!mutable) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(
            c, code, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), fl
        )
    }

    private fun rank(p: String) = when (p) { "high" -> 3; "normal" -> 2; else -> 1 }
    fun best(list: List<WTask>): String = list.maxByOrNull { rank(it.pr) }?.pr ?: "normal"
}

/** Місяць із кольорами за пріоритетом, як у програмі. ◀ ▶ гортають місяці, натиск на назву повертає до поточного. */
class MonthWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { update(ctx) }

    override fun onReceive(ctx: Context, i: Intent) {
        val sp = ctx.getSharedPreferences("p", 0)
        when (i.action) {
            PREV -> sp.edit().putInt("wmo", sp.getInt("wmo", 0) - 1).apply()
            NEXT -> sp.edit().putInt("wmo", sp.getInt("wmo", 0) + 1).apply()
            NOW -> sp.edit().putInt("wmo", 0).apply()
        }
        super.onReceive(ctx, i)
        if (i.action == PREV || i.action == NEXT || i.action == NOW) update(ctx)
    }

    companion object {
        const val PREV = "ua.planner.WM_PREV"
        const val NEXT = "ua.planner.WM_NEXT"
        const val NOW = "ua.planner.WM_NOW"

        private fun act(c: Context, a: String, code: Int) = PendingIntent.getBroadcast(
            c, code, Intent(c, MonthWidget::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun update(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, MonthWidget::class.java))
            if (ids.isEmpty()) return
            val rv = build(c)
            for (id in ids) m.updateAppWidget(id, rv)
        }

        private fun build(c: Context): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.widget_month)
            val off = c.getSharedPreferences("p", 0).getInt("wmo", 0)
            val first = LocalDate.now().withDayOfMonth(1).plusMonths(off.toLong())
            val today = LocalDate.now().toString()
            rv.setInt(R.id.root, "setBackgroundResource", WData.bg(c))
            val monthName = first.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Locale("uk"))
            rv.setTextViewText(R.id.mTitle, monthName.replaceFirstChar { it.uppercase() } + " " + first.year)
            val tx = WData.text(c)
            val dim = WData.dim(c)
            for (id in intArrayOf(R.id.mTitle, R.id.mPrev, R.id.mNext)) rv.setTextColor(id, tx)
            rv.setOnClickPendingIntent(R.id.mPrev, act(c, PREV, 1))
            rv.setOnClickPendingIntent(R.id.mNext, act(c, NEXT, 2))
            rv.setOnClickPendingIntent(R.id.mTitle, act(c, NOW, 3))
            rv.setOnClickPendingIntent(R.id.grid, WData.openApp(c))
            val names = arrayOf("пн", "вт", "ср", "чт", "пт", "сб", "нд")
            for (i in 0 until 7) {
                val id = c.resources.getIdentifier("w$i", "id", c.packageName)
                rv.setTextViewText(id, names[i]); rv.setTextColor(id, dim)
            }
            val byDay = WData.tasks(c).groupBy { it.d }
            val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
            val dark = Color.parseColor("#1E2C39")
            for (i in 0 until 42) {
                val d = start.plusDays(i.toLong())
                val id = c.resources.getIdentifier("c$i", "id", c.packageName)
                val ds = d.toString()
                val inM = d.month == first.month
                val list = byDay[ds].orEmpty()
                val sp = SpannableString(d.dayOfMonth.toString())
                var bgc = 0
                var fg = dim
                if (inM) {
                    if (list.isEmpty()) { bgc = WData.empty(c); fg = tx }
                    else {
                        val open = list.filter { !it.dn }
                        bgc = if (open.isEmpty()) WData.color(c, "ok") else WData.color(c, WData.best(open))
                        fg = if (WData.luminant(bgc) > 0.55) dark else Color.WHITE
                    }
                }
                if (ds == today) {
                    sp.setSpan(StyleSpan(Typeface.BOLD), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sp.setSpan(UnderlineSpan(), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                rv.setTextViewText(id, sp)
                rv.setTextColor(id, fg)
                rv.setInt(id, "setBackgroundColor", bgc)
            }
            return rv
        }
    }
}

open class ListWidget(private val mode: String) : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(ctx, this::class.java, mode)
    }

    companion object {
        fun updateAll(c: Context, cls: Class<*>, mode: String) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, cls))
            for (id in ids) {
                val rv = RemoteViews(c.packageName, R.layout.widget_list)
                val now = LocalDate.now()
                rv.setInt(R.id.root, "setBackgroundResource", WData.bg(c))
                rv.setTextViewText(
                    R.id.lTitle,
                    if (mode == "today") "Сьогодні · " + WData.dayName(now) + " " + WData.dm(now)
                    else "Тиждень · " + WData.dm(now) + " – " + WData.dm(now.plusDays(6))
                )
                rv.setTextColor(R.id.lTitle, WData.text(c))
                rv.setTextColor(R.id.lAdd, WData.text(c))
                rv.setTextColor(R.id.lEmpty, WData.dim(c))
                val si = Intent(c, ListService::class.java).putExtra("mode", mode).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                si.data = Uri.parse(si.toUri(Intent.URI_INTENT_SCHEME))
                rv.setRemoteAdapter(R.id.lList, si)
                rv.setEmptyView(R.id.lList, R.id.lEmpty)
                rv.setPendingIntentTemplate(R.id.lList, WData.openApp(c, true, 71))
                rv.setOnClickPendingIntent(R.id.lTitle, WData.openApp(c, false, 72))
                rv.setOnClickPendingIntent(R.id.lAdd, WData.openApp(c, false, 73))
                m.updateAppWidget(id, rv)
                m.notifyAppWidgetViewDataChanged(id, R.id.lList)
            }
        }
    }
}

class TodayWidget : ListWidget("today")
class WeekWidget : ListWidget("week")

class ListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ListFactory(applicationContext, intent.getStringExtra("mode") ?: "today")
}

class ListFactory(private val c: Context, private val mode: String) : RemoteViewsService.RemoteViewsFactory {
    private class Row(val hdr: String?, val t: WTask?)

    private var rows: List<Row> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun onDataSetChanged() { rows = build() }
    override fun getCount() = rows.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 2
    override fun getItemId(p: Int) = p.toLong()
    override fun hasStableIds() = false

    private fun order(l: List<WTask>) = l.sortedWith(
        compareBy<WTask> { it.dn }.thenBy { it.t.isEmpty() }.thenBy { it.t }
    )

    private fun build(): List<Row> {
        val all = WData.tasks(c).groupBy { it.d }
        val now = LocalDate.now()
        val out = ArrayList<Row>()
        if (mode == "today") {
            for (t in order(all[now.toString()].orEmpty())) out.add(Row(null, t))
        } else {
            for (i in 0 until 7) {
                val d = now.plusDays(i.toLong())
                val l = order(all[d.toString()].orEmpty())
                if (l.isEmpty()) continue
                val name = when (i) { 0 -> "Сьогодні"; 1 -> "Завтра"; else -> WData.dayName(d) }
                out.add(Row(name + " · " + WData.dm(d), null))
                for (t in l) out.add(Row(null, t))
            }
        }
        return out
    }

    override fun getViewAt(pos: Int): RemoteViews {
        val r = rows.getOrNull(pos) ?: return RemoteViews(c.packageName, R.layout.widget_hdr)
        if (r.hdr != null) {
            val h = RemoteViews(c.packageName, R.layout.widget_hdr)
            h.setTextViewText(R.id.hd, r.hdr)
            h.setTextColor(R.id.hd, WData.dim(c))
            return h
        }
        val t = r.t!!
        val v = RemoteViews(c.packageName, R.layout.widget_item)
        val strong = if (t.dn) WData.color(c, "ok") else WData.color(c, t.pr)
        v.setInt(R.id.row, "setBackgroundColor", WData.soft(strong))
        val ink = Color.parseColor("#1E2C39")
        val title = SpannableString(t.ti)
        if (t.dn) title.setSpan(StrikethroughSpan(), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.setTextViewText(R.id.iTitle, title)
        v.setTextColor(R.id.iTitle, if (t.dn) Color.parseColor("#5B6770") else ink)
        v.setTextViewText(R.id.iTime, if (t.t.isEmpty()) "•" else WData.time(c, t.t))
        v.setTextColor(R.id.iTime, ink)
        if (t.c.isEmpty()) v.setViewVisibility(R.id.iCal, android.view.View.GONE)
        else {
            v.setViewVisibility(R.id.iCal, android.view.View.VISIBLE)
            v.setTextViewText(R.id.iCal, "🏢 " + t.c)
            v.setTextColor(R.id.iCal, Color.parseColor("#5B6770"))
        }
        v.setOnClickFillInIntent(R.id.row, Intent())
        return v
    }
}
