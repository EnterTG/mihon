package mihon.data.extension.service

/**
 * Normalizes well-known extension store addresses to the index URL the store actually serves
 * today.
 *
 * Keiyoushi retired its legacy index: `index.min.json` now serves an "Outdated App" stub with
 * no real extensions, and its website (`keiyoushi.github.io`) is frequently pasted as a repo
 * address even though it's an HTML page that can never parse. Both leave every installed
 * extension looking outdated. Mapping every known Keiyoushi address shape to the v2 index lets
 * existing installs heal themselves on the next refresh instead.
 */
object ExtensionStoreUrlResolver {

    private const val KEIYOUSHI_INDEX_V2 = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"

    private val KEIYOUSHI_HOSTS = setOf(
        "keiyoushi.github.io",
        "github.com",
        "www.github.com",
        "raw.githubusercontent.com",
    )

    fun resolve(indexUrl: String): String {
        val url = indexUrl.trim()
        val withoutScheme = url.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return url

        val host = withoutScheme.substringBefore('/').lowercase()
        if (host !in KEIYOUSHI_HOSTS) return url

        if (host == "keiyoushi.github.io") return KEIYOUSHI_INDEX_V2

        // Any address of the keiyoushi/extensions repo (repo page, raw index.min.json,
        // index.json, repo.json, releases, ...) means the extension index
        val segments = withoutScheme.substringAfter('/', "").split('/')
        val isExtensionRepo = segments.size >= 2 &&
            segments[0].equals("keiyoushi", ignoreCase = true) &&
            segments[1].equals("extensions", ignoreCase = true)
        return if (isExtensionRepo) KEIYOUSHI_INDEX_V2 else url
    }
}
