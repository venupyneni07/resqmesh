package org.resqmesh.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.resqmesh.app.core.LocationContext
import org.resqmesh.app.data.LocalCipher

data class SavedSosDraft(val fields: Map<String, String>, val category: Int, val needs: Set<String>,
    val expanded: Set<String>, val location: LocationContext, val initialized: Boolean)

/** Private encrypted recovery for an unsent text draft. Media has its separate recovery lifecycle. */
class SosDraftStore(context: Context, preferencesName: String = "resqmesh_unsent_draft") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val cipher = LocalCipher()
    private val encryptionContext = "unsent-draft:$preferencesName"
    var restoreFailed = false
        private set

    fun load(): SavedSosDraft? {
        val stored = preferences.getString("draft", null) ?: return null
        return runCatching {
            val value = JSONObject(cipher.decrypt(stored, encryptionContext))
            require(value.optInt("version") == 1)
            val fields = value.getJSONObject("fields")
            val location = value.getJSONObject("location")
            SavedSosDraft(fieldKeys.associateWith { fields.optString(it).take(2000) }, value.optInt("category", -1).coerceIn(-1, 6),
                strings(value.optJSONArray("needs")).filter { it in needKeys }.toSet(),
                strings(value.optJSONArray("expanded")).filter { it in setOf("people", "message", "location") }.toSet(),
                LocationContext(location.optString("source", "unknown"), location.optLong("observedAt").takeIf { it > 0 },
                    location.optDouble("latitude", Double.NaN).takeIf { it.isFinite() },
                    location.optDouble("longitude", Double.NaN).takeIf { it.isFinite() },
                    location.optDouble("accuracyM", Double.NaN).takeIf { it.isFinite() }), value.optBoolean("initialized"))
        }.onFailure { restoreFailed = true }.getOrNull()
    }

    fun save(draft: SavedSosDraft): Boolean = runCatching {
        val location = JSONObject().put("source", draft.location.source)
        draft.location.observedAt?.let { location.put("observedAt", it) }
        draft.location.latitude?.let { location.put("latitude", it) }
        draft.location.longitude?.let { location.put("longitude", it) }
        draft.location.accuracyM?.let { location.put("accuracyM", it) }
        val value = JSONObject().put("version", 1).put("fields", JSONObject(draft.fields.filterKeys { it in fieldKeys }))
            .put("category", draft.category).put("needs", JSONArray(draft.needs.toList()))
            .put("expanded", JSONArray(draft.expanded.toList())).put("location", location).put("initialized", draft.initialized)
        preferences.edit().putString("draft", cipher.encrypt(value.toString(), encryptionContext)).apply()
        restoreFailed = false
        true
    }.getOrDefault(false)

    fun clear() { preferences.edit().remove("draft").apply(); restoreFailed = false }

    private fun strings(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.optString(it) }
    companion object {
        val fieldKeys = listOf("text", "building", "location", "floor", "room", "zone", "people", "vulnerability")
        private val needKeys = setOf("cannot_move", "cannot_speak", "people_injured")
    }
}
