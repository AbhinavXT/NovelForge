package com.abhinavxt.novelforge.data.annas

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The mirror to use and the member's secret key.
 *
 * The file is excluded from Android backups (see backup_rules.xml): the key
 * signs in to a paid account, and a cloud copy of it is a copy to lose.
 */
class AnnasArchivePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)?.let(::normalizeBaseUrl) ?: DEFAULT_MIRROR
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, normalizeBaseUrl(value) ?: DEFAULT_MIRROR).apply()
        }

    var secretKey: String
        get() = prefs.getString(KEY_SECRET, null).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SECRET, value.trim()).apply()
        }

    companion object {
        const val PREFS_NAME = "annas_archive"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SECRET = "secret_key"

        /**
         * Domains that worked when this was written. The site loses domains
         * often (.org and .li in 2026), so search falls through this list
         * when the chosen one is down or parked, and remembers the one that
         * answered.
         */
        val KNOWN_MIRRORS = listOf(
            "https://annas-archive.gl",
            "https://annas-archive.pk",
            "https://annas-archive.gd",
        )
        val DEFAULT_MIRROR = KNOWN_MIRRORS.first()

        /** "annas-archive.gl/search?q=x" → "https://annas-archive.gl". */
        fun normalizeBaseUrl(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (url.host.isBlank() || '.' !in url.host) return null
            val port = if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
            return "${url.scheme}://${url.host}$port"
        }
    }
}
