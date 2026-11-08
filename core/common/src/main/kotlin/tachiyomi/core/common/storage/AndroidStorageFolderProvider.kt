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
        // Keep a separate, stable default directory for Yomira installations.
        return File(
            Environment.getExternalStorageDirectory().absolutePath + File.separator +
                "Yomira",
        )
    }

    override fun path(): String {
        return directory().toUri().toString()
    }
}
