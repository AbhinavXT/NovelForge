package com.abhinavxt.novelforge.data.source.plugin

// Source plugins — repositories, install/update/uninstall, persistence.
//
// Layout on disk (app-private, so other apps cannot swap a plugin's code):
//     filesDir/plugins/<pluginId>.js
//     filesDir/plugins/installed.json      [InstalledPlugin, ...]
// Repository URLs live in SharedPreferences.
//
// Repository index format (same fields as LNReader's plugins.json):
//     [ { "id": "...", "name": "...", "site": "https://...", "lang": "en",
//         "version": "1.0.0", "url": "https://.../plugin.js", "iconUrl": "..." } ]
// A relative "url" is resolved against the index URL.
//
// Bundled plugins
// ---------------
// The app ships its first-party sources as plugins in assets/plugins/
// (index.json in the same format, "url" relative to that folder). They are
// installed on first start and updated whenever the app ships a newer
// version, unless the user removed them. They also stay listed under
// "Available", so a removed one can be put back.
//
// Bundled entries may carry "sourceId": the id of the built-in Kotlin source
// the plugin replaced. The plugin then registers under that id instead of
// "js-<id>" so novels already in users' libraries keep resolving, and may
// return explicit novel/chapter ids in the old format (see JsPluginSource).
// Only ids listed in LEGACY_SOURCES are accepted, and never from a
// downloaded repository.
//
// Most first-party plugins are no longer in the APK and come only from the
// official repository (OFFICIAL_ONLY). They keep their old source ids when
// installed from there, so libraries and restored backups still resolve.

import android.content.Context
import com.abhinavxt.novelforge.data.source.SourceHttp
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.abhinavxt.novelforge.data.source.net.CloudflareInterceptor
import com.abhinavxt.novelforge.data.source.net.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URI

/** A plugin on disk. Persisted as JSON — keep field names stable. */
data class InstalledPlugin(
    val id: String,
    val name: String,
    val version: String,
    val site: String,
    val lang: String,
    val iconUrl: String?,
    /** Where the script was downloaded from; null when installed from a file. */
    val scriptUrl: String?,
    /** Set for bundled plugins that replaced a built-in source; see LEGACY_SOURCES. */
    val legacySourceId: String? = null,
) {
    val sourceId: String get() = legacySourceId ?: PluginManager.sourceIdFor(id)

    /** Prefix of novel ids this plugin may hand out explicitly, or null if it may not. */
    val legacyIdPrefix: String? get() = legacySourceId?.let { PluginManager.LEGACY_SOURCES[it] }
}

/** A plugin listed by a repository index. */
data class RepoPlugin(
    val id: String,
    val name: String,
    val version: String,
    val site: String,
    val lang: String,
    val url: String,
    val iconUrl: String?,
    val repo: String,
    val legacySourceId: String? = null,
)

object PluginManager {

    private const val TAG = "PluginManager"
    private const val PREFS = "source_plugins"
    private const val PREF_REPOS = "repos"
    private const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
    private const val MAX_INDEX_BYTES = 4L * 1024 * 1024
    private const val PREF_DECLINED = "declined_bundled"
    private const val PREF_LAST_AUTO_UPDATE = "last_auto_update"
    private const val AUTO_UPDATE_INTERVAL_MS = 12L * 60 * 60 * 1000

    /**
     * The public repository NovelForge's own plugins are published to
     * (github.com/AbhinavXT/NovelForge-plugins). Listed by default and
     * removable like any other repository.
     */
    const val OFFICIAL_REPO = "https://raw.githubusercontent.com/AbhinavXT/NovelForge-plugins/main/index.json"
    private const val ASSET_DIR = "plugins"

    /** RepoPlugin.repo for plugins shipped in the APK. */
    const val BUNDLED = "bundled"

    /**
     * Built-in source ids a bundled plugin may take over, mapped to the
     * prefix of the novel ids that source wrote ("rr_12345" for royalroad).
     * The "q…" sources already used "<sourceId>_<base64url>", which is the
     * plugin default, so their prefix is the source id itself.
     */
    val LEGACY_SOURCES: Map<String, String> = mapOf(
        "royalroad" to "rr",
        "rnf" to "rnf",
        "fwn" to "fwn",
        "lr" to "lr",
        "nfn" to "nfn",
        "pr" to "pr",
        "pt" to "pt",
        "wtr" to "wtr",
    ) + listOf(
        "qanv", "qfmtl", "qgray", "qhir", "qlnt", "qlnw", "qlno", "qmtln", "qnbn",
        "qnful", "qnlig", "qrnb", "qrfn", "qrhv", "qsonic", "qwatt", "qwbox", "qwclick",
    ).associateWith { it }

    /**
     * First-party plugins that used to ship in the APK and are now installed
     * from the official repository only, with the source id each keeps.
     */
    val OFFICIAL_ONLY: Map<String, String> = mapOf(
        "readnovelfull" to "rnf", "freewebnovel" to "fwn", "libread" to "lr", "novelfull-net" to "nfn",
        "pawread" to "pr", "fanmtl" to "qfmtl", "hiraethtranslation" to "qhir", "lightnoveltranslations" to "qlnt",
        "lnori" to "qlno", "mtlnovel" to "qmtln", "nobadnovel" to "qnbn", "novelfull-com" to "qnful",
        "novellight" to "qnlig", "ranobes" to "qrnb", "readfromnet" to "qrfn", "readhive" to "qrhv",
        "sonicmtl" to "qsonic", "wattpad" to "qwatt", "wuxiabox" to "qwbox",
    )

    /**
     * Legacy source ids that SourceManager.constructNovelUrl() parses as
     * "<id>_<slug>". Their plugins must hand out explicit ids, since the
     * default "<id>_<base64url>" would be read back as a slug.
     */
    val HARDCODED_URL_PREFIXES: Set<String> = setOf("rnf", "fwn", "lr", "nfn", "pr", "pt", "wtr")

    private val gson = Gson()
    private val mutex = Mutex()

    private var appContextField: Context? = null
    private val appContext: Context get() = appContextField ?: error("PluginManager.init() was not called")
    private val pluginDir: File get() = File(appContext.filesDir, "plugins")
    private val installedFile: File get() = File(pluginDir, "installed.json")

    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed: StateFlow<List<InstalledPlugin>> = _installed.asStateFlow()

    private val _repos = MutableStateFlow<List<String>>(emptyList())
    val repos: StateFlow<List<String>> = _repos.asStateFlow()

    private val _available = MutableStateFlow<List<RepoPlugin>>(emptyList())
    val available: StateFlow<List<RepoPlugin>> = _available.asStateFlow()

    /** Shared transport for every plugin's fetch(). */
    val http: PluginHttp = OkHttpPluginHttp()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var bundled: List<RepoPlugin> = emptyList()

    /**
     * Registers every installed plugin with SourceManager. Synchronous on
     * purpose: it only reads one small JSON file (scripts compile lazily), and
     * doing it before the first screen means a novel from a plugin source can
     * be opened straight away on a cold start.
     */
    fun init(context: Context) {
        if (appContextField != null) return
        appContextField = context.applicationContext
        _repos.value = loadRepos()
        _installed.value = readInstalled()
        bundled = readBundledIndex()
        syncBundled()
        _available.value = bundled
        val sources = _installed.value.map { register(it) }
        // Compile scripts in the background so the first search, or the
        // Browse screen reading a plugin's filters, rarely waits for it.
        scope.launch {
            sources.forEach { it.warmUp() }
            autoUpdateFirstParty()
        }
    }

    /** Tests only: forget everything init() set up, so it can run again with another Context. */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTests() {
        _installed.value.forEach { SourceManager.unregisterSource(it.sourceId) }
        _installed.value = emptyList()
        _available.value = emptyList()
        _repos.value = emptyList()
        bundled = emptyList()
        appContextField = null
    }

    /** Built-in sources never start with "js-" and plugin source ids never contain "_". */
    fun sourceIdFor(pluginId: String): String =
        "js-" + pluginId.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun isPluginSourceId(sourceId: String): Boolean = sourceId.startsWith("js-")

    // ── Repositories ────────────────────────────────────────────────────

    fun addRepo(url: String): Result<Unit> {
        val clean = url.trim()
        if (clean.toHttpUrlOrNull() == null) {
            return Result.failure(IllegalArgumentException("Not a valid http(s) URL"))
        }
        if (clean !in _repos.value) {
            _repos.value = _repos.value + clean
            saveRepos()
        }
        return Result.success(Unit)
    }

    fun removeRepo(url: String) {
        _repos.value = _repos.value - url
        _available.value = _available.value.filter { it.repo != url }
        saveRepos()
    }

    /** Re-fetches every repository index. Returns one message per repo that failed. */
    suspend fun refreshRepos(): List<String> = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val all = mutableListOf<RepoPlugin>()
        for (repo in _repos.value) {
            try {
                all += fetchIndex(repo)
            } catch (e: Exception) {
                Logger.e(TAG, "repo $repo failed: ${e.message}")
                errors += "$repo: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        // First-party ids may only come from the bundle or the official
        // repository: anything else listing them would replace a built-in
        // source (and inherit its library ids).
        val firstParty = firstPartyIds()
        val trusted = all.filter { it.id !in firstParty || it.repo == OFFICIAL_REPO }
        // Bundled entries first so they win ties; otherwise the highest version wins.
        _available.value = (bundled + trusted)
            .groupBy { it.id }
            .map { (_, entries) -> entries.reduce { best, e -> if (compareVersions(e.version, best.version) > 0) e else best } }
            .sortedBy { it.name.lowercase() }
        errors
    }

    private fun fetchIndex(repo: String): List<RepoPlugin> {
        val body = download(repo, MAX_INDEX_BYTES)
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val entries: List<Map<String, Any?>> = gson.fromJson(body, type)
            ?: throw IOException("index is empty")
        return entries.mapNotNull { e ->
            fun s(k: String) = (e[k] as? String)?.trim()?.ifEmpty { null }
            val id = s("id") ?: return@mapNotNull null
            val url = s("url")?.let { runCatching { URI(repo).resolve(it).toString() }.getOrNull() }
                ?: return@mapNotNull null
            RepoPlugin(
                id = id,
                name = s("name") ?: id,
                version = s("version") ?: "0",
                site = s("site") ?: "",
                lang = s("lang") ?: "en",
                url = url,
                iconUrl = s("iconUrl")?.let { runCatching { URI(repo).resolve(it).toString() }.getOrNull() },
                repo = repo,
            )
        }
    }

    // ── Install / uninstall ─────────────────────────────────────────────

    suspend fun install(entry: RepoPlugin): Result<InstalledPlugin> = runCatchingIo {
        val script = if (entry.repo == BUNDLED) readAsset(entry.url) else download(entry.url, MAX_SCRIPT_BYTES)
        val plugin = installScript(
            script,
            scriptUrl = entry.url.takeIf { entry.repo != BUNDLED },
            expectedId = entry.id,
            iconUrl = entry.iconUrl,
            firstPartyOrigin = entry.repo == BUNDLED || entry.repo == OFFICIAL_REPO,
        )
        setDeclined(entry.id, false)
        plugin
    }

    /** Installs a script the user picked from storage (handy while writing a plugin). */
    suspend fun installFromScript(script: String): Result<InstalledPlugin> = runCatchingIo {
        if (script.length > MAX_SCRIPT_BYTES) throw IOException("Script is larger than 2 MB")
        installScript(script, scriptUrl = null, expectedId = null, iconUrl = null, firstPartyOrigin = false)
    }

    suspend fun uninstall(pluginId: String): Result<Unit> = mutex.withLock {
        runCatchingIo {
            val plugin = _installed.value.find { it.id == pluginId } ?: return@runCatchingIo
            if (SourceManager.getAllSources().size <= 1) {
                throw IllegalStateException("Can't remove the last source")
            }
            SourceManager.unregisterSource(plugin.sourceId)
            // Don't quietly reinstall a bundled plugin the user removed.
            if (bundled.any { it.id == pluginId }) setDeclined(pluginId, true)
            scriptFile(plugin).delete()
            _installed.value = _installed.value.filter { it.id != pluginId }
            writeInstalled()
        }
    }

    /** Repository entries that are newer than the installed copy, keyed by plugin id. */
    fun updatesAvailable(): Map<String, RepoPlugin> {
        val byId = _available.value.associateBy { it.id }
        return _installed.value.mapNotNull { p ->
            val remote = byId[p.id] ?: return@mapNotNull null
            if (compareVersions(remote.version, p.version) > 0) p.id to remote else null
        }.toMap()
    }

    private suspend fun installScript(
        script: String,
        scriptUrl: String?,
        expectedId: String?,
        iconUrl: String?,
        /** From the bundle or the official repository: may use a built-in source's id. */
        firstPartyOrigin: Boolean,
    ): InstalledPlugin = mutex.withLock {
        // Evaluating it is the validation: syntax, exports, required functions.
        val info = JsPlugin.load(script, "plugin.js", http).info
        if (expectedId != null && info.id != expectedId) {
            throw PluginException("Script declares id '${info.id}', repository says '$expectedId'")
        }
        if (!firstPartyOrigin && info.id in firstPartyIds()) {
            throw PluginException("'${info.id}' is the id of a built-in source; a plugin from elsewhere can't replace it")
        }
        if (sourceIdFor(info.id) == "js-") throw PluginException("Plugin id '${info.id}' has no letters or digits")
        val legacy = if (firstPartyOrigin) legacySourceIdFor(info.id) else null
        val sourceId = legacy ?: sourceIdFor(info.id)
        val clash = _installed.value.find { it.sourceId == sourceId && it.id != info.id }
        if (clash != null) {
            throw PluginException("Plugin id '${info.id}' collides with installed plugin '${clash.id}'")
        }

        val plugin = InstalledPlugin(
            id = info.id,
            name = info.name,
            version = info.version,
            site = info.site,
            lang = info.lang,
            iconUrl = iconUrl ?: info.icon,
            scriptUrl = scriptUrl,
            // An update keeps the id it replaced; only the bundle can grant one.
            legacySourceId = legacy,
        )
        writeScript(plugin, script)
        _installed.value = _installed.value.filter { it.id != info.id } + plugin
        writeInstalled()
        register(plugin)
        plugin
    }

    private fun register(plugin: InstalledPlugin): JsPluginSource =
        JsPluginSource(plugin, scriptFile(plugin), http)
            .also { SourceManager.registerSource(it) }

    /**
     * Named after the source id the plugin registers under, so two
     * plugins can only share a file if they would share a source, which
     * the clash check prevents. ("royalroad.js" for the bundled Royal Road,
     * "js-<id>.js" for everything else; both are filesystem-safe.)
     */
    private fun scriptFile(plugin: InstalledPlugin) = File(pluginDir, plugin.sourceId + ".js")

    /** Writes a plugin's script atomically (temp file, then rename). */
    private fun writeScript(plugin: InstalledPlugin, script: String) {
        pluginDir.mkdirs()
        val target = scriptFile(plugin)
        val tmp = File(pluginDir, target.name + ".tmp")
        tmp.writeText(script)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not write ${target.name}")
        }
    }

    private fun firstPartyIds(): Set<String> = bundled.map { it.id }.toSet() + OFFICIAL_ONLY.keys

    private fun legacySourceIdFor(pluginId: String): String? =
        bundled.find { it.id == pluginId }?.legacySourceId
            ?: _installed.value.find { it.id == pluginId }?.legacySourceId
            ?: OFFICIAL_ONLY[pluginId]

    /**
     * Keeps NovelForge's own plugins current between app releases: at most
     * every 12 hours, re-reads the repositories and installs any newer
     * version of a first-party plugin that the official repository offers.
     * Third-party plugins are never updated without the user.
     */
    private suspend fun autoUpdateFirstParty() {
        if (OFFICIAL_REPO !in _repos.value) return
        val now = System.currentTimeMillis()
        val last = prefs().getLong(PREF_LAST_AUTO_UPDATE, 0L)
        if (now - last in 0 until AUTO_UPDATE_INTERVAL_MS) return

        // Offline, or the repository is down: try again on the next start.
        if (refreshRepos().any { it.startsWith(OFFICIAL_REPO) }) return
        prefs().edit().putLong(PREF_LAST_AUTO_UPDATE, now).apply()
        val firstParty = firstPartyIds()
        for ((id, remote) in updatesAvailable()) {
            if (id !in firstParty || remote.repo != OFFICIAL_REPO) continue
            install(remote)
                .onSuccess { Logger.d(TAG, "auto-updated $id to ${it.version}") }
                .onFailure { Logger.e(TAG, "auto-update of $id failed: ${it.message}") }
        }
    }

    // ── Bundled plugins ─────────────────────────────────────────────────

    private fun readAsset(name: String): String =
        appContext.assets.open("$ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }

    private fun readBundledIndex(): List<RepoPlugin> = try {
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        gson.fromJson<List<Map<String, Any?>>>(readAsset("index.json"), type).orEmpty().mapNotNull { e ->
            fun s(k: String) = (e[k] as? String)?.trim()?.ifEmpty { null }
            val id = s("id") ?: return@mapNotNull null
            val legacy = s("sourceId")
            if (legacy != null && legacy !in LEGACY_SOURCES) {
                Logger.e(TAG, "bundled plugin $id claims unknown sourceId $legacy; ignored")
                return@mapNotNull null
            }
            RepoPlugin(
                id = id,
                name = s("name") ?: id,
                version = s("version") ?: "0",
                site = s("site") ?: "",
                lang = s("lang") ?: "en",
                url = s("url") ?: return@mapNotNull null,
                iconUrl = s("iconUrl"),
                repo = BUNDLED,
                legacySourceId = legacy,
            )
        }
    } catch (e: Exception) {
        Logger.e(TAG, "bundled index unreadable: ${e.message}")
        emptyList()
    }

    /**
     * Installs bundled plugins that are missing (and not removed by the user)
     * or older than the bundled copy. Trusts the bundled index for metadata
     * instead of evaluating each script at startup; BundledPluginsTest checks
     * that the two agree.
     */
    private fun syncBundled() {
        val declined = declinedIds()
        var changed = false
        for (entry in bundled) {
            val current = _installed.value.find { it.id == entry.id }
            val wanted = if (current == null) entry.id !in declined
                else compareVersions(entry.version, current.version) > 0
            if (!wanted) continue
            try {
                val plugin = InstalledPlugin(
                    id = entry.id,
                    name = entry.name,
                    version = entry.version,
                    site = entry.site,
                    lang = entry.lang,
                    iconUrl = entry.iconUrl,
                    scriptUrl = null,
                    legacySourceId = entry.legacySourceId,
                )
                writeScript(plugin, readAsset(entry.url))
                _installed.value = _installed.value.filter { it.id != entry.id } + plugin
                changed = true
            } catch (e: Exception) {
                Logger.e(TAG, "bundled plugin ${entry.id} failed to install: ${e.message}")
            }
        }
        if (changed) writeInstalled()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun declinedIds(): Set<String> = prefs().getStringSet(PREF_DECLINED, emptySet()).orEmpty()

    private fun setDeclined(pluginId: String, declined: Boolean) {
        val current = declinedIds()
        val updated = if (declined) current + pluginId else current - pluginId
        if (updated != current) prefs().edit().putStringSet(PREF_DECLINED, updated).apply()
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private fun readInstalled(): List<InstalledPlugin> = try {
        if (!installedFile.exists()) emptyList()
        else {
            val type = object : TypeToken<List<InstalledPlugin>>() {}.type
            gson.fromJson<List<InstalledPlugin>>(installedFile.readText(), type).orEmpty()
                .filter { scriptFile(it).exists() }
        }
    } catch (e: Exception) {
        Logger.e(TAG, "installed.json unreadable: ${e.message}")
        emptyList()
    }

    private fun writeInstalled() {
        pluginDir.mkdirs()
        val tmp = File(pluginDir, "installed.json.tmp")
        tmp.writeText(gson.toJson(_installed.value))
        if (!tmp.renameTo(installedFile)) throw IOException("Could not write installed.json")
    }

    private fun loadRepos(): List<String> {
        // Never saved: the official repository. Once the user edits the list
        // (even to remove it), their list is used.
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_REPOS, null)
            ?: return listOf(OFFICIAL_REPO)
        return runCatching {
            gson.fromJson<List<String>>(raw, object : TypeToken<List<String>>() {}.type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun saveRepos() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_REPOS, gson.toJson(_repos.value)).apply()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun <T> runCatchingIo(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "install failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun download(url: String, limit: Long): String {
        val request = Request.Builder().url(url).header("User-Agent", DEFAULT_USER_AGENT).build()
        SourceHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return readLimited(response.body ?: throw IOException("empty response"), limit)
        }
    }

    /** Numeric, dot-separated comparison; non-numeric parts compare as 0. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

internal const val DEFAULT_USER_AGENT = USER_AGENT

private fun readLimited(body: okhttp3.ResponseBody, limit: Long): String {
    val source = body.source()
    if (source.request(limit + 1)) throw IOException("response larger than ${limit / 1024 / 1024} MB")
    return body.string()
}

/**
 * fetch() for plugins, on the shared source client (retries, timeouts,
 * pooling) plus CloudflareInterceptor: when a response is a Cloudflare
 * challenge, a hidden WebView solves it and the request is replayed.
 */
private class OkHttpPluginHttp : PluginHttp {

    private val client by lazy {
        SourceHttp.client.newBuilder().addInterceptor(CloudflareInterceptor()).build()
    }

    override fun execute(request: PluginRequest): PluginResponse {
        val url = request.url.toHttpUrlOrNull() ?: throw IOException("invalid URL: ${request.url}")
        val builder = Request.Builder().url(url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        if (request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", DEFAULT_USER_AGENT)
        }

        val method = request.method
        val body = if (method == "GET" || method == "HEAD") null else {
            val type = request.headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                ?.toMediaTypeOrNull()
            (request.body ?: "").toRequestBody(type)
        }
        builder.method(method, body)

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.let { readLimited(it, MAX_BODY_BYTES) } ?: ""
            return PluginResponse(
                status = response.code,
                url = response.request.url.toString(),
                body = text,
                headers = response.headers.names().associate { name ->
                    name.lowercase() to response.headers.values(name).joinToString(", ")
                },
            )
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 10L * 1024 * 1024
    }
}
