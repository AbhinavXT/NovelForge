package com.abhinavxt.novelforge.data

import android.content.Context
import android.content.SharedPreferences
import com.abhinavxt.novelforge.BuildConfig
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Checks for new app versions against an update feed.
 *
 * The feed is expected to return JSON in this shape:
 *   {
 *     "version": "1.4.0",
 *     "versionCode": 14,             // optional
 *     "downloadUrl": "https://.../NovelForge-1.4.0.apk",
 *     "releaseNotes": "• new thing\n• fixed bug",
 *     "publishedAt": "2026-01-15T00:00:00Z"   // ISO-8601, optional
 *   }
 *
 * The default implementation here targets GitHub Releases' API, which
 * hands back a richer JSON that we map down to the shape above. Users
 * who prefer a custom backend can plug that in by changing
 * [UpdateConfig.feedUrl] + [UpdateConfig.parser] — see AppConfig.
 *
 * ── Why pluggable ──
 * Today: free, no infra, just works if you publish GitHub releases.
 * Tomorrow: your devblog could host a tiny JSON at /update.json.
 * Either way, this class doesn't care.
 *
 * ── Privacy ──
 * This is a plain HTTP GET to a public URL. No identifiers are sent
 * beyond standard User-Agent. No analytics. No telemetry.
 *
 * ── Rate limits ──
 * We only check at most once every [MIN_CHECK_INTERVAL_MS]. Unauthenticated
 * GitHub API allows 60 req/hour per IP, so even a user hammering the
 * "check now" button won't run out.
 */
class UpdateChecker(
    private val context: Context,
    private val config: UpdateConfig = UpdateConfig.default()
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val PREFS_NAME = "update_checker"

        // Keys persisted across sessions
        private const val KEY_LAST_CHECK_MS = "last_check_ms"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
        private const val KEY_LATEST_SEEN_VERSION = "latest_seen_version"
        private const val KEY_LATEST_DOWNLOAD_URL = "latest_download_url"
        private const val KEY_LATEST_RELEASE_NOTES = "latest_release_notes"

        // Throttle. User can force a check at any time but automatic
        // background checks won't fire more often than this.
        const val MIN_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L   // 12 hours
    }

    /** Info about an available update. */
    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    /**
     * Current update-check status.
     *
     *   Idle:            never checked, or check hasn't produced a result yet
     *   UpToDate:        last check succeeded, we're on the latest version
     *   Available(info): a newer version exists
     *   Failed(msg):     last check errored out
     *   Disabled:        update checks are turned off in config
     */
    sealed class Status {
        data object Idle : Status()
        data object UpToDate : Status()
        data class Available(val info: UpdateInfo) : Status()
        data class Failed(val message: String) : Status()
        data object Disabled : Status()
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        // On process start, restore any persisted "available update" info
        // so the UI can show it immediately without re-checking.
        loadPersistedStatus()
    }

    /**
     * Check for updates. Returns the new Status. Respects throttling
     * unless [force] is true (user-initiated check from Settings).
     */
    suspend fun check(force: Boolean = false): Status = withContext(Dispatchers.IO) {
        if (!config.enabled) {
            _status.value = Status.Disabled
            return@withContext Status.Disabled
        }

        if (!force && !shouldCheck()) {
            // Throttled — return whatever we have. If we have nothing,
            // stay Idle; next natural check will run.
            return@withContext _status.value
        }

        val result = try {
            val request = Request.Builder()
                .url(config.feedUrl)
                .header("User-Agent", "NovelForge-UpdateChecker")
                .header("Accept", "application/json")
                .build()

            SourceManager.sharedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use Status.Failed(
                        "Check failed: HTTP ${response.code}"
                    )
                }
                val body = response.body?.string()
                    ?: return@use Status.Failed("Empty response")

                val parsed = config.parser.parse(body)
                    ?: return@use Status.Failed(
                        "Couldn't read update info — malformed feed"
                    )

                handleParsedInfo(parsed)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "update check failed", e)
            Status.Failed(e.message ?: "Network error")
        }

        prefs.edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
        _status.value = result
        result
    }

    /**
     * User dismissed an update prompt. We remember the version so we
     * don't pester them about THAT version again, but we will still
     * show prompts for newer versions after this one.
     */
    fun dismiss(version: String) {
        prefs.edit().putString(KEY_DISMISSED_VERSION, version).apply()
    }

    /**
     * Did the user already dismiss a prompt for [version]?
     */
    fun isDismissed(version: String): Boolean {
        return prefs.getString(KEY_DISMISSED_VERSION, null) == version
    }

    // ─── Private ────────────────────────────────────────────────────

    private fun shouldCheck(): Boolean {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0)
        return System.currentTimeMillis() - lastCheck >= MIN_CHECK_INTERVAL_MS
    }

    private fun handleParsedInfo(raw: RawUpdateInfo): Status {
        val current = BuildConfig.VERSION_NAME
        val latest = raw.version

        return if (isNewerVersion(latest, current)) {
            val info = UpdateInfo(
                currentVersion = current,
                latestVersion = latest,
                downloadUrl = raw.downloadUrl,
                releaseNotes = raw.releaseNotes
            )
            // Persist so Settings can show a badge immediately on next launch.
            prefs.edit()
                .putString(KEY_LATEST_SEEN_VERSION, latest)
                .putString(KEY_LATEST_DOWNLOAD_URL, raw.downloadUrl)
                .putString(KEY_LATEST_RELEASE_NOTES, raw.releaseNotes)
                .apply()
            Status.Available(info)
        } else {
            // Clear any stale "available" persistence — we're up to date now.
            prefs.edit()
                .remove(KEY_LATEST_SEEN_VERSION)
                .remove(KEY_LATEST_DOWNLOAD_URL)
                .remove(KEY_LATEST_RELEASE_NOTES)
                .apply()
            Status.UpToDate
        }
    }

    private fun loadPersistedStatus() {
        if (!config.enabled) {
            _status.value = Status.Disabled
            return
        }
        val latest = prefs.getString(KEY_LATEST_SEEN_VERSION, null) ?: return
        val url = prefs.getString(KEY_LATEST_DOWNLOAD_URL, null) ?: return
        val notes = prefs.getString(KEY_LATEST_RELEASE_NOTES, null) ?: ""
        val current = BuildConfig.VERSION_NAME

        // Validate that the persisted "available" version is still newer
        // than the current build — the user may have just installed the
        // update, in which case we drop the stale info.
        if (isNewerVersion(latest, current)) {
            _status.value = Status.Available(
                UpdateInfo(current, latest, url, notes)
            )
        } else {
            // We're now on or past the persisted latest — clear it.
            prefs.edit()
                .remove(KEY_LATEST_SEEN_VERSION)
                .remove(KEY_LATEST_DOWNLOAD_URL)
                .remove(KEY_LATEST_RELEASE_NOTES)
                .apply()
            _status.value = Status.UpToDate
        }
    }

    /**
     * Semantic version comparison. Handles "1.4.0" vs "1.3.9", with
     * missing segments treated as 0 ("1.4" == "1.4.0") and non-numeric
     * suffixes stripped (so "1.4.0-beta" compares as 1.4.0).
     *
     * Returns true if [latest] > [current].
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun parse(v: String): List<Int> = v.trim()
            .removePrefix("v")
            .split("-", "+", "_").first()     // strip pre-release / build
            .split(".")
            .mapNotNull { it.toIntOrNull() }

        val a = parse(latest)
        val b = parse(current)

        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrNull(i) ?: 0
            val bv = b.getOrNull(i) ?: 0
            if (av > bv) return true
            if (av < bv) return false
        }
        return false   // equal or missing data
    }
}

// ─── Config + feed parsing ──────────────────────────────────────────

/**
 * Raw shape returned by a feed parser, before version comparison.
 */
data class RawUpdateInfo(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String
)

/**
 * Abstraction over the JSON shape of an update feed. Different sources
 * (GitHub, your blog, your CDN) hand back different JSON; each gets
 * its own parser.
 */
fun interface UpdateFeedParser {
    /** Parse feed response body. Return null if malformed. */
    fun parse(body: String): RawUpdateInfo?
}

/**
 * Top-level config. Passed into [UpdateChecker] at construction. The
 * default returns a GitHub-releases configuration; override to point
 * at a custom backend.
 */
data class UpdateConfig(
    val enabled: Boolean,
    val feedUrl: String,
    val parser: UpdateFeedParser
) {
    companion object {
        /**
         * Default config targets GitHub Releases for a repo. Edit the
         * constants inside this method (or move them to AppConfig) to
         * point at your own repo.
         *
         * Set [GITHUB_REPO] to an empty string to disable update checks
         * entirely — useful for Play Store builds where Play handles
         * updates.
         */
        fun default(): UpdateConfig {
            // ─── CONFIGURE THIS ───
            val GITHUB_OWNER = "AbhinavXT"        // ← change me
            val GITHUB_REPO = "NovelForge"        // ← change me, or set "" to disable
            // ──────────────────────

            if (GITHUB_REPO.isBlank()) {
                return UpdateConfig(
                    enabled = false,
                    feedUrl = "",
                    parser = UpdateFeedParser { null }
                )
            }

            return UpdateConfig(
                enabled = true,
                feedUrl = "https://api.github.com/repos/" +
                        "$GITHUB_OWNER/$GITHUB_REPO/releases/latest",
                parser = GitHubReleasesParser
            )
        }
    }
}

/**
 * Parser for the standard GitHub "latest release" endpoint. Maps from
 * the GitHub JSON shape to our [RawUpdateInfo].
 *
 * GitHub shape (abridged):
 *   {
 *     "tag_name": "v1.4.0",
 *     "name": "1.4.0 — New Stats Design",
 *     "body": "## Changes\n- …",
 *     "html_url": "https://github.com/.../releases/tag/v1.4.0",
 *     "assets": [
 *       { "name": "NovelForge-1.4.0.apk",
 *         "browser_download_url": "https://github.com/.../NovelForge-1.4.0.apk" }
 *     ]
 *   }
 *
 * We prefer the first asset with a .apk extension for downloadUrl;
 * fall back to html_url if no APK asset was published.
 */
object GitHubReleasesParser : UpdateFeedParser {
    override fun parse(body: String): RawUpdateInfo? {
        return try {
            val root = JSONObject(body)
            val tagName = root.optString("tag_name", "")
            if (tagName.isBlank()) return null

            // Prefer the APK asset's browser_download_url. Falls back to
            // the release's html_url so users at least land on the page.
            val downloadUrl = run {
                val assets = root.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            return@run asset.optString("browser_download_url", "")
                                .ifBlank { null }
                        }
                    }
                }
                root.optString("html_url", "").ifBlank { null }
            } ?: return null

            RawUpdateInfo(
                version = tagName.removePrefix("v"),
                downloadUrl = downloadUrl,
                releaseNotes = root.optString("body", "")
            )
        } catch (e: Exception) {
            Logger.e("GitHubReleasesParser", "parse failed", e)
            null
        }
    }
}