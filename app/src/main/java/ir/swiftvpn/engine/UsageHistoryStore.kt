package ir.swiftvpn.engine

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Persistent per-profile traffic history, bucketed by calendar day.
 *
 * The live TrafficStats flow only covers the current session — it resets on
 * every connect. This store accumulates the same per-second deltas into daily
 * buckets in SharedPreferences, which is what powers the day/week/month totals
 * on the Usage tab. Keys are ISO day strings ("2026-08-18") so lexicographic
 * comparison IS chronological comparison.
 *
 * Buckets older than [KEEP_DAYS] are pruned on every write, so the stored JSON
 * stays small regardless of how long the app lives on the device.
 */
object UsageHistoryStore {

    private const val PREFS = "usage_history"
    private const val KEEP_DAYS = 400

    /** Aggregated usage for "today", "this week" and "this month". */
    data class Totals(
        val dayIn: Long, val dayOut: Long,
        val weekIn: Long, val weekOut: Long,
        val monthIn: Long, val monthOut: Long,
    )

    /**
     * Records one sampling interval's worth of traffic. Called from the
     * engine's byte pipeline (about once per second while connected), so this
     * must stay cheap: one tiny JSON rewrite per call is acceptable because
     * SharedPreferences batches the actual disk flush.
     */
    @Synchronized
    fun add(context: Context, uuid: String, dIn: Long, dOut: Long) {
        if (dIn <= 0L && dOut <= 0L) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val days = readDays(prefs.getString(uuid, null))

        val now = Calendar.getInstance()
        val key = dayKey(now)
        val cur = days[key] ?: longArrayOf(0L, 0L)
        cur[0] += dIn
        cur[1] += dOut
        days[key] = cur

        // Prune ancient buckets so the value never grows without bound.
        val cutoff = dayKey(Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -KEEP_DAYS)
        })
        days.keys.toList().forEach { if (it < cutoff) days.remove(it) }

        val o = JSONObject()
        for ((k, v) in days) {
            o.put(k, org.json.JSONArray().put(v[0]).put(v[1]))
        }
        prefs.edit().putString(uuid, o.toString()).apply()
    }

    /**
     * Sums the buckets for the three reporting windows. "This week" follows
     * the Iranian convention of starting on Saturday; "this month" is the
     * calendar month.
     */
    fun totals(context: Context, uuid: String): Totals {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val days = readDays(prefs.getString(uuid, null))

        val now = Calendar.getInstance()
        val todayKey = dayKey(now)

        // Calendar.SATURDAY is 7, SUNDAY is 1; distance back to Saturday.
        val weekStart = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7))
        }
        val weekStartKey = dayKey(weekStart)
        val monthPrefix = todayKey.substring(0, 7) // "yyyy-MM"

        var dIn = 0L; var dOut = 0L
        var wIn = 0L; var wOut = 0L
        var mIn = 0L; var mOut = 0L
        for ((k, v) in days) {
            if (k == todayKey) { dIn += v[0]; dOut += v[1] }
            if (k >= weekStartKey) { wIn += v[0]; wOut += v[1] }
            if (k.startsWith(monthPrefix)) { mIn += v[0]; mOut += v[1] }
        }
        return Totals(dIn, dOut, wIn, wOut, mIn, mOut)
    }

    /** Wipes the history of a deleted profile. */
    @Synchronized
    fun remove(context: Context, uuid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(uuid).apply()
    }

    private fun dayKey(cal: Calendar): String = String.format(
        Locale.US, "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
    )

    private fun readDays(raw: String?): MutableMap<String, LongArray> {
        val out = mutableMapOf<String, LongArray>()
        if (raw.isNullOrBlank()) return out
        runCatching {
            val o = JSONObject(raw)
            for (k in o.keys()) {
                val arr = o.getJSONArray(k)
                out[k] = longArrayOf(arr.optLong(0), arr.optLong(1))
            }
        }
        return out
    }
}
