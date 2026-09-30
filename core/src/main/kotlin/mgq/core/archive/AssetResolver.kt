package mgq.core.archive

import java.io.File

/**
 * Resolves a script-relative asset path to bytes.
 *
 * NScripter's own search order is: a loose file in the game directory first, then the `.nsa`
 * archives in order. The port preserves that order because the game ships `system\` and `mod\`
 * loose while putting `chara\`, `bg\` and the audio inside archives, and a few names exist in both
 * places.
 *
 * The archive list is built lazily: constructing an [NsaArchive] scans that archive's signatures, and
 * the game ships 3.1 GB across five archives, so we only open the ones we actually need.
 */
class AssetResolver(
    private val gameDir: File,
    private val archives: List<File> = NsaArchive.discover(gameDir),
) : AutoCloseable {

    private val opened = LinkedHashMap<File, NsaArchive>()

    /** Small LRU of raw asset bytes; images are decoded from these by the renderer. */
    private val byteCache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > MAX_CACHED
    }

    fun exists(path: String): Boolean {
        if (looseFile(path)?.isFile == true) return true
        return archives.any { archiveFor(it)?.contains(path) == true }
    }

    /** Read an asset, or null when it is not present anywhere. */
    fun readBytes(path: String): ByteArray? {
        val key = path.replace('\\', '/').lowercase()
        byteCache[key]?.let { return it }

        val bytes = looseFile(path)?.takeIf { it.isFile }?.readBytes()
            ?: archives.firstNotNullOfOrNull { archiveFor(it)?.read(path) }
            ?: return null

        byteCache[key] = bytes
        return bytes
    }

    /**
     * Where an asset came from, for the porting report and for debugging missing-asset bugs.
     *
     * Returns `loose:<name>`, `archive:<n>`, or `missing`.
     */
    fun locate(path: String): String {
        looseFile(path)?.takeIf { it.isFile }?.let { return "loose:${it.name}" }
        archives.forEachIndexed { i, f ->
            if (archiveFor(f)?.contains(path) == true) return "archive:$i(${f.name})"
        }
        return "missing"
    }

    /** Open an archive on first use and keep it open; random access needs a persistent handle. */
    private fun archiveFor(file: File): NsaArchive? {
        opened[file]?.let { return it }
        val a = runCatching { NsaArchive(file) }.getOrNull() ?: return null
        opened[file] = a
        return a
    }

    private fun looseFile(path: String): File? {
        // Guard against path traversal: script-provided names must not escape the game directory.
        val rel = path.replace('\\', '/').removePrefix("./")
        if (rel.contains("..")) return null
        return File(gameDir, rel)
    }

    /** Total payload objects discovered across opened archives, for diagnostics. */
    fun describe(): String =
        "archives=${archives.size} opened=${opened.size} " +
            opened.entries.joinToString("; ") { (f, a) -> "${f.name}{${a.describe()}}" }

    override fun close() {
        opened.values.forEach { runCatching { it.close() } }
        opened.clear()
        byteCache.clear()
    }

    companion object {
        private const val MAX_CACHED = 96
    }
}
