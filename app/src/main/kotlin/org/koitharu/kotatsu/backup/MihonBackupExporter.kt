package org.koitharu.kotatsu.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.sink
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupHistory
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.backup.model.MihonBackupTracking
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.kotatsumigration.data.KotatsuSourceMap
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.util.longHashCode
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonBackupExporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val db: MangaDatabase,
	private val mihonExtensionManager: MihonExtensionManager,
	private val kotatsuSourceMap: KotatsuSourceMap,
) {
	private val proto = ProtoBuf

	suspend fun exportBackup(uri: Uri): String = withContext(Dispatchers.IO) {
		val outputStream = context.contentResolver.openOutputStream(uri)
			?: throw IllegalStateException("Cannot open output stream for $uri")

		outputStream.use { stream ->
			exportBackupToStream(stream)
		}
		uri.toString()
	}

	suspend fun exportBackupToStream(outputStream: OutputStream) = withContext(Dispatchers.IO) {
		val categories = db.getFavouriteCategoriesDao().findAll().filter { it.deletedAt == 0L }
		val categoryIdToOrder = categories.associate { it.categoryId.toLong() to it.sortKey.toLong() }
		val backupCategories = categories.map {
			MihonBackupCategory(
				name = it.title,
				order = it.sortKey.toLong(),
				id = it.categoryId.toLong(),
				flags = 0L,
			)
		}

		val favourites = db.getFavouritesDao().findAllForSync().filter { it.deletedAt == 0L }
		val historyList = db.getHistoryDao().findAllForSync().filter { it.deletedAt == 0L }

		val favMangaIds = favourites.map { it.mangaId }.toSet()
		val historyMangaIds = historyList.map { it.mangaId }.toSet()

		val allMangaIds = (favMangaIds + historyMangaIds)

		val sourcesMap = mutableMapOf<Long, String>()
		val backupMangaList = mutableListOf<MihonBackupManga>()

		for (mangaId in allMangaIds) {
			val mangaWithTags = db.getMangaDao().find(mangaId) ?: continue
			val manga = mangaWithTags.manga
			val tags = mangaWithTags.tags

			val isFavorite = favMangaIds.contains(mangaId)
			val mangaFavEntries = favourites.filter { it.mangaId == mangaId }
			val categoryOrders = mangaFavEntries.mapNotNull { categoryIdToOrder[it.categoryId] }.distinct()

			val (sourceId, sourceName) = resolveMihonSource(manga.source, manga.sourceTitle)
			sourcesMap[sourceId] = sourceName

			val historyEntry = historyList.firstOrNull { it.mangaId == mangaId }
			val chapters = db.getChaptersDao().findAll(mangaId)
			val mangaBookmarks = db.getBookmarksDao().findAll(mangaId)
			val bookmarkedChapterIds = mangaBookmarks.map { it.chapterId }.toSet()

			val historyChapter = historyEntry?.let { h -> chapters.firstOrNull { it.chapterId == h.chapterId } }
			val historyChapterIndex = historyChapter?.index ?: -1

			val backupChapters = chapters.map { chapter ->
				val isRead = when {
					historyEntry != null && chapter.chapterId == historyEntry.chapterId -> historyEntry.percent >= 0.95f
					historyChapterIndex >= 0 && chapter.index < historyChapterIndex -> true
					else -> false
				}
				val lastPageRead = if (historyEntry != null && chapter.chapterId == historyEntry.chapterId) {
					historyEntry.page.toLong().coerceAtLeast(0L)
				} else 0L

				MihonBackupChapter(
					url = chapter.url,
					name = chapter.title,
					scanlator = chapter.scanlator,
					read = isRead,
					bookmark = bookmarkedChapterIds.contains(chapter.chapterId),
					lastPageRead = lastPageRead,
					dateFetch = chapter.uploadDate,
					dateUpload = chapter.uploadDate,
					chapterNumber = chapter.number,
					sourceOrder = chapter.index.toLong(),
					lastModifiedAt = 0L,
					version = 0L,
				)
			}

			val backupHistory = if (historyEntry != null && historyChapter != null && historyEntry.updatedAt > 0) {
				val statsList = db.getStatsDao().findAll(mangaId)
				val duration = statsList.sumOf { it.duration }
				listOf(
					MihonBackupHistory(
						url = historyChapter.url,
						lastRead = historyEntry.updatedAt,
						readDuration = duration,
					),
				)
			} else {
				emptyList()
			}

			val scrobblings = db.getScrobblingDao().findAll(mangaId)
			val backupTracking = scrobblings.mapNotNull { scrobbling ->
				val syncId = scrobblerToMihonTrackerId(scrobbling.scrobbler) ?: return@mapNotNull null
				MihonBackupTracking(
					syncId = syncId,
					libraryId = scrobbling.id.toLong(),
					mediaIdInt = scrobbling.targetId.toInt(),
					trackingUrl = "",
					title = manga.title,
					lastChapterRead = scrobbling.chapter.toFloat(),
					totalChapters = 0,
					score = scrobbling.rating,
					status = encodeTrackingStatus(scrobbling.status),
					startedReadingDate = 0L,
					finishedReadingDate = 0L,
					private = false,
					mediaId = scrobbling.targetId,
				)
			}

			val dateAdded = mangaFavEntries.minOfOrNull { it.createdAt }?.takeIf { it > 0 }
				?: historyEntry?.createdAt?.takeIf { it > 0 }
				?: System.currentTimeMillis()

			backupMangaList.add(
				MihonBackupManga(
					source = sourceId,
					url = manga.url,
					title = manga.title,
					artist = manga.authors,
					author = manga.authors,
					description = manga.description,
					genre = tags.map { it.title },
					status = 0,
					thumbnailUrl = manga.coverUrl,
					dateAdded = dateAdded,
					chapters = backupChapters,
					categories = categoryOrders,
					tracking = backupTracking,
					favorite = isFavorite,
					chapterFlags = 0,
					viewerFlags = null,
					history = backupHistory,
					updateStrategy = 0,
					lastModifiedAt = System.currentTimeMillis(),
					favoriteModifiedAt = null,
					excludedScanlators = emptyList(),
					version = 0L,
					notes = "",
					initialized = true,
				),
			)
		}

		val backupSources = sourcesMap.map { (sourceId, name) ->
			MihonBackupSource(
				name = name,
				sourceId = sourceId,
			)
		}

		val backup = MihonBackup(
			backupManga = backupMangaList,
			backupCategories = backupCategories,
			backupSources = backupSources,
			backupPreferences = emptyList(),
			backupSourcePreferences = emptyList(),
			backupExtensionRepo = emptyList(),
		)

		val byteArray = proto.encodeToByteArray(MihonBackup.serializer(), backup)
		if (byteArray.isEmpty()) {
			throw IllegalStateException("Backup data is empty")
		}

		outputStream.sink().gzip().buffer().use { sink ->
			sink.write(byteArray)
		}
	}

	private suspend fun resolveMihonSource(sourceKey: String, sourceTitle: String?): Pair<Long, String> {
		if (sourceKey.startsWith("MIHON_")) {
			val id = sourceKey.removePrefix("MIHON_").toLongOrNull()
			if (id != null) {
				val mihonSource = mihonExtensionManager.getMihonMangaSourceById(id)
				val name = mihonSource?.displayName ?: sourceTitle ?: "MIHON_$id"
				return Pair(id, name)
			}
		}

		val installedBySource = mihonExtensionManager.getMihonMangaSourceByName(sourceKey)
		if (installedBySource != null) {
			return Pair(installedBySource.sourceId, installedBySource.displayName)
		}

		val target = kotatsuSourceMap.resolve(sourceKey)
		if (target != null) {
			return Pair(target.sourceId, target.sourceName)
		}

		val numericId = sourceKey.toLongOrNull()
		if (numericId != null) {
			val targetById = kotatsuSourceMap.resolveById(numericId)
			if (targetById != null) {
				return Pair(targetById.sourceId, targetById.sourceName)
			}
			return Pair(numericId, sourceTitle ?: "Source $numericId")
		}

		val hashId = sourceKey.longHashCode()
		val name = sourceTitle ?: sourceKey
		return Pair(hashId, name)
	}

	private fun scrobblerToMihonTrackerId(scrobblerId: Int): Int? = when (scrobblerId) {
		3 -> 1 // MAL
		2 -> 2 // AniList
		4 -> 3 // Kitsu
		1 -> 4 // Shikimori
		else -> null
	}

	private fun encodeTrackingStatus(status: String?): Int = when (status) {
		"planned", "plan_to_read" -> 1
		"reading" -> 2
		"completed" -> 3
		"on_hold", "onhold" -> 4
		"dropped" -> 5
		"re_reading", "rereading" -> 6
		else -> 2
	}

	companion object {
		fun getFilename(): String {
			val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
			return "DropSauce_$date.tachibk"
		}
	}
}
