package tachiyomi.core.common.storage

import android.os.Environment
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidStorageFolderProvider : FolderProvider {

    override fun directory(): File {
        // Keep the legacy default path stable when the app display name changes.
        return File(
            Environment.getExternalStorageDirectory().absolutePath + File.separator +
                "Mihon",
        )
    }

    override fun path(): String {
        return directory().toUri().toString()
    }
}
