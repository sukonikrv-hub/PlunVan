package ua.planner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** Дії з екрана будильника: пишемо у Firestore напряму; без мережі — у чергу, відправиться при наступній синхронізації. */
object QuickOps {
    private fun fields(op: JSONObject): Pair<JSONObject, List<String>> {
        val f = JSONObject()
        val names = ArrayList<String>()
        if (op.getString("k") == "done") {
            f.put("done", JSONObject().put("booleanValue", true)); names.add("done")
            val e = op.optString("e")
            if (e.isNotEmpty() && op.getString("i").startsWith("calendars/")) {
                f.put("dby", JSONObject().put("stringValue", e)); names.add("dby")
            }
        } else {
            f.put("date", JSONObject().put("stringValue", op.getString("d")))
            f.put("time", JSONObject().put("stringValue", op.getString("m")))
            f.put("sn", JSONObject().put("integerValue", "1"))
            f.put("ot", JSONObject().put("stringValue", op.getString("ot")))
            f.put("od", JSONObject().put("stringValue", op.getString("od")))
            f.put("ak", JSONObject().put("integerValue", op.getLong("ak").toString()))
            names.addAll(listOf("date", "time", "sn", "ot", "od", "ak"))
        }
        return Pair(f, names)
    }

    /** Відправляє операцію. true — успіх. */
    fun send(ctx: Context, op: JSONObject): Boolean {
        val (f, names) = fields(op)
        val (id, base) = Sync.session(ctx) ?: return false
        val mask = names.joinToString("&") { "updateMask.fieldPaths=$it" }
        Sync.patch("$base/${op.getString("i")}?$mask", JSONObject().put("fields", f).toString(), id)
        return true
    }

    private fun queue(ctx: Context, op: JSONObject) {
        val sp = ctx.getSharedPreferences("p", 0)
        val a = JSONArray(sp.getString("ops", "[]"))
        a.put(op)
        sp.edit().putString("ops", a.toString()).apply()
    }

    /** Викликається на початку синхронізації: домовляємо те, що не вдалося відправити. */
    fun flush(ctx: Context) {
        val sp = ctx.getSharedPreferences("p", 0)
        val a = JSONArray(sp.getString("ops", "[]"))
        if (a.length() == 0) return
        val left = JSONArray()
        for (k in 0 until a.length()) {
            val op = a.getJSONObject(k)
            try { send(ctx, op) } catch (e: Exception) { left.put(op) }
        }
        sp.edit().putString("ops", left.toString()).apply()
    }

    private fun email(ctx: Context): String =
        try { JSONObject(ctx.getSharedPreferences("p", 0).getString("auth", "{}")!!).optString("e") } catch (e: Exception) { "" }

    private fun run(ctx: Context, op: JSONObject, local: (() -> Unit)?) {
        Thread {
            try { send(ctx, op) } catch (e: Exception) { queue(ctx, op); local?.invoke() }
            try { Sync.run(ctx) } catch (e: Exception) { }
        }.start()
    }

    fun done(ctx: Context, path: String) {
        run(ctx, JSONObject().put("k", "done").put("i", path).put("e", email(ctx)), null)
    }

    fun snooze(ctx: Context, path: String, min: Int, j: JSONObject) {
        val at = System.currentTimeMillis() + min * 60_000L
        val s = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(at)
        val op = JSONObject().put("k", "snooze").put("i", path)
            .put("d", s.substring(0, 10)).put("m", s.substring(11))
            .put("ot", j.optString("ot")).put("od", j.optString("od"))
            .put("ak", System.currentTimeMillis())
        run(ctx, op) {
            // без мережі: ставимо будильник локально, щоб нагадування не загубилося
            try {
                val sp = ctx.getSharedPreferences("p", 0)
                val arr = JSONArray(sp.getString("list", "[]"))
                val n = JSONObject(j.toString()).put("w", at)
                arr.put(n)
                Alarms.schedule(ctx, arr.toString())
            } catch (e: Exception) { }
        }
    }
}
