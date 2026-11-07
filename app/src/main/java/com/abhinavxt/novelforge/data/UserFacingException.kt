package com.abhinavxt.novelforge.data

/**
 * A failure whose message is written for the person using the app, so it
 * can be shown as-is (a download that can't be found, a sign-in that was
 * refused). Anything else gets a generic message instead.
 */
open class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)
