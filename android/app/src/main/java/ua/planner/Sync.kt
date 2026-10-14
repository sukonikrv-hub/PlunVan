package ua.planner

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

        fun collect(url: String, prefix: String) {
            val docs = JSONObject(call(url, "GET", null, id, "")).optJSONArray("documents") ?: return
            for (i in 0 until docs.length()) {
                val f = docs.getJSONObject(i).optJSONObject("fields") ?: continue
                if (f.optJSONObject("done")?.optBoolean("booleanValue", false) == true) continue
                val d = str(f, "date")
                val t = str(f, "time")
                if (d.isEmpty() || t.isEmpty()) continue
                val asg = str(f, "asg")
                if (asg.isNotEmpty() && asg != uid) continue // задача призначена комусь іншому
                val w = try { fmt.parse("$d $t")!!.time } catch (e: Exception) { continue }
                val title = prefix + str(f, "title")
                if (w > now) found.add(Triple(w, title, 0))
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
            val n = cals.getJSONObject(i).optJSONObject("document")?.optString("name") ?: continue
            collect("https://firestore.googleapis.com/v1/$n/tasks?pageSize=300", "\uD83C\uDFE2 ")
        }
        val out = JSONArray()
        for (p in found.sortedBy { it.first }.take(60)) {
            val o = JSONObject().put("t", p.second).put("w", p.first)
            if (p.third == 1) o.put("p", 1)
            out.put(o)
        }
        Alarms.schedule(ctx, out.toString())
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
    }
}

class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { Sync.run(applicationContext) } catch (e: Exception) { }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
