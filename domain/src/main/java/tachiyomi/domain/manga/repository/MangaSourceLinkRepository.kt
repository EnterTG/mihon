package tachiyomi.domain.manga.repository

interface MangaSourceLinkRepository {
    suspend fun linkedIds(primaryId: Long): List<Long>
    suspend fun primaryId(mirrorId: Long): Long?
    suspend fun link(primaryId: Long, mirrorId: Long)
    suspend fun unlink(primaryId: Long, mirrorId: Long)
}
