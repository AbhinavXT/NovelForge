package com.abhinavxt.novelforge.data.epub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.abhinavxt.novelforge.util.Logger

/**
 * Lasting read access to individual book files the user picked by hand.
 *
 * A file picked through the system picker comes with a grant that dies with
 * the process. That was fine while an import copied everything it needed,
 * but an archived book has to reopen its file weeks later, so a hand-picked
 * book keeps its grant for as long as it stays in the library.
 *
 * Android caps how many of these an app may hold — 128 before Android 11,
 * 512 from it — and every grant counts, including the library folder and
 * the auto-backup folder. [RESERVED] keeps room for those, and once the
 * budget is spent a book simply imports the old way: fully stored, not
 * archivable. Books from the library folder never use this budget; the one
 * folder grant covers all of them.
 */
object BookFileAccess {

    private const val TAG = "BookFileAccess"

    /** Kept free for folder grants, which matter more than any single book. */
    private const val RESERVED = 16

    private val limit: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 512 else 128

    /**
     * Try to keep read access to [uri] across restarts. Returns false when the
     * provider does not offer lasting grants, the budget is spent, or the
     * grant is refused — all of which mean the same to callers: do not record
     * this URI as the book's file.
     */
    fun hold(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return uri.scheme == "file"
        val resolver = context.contentResolver
        if (isHeld(context, uri)) return true
        if (resolver.persistedUriPermissions.size >= limit - RESERVED) {
            Logger.d(TAG, "Grant budget spent; not holding $uri")
            return false
        }
        return try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            // The provider did not mark this grant persistable.
            Logger.d(TAG, "Provider refused a lasting grant for $uri")
            false
        }
    }

    /**
     * Give back the grant on [uri], if this app holds one on exactly that URI.
     * A file inside the library folder is reached through the folder's grant
     * and has none of its own, so this leaves it — and the folder — alone.
     */
    fun release(context: Context, uri: Uri) {
        if (!isHeld(context, uri)) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Logger.e(TAG, "Could not release grant for $uri", e)
        }
    }

    private fun isHeld(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
}
