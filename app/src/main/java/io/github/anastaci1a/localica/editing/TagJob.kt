package io.github.anastaci1a.localica.editing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small durable outcomes, independent of the editor draft and recoverable file journal. */
data class TagJob(val id: String, val total: Int, val status: String = "Editing", val outcomes: List<String> = emptyList())

internal class TagJobHistory(context: Context) {
    private val preferences = context.getSharedPreferences("tag-jobs", 0)
    fun read(): List<TagJob> = runCatching {
        val array = JSONArray(preferences.getString("jobs", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val outcomes = item.getJSONArray("outcomes")
            TagJob(item.getString("id"), item.getInt("total"), item.getString("status").let {
                if (it == "Editing") "Interrupted — check tag recovery before retrying" else it
            }, (0 until outcomes.length()).map(outcomes::getString))
        }
    }.getOrDefault(emptyList())
    fun write(jobs: List<TagJob>) {
        val array = JSONArray()
        jobs.take(8).forEach { job -> array.put(JSONObject().apply {
            put("id", job.id); put("total", job.total); put("status", job.status); put("outcomes", JSONArray(job.outcomes))
        }) }
        check(preferences.edit().putString("jobs", array.toString()).commit()) { "Couldn’t save tag job progress." }
    }
}
