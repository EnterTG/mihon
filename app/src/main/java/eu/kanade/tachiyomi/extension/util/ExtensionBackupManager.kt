package eu.kanade.tachiyomi.extension.util

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Keeps copies of extension apks that were installed before an update, so a broken update can
 * be rolled back. Backups live in the app's private storage and only the newest
 * [MAX_BACKUPS_PER_EXTENSION] are kept per extension.
 */
@Inject
class ExtensionBackupManager(
    private val context: Context,
) {

    private val backupDir: File
        get() = File(context.filesDir, BACKUP_DIR_NAME)

    /**
     * Copies the currently installed apk of [pkgName] into the backup directory. Does nothing
     * when the extension isn't installed yet or its apk can't be read.
     */
    fun backupInstalledExtension(pkgName: String) {
        val packageInfo = ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName) ?: return
        val sourceApk = packageInfo.applicationInfo?.sourceDir?.let(::File)?.takeIf { it.isFile } ?: return
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

        try {
            val target = File(backupDir, backupFileName(pkgName, versionCode))
            if (target.isFile) return
            sourceApk.copyTo(target, overwrite = false)
            pruneBackups(pkgName)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to back up extension $pkgName" }
        }
    }

    fun hasBackup(pkgName: String): Boolean = getBackups(pkgName).isNotEmpty()

    /**
     * The backed up apks of [pkgName], newest version first.
     */
    fun getBackups(pkgName: String): List<File> {
        return backupDir.listFiles()
            ?.mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null
                val versionCode = backupVersionCode(file, pkgName) ?: return@mapNotNull null
                file to versionCode
            }
            ?.sortedByDescending { it.second }
            ?.map { it.first }
            .orEmpty()
    }

    /**
     * Installs the newest backup of [pkgName] as a private extension. When the broken version
     * is installed as a shared (system) package, the restored copy only takes effect once that
     * package is uninstalled; the caller is responsible for that step.
     *
     * @return true when the backup was installed.
     */
    fun restoreExtension(pkgName: String): Boolean {
        val backup = getBackups(pkgName).firstOrNull() ?: return false
        val packageInfo = ExtensionLoader.getArchivePackageInfo(context, backup) ?: return false

        return try {
            ExtensionLoader.installPrivateExtensionFile(context, backup, packageInfo, allowDowngrade = true)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to restore backup of extension $pkgName" }
            false
        }
    }

    fun deleteBackups(pkgName: String) {
        backupDir.listFiles()
            ?.filter { it.isFile && backupVersionCode(it, pkgName) != null }
            ?.forEach { it.delete() }
    }

    private fun pruneBackups(pkgName: String) {
        getBackups(pkgName)
            .drop(MAX_BACKUPS_PER_EXTENSION)
            .forEach { it.delete() }
    }

    private fun backupFileName(pkgName: String, versionCode: Long) = "$pkgName.$versionCode.$BACKUP_FILE_EXTENSION"

    /**
     * The version code encoded in [file]'s name, or null when [file] isn't a backup of [pkgName].
     */
    private fun backupVersionCode(file: File, pkgName: String): Long? {
        return file.name
            .takeIf { it.startsWith("$pkgName.") && it.endsWith(".$BACKUP_FILE_EXTENSION") }
            ?.removePrefix("$pkgName.")
            ?.removeSuffix(".$BACKUP_FILE_EXTENSION")
            ?.toLongOrNull()
    }

    companion object {
        private const val BACKUP_DIR_NAME = "ext_backups"
        private const val BACKUP_FILE_EXTENSION = "apk"
        private const val MAX_BACKUPS_PER_EXTENSION = 2
    }
}
