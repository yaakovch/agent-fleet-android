package com.termux.app.fleet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedPhrase(val id: String, val text: String, val enter: Boolean)

/** App-local templates, never pending input or session transcripts. */
object SavedPhrases {
    const val MAX_COUNT = 30
    const val MAX_LENGTH = 2000
    private const val STORE = "agent-fleet-saved-phrases-v1"
    private val idPattern = Regex("^[a-zA-Z0-9-]{1,64}$")

    fun validText(text: String) = text.isNotBlank() && text.length <= MAX_LENGTH && text.none { it.isISOControl() }
    fun newPhrase() = SavedPhrase(UUID.randomUUID().toString(), "", false)
    fun load(context: Context): List<SavedPhrase> {
        val raw = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("phrases", null)
            ?: return listOf(SavedPhrase("continue", "Continue", true))
        require(raw.length <= 128_000) { "Saved phrases could not be read" }
        val rows = JSONArray(raw)
        require(rows.length() <= MAX_COUNT) { "Too many saved phrases" }
        return validate((0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.get("id") is String && row.get("text") is String) { "Invalid saved phrase" }
            require(row.get("enter") is Boolean) { "Invalid Enter choice" }
            SavedPhrase(row.getString("id"), row.getString("text"), row.getBoolean("enter"))
        })
    }
    fun save(context: Context, phrases: List<SavedPhrase>) {
        val rows = JSONArray()
        validate(phrases).forEach { phrase -> rows.put(JSONObject().put("id", phrase.id).put("text", phrase.text).put("enter", phrase.enter)) }
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString("phrases", rows.toString()).commit()) { "Could not save phrases" }
    }
    private fun validate(phrases: List<SavedPhrase>): List<SavedPhrase> {
        require(phrases.size <= MAX_COUNT && phrases.map { it.id }.distinct().size == phrases.size) { "Invalid saved phrases" }
        require(phrases.all { idPattern.matches(it.id) && validText(it.text) }) { "Invalid saved phrase" }
        return phrases
    }
}
