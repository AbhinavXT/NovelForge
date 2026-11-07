package com.abhinavxt.novelforge.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.abhinavxt.novelforge.data.annas.AnnasArchiveClient
import com.abhinavxt.novelforge.data.annas.AnnasArchiveException
import com.abhinavxt.novelforge.data.annas.AnnasBook
import java.io.File

/**
 * Downloads one Anna's Archive book by MD5 and imports it. One unique work
 * per MD5, so tapping the same book twice doesn't fetch it twice.
 */
class AnnasDownloadWorker(
    context: Context,
    params: WorkerParameters
) : BookDownloadWorker(context, params) {

    override val bookKey: String = inputData.getString(KEY_MD5).orEmpty()

    override suspend fun fetch(dest: File, onProgress: (Long, Long?) -> Unit) {
        if (bookKey.length != 32) throw AnnasArchiveException("Invalid book.")
        val book = AnnasBook(md5 = bookKey, title = displayTitle, format = "epub")
        AnnasArchiveClient(applicationContext).download(book, dest, onProgress)
    }

    companion object {
        /** Every Anna's Archive download carries this tag, and `<tag>:<md5>`. */
        const val TAG_DOWNLOAD = "annas_download"
        const val KEY_MD5 = "md5"

        fun workName(md5: String) = "annas_download_$md5"

        /**
         * Queues [book]. KEEP only keeps work that hasn't finished: a queued
         * or running download stays, a failed one is replaced by this attempt.
         */
        fun enqueue(context: Context, book: AnnasBook) {
            val request = OneTimeWorkRequestBuilder<AnnasDownloadWorker>()
                .setInputData(workDataOf(KEY_MD5 to book.md5, KEY_TITLE to book.title))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG_DOWNLOAD)
                .addTag("$TAG_DOWNLOAD:${book.md5}")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(book.md5), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, md5: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(md5))
        }
    }
}
