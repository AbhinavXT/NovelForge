package com.abhinavxt.novelforge.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.source.plugin.InstalledPlugin
import com.abhinavxt.novelforge.data.source.plugin.PluginManager
import com.abhinavxt.novelforge.data.source.plugin.RepoPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PluginsViewModel(app: Application) : AndroidViewModel(app) {

    val repos: StateFlow<List<String>> = PluginManager.repos
    val installed: StateFlow<List<InstalledPlugin>> = PluginManager.installed

    /** Plugin id -> newer repository entry. */
    val updates: StateFlow<Map<String, RepoPlugin>> =
        combine(PluginManager.installed, PluginManager.available) { _, _ -> PluginManager.updatesAvailable() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Repository plugins that are not installed yet, filtered by the search box. */
    val available: StateFlow<List<RepoPlugin>> =
        combine(PluginManager.available, PluginManager.installed, _query) { all, inst, q ->
            val installedIds = inst.map { it.id }.toSet()
            all.filter { it.id !in installedIds }
                .filter { q.isBlank() || it.name.contains(q, true) || it.lang.contains(q, true) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** Plugin ids with an install/update/uninstall in flight, plus REFRESHING. */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // "Available" always holds the bundled plugins; refresh when it has
        // nothing from the repositories yet.
        val fromRepos = PluginManager.available.value.any { it.repo != PluginManager.BUNDLED }
        if (!fromRepos && PluginManager.repos.value.isNotEmpty()) refresh()
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun refresh() = track(REFRESHING) {
        val errors = PluginManager.refreshRepos()
        if (errors.isNotEmpty()) _messages.emit("Some repositories failed: ${errors.joinToString("; ")}")
    }

    fun addRepo(url: String) {
        PluginManager.addRepo(url)
            .onSuccess { refresh() }
            .onFailure { _messages.tryEmit(it.message ?: "Invalid URL") }
    }

    fun removeRepo(url: String) = PluginManager.removeRepo(url)

    fun install(entry: RepoPlugin) = track(entry.id) {
        PluginManager.install(entry)
            .onSuccess { _messages.emit("Installed ${it.name} ${it.version}") }
            .onFailure { _messages.emit("Couldn't install ${entry.name}: ${it.message}") }
    }

    fun uninstall(plugin: InstalledPlugin) = track(plugin.id) {
        PluginManager.uninstall(plugin.id)
            .onSuccess { _messages.emit("Removed ${plugin.name}") }
            .onFailure { _messages.emit("Couldn't remove ${plugin.name}: ${it.message}") }
    }

    fun installFromFile(uri: Uri) = track(FROM_FILE) {
        val script = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
        if (script == null) {
            _messages.emit("Couldn't read that file")
            return@track
        }
        PluginManager.installFromScript(script)
            .onSuccess { _messages.emit("Installed ${it.name} ${it.version}") }
            .onFailure { _messages.emit("Not a valid plugin: ${it.message}") }
    }

    private fun track(key: String, block: suspend () -> Unit) {
        if (key in _busy.value) return
        _busy.value = _busy.value + key
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = _busy.value - key
            }
        }
    }

    companion object {
        const val REFRESHING = "\u0000refresh"
        const val FROM_FILE = "\u0000file"
    }
}
