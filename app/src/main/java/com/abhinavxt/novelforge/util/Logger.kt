package com.abhinavxt.novelforge.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Logger utility that only logs in debug builds.
 * Initialize in Application class: Logger.init(this)
 *
 * Usage: Logger.d("Tag", "Message") - same as Logger.d()
 */
object Logger {
    private const val DEFAULT_TAG = "NovelForge"
    private var isDebug: Boolean = false

    fun init(context: Context) {
        isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun d(tag: String, message: String) {
        if (isDebug) Log.d(tag, message)
    }

    fun d(message: String) {
        if (isDebug) Log.d(DEFAULT_TAG, message)
    }

    fun e(tag: String, message: String) {
        if (isDebug) Log.e(tag, message)
    }

    fun e(message: String) {
        if (isDebug) Log.e(DEFAULT_TAG, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (isDebug) Log.e(tag, message, throwable)
    }

    fun e(message: String, throwable: Throwable) {
        if (isDebug) Log.e(DEFAULT_TAG, message, throwable)
    }

    fun i(tag: String, message: String) {
        if (isDebug) Log.i(tag, message)
    }

    fun i(message: String) {
        if (isDebug) Log.i(DEFAULT_TAG, message)
    }

    fun w(tag: String, message: String) {
        if (isDebug) Log.w(tag, message)
    }

    fun w(message: String) {
        if (isDebug) Log.w(DEFAULT_TAG, message)
    }

    // Throwable overloads, mirroring e(). Without these, callers that had an
    // exception in hand could only log e.message -- and several exception types
    // worth seeing here (NetworkOnMainThreadException among them) carry a null
    // message, so those failures logged as literally nothing.
    fun w(tag: String, message: String, throwable: Throwable) {
        if (isDebug) Log.w(tag, message, throwable)
    }

    fun w(message: String, throwable: Throwable) {
        if (isDebug) Log.w(DEFAULT_TAG, message, throwable)
    }

    fun v(tag: String, message: String) {
        if (isDebug) Log.v(tag, message)
    }

    fun v(message: String) {
        if (isDebug) Log.v(DEFAULT_TAG, message)
    }
}