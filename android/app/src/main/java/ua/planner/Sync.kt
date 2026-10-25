package ua.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class Chg(val et: Long, val eb: String, val ev: String, val title: String, val cal: String)

object Sync {
    private fun call(url: String, method: String, body: String?, token: String?, ctype: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Referer", BuildConfig.SITE_URL) // ключ Firebase обмежено за сайтом
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", ctype)
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val ok = c.responseCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        if (!ok) throw RuntimeException("HTTP " + c.responseCode)
        return text
    }

    /** Свіжий id-токен і базовий URL Firestore (для дій з екрана будильника). */
    fun session(ctx: Context): Pair<String, String>? {
        val sp = ctx.getSharedPreferences("p", 0)
        val a = JSONObject(sp.getString("auth", null) ?: return null)
        val tok = JSONObject(
            call(
                "https://securetoken.googleapis.com/v1/token?key=" + a.getString("k"), "POST",
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(a.getString("r"), "UTF-8"),
                null, "application/x-www-form-urlencoded"
            )
        )
        val nr = tok.optString("refresh_token")
        if (nr.isNotEmpty() && nr != a.getString("r")) {
            a.put("r", nr)
            sp.edit().putString("auth", a.toString()).apply()
        }
        return Pair(tok.getString("id_token"), "https://firestore.googleapis.com/v1/projects/" + a.getString("p") + "/databases/(default)/documents")
    }

    /** PATCH через POST + X-HTTP-Method-Override (надійно працює на всіх версіях Android). */
    fun patch(url: String, body: String, token: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Referer", BuildConfig.SITE_URL)
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode !in 200..299) throw RuntimeException("HTTP " + c.responseCode)
        c.inputStream.close()
    }

    private fun str(f: JSONObject, k: String): String = f.optJSONObject(k)?.optString("stringValue", "") ?: ""

    private fun lng(f: JSONObject, k: String): Long {
        val o = f.optJSONObject(k) ?: return 0L
        return o.optString("integerValue", "").toLongOrNull() ?: o.optDouble("doubleValue", 0.0).toLong()
    }

    private fun pres(f: JSONObject): List<Long> {
        val a = f.optJSONObject("pre")?.optJSONObject("arrayValue")?.optJSONArray("values") ?: return emptyList()
        val r = ArrayList<Long>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val v = o.optString("integerValue", "").toLongOrNull() ?: o.optDouble("doubleValue", 0.0).toLong()
            if (v > 0) r.add(v)
        }
        return r
    }

    private fun label(m: Long): String = when {
        m % 1440 == 0L -> "${m / 1440} дн"
        m % 60 == 0L -> "${m / 60} год"
        else -> "$m хв"
    }

    private const val FULL_EVERY = 6 * 60 * 60 * 1000L // повне перечитування раз на 6 год, між ними лише зміни
    private const val OVERLAP = 120_000L // запас на різницю годинників пристроїв

    private class Src(val key: String, val parent: String, val name: String, val viewer: Boolean)

    /** Стискає документ Firestore у короткий запис для локального кешу. */
    private fun compact(doc: JSONObject): JSONObject? {
        val f = doc.optJSONObject("fields") ?: return null
        val o = JSONObject()
        o.put("i", doc.optString("name").substringAfter("/documents/"))
        o.put("d", str(f, "date")).put("t", str(f, "time")).put("ti", str(f, "title"))
        o.put("pr", str(f, "prio").ifEmpty { "normal" })
        o.put("dn", f.optJSONObject("done")?.optBoolean("booleanValue", false) == true)
        o.put("asg", str(f, "asg")).put("rep", if (str(f, "rep").isNotEmpty()) 1 else 0)
        o.put("ot", str(f, "ot")).put("od", str(f, "od")).put("ak", lng(f, "ak"))
        val pa = JSONArray()
        for (m in pres(f)) pa.put(m)
        o.put("pre", pa)
        o.put("eb", str(f, "eb")).put("et", lng(f, "et")).put("ev", str(f, "ev")).put("ut", lng(f, "ut"))
        o.put("del", f.has("del")).put("tpl", f.has("tpl"))
        return o
    }

    private fun cacheFile(ctx: Context) = java.io.File(ctx.filesDir, "sync_cache.json")

    private fun loadCache(ctx: Context, uid: String): JSONObject {
        try {
            val f = cacheFile(ctx)
            if (f.exists()) {
                val j = JSONObject(f.readText())
                if (j.optString("uid") == uid) return j
            }
        } catch (e: Exception) { }
        return JSONObject().put("uid", uid).put("full", 0L).put("last", JSONObject()).put("docs", JSONObject())
    }

    fun clearCache(ctx: Context) {
        try { cacheFile(ctx).delete() } catch (e: Exception) { }
    }

    private fun sinceQuery(since: Long) =
        "{\"structuredQuery\":{\"from\":[{\"collectionId\":\"tasks\"}],\"where\":{\"fieldFilter\":" +
            "{\"field\":{\"fieldPath\":\"ut\"},\"op\":\"GREATER_THAN\",\"value\":{\"integerValue\":\"$since\"}}}}}"

    /**
     * Читає задачі й ставить будильники. Щоб не вичерпати безкоштовні ліміти Firestore, повністю все
     * перечитується раз на 6 год, а між цим запитуються лише задачі, змінені після останньої синхронізації (поле ut).
     */
    fun run(ctx: Context) {
        val sp = ctx.getSharedPreferences("p", 0)
        if (!sp.contains("auth")) return
        try { QuickOps.flush(ctx) } catch (e: Exception) { }
        val a = JSONObject(sp.getString("auth", null) ?: return)
        val uid = a.getString("u")
        val myEmail = a.optString("e")
        val (id, base) = session(ctx) ?: return
        val now = System.currentTimeMillis()
        val cache = loadCache(ctx, uid)
        val docs = cache.getJSONObject("docs")
        val last = cache.getJSONObject("last")
        val full = cache.optLong("full", 0L) == 0L || now - cache.optLong("full", 0L) > FULL_EVERY

        // календарі, у яких я є (їх мало, тому читаємо щоразу)
        val q = "{\"structuredQuery\":{\"from\":[{\"collectionId\":\"calendars\"}],\"where\":{\"fieldFilter\":" +
            "{\"field\":{\"fieldPath\":\"members\"},\"op\":\"ARRAY_CONTAINS\",\"value\":{\"stringValue\":\"$uid\"}}}}}"
        val cals = JSONArray(call("$base:runQuery", "POST", q, id, "application/json"))
        val srcs = ArrayList<Src>()
        srcs.add(Src("u", "$base/users/$uid", "", false))
        for (i in 0 until cals.length()) {
            val cd = cals.getJSONObject(i).optJSONObject("document") ?: continue
            val n = cd.optString("name")
            if (n.isEmpty()) continue
            val cf = cd.optJSONObject("fields")
            val role = cf?.optJSONObject("roles")?.optJSONObject("mapValue")?.optJSONObject("fields")
                ?.optJSONObject(uid)?.optString("stringValue", "") ?: ""
            val owner = cf?.let { str(it, "owner") } ?: ""
            srcs.add(Src(n.substringAfterLast("/"), "https://firestore.googleapis.com/v1/$n", (cf?.let { str(it, "name") } ?: "").ifEmpty { "Календар" }, owner != uid && role == "v"))
        }

        // прибираємо з кешу календарі, яких більше немає
        val keys = srcs.map { it.key }.toSet()
        val del = ArrayList<String>()
        for (k in docs.keys()) if (!keys.contains(docs.getJSONObject(k).optString("s"))) del.add(k)
        for (k in del) docs.remove(k)

        val fresh = ArrayList<Pair<JSONObject, Src>>()
        for (s in srcs) {
            val list = ArrayList<JSONObject>()
            if (full) {
                var tok: String? = null
                do {
                    val url = s.parent + "/tasks?pageSize=300" + (tok?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
                    val r = JSONObject(call(url, "GET", null, id, ""))
                    val arr = r.optJSONArray("documents")
                    if (arr != null) for (i in 0 until arr.length()) compact(arr.getJSONObject(i))?.let { list.add(it) }
                    tok = r.optString("nextPageToken").ifEmpty { null }
                } while (tok != null)
                val gone = ArrayList<String>()
                for (k in docs.keys()) if (docs.getJSONObject(k).optString("s") == s.key) gone.add(k)
                for (k in gone) docs.remove(k)
            } else {
                val since = last.optLong(s.key, 0L) - OVERLAP
                val res = JSONArray(call(s.parent + ":runQuery", "POST", sinceQuery(since), id, "application/json"))
                for (i in 0 until res.length()) {
                    val d = res.getJSONObject(i).optJSONObject("document") ?: continue
                    compact(d)?.let { list.add(it) }
                }
            }
            var mx = last.optLong(s.key, 0L)
            for (c in list) {
                c.put("s", s.key)
                docs.put(c.getString("i"), c)
                fresh.add(Pair(c, s))
                mx = maxOf(mx, c.optLong("ut", 0L))
            }
            last.put(s.key, mx)
        }
        if (full) cache.put("full", now)
        try { cacheFile(ctx).writeText(cache.toString()) } catch (e: Exception) { }

        // зміни інших учасників (push)
        val cn = sp.getLong("cn", -1L)
        val changes = ArrayList<Chg>()
        for ((c, s) in fresh) {
            if (s.name.isEmpty() || c.optBoolean("tpl")) continue
            val eb = c.optString("eb")
            val et = c.optLong("et", 0L)
            if (eb.isNotEmpty() && myEmail.isNotEmpty() && eb != myEmail && et > cn && c.optString("ev") != "rep")
                changes.add(Chg(et, eb, c.optString("ev"), c.optString("ti"), s.name))
        }
        if (cn < 0) {
            sp.edit().putLong("cn", now).apply() // перший запуск: старі зміни не повідомляємо
        } else if (changes.isNotEmpty()) {
            if (!Quiet.isQuiet(ctx, now)) Notify.changes(ctx, changes.sortedBy { it.et })
            sp.edit().putLong("cn", maxOf(cn, changes.maxOf { it.et })).apply()
        }

        // будильники й дані для віджетів
        val info = HashMap<String, Src>()
        for (s in srcs) info[s.key] = s
        val defT = sp.getString("defT", "") ?: ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val todayS = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val wFrom = java.time.LocalDate.now().minusDays(60).toString()
        val wTo = java.time.LocalDate.now().plusDays(120).toString()
        val wl = JSONArray()
        val found = ArrayList<Triple<Long, String, Int>>()
        val extra = HashMap<String, JSONObject>()
        for (k in docs.keys()) {
            val c = docs.getJSONObject(k)
            if (c.optBoolean("tpl") || c.optBoolean("del")) continue
            val s = info[c.optString("s")] ?: continue
            val d = c.optString("d")
            val asg = c.optString("asg")
            if (d >= wFrom && d <= wTo && (asg.isEmpty() || asg == uid) && wl.length() < 700)
                wl.put(JSONObject().put("d", d).put("t", c.optString("t")).put("ti", c.optString("ti")).put("pr", c.optString("pr")).put("dn", c.optBoolean("dn")).put("c", s.name))
            if (s.viewer || c.optBoolean("dn")) continue
            if (asg.isNotEmpty() && asg != uid) continue
            val t = c.optString("t").ifEmpty { if (d >= todayS) defT else "" }
            if (d.isEmpty() || t.isEmpty()) continue
            val w = try { fmt.parse("$d $t")!!.time } catch (e: Exception) { continue }
            val title = (if (s.name.isNotEmpty()) "\uD83C\uDFE2 " else "") + c.optString("ti")
            if (w > now) {
                found.add(Triple(w, title, 0))
                extra[w.toString() + title] = JSONObject().put("i", c.getString("i")).put("r", c.optInt("rep"))
                    .put("pr", c.optString("pr")).put("ot", c.optString("ot").ifEmpty { t }).put("od", c.optString("od").ifEmpty { d })
            }
            val ak = c.optLong("ak", 0L)
            val pa = c.optJSONArray("pre")
            if (pa != null) for (n in 0 until pa.length()) {
                val m = pa.optLong(n)
                val e = w - m * 60_000L
                if (m > 0 && e > now && e > ak) found.add(Triple(e, "Через ${label(m)}: $title", 1))
            }
        }
        try { WData.save(ctx, wl, null) } catch (e: Exception) { }
        val out = JSONArray()
        for (p in found.sortedBy { it.first }.take(60)) {
            val o = JSONObject().put("t", p.second).put("w", p.first)
            if (p.third == 1) o.put("p", 1)
            else extra[p.first.toString() + p.second]?.let { x -> for (kk in x.keys()) o.put(kk, x.get(kk)) }
            out.put(o)
        }
        Alarms.schedule(ctx, out.toString())
    }

    const val EVERY_MS = 5 * 60 * 1000L

    private fun tickPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 5000, Intent(ctx, SyncReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Наступна синхронізація через 5 хв (працює і в режимі економії; у Doze Android може розтягнути до ~9 хв). */
    fun next(ctx: Context) {
        if (!ctx.getSharedPreferences("p", 0).contains("auth")) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + EVERY_MS, tickPi(ctx))
    }

    fun start(ctx: Context) {
        ensureJob(ctx)
        next(ctx)
    }

    fun ensureJob(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(1) != null) return
        js.schedule(
            JobInfo.Builder(1, ComponentName(ctx, SyncJob::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
        )
    }

    fun cancelJob(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java).cancel(1)
        ctx.getSystemService(AlarmManager::class.java).cancel(tickPi(ctx))
    }
}

class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { Sync.run(applicationContext) } catch (e: Exception) { }
            try { Sync.next(applicationContext) } catch (e: Exception) { }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try { Sync.next(c) } catch (e: Exception) { } // спершу плануємо наступний запуск, щоб ланцюжок не обірвався
        val pr = goAsync()
        Thread {
            try { Sync.run(c.applicationContext) } catch (e: Exception) { }
            pr.finish()
        }.start()
    }
}
