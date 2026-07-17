package com.abhinavxt.novelforge.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.data.source.health.HealthStatus
import com.abhinavxt.novelforge.data.source.health.SourceHealth
import com.abhinavxt.novelforge.data.source.health.SourceHealthStore
import com.abhinavxt.novelforge.data.source.nf.USER_AGENT
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that probes every registered source's base URL
 * and records the result in [SourceHealthStore].
 *
 * Probe strategy: a single GET of Source.baseUrl with a browser User-Agent and
 * a dedicated no-retry client (SourceManager.sharedClient's RetryInterceptor
 * would triple the cost of a dead host and Thread.sleep() through it).
 *
 * Classification:
 *  - 2xx/3xx                     -> UP
 *  - 403/503 + Cloudflare marker -> CLOUDFLARE (alive, challenge active)
 *  - 429                         -> UP (rate-limited implies alive)
 *  - other 4xx on the base page  -> DOWN (domain answers but site is broken)
 *  - 5xx / IOException / timeout -> DOWN
 *
 * Schedule via [SourceHealthWorker.schedule]; one-shot via [runNow].
 */
class SourceHealthWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Starting source health check...")
            SourceHealthStore.load(context)

            val sources = SourceManager.getAllSources()
            val semaphore = Semaphore(MAX_CONCURRENT_PROBES)

            val results = coroutineScope {
                sources.map { source ->
                    async {
                        semaphore.withPermit { probe(source.id, source.baseUrl) }
                    }
                }.awaitAll()
            }

            SourceHealthStore.update(context, results)

            val down = results.count { it.status == HealthStatus.DOWN }
            val cf = results.count { it.status == HealthStatus.CLOUDFLARE }
            Logger.d(TAG, "Health check done: ${results.size} probed, $down down, $cf cloudflare")
            Result.success()
        } catch (e: Exception) {
            Logger.e(TAG, "Health check failed: ${e.message}")
            Result.retry()
        }
    }

    private fun probe(sourceId: String, baseUrl: String): SourceHealth {
        val previous = SourceHealthStore.get(sourceId)
        val start = System.currentTimeMillis()

        val status: HealthStatus = try {
            val request = Request.Builder()
                .url(baseUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()

            probeClient.newCall(request).execute().use { response ->
                classify(response.code, response.header("server"), response.header("cf-ray"))
            }
        } catch (e: IOException) {
            HealthStatus.DOWN
        } catch (e: IllegalArgumentException) {
            // Malformed baseUrl — a source bug, not a network condition.
            Logger.e(TAG, "Bad baseUrl for $sourceId: $baseUrl")
            HealthStatus.DOWN
        }

        val latency = System.currentTimeMillis() - start
        return SourceHealth(
            sourceId = sourceId,
            status = status,
            latencyMs = if (status == HealthStatus.DOWN) -1 else latency,
            checkedAt = System.currentTimeMillis(),
            consecutiveFailures =
                if (status == HealthStatus.DOWN) previous.consecutiveFailures + 1 else 0,
        )
    }

    private fun classify(code: Int, serverHeader: String?, cfRay: String?): HealthStatus {
        val isCloudflare = cfRay != null ||
                serverHeader?.contains("cloudflare", ignoreCase = true) == true
        return when {
            code in 200..399 -> HealthStatus.UP
            code == 429 -> HealthStatus.UP
            (code == 403 || code == 503) && isCloudflare -> HealthStatus.CLOUDFLARE
            else -> HealthStatus.DOWN
        }
    }

    companion object {
        private const val TAG = "SourceHealthWorker"
        private const val WORK_NAME = "source_health_check"
        private const val MAX_CONCURRENT_PROBES = 6

        /**
         * Dedicated probe client: short timeouts, no retry interceptor,
         * follows redirects (many sources 301 http->https or to www).
         */
        private val probeClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /**
         * Schedule the periodic health check.
         * @param intervalHours how often to probe. Clamped to >= 6h — probing
         *        more often burns battery for information that barely changes.
         */
        fun schedule(context: Context, enabled: Boolean, intervalHours: Long = 24) {
            val workManager = WorkManager.getInstance(context)

            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                Logger.d(TAG, "Source health check disabled")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val interval = intervalHours.coerceAtLeast(6)

            val request = PeriodicWorkRequestBuilder<SourceHealthWorker>(
                interval, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            Logger.d(TAG, "Source health check scheduled every ${interval}h")
        }

        /** Run a one-time check immediately (e.g. "Check now" in Settings). */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SourceHealthWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Logger.d(TAG, "One-time source health check enqueued")
        }
    }
}