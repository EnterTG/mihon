package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.remote.LocalChapterMigration
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.i18n.R

class LocalChapterMigrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @Inject lateinit var migration: LocalChapterMigration

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(applicationContext.getString(R.string.remote_migration_title))
            setSmallIcon(android.R.drawable.stat_sys_upload)
            setOngoing(true)
        }.build()
        return ForegroundInfo(
            -204,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    override suspend fun doWork(): Result {
        val graph: AppGraph = applicationContext.metroGraph()
        graph.inject(this)
        return try {
            setForegroundSafely()
            val (migrated, skipped) = migration.run { processed, done, failed ->
                setProgress(workDataOf(PROCESSED to processed, MIGRATED to done, SKIPPED to failed))
            }
            Result.success(workDataOf(MIGRATED to migrated, SKIPPED to skipped))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure()
        }
    }

    companion object {
        private const val NAME = "LocalChapterMigration"
        const val PROCESSED = "processed"
        const val MIGRATED = "migrated"
        const val SKIPPED = "skipped"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<LocalChapterMigrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun state(context: Context): Flow<WorkInfo?> = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(NAME)
            .asFlow()
            .map { infos -> infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull() }
    }
}
