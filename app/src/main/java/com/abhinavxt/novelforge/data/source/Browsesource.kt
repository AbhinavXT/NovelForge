package com.abhinavxt.novelforge.data.source

import com.abhinavxt.novelforge.data.model.NovelPreview

/**
 * One selectable value inside a browse filter, e.g. ("Fantasy", "fantasy")
 * or ("Popular", "popular-novel"). `label` is what the user sees; `value`
 * is what the provider's URL builder expects.
 */
data class FilterOption(
    val label: String,
    val value: String,
)

/**
 * The filter surface a source offers for catalog browsing. Mirrors the
 * QuickNovel MainAPI trio (mainCategories / orderBys / tags); any list may be
 * empty, in which case the UI hides that filter.
 */
data class BrowseFilters(
    val categories: List<FilterOption> = emptyList(),
    val orderBys: List<FilterOption> = emptyList(),
    val tags: List<FilterOption> = emptyList(),
) {
    val isEmpty: Boolean
        get() = categories.isEmpty() && orderBys.isEmpty() && tags.isEmpty()
}

/**
 * Optional capability on top of [Source]: a filterable, paginated catalog.
 *
 * Plain sources are still browsable through [Source.getPopular] (no filters);
 * this interface adds genre/category/sort filtering for sources that support
 * it — currently all NfSourceAdapter-backed sources whose provider has a main
 * page. Native sources can adopt it later without any UI changes.
 */
interface BrowseSource : Source {

    /** False when the underlying provider has no main page; the browse UI
     *  then falls back to [Source.getPopular]. */
    val canBrowse: Boolean

    val filters: BrowseFilters

    /**
     * Load one catalog page. Any filter value may be null = provider default.
     * Pages are 1-based (QuickNovel convention). An empty list means the
     * catalog is exhausted.
     */
    suspend fun browse(
        page: Int,
        category: String?,
        orderBy: String?,
        tag: String?,
    ): List<NovelPreview>
}