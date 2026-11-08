package com.abhinavxt.novelforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.UserFacingException
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.data.model.NovelPreview
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

sealed interface MigrationState {
    object Searching : MigrationState
    data class Results(val groups: List<SourceResults>) : MigrationState
    object LoadingTarget : MigrationState
    /** What moving would do, shown before anything changes. */
    data class Preview(
        val target: Novel,
        val sourceName: String,
        val oldChapters: Int,
        val matchedChapters: Int,
        val progressFrom: Int?,
        val progressTo: Int?
    ) : MigrationState
    object Migrating : MigrationState
    data class Done(val result: NovelRepository.MigrationResult) : MigrationState
    data class Failed(val message: String) : MigrationState
}

/** Finds the same book on another source and moves a library novel onto it. */
class MigrationViewModel(
    private val repository: NovelRepository,
    private val novelId: String,
    title: String
) : ViewModel() {

    private val _state = MutableStateFlow<MigrationState>(MigrationState.Searching)
    val state: StateFlow<MigrationState> = _state.asStateFlow()

    private val currentSourceId = SourceManager.getSourceFromNovelId(novelId)?.id
    private var job: Job? = null

    init { search(title) }

    fun search(query: String) {
        if (query.isBlank()) return
        job?.cancel()
        _state.value = MigrationState.Searching
        job = viewModelScope.launch {
            val sources = SourceManager.getAllSources().filter { it.id != currentSourceId }
            // Same isolation as the all-sources search: a dead site drops its group, nothing more.
            val results = supervisorScope {
                sources.map { source ->
                    async {
                        try {
                            withTimeoutOrNull(12_000) { repository.searchNovels(query, source) } ?: emptyList()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.e("Migration search failed for ${source.name}", e)
                            emptyList<NovelPreview>()
                        }
                    }
                }.awaitAll()
            }
            _state.value = MigrationState.Results(
                sources.zip(results).filter { it.second.isNotEmpty() }
                    .map { (source, novels) -> SourceResults(source.name, source.id, novels) }
            )
        }
    }

    fun pick(preview: NovelPreview, sourceName: String) {
        job?.cancel()
        _state.value = MigrationState.LoadingTarget
        job = viewModelScope.launch {
            _state.value = try {
                val target = repository.fetchNovelDetails(preview.id, SourceManager.constructNovelUrl(preview.id))
                    ?: throw UserFacingException("Couldn't load that book from $sourceName.")
                val old = repository.getChaptersOnce(novelId)
                val newNumbers = target.chapters.map { it.number }.toSet()
                val progress = repository.getReadingProgress(novelId)
                MigrationState.Preview(
                    target = target,
                    sourceName = sourceName,
                    oldChapters = old.size,
                    matchedChapters = old.count { it.number in newNumbers },
                    progressFrom = progress?.currentChapterNumber,
                    progressTo = progress?.let { p ->
                        com.abhinavxt.novelforge.data.closestChapter(target.chapters, p.currentChapterNumber)?.number
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MigrationState.Failed(failure(e))
            }
        }
    }

    fun migrate(target: Novel) {
        _state.value = MigrationState.Migrating
        viewModelScope.launch {
            _state.value = try {
                MigrationState.Done(repository.migrateNovel(novelId, target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("Migration failed", e)
                MigrationState.Failed(failure(e))
            }
        }
    }

    private fun failure(e: Exception) =
        (e as? UserFacingException)?.message ?: "Something went wrong. Your book was left as it was."

    class Factory(
        private val repository: NovelRepository,
        private val novelId: String,
        private val title: String
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MigrationViewModel(repository, novelId, title) as T
    }
}
