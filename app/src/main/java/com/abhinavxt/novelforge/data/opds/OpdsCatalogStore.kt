package com.abhinavxt.novelforge.data.opds

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A catalog the user browses. [password] is kept apart from the rest (see [OpdsCatalogStore]). */
data class OpdsCatalog(
    val id: String,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = ""
) {
    val needsLogin: Boolean get() = username.isNotBlank()
}

/**
 * The user's OPDS catalogs.
 *
 * Names, addresses and usernames live in `opds_catalogs`, which backups
 * keep. Passwords live in `opds_credentials`, which backups and device
 * transfers leave out (see backup_rules.xml): a restored phone keeps its
 * catalogs and asks for the password again.
 */
class OpdsCatalogStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = context.applicationContext.getSharedPreferences(SECRETS, Context.MODE_PRIVATE)

    private val _catalogs = MutableStateFlow(load())
    val catalogs: StateFlow<List<OpdsCatalog>> = _catalogs.asStateFlow()

    fun get(id: String): OpdsCatalog? = _catalogs.value.firstOrNull { it.id == id }

    /** Adds [catalog], or replaces the one with its id. */
    fun save(catalog: OpdsCatalog) {
        val list = _catalogs.value.toMutableList()
        val i = list.indexOfFirst { it.id == catalog.id }
        if (i >= 0) list[i] = catalog else list += catalog
        persist(list)
    }

    fun delete(id: String) {
        persist(_catalogs.value.filterNot { it.id == id })
        secrets.edit().remove(id).apply()
    }

    private fun persist(list: List<OpdsCatalog>) {
        val json = JSONArray()
        val secretEdit = secrets.edit()
        for (c in list) {
            json.put(JSONObject().put("id", c.id).put("name", c.name).put("url", c.url).put("username", c.username))
            if (c.password.isNotEmpty()) secretEdit.putString(c.id, c.password) else secretEdit.remove(c.id)
        }
        secretEdit.apply()
        prefs.edit().putString(KEY_LIST, json.toString()).putBoolean(KEY_SEEDED, true).apply()
        _catalogs.value = list
    }

    private fun load(): List<OpdsCatalog> {
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            // First run: start with the open catalog that needs no account.
            val seeded = DEFAULTS.map { it.copy(id = UUID.randomUUID().toString()) }
            prefs.edit()
                .putString(KEY_LIST, JSONArray().apply {
                    seeded.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("username", "")) }
                }.toString())
                .putBoolean(KEY_SEEDED, true)
                .apply()
            return seeded
        }
        val array = runCatching { JSONArray(prefs.getString(KEY_LIST, "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            OpdsCatalog(
                id = id,
                name = o.optString("name"),
                url = o.optString("url"),
                username = o.optString("username"),
                password = secrets.getString(id, null).orEmpty()
            )
        }
    }

    companion object {
        const val PREFS = "opds_catalogs"
        const val SECRETS = "opds_credentials"
        private const val KEY_LIST = "catalogs"
        private const val KEY_SEEDED = "seeded"

        private val DEFAULTS = listOf(
            OpdsCatalog("", "Project Gutenberg", "https://www.gutenberg.org/ebooks.opds/"),
        )

        /** Catalogs offered as one-tap suggestions when adding one. */
        val SUGGESTIONS = listOf(
            Suggestion("Project Gutenberg", "https://www.gutenberg.org/ebooks.opds/", null),
            Suggestion(
                "Standard Ebooks", "https://standardebooks.org/feeds/opds",
                "Needs a Patrons Circle membership: sign in with your email address, no password."
            ),
            Suggestion(
                "Calibre-Web, Kavita or Komga", "https://",
                "Your own server's OPDS address, e.g. https://books.example.com/opds, with your login."
            ),
        )

        data class Suggestion(val name: String, val url: String, val note: String?)

        /** "gutenberg.org/ebooks.opds" → "https://gutenberg.org/ebooks.opds"; null when it isn't a web address. */
        fun normalizeUrl(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (url.host.isBlank()) return null
            return url.toString()
        }
    }
}
