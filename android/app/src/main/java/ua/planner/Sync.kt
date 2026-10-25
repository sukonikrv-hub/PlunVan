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

    /** Читає задачі (особисті й корпоративні) з Firestore і ставить будильники на телефоні. */
    fun run(ctx: Context) {
        val sp = ctx.getSharedPreferences("p", 0)
        if (!sp.contains("auth")) return
        try { QuickOps.flush(ctx) } catch (e: Exception) { }
        val a = JSONObject(sp.getString("auth", null) ?: return)
        val key = a.getString("k")
        val proj = a.getString("p")
        val uid = a.getString("u")
        val refresh = a.getString("r")
        val tok = JSONObject(
            call(
                "https://securetoken.googleapis.com/v1/token?key=$key", "POST",
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"),
                null, "application/x-www-form-urlencoded"
            )
        )
        val id = tok.getString("id_token")
        val newRefresh = tok.optString("refresh_token")
        if (newRefresh.isNotEmpty() && newRefresh != refresh) {
            a.put("r", newRefresh)
            sp.edit().putString("auth", a.toString()).apply()
        }
        val base = "https://firestore.googleapis.com/v1/projects/$proj/databases/(default)/documents"
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val found = ArrayList<Triple<Long, String, Int>>()
        val extra = HashMap<String, JSONObject>()
        val myEmail = a.optString("e")
        val defT = sp.getString("defT", "") ?: ""
        val todayS = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val cn = sp.getLong("cn", -1L)
        val changes = ArrayList<Chg>()
        val wl = JSONArray()
        val wFrom = java.time.LocalDate.now().minusDays(60).toString()
        val wTo = java.time.LocalDate.now().plusDays(120).toString()

        fun collect(url: String, prefix: String, viewer: Boolean = false, calName: String = "") {
            val docs = JSONObject(call(url, "GET", null, id, "")).optJSONArray("documents") ?: return
            for (i in 0 until docs.length()) {
                val f = docs.getJSONObject(i).optJSONObject("fields") ?: continue
                val eb = str(f, "eb")
                val et = lng(f, "et")
                val ev = str(f, "ev")
                if (calName.isNotEmpty() && eb.isNotEmpty() && myEmail.isNotEmpty() && eb != myEmail && et > cn && ev != "rep")
                    changes.add(Chg(et, eb, ev, str(f, "title"), calName))
                if (f.has("del")) continue // у кошику
                // дані для віджетів (усі задачі навколо сьогодні, включно з виконаними)
                val wd = str(f, "date")
                val wa = str(f, "asg")
                if (wd >= wFrom && wd <= wTo && (wa.isEmpty() || wa == uid) && wl.length() < 700)
                    wl.put(
                        JSONObject().put("d", wd).put("t", str(f, "time")).put("ti", str(f, "title"))
                            .put("pr", str(f, "prio").ifEmpty { "normal" })
                            .put("dn", f.optJSONObject("done")?.optBoolean("booleanValue", false) == true)
                            .put("c", calName)
                    )
                if (viewer) continue // роль «лише перегляд»: без нагадувань
                if (f.optJSONObject("done")?.optBoolean("booleanValue", false) == true) continue
                val d = str(f, "date")
                val t = str(f, "time").ifEmpty { if (d >= todayS) defT else "" } // задачі без часу: час із налаштувань
                if (d.isEmpty() || t.isEmpty()) continue
                val asg = str(f, "asg")
                if (asg.isNotEmpty() && asg != uid) continue // задача призначена комусь іншому
                val w = try { fmt.parse("$d $t")!!.time } catch (e: Exception) { continue }
                val title = prefix + str(f, "title")
                if (w > now) {
                    found.add(Triple(w, title, 0))
                    val nm = docs.getJSONObject(i).optString("name")
                    val path = nm.substringAfter("/documents/")
                    extra[w.toString() + title] = JSONObject().put("i", path)
                        .put("r", if (str(f, "rep").isNotEmpty()) 1 else 0)
                        .put("pr", str(f, "prio").ifEmpty { "normal" })
                        .put("ot", str(f, "ot").ifEmpty { t }).put("od", str(f, "od").ifEmpty { d })
                }
                val ak = lng(f, "ak")
                for (m in pres(f)) {
                    val e = w - m * 60_000L
                    if (e > now && e > ak) found.add(Triple(e, "Через ${label(m)}: $title", 1))
                }
            }
        }

        collect("$base/users/$uid/tasks?pageSize=300", "")
        val q = "{\"structuredQuery\":{\"from\":[{\"collectionId\":\"calendars\"}],\"where\":{\"fieldFilter\":" +
            "{\"field\":{\"fieldPath\":\"members\"},\"op\":\"ARRAY_CONTAINS\",\"value\":{\"stringValue\":\"$uid\"}}}}}"
        val cals = JSONArray(call("$base:runQuery", "POST", q, id, "application/json"))
        for (i in 0 until cals.length()) {
            val cd = cals.getJSONObject(i).optJSONObject("document") ?: continue
            val n = cd.optString("name")
            if (n.isEmpty()) continue
            val cf = cd.optJSONObject("fields")
            val role = cf?.optJSONObject("roles")?.optJSONObject("mapValue")?.optJSONObject("fields")
                ?.optJSONObject(uid)?.optString("stringValue", "") ?: ""
            val viewer = (cf?.let { str(it, "owner") } ?: "") != uid && role == "v"
            collect(
                "https://firestore.googleapis.com/v1/$n/tasks?pageSize=300", "\uD83C\uDFE2 ",
                viewer, (cf?.let { str(it, "name") } ?: "").ifEmpty { "Календар" }
            )
        }
        if (cn < 0) {
            sp.edit().putLong("cn", now).apply() // перший запуск: старі зміни не повідомляємо
        } else if (changes.isNotEmpty()) {
            if (!Quiet.isQuiet(ctx, now)) Notify.changes(ctx, changes.sortedBy { it.et })
            sp.edit().putLong("cn", maxOf(cn, changes.maxOf { it.et })).apply()
        }
        try { WData.save(ctx, wl, null) } catch (e: Exception) { }
        val out = JSONArray()
        for (p in found.sortedBy { it.first }.take(60)) {
            val o = JSONObject().put("t", p.second).put("w", p.first)
            if (p.third == 1) o.put("p", 1)
            else extra[p.first.toString() + p.second]?.let { x -> for (k in x.keys()) o.put(k, x.get(k)) }
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
