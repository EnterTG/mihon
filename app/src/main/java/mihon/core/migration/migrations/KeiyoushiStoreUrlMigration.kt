package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.data.extension.service.ExtensionStoreUrlResolver
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.system.logcat

/**
 * Rewrites saved store addresses that no longer serve a usable index (the Keiyoushi website
 * address, its retired legacy index, or repo metadata pages) to the index the store serves
 * today, so existing installs pick up extension updates again without the user fixing the
 * address by hand. Pure string rewrite, so it also works offline; the next store refresh
 * pulls the real metadata.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class KeiyoushiStoreUrlMigration(
    private val repository: ExtensionStoreRepository,
) : Migration {
    override val version: Float = 35f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        for (store in repository.getAll()) {
            val resolved = ExtensionStoreUrlResolver.resolve(store.indexUrl)
            if (resolved == store.indexUrl) continue

            try {
                repository.upsert(
                    store.copy(
                        indexUrl = resolved,
                        // The resolved address is a v2 index, not a legacy repo
                        isLegacy = false,
                        extensionListUrl = null,
                    ),
                )
                repository.remove(store.indexUrl)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) {
                    "Error migrating extension store URL: ${store.indexUrl}"
                }
            }
        }
        return@withContext true
    }
}
