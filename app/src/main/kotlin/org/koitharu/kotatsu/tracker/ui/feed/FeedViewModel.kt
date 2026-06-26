package org.koitharu.kotatsu.tracker.ui.feed

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.model.DateTimeAgo
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.calculateTimeAgo
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.domain.QuickFilterListener
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import org.koitharu.kotatsu.tracker.domain.UpdatesListQuickFilter
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.tracker.work.TrackWorker
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import org.koitharu.kotatsu.tracker.ui.feed.model.UpdatedMangaHeader
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 20
private const val MAX_FEED_SIZE = 200

@HiltViewModel
class FeedViewModel @Inject constructor(
	private val settings: AppSettings,
	private val repository: TrackingRepository,
	private val scheduler: TrackWorker.Scheduler,
	private val mangaListMapper: MangaListMapper,
	private val quickFilter: UpdatesListQuickFilter,
	private val historyRepository: HistoryRepository,
	private val downloadScheduler: DownloadWorker.Scheduler,
	private val db: MangaDatabase,
	@LocalStorageChanges private val localStorageChanges: SharedFlow<LocalManga?>,
	private val localMangaRepository: LocalMangaRepository,
) : BaseViewModel(), QuickFilterListener by quickFilter {

	sealed class DownloadPrompt {
		data class MultipleUpdates(
			val manga: Manga,
			val lastChapterId: Long,
			val allNewChaptersIds: LongArray,
		) : DownloadPrompt() {
			override fun equals(other: Any?): Boolean {
				if (this === other) return true
				if (other !is MultipleUpdates) return false
				if (manga != other.manga) return false
				if (lastChapterId != other.lastChapterId) return false
				return allNewChaptersIds contentEquals other.allNewChaptersIds
			}

			override fun hashCode(): Int {
				var result = manga.hashCode()
				result = 31 * result + lastChapterId.hashCode()
				result = 31 * result + allNewChaptersIds.contentHashCode()
				return result
			}
		}

		data class NoReadHistory(
			val manga: Manga,
			val lastChapterId: Long,
			val allChaptersIds: LongArray,
		) : DownloadPrompt() {
			override fun equals(other: Any?): Boolean {
				if (this === other) return true
				if (other !is NoReadHistory) return false
				if (manga != other.manga) return false
				if (lastChapterId != other.lastChapterId) return false
				return allChaptersIds contentEquals other.allChaptersIds
			}

			override fun hashCode(): Int {
				var result = manga.hashCode()
				result = 31 * result + lastChapterId.hashCode()
				result = 31 * result + allChaptersIds.contentHashCode()
				return result
			}
		}
	}

	private val limit = MutableStateFlow(PAGE_SIZE)
	private val isReady = AtomicBoolean(false)

	val isRunning = scheduler.observeIsRunning()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val isHeaderEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_FEED_HEADER,
		valueProducer = { isFeedHeaderVisible },
	)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val showDownloadPrompt = MutableEventFlow<DownloadPrompt>()

	data class DeleteChapterPrompt(
		val manga: Manga,
		val chapterId: Long,
		val chapterTitle: String,
	)

	val showDeleteChapterPrompt = MutableEventFlow<DeleteChapterPrompt>()

	@Suppress("USELESS_CAST")
	val content = combine(
		quickFilter.appliedOptions,
		combine(limit, quickFilter.appliedOptions.combineWithSettings(), ::Pair)
			.flatMapLatest { repository.observeTrackingLog(it.first, it.second) },
		localStorageChanges.onStart { emit(null) },
	) { filters, list, _ ->
		val result = ArrayList<ListModel>((list.size * 1.4).toInt().coerceAtLeast(3))
		quickFilter.filterItem(filters)?.let(result::add)
		if (list.isEmpty()) {
			result += EmptyState(
				icon = R.drawable.ic_empty_feed,
				textPrimary = R.string.text_empty_holder_primary,
				textSecondary = R.string.text_feed_holder,
				actionStringRes = 0,
			)
		} else {
			isReady.set(true)
			list.mapListTo(result)
		}
		result as List<ListModel>
	}.catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		launchJob(Dispatchers.Default) {
			repository.gc()
		}
	}

	fun clearFeed(clearCounters: Boolean) {
		launchLoadingJob(Dispatchers.Default) {
			repository.clearLogs()
			if (clearCounters) {
				repository.clearCounters()
			}
			onActionDone.call(ReversibleAction(R.string.updates_feed_cleared, null))
		}
	}

	fun requestMoreItems() {
		if (isReady.compareAndSet(true, false)) {
			limit.value = (limit.value + PAGE_SIZE).coerceAtMost(MAX_FEED_SIZE)
		}
	}

	fun update() {
		scheduler.startNow()
	}

	fun setHeaderEnabled(value: Boolean) {
		settings.isFeedHeaderVisible = value
	}

	fun onDownloadClick(item: FeedItem) {
		launchJob(Dispatchers.Default) {
			val manga = item.toMangaWithOverride()
			val fullManga = db.getMangaDao().find(manga.id)?.toManga(
				db.getChaptersDao().findAll(manga.id)
			) ?: return@launchJob

			val chapters = fullManga.chapters
			if (chapters.isNullOrEmpty()) {
				return@launchJob
			}

			val history = historyRepository.getOne(manga)
			val hasNoReadHistory = history == null || history.chapterId == 0L

			if (hasNoReadHistory) {
				val lastChapterId = chapters.lastOrNull()?.id ?: 0L
				val allChaptersIds = chapters.map { it.id }.toLongArray()
				showDownloadPrompt.call(DownloadPrompt.NoReadHistory(fullManga, lastChapterId, allChaptersIds))
			} else {
				val lastReadChapterId = history.chapterId
				val newChapters = chapters.takeLastWhile { it.id != lastReadChapterId }
				if (newChapters.isEmpty()) {
					return@launchJob
				}

				if (newChapters.size > 1) {
					val lastChapterId = newChapters.lastOrNull()?.id ?: 0L
					val allNewChaptersIds = newChapters.map { it.id }.toLongArray()
					showDownloadPrompt.call(DownloadPrompt.MultipleUpdates(fullManga, lastChapterId, allNewChaptersIds))
				} else {
					startDownload(fullManga, longArrayOf(newChapters.first().id))
				}
			}
		}
	}

	fun onDownloadChapterClick(item: FeedItem, chapterId: Long) {
		launchJob(Dispatchers.Default) {
			val manga = item.toMangaWithOverride()
			startDownload(manga, longArrayOf(chapterId))
		}
	}

	fun onDeleteChapterClick(item: FeedItem, chapterId: Long) {
		launchJob(Dispatchers.Default) {
			val manga = item.toMangaWithOverride()
			val chapterTitle = item.chapters.find { it.id == chapterId }?.title ?: ""
			showDeleteChapterPrompt.call(DeleteChapterPrompt(manga, chapterId, chapterTitle))
		}
	}

	fun deleteDownloadedChapter(manga: Manga, chapterId: Long) {
		launchJob(Dispatchers.Default) {
			localMangaRepository.deleteChapters(manga, setOf(chapterId))
		}
	}

	fun startDownload(manga: Manga, chapterIds: LongArray) {
		launchJob(Dispatchers.Default) {
			val task = DownloadTask(
				mangaId = manga.id,
				isPaused = false,
				isSilent = false,
				chaptersIds = chapterIds,
				destination = null,
				format = null,
				allowMeteredNetwork = settings.allowDownloadOnMeteredNetwork != TriStateOption.DISABLED,
			)
			downloadScheduler.schedule(setOf(manga to task))
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
	fun onItemClick(item: FeedItem) {
		launchJob(Dispatchers.Default, CoroutineStart.ATOMIC) {
			repository.markAsRead(item.id)
		}
	}

	private suspend fun List<TrackingLogItem>.mapListTo(destination: MutableList<ListModel>) {
		var prevDate: DateTimeAgo? = null
		for (item in this) {
			val date = calculateTimeAgo(item.createdAt)
			if (prevDate != date) {
				destination += if (date != null) {
					ListHeader(date)
				} else {
					ListHeader(R.string.unknown)
				}
			}
			prevDate = date
			destination += mangaListMapper.toFeedItem(item)
		}
	}

	private fun observeHeader() = isHeaderEnabled.flatMapLatest { hasHeader ->
		if (hasHeader) {
			quickFilter.appliedOptions.combineWithSettings().flatMapLatest {
				repository.observeUpdatedManga(10, it)
			}.map { mangaList ->
				if (mangaList.isEmpty()) {
					null
				} else {
					UpdatedMangaHeader(
						mangaList.map { mangaListMapper.toListModel(it.manga, ListMode.GRID) },
					)
				}
			}
		} else {
			flowOf(null)
		}
	}

	private fun Flow<Set<ListFilterOption>>.combineWithSettings(): Flow<Set<ListFilterOption>> = combine(
		settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
	) { filters, skipNsfw ->
		if (skipNsfw) {
			filters + ListFilterOption.SFW
		} else {
			filters
		}
	}
}
