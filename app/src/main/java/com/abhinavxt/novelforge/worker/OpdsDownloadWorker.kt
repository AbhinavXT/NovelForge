package com.abhinavxt.novelforge.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.abhinavxt.novelforge.data.opds.OpdsAcquisition
import com.abhinavxt.novelforge.data.opds.OpdsCatalogStore
import com.abhinavxt.novelforge.data.opds.OpdsClient
import com.abhinavxt.novelforge.data.opds.OpdsException
import com.abhinavxt.novelforge.data.opds.OpdsParser
import java.io.File
import java.security.MessageDigest

/**
 * Downloads one book from an OPDS catalog and imports it.
 *
 * The request carries the catalog's id, not its credentials: WorkManager
 * keeps input data in its own database, and a password has no business
 * there. The catalog is looked up again when the work runs.
 */
class OpdsDownloadWorker(
    context: Context,
    params: WorkerParameters
) : BookDownloadWorker(context, params) {

    private val url = inputData.getString(KEY_URL).orEmpty()

    override val bookKey: String = keyFor(url)

    override suspend fun fetch(dest: File, onProgress: (Long, Long?) -> Unit) {
        val catalogId = inputData.getString(KEY_CATALOG).orEmpty()
        val catalog = OpdsCatalogStore(applicationContext).get(catalogId)
            ?: throw OpdsException("That catalog has been removed.")
        val acquisition = OpdsAcquisition(
            url = url,
            type = OpdsParser.EPUB_TYPE,
            length = inputData.getLong(KEY_LENGTH, -1L).takeIf { it > 0 }
        )
        OpdsClient().download(catalog, acquisition, dest, onProgress)
    }

    companion object {
        /** Every OPDS download carries this tag, and `<tag>:<key>` (see [keyFor]). */
        const val TAG_DOWNLOAD = "opds_download"
        private const val KEY_CATALOG = "catalog_id"
        private const val KEY_URL = "url"
        private const val KEY_LENGTH = "length"

        /** A download is identified by its file's address. */
        fun keyFor(url: String): String =
            MessageDigest.getInstance("MD5").digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** KEEP leaves a queued or running download alone and replaces a failed one. */
        fun enqueue(context: Context, catalogId: String, title: String, acquisition: OpdsAcquisition) {
            val key = keyFor(acquisition.url)
            val request = OneTimeWorkRequestBuilder<OpdsDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_CATALOG to catalogId,
                        KEY_URL to acquisition.url,
                        KEY_LENGTH to (acquisition.length ?: -1L),
                        KEY_TITLE to title
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG_DOWNLOAD)
                .addTag("$TAG_DOWNLOAD:$key")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("opds_download_$key", ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, url: String) {
            WorkManager.getInstance(context).cancelUniqueWork("opds_download_${keyFor(url)}")
        }
    }
}
