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

import android.content.Context
import com.abhinavxt.novelforge.data.source.SourceHttp
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
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
) {
    val sourceId: String get() = PluginManager.sourceIdFor(id)
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
)

object PluginManager {

    private const val TAG = "PluginManager"
    private const val PREFS = "source_plugins"
    private const val PREF_REPOS = "repos"
    private const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
    private const val MAX_INDEX_BYTES = 4L * 1024 * 1024

    private val gson = Gson()
    private val mutex = Mutex()

    private lateinit var appContext: Context
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

    /**
     * Registers every installed plugin with SourceManager. Synchronous on
     * purpose: it only reads one small JSON file (scripts compile lazily), and
     * doing it before the first screen means a novel from a plugin source can
     * be opened straight away on a cold start.
     */
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _repos.value = loadRepos()
        _installed.value = readInstalled()
        _installed.value.forEach { register(it) }
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
        // First repo wins when two list the same plugin id.
        _available.value = all.distinctBy { it.id }.sortedBy { it.name.lowercase() }
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
        val script = download(entry.url, MAX_SCRIPT_BYTES)
        installScript(script, scriptUrl = entry.url, expectedId = entry.id, iconUrl = entry.iconUrl)
    }

    /** Installs a script the user picked from storage (handy while writing a plugin). */
    suspend fun installFromScript(script: String): Result<InstalledPlugin> = runCatchingIo {
        if (script.length > MAX_SCRIPT_BYTES) throw IOException("Script is larger than 2 MB")
        installScript(script, scriptUrl = null, expectedId = null, iconUrl = null)
    }

    suspend fun uninstall(pluginId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val plugin = _installed.value.find { it.id == pluginId } ?: return@withContext
            SourceManager.unregisterSource(plugin.sourceId)
            File(pluginDir, scriptName(plugin.id)).delete()
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
    ): InstalledPlugin = mutex.withLock {
        // Evaluating it is the validation: syntax, exports, required functions.
        val info = JsPlugin.load(script, "plugin.js", http).info
        if (expectedId != null && info.id != expectedId) {
            throw PluginException("Script declares id '${info.id}', repository says '$expectedId'")
        }
        val sourceId = sourceIdFor(info.id)
        if (sourceId == "js-") throw PluginException("Plugin id '${info.id}' has no letters or digits")
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
        )
        pluginDir.mkdirs()
        val target = File(pluginDir, scriptName(info.id))
        val tmp = File(pluginDir, scriptName(info.id) + ".tmp")
        tmp.writeText(script)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not write ${target.name}")
        }
        _installed.value = _installed.value.filter { it.id != info.id } + plugin
        writeInstalled()
        register(plugin)
        plugin
    }

    private fun register(plugin: InstalledPlugin) {
        SourceManager.registerSource(
            JsPluginSource(plugin, File(pluginDir, scriptName(plugin.id)), http)
        )
    }

    /** File name derived from the source id, so it is always filesystem-safe. */
    private fun scriptName(pluginId: String) = sourceIdFor(pluginId) + ".js"

    // ── Persistence ─────────────────────────────────────────────────────

    private fun readInstalled(): List<InstalledPlugin> = try {
        if (!installedFile.exists()) emptyList()
        else {
            val type = object : TypeToken<List<InstalledPlugin>>() {}.type
            gson.fromJson<List<InstalledPlugin>>(installedFile.readText(), type).orEmpty()
                .filter { File(pluginDir, scriptName(it.id)).exists() }
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
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_REPOS, null)
            ?: return emptyList()
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

internal const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

private fun readLimited(body: okhttp3.ResponseBody, limit: Long): String {
    val source = body.source()
    if (source.request(limit + 1)) throw IOException("response larger than ${limit / 1024 / 1024} MB")
    return body.string()
}

/** fetch() for plugins, on the shared source client (retries, timeouts, pooling). */
private class OkHttpPluginHttp : PluginHttp {

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

        SourceHttp.client.newCall(builder.build()).execute().use { response ->
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
