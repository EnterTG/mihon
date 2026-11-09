package tachiyomi.data.manga

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.manga.repository.MangaSourceLinkRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MangaSourceLinkRepositoryImpl(
    private val database: Database,
) : MangaSourceLinkRepository {
    override suspend fun linkedIds(primaryId: Long): List<Long> =
        database.manga_source_linkQueries.getLinkedMangaIds(primaryId).awaitAsList()

    override suspend fun primaryId(mirrorId: Long): Long? =
        database.manga_source_linkQueries.getPrimaryMangaId(mirrorId).awaitAsOneOrNull()

    override suspend fun link(primaryId: Long, mirrorId: Long) {
        database.manga_source_linkQueries.insertLink(primaryId, mirrorId)
    }

    override suspend fun unlink(primaryId: Long, mirrorId: Long) {
        database.manga_source_linkQueries.removeLink(primaryId, mirrorId)
    }
}
