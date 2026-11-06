package dev.keel.spike

import android.content.Context
import org.json.JSONObject

data class SurfaceRule(val id: String, val pkg: String, val anyViewIds: List<String>)

/** Rules are data, so a broken rule can be fixed without changing code. */
object SurfaceRules {
    fun load(context: Context): List<SurfaceRule> {
        val text = context.assets.open("surface_rules.json").bufferedReader().use { it.readText() }
        val rules = JSONObject(text).getJSONArray("rules")
        return (0 until rules.length()).map { i ->
            val r = rules.getJSONObject(i)
            val ids = r.getJSONArray("anyViewIds")
            SurfaceRule(
                r.getString("id"),
                r.getString("package"),
                (0 until ids.length()).map { ids.getString(it) },
            )
        }
    }
}
