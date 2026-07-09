package com.abhinavxt.novelforge

/**
 * Central feature flags for the app.
 *
 * Toggle [ONLINE_SOURCES_ENABLED] to switch between:
 *   true  → Full app with Browse, online sources (RoyalRoad, ReadNovelFull),
 *           chapter downloads, and scraping
 *   false → EPUB-only mode. Library is the home screen, users import local
 *           EPUB files. No network scraping, no source picker, no downloads.
 *           Safe for Google Play Store distribution.
 *
 * Usage: if (AppConfig.ONLINE_SOURCES_ENABLED) { ... }
 */
object AppConfig {

    /**
     * Master flag: enable/disable online novel sources.
     * Set to false for Play Store builds.
     */
    const val ONLINE_SOURCES_ENABLED = true
}