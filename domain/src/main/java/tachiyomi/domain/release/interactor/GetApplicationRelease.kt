package tachiyomi.domain.release.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
class GetApplicationRelease(
    private val service: ReleaseService,
) {
    suspend fun await(arguments: Arguments): Result {
        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isNightly,
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isNightly: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        if (isNightly) {
            return (versionTag.removePrefix("r").toIntOrNull() ?: return false) > commitCount
        }

        val available = parseVersion(versionTag) ?: return false
        val installed = parseVersion(versionName) ?: return false

        // Compare components from left to right: 2.0.0 is newer than 1.99.99.
        val length = maxOf(available.numbers.size, installed.numbers.size)
        for (index in 0 until length) {
            val comparison = (available.numbers.getOrNull(index) ?: 0)
                .compareTo(installed.numbers.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison > 0
        }

        // CI preview builds keep the same base version. Compare their build numbers.
        return (available.previewBuild ?: 0) > (installed.previewBuild ?: 0)
    }

    private fun parseVersion(value: String): Version? {
        val match = VERSION_PATTERN.matchEntire(value.removePrefix("v")) ?: return null
        return Version(
            numbers = match.groupValues[1].split(".").map { it.toIntOrNull() ?: return null },
            previewBuild = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull(),
        )
    }

    private data class Version(val numbers: List<Int>, val previewBuild: Int?)

    private companion object {
        val VERSION_PATTERN = Regex("""^(\\d+(?:\\.\\d+)*)(?:-yomira-preview\\.(\\d+))?$""")
    }

    data class Arguments(
        val isFoss: Boolean,
        val isNightly: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}
