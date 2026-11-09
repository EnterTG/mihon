package eu.kanade.tachiyomi.data.download

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.first
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import kotlin.math.abs

/**
 * Keeps one primary library entry with any number of additional catalogue mirrors.
 * Each mirror retains its original manga and chapter IDs for its extension.
 */
@Inject
@SingleIn(AppScope::class)
class MangaSourceLinks(
    private val database: Database,
    private val mangaRepository: MangaRepository,
    private val mangaWithChapters: GetMangaWithChapters,
    private val sourceManager: SourceManager,
) {
    data class Candidate(val source: HttpSource, val chapter: Chapter)

    suspend fun linked(primaryId: Long): List<Manga> =
        database.manga_source_linkQueries.getLinkedMangaIds(primaryId)
            .awaitAsList().mapNotNull { id ->
                runCatching { mangaRepository.getMangaById(id) }.getOrNull()
            }

    suspend fun link(primaryId: Long, mirrorId: Long) {
        require(primaryId != mirrorId) { "A source cannot link to itself" }
        val primary = mangaRepository.getMangaById(primaryId)
        val mirror = mangaRepository.getMangaById(mirrorId)
        require(primary.favorite && mirror.favorite) { "Add both entries to the library first" }
        require(primary.source != mirror.source) { "Mirrors must use different source extensions" }
        require(database.manga_source_linkQueries.getPrimaryMangaId(primaryId).awaitAsOneOrNull() == null) {
            "The primary book is already a mirror of another book"
        }
        require(database.manga_source_linkQueries.getPrimaryMangaId(mirrorId).awaitAsOneOrNull() == null) {
            "This book is already linked to another primary book"
        }
        require(database.manga_source_linkQueries.getLinkedMangaIds(mirrorId).awaitAsList().isEmpty()) {
            "A primary book with mirrors cannot become a mirror"
        }
        require(linked(primaryId).none { it.source == mirror.source }) {
            "This source has already been linked"
        }
        database.manga_source_linkQueries.insertLink(primaryId, mirrorId)
    }

    suspend fun unlink(primaryId: Long, mirrorId: Long) {
        database.manga_source_linkQueries.removeLink(primaryId, mirrorId)
    }

    suspend fun candidates(manga: Manga, chapter: Chapter, primary: HttpSource): List<Candidate> {
        val candidates = mutableListOf(Candidate(primary, chapter))
        linked(manga.id).forEach { mirror ->
            val source = sourceManager.get(mirror.source) as? HttpSource ?: return@forEach
            val chapters = runCatching {
                mangaWithChapters.subscribe(mirror.id).first().second
            }.getOrNull().orEmpty()
            val match = chapters.firstOrNull { other -> chaptersMatch(chapter, other) }
            if (match != null) candidates += Candidate(source, match)
        }
        return candidates
    }
}

/** Numbers are authoritative; for unnumbered chapters compare normalized names instead. */
internal fun chaptersMatch(a: Chapter, b: Chapter): Boolean {
    if (a.isRecognizedNumber && b.isRecognizedNumber) {
        return abs(a.chapterNumber - b.chapterNumber) < 0.0001
    }
    if (a.isRecognizedNumber != b.isRecognizedNumber) return false
    fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
    val name = normalize(a.name)
    return name.isNotEmpty() && name == normalize(b.name)
}
