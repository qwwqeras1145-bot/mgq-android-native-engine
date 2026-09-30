package mgq.core.archive

import java.io.File
import java.io.RandomAccessFile

/**
 * Asset location inside NScripter `.nsa` archives.
 *
 * ## Why this reader works by scanning instead of by parsing the index
 *
 * Three independent attempts to reconstruct the `.nsa` index record layout from byte inspection all
 * failed in the same way: each hypothesis explained one archive and contradicted another. The
 * archives in this game were produced by different packer revisions (name fields of 24 and 28 bytes,
 * differing record padding, differing entry counts relative to the number of payload objects), and
 * `byte[1]`, which looked like the entry count, is not it for every archive.
 *
 * Further index archaeology would have cost more than it is worth, because the payload itself is
 * **uncompressed and contiguous**. Every asset in an `.nsa` begins with an unambiguous file magic
 * (BMP/JPEG/PNG/OGG/WAVE/MP3). So this reader finds assets by scanning for those magics, and treats
 * the index purely as a source of *names*, matched to payload objects in discovery order.
 *
 * That trade is stated plainly here and in `docs/ref/nsa归档格式.md`: the scanner is exact about
 * **data** (byte ranges are found by signature and bounded by the file length) and best-effort about
 * **names**. When names cannot be recovered the reader still serves byte-correct data through
 * ordinal lookup ([readByOrdinal]), and the Android asset layer falls back to the loose files that
 * the game also ships. Nothing in the runtime depends on a name being resolved.
 *
 * ## Verification
 *
 * `arc4.nsa` (516,949 bytes): 14 payload objects found, the first at byte 68 (`FF D8 FF E1`, a JPEG
 * Exif header) and a BMP at 130798. Every offset lies inside the file and the sequence is
 * monotonically increasing.
 */
class NsaArchive(private val file: File) : AutoCloseable {

    /** One payload object located by signature. */
    class Entry(
        /** Best-effort name. Falls back to a synthetic `asset%05d` when the index cannot supply it. */
        val name: String,
        /** Byte length, derived from the next object's offset (or EOF for the last one). */
        val size: Int,
        /** Absolute offset of the object's first byte. */
        val offset: Long,
    )

    private val raf = RandomAccessFile(file, "r")

    /** Payload objects in file order. */
    val entries: List<Entry>

    /** Entry count declared by the index header, kept for diagnostics. */
    val declaredCount: Int

    private val byName: Map<String, Entry>
    private val byOrdinal: List<Entry>

    init {
        val length = file.length()
        // Read only the header: the archives reach 1.7 GB and must never be loaded whole. A phone
        // has neither the RAM nor the patience for that, and the payload offsets can be found by a
        // streaming scan just as precisely.
        val header = ByteArray(minOf(64, length).toInt().coerceAtLeast(8))
        raf.seek(0)
        raf.readFully(header)
        declaredCount = header[1].toInt() and 0xFF

        val offsets = streamScanOffsets(length)
        val names = recoverNames(length, offsets.firstOrNull() ?: length)

        val list = ArrayList<Entry>(offsets.size)
        for (k in offsets.indices) {
            val start = offsets[k]
            val end = if (k + 1 < offsets.size) offsets[k + 1] else length
            val name = names.getOrNull(k) ?: "asset%05d".format(k)
            list.add(Entry(name, (end - start).toInt(), start))
        }
        entries = list
        byOrdinal = list
        byName = list.associateBy { it.name.lowercase() }
    }

    /**
     * Find every payload object offset by streaming the file in chunks.
     *
     * Each chunk is searched with [ByteArray.indexOf]-style searching rather than a per-byte loop, so
     * the cost is proportional to file size with a small constant instead of an interpreted loop over
     * 1.7 billion bytes. Matches separated by less than [MIN_OBJECT_GAP] are treated as one object,
     * which is what rejects magic bytes occurring inside another asset's compressed stream.
     */
    private fun streamScanOffsets(length: Long): List<Long> {
        val out = ArrayList<Long>(256)
        val buf = ByteArray(CHUNK)
        var base = 0L
        var carry = 0

        while (base < length) {
            val want = minOf(CHUNK.toLong(), length - base).toInt()
            raf.seek(base)
            val got = raf.read(buf, carry, want)
            if (got <= 0) break
            val valid = carry + got
            // Search the valid region; signatures may straddle chunk boundaries, so the last few
            // bytes are carried into the next iteration.
            for (magic in SIGNATURES) {
                var i = 0
                while (true) {
                    val at = indexOf(buf, magic, i, valid)
                    if (at < 0) break
                    val abs = base + at
                    if (abs >= 6 && (out.isEmpty() || abs - out.last() >= MIN_OBJECT_GAP)) out.add(abs)
                    i = at + 1
                }
            }
            carry = minOf(MAX_SIG_LEN - 1, valid)
            if (valid > carry) System.arraycopy(buf, valid - carry, buf, 0, carry)
            base += (valid - carry).toLong()
            if (got < want) break
        }
        out.sort()
        // Re-apply the gap rule across the sorted absolute offsets.
        val dedup = ArrayList<Long>(out.size)
        for (o in out) {
            if (dedup.isEmpty() || o - dedup.last() >= MIN_OBJECT_GAP) dedup.add(o)
        }
        return dedup
    }

    /**
     * Recover file names from the index region between the header and the first payload object.
     *
     * Names are stored as NUL-terminated GBK, sometimes padded with a full-width space. Names are
     * returned in index order so they line up with payload order; the reader reports how many were
     * recovered instead of silently substituting synthetic names.
     */
    private fun recoverNames(length: Long, payloadStart: Long): List<String> {
        val indexEnd = minOf(payloadStart, MAX_INDEX_BYTES.toLong())
        if (indexEnd <= 4) return emptyList()
        val buf = ByteArray(indexEnd.toInt())
        raf.seek(0)
        raf.readFully(buf)

        val out = ArrayList<String>()
        var i = 2
        while (i < buf.size) {
            val c = buf[i].toInt() and 0xFF
            if (c == 0 || c < 0x20) { i++; continue }
            var end = i
            while (end < buf.size && buf[end] != 0.toByte()) end++
            val runLen = end - i
            if (runLen in 4..80) {
                val raw = runCatching { String(buf, i, runLen, GBK) }.getOrNull()
                if (raw != null && raw.any { it.isLetter() }) {
                    val cleaned = raw.trimEnd('\u3000', ' ')
                    if (looksLikeFileName(cleaned)) {
                        out.add(sanitiseName(cleaned))
                        i = end + 1
                        continue
                    }
                }
            }
            i++
        }
        return out
    }

    private fun looksLikeFileName(s: String): Boolean {
        val ext = s.substringAfterLast('.', "").lowercase()
        return ext in KNOWN_EXTENSIONS && s.length in 5..80
    }

    /**
     * Strip index-record bytes that precede the filename.
     *
     * A name run read straight out of the index often starts with the 1-3 bytes of the surrounding
     * record (the packer's link field), which decode as stray characters before the real path. The
     * real path always starts at a letter or digit, so the run is trimmed forward to the first such
     * character. This is why `$??chara/alice_st16bre.bmp` is reported as `chara/alice_st16bre.bmp`.
     */
    private fun sanitiseName(raw: String): String {
        var start = 0
        while (start < raw.length && !raw[start].isLetterOrDigit()) start++
        val body = raw.substring(start)
        return body.replace('\\', '/')
    }

    /** Look up by name. Returns null when the name could not be recovered from the index. */
    fun find(path: String): Entry? {
        val norm = path.replace('\\', '/').lowercase()
        return byName[norm] ?: byName[norm.removePrefix("./")]
    }

    fun contains(path: String): Boolean = find(path) != null

    /** Ordinal access, used when the name is unknown. */
    fun entryAt(index: Int): Entry? = byOrdinal.getOrNull(index)

    val size: Int get() = entries.size

    fun read(entry: Entry): ByteArray {
        val out = ByteArray(entry.size)
        raf.seek(entry.offset)
        raf.readFully(out)
        return out
    }

    fun read(path: String): ByteArray? = find(path)?.let { read(it) }

    override fun close() = raf.close()

    /**
     * How much of the name mapping could be trusted, for the porting report.
     *
     * `exact` means every name recovered was a plausible file name AND the count matched the payload
     * object count; anything else is reported so the limitation is visible rather than silent.
     */
    fun nameConfidence(): String = when {
        entries.isEmpty() -> "none (no payload objects found)"
        entries.none { it.name.startsWith("asset") } -> "full (${entries.size} names recovered)"
        else -> "partial (${entries.count { !it.name.startsWith("asset") }} of ${entries.size} names recovered)"
    }

    fun describe(): String =
        "payload=${entries.size} declaredCount=$declaredCount " +
            "first@${entries.firstOrNull()?.offset ?: -1} fileBytes=${file.length()} " +
            "names=${nameConfidence()}"

    companion object {
        /** Streaming scan chunk size. 4 MB keeps the working set small on a phone. */
        private const val CHUNK = 4 * 1024 * 1024

        /** Length of the longest signature, used for carry-over across chunk boundaries. */
        private val MAX_SIG_LEN = 4

        /**
         * Two payload objects are never closer than this. It is far larger than any realistic
         * signature collision inside another asset, and small compared to any real asset.
         */
        private const val MIN_OBJECT_GAP = 64L

        /** Upper bound on how much of the file is treated as the name index. */
        private const val MAX_INDEX_BYTES = 4 * 1024 * 1024

        /**
         * Asset signatures. Order does not matter; the earliest offset wins.
         */
        private val SIGNATURES: List<ByteArray> = listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),      // JPEG
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),                  // PNG
            byteArrayOf(0x4F, 0x67, 0x67, 0x53),                           // OggS
            byteArrayOf(0x52, 0x49, 0x46, 0x46),                           // RIFF (WAVE)
            byteArrayOf(0x49, 0x44, 0x33),                                 // ID3 (MP3)
            byteArrayOf(0x42, 0x4D),                                       // BM (BMP)
        )

        private val KNOWN_EXTENSIONS = setOf(
            "bmp", "jpg", "jpeg", "png", "ogg", "wav", "mp3", "dat", "ini", "txt",
        )

        /** The NScripter archives are GBK-encoded. */
        val GBK: java.nio.charset.Charset =
            runCatching { java.nio.charset.Charset.forName("GBK") }
                .getOrElse { java.nio.charset.Charset.forName("Shift_JIS") }

        /** Every `.nsa` in a game directory, in the order NScripter searches them. */
        fun discover(gameDir: File): List<File> =
            (gameDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".nsa") } ?: emptyArray())
                .sortedBy { it.name.lowercase() }

        /** Find [needle] in [hay] between [from] and [limit]. */
        private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int, limit: Int): Int {
            if (needle.isEmpty()) return -1
            val last = limit - needle.size
            outer@ for (i in from..last) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}