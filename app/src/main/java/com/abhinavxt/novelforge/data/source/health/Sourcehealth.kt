package com.abhinavxt.novelforge.data.source.health

import android.content.Context
import com.abhinavxt.novelforge.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Health status of a single source, as determined by the periodic probe in
 * SourceHealthWorker. Persisted as JSON in filesDir (no Room migration needed)
 * and exposed as a StateFlow so any screen can render live status badges.
 */
enum class HealthStatus {
    /** Never probed (fresh install, or source added after last check). */
    UNKNOWN,

    /** Base URL reachable and returned a normal response. */
    UP,

    /**
     * Site is alive but behind an active Cloudflare challenge (403/503 with
     * cf markers). Sources using the NfCloudflare layer may still work, so
     * this is "degraded", not dead.
     */
    CLOUDFLARE,

    /** Timeout, DNS failure, connection refused, 5xx, or broken base page. */
    DOWN,
}

data class SourceHealth(
    val sourceId: String,
    val status: HealthStatus = HealthStatus.UNKNOWN,
    val latencyMs: Long = -1,
    val checkedAt: Long = 0L,
    /**
     * Number of consecutive probes that came back DOWN. UI should only badge
     * a source as dead at >= 2 to avoid flapping on a single bad probe.
     */
    val consecutiveFailures: Int = 0,
) {
    /** True when the UI should visibly flag this source as unavailable. */
    val isEffectivelyDown: Boolean
        get() = status == HealthStatus.DOWN && consecutiveFailures >= 2
}

/**
 * In-memory cache + JSON-file persistence for source health results.
 *
 * Deliberately not a Room table: the data is tiny (~35 rows), fully
 * replaceable, and adding it to AppDatabase would force a schema migration
 * for throwaway diagnostics. If it ever needs querying/joining, promote it.
 */
object SourceHealthStore {

    private const val TAG = "SourceHealthStore"
    private const val FILE_NAME = "source_health.json"

    private val gson = Gson()
    private val lock = Any()

    private val _health = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())
    val health: StateFlow<Map<String, SourceHealth>> = _health.asStateFlow()

    @Volatile
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /**
     * Load persisted results into the StateFlow. Cheap and idempotent; call
     * from Application.onCreate() or lazily before first read.
     * Must be called off the main thread (file I/O).
     */
    fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            try {
                val f = file(context.applicationContext)
                if (f.exists()) {
                    val type = object : TypeToken<Map<String, SourceHealth>>() {}.type
                    val map: Map<String, SourceHealth>? = gson.fromJson(f.readText(), type)
                    if (map != null) _health.value = map
                }
            } catch (e: Exception) {
                // Corrupt file — start fresh rather than crash.
                Logger.e(TAG, "Failed to load health file: ${e.message}")
            }
            loaded = true
        }
    }

    /** Get the last known health for one source (UNKNOWN if never probed). */
    fun get(sourceId: String): SourceHealth =
        _health.value[sourceId] ?: SourceHealth(sourceId)

    /**
     * Replace results for the given sources and persist. Sources not present
     * in [results] keep their previous entry (so a partial/cancelled probe
     * run doesn't wipe good data).
     * Must be called off the main thread (file I/O).
     */
    fun update(context: Context, results: List<SourceHealth>) {
        synchronized(lock) {
            val merged = _health.value.toMutableMap()
            results.forEach { merged[it.sourceId] = it }
            _health.value = merged
            try {
                file(context.applicationContext).writeText(gson.toJson(merged))
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to persist health file: ${e.message}")
            }
        }
    }
}