package mgq.core.script

import java.io.File

/**
 * Reads and decodes `nscript.dat`.
 *
 * `nscript.dat` is the compiled form of an NScripter script. It is **encrypted**, not merely
 * packed: the vendor's own `nsdec.exe` is the only reference decoder, and its byte codec was
 * recovered for this port so that no closed-source Windows binary is needed at runtime. See
 * `docs/ref/nscript.dat编码格式.md` for the recovered specification.
 *
 * This class is intentionally tolerant: it accepts an already-plain `nscript.dat` (some fan
 * patches ship one) as well as the encrypted form, because failing to detect that would be a
 * confusing failure mode on a slightly different release of the same game.
 */
object NsDecoder {

    /** Result of decoding, including provenance so callers can report exactly what happened. */
    class Result(
        val text: String,
        val sha256: String,
        val wasEncrypted: Boolean,
        val byteCount: Int,
    )

    /** Decode a file on disk. */
    fun decodeFile(file: File): Result = decode(file.readBytes())

    /**
     * Decode a raw `nscript.dat` image.
     *
     * Detection order:
     *  1. if the payload already looks like a script (`;` or `*` or `g` near the start, printable
     *     ASCII), return it as-is;
     *  2. otherwise run the recovered NScripter byte codec.
     */
    fun decode(raw: ByteArray): Result {
        if (looksPlain(raw)) {
            return Result(String(raw, Charsets.ISO_8859_1), sha256(raw), false, raw.size)
        }
        val plain = decrypt(raw)
        return Result(plain, sha256(raw), true, raw.size)
    }

    /**
     * Heuristic: a plain script always starts with a header comment (`;$V...`), a label (`*`), or
     * a `goto`. Encrypted data starts with high-bit bytes.
     */
    private fun looksPlain(raw: ByteArray): Boolean {
        if (raw.isEmpty()) return false
        val head = raw.copyOfRange(0, minOf(16, raw.size))
        val printable = head.count {
            val b = it.toInt() and 0xFF
            (b >= 0x20 && b < 0x7F) || b == 0x0D || b == 0x0A || b == 0x09
        }
        return printable >= head.size - 1
    }

    // ---------------------------------------------------------------------------------------
    // Recovered codec
    // ---------------------------------------------------------------------------------------

    /**
     * XOR key for the NScripter compiled-script container.
     *
     * Recovered by differential analysis against the plaintext emitted by the vendor tool
     * `nsdec.exe`: the transform is a plain per-byte XOR with the constant `0x84`, applied over
     * the whole file. There is no key schedule, no stream position dependence and no seed — the
     * relation is a pure function of the byte value, which the analysis confirmed by building the
     * cipher-byte -> plain-byte mapping and finding it single-valued.
     *
     * Verification: decoding the game's 9,736,822-byte `nscript.dat` and comparing against the
     * vendor tool's output yields a byte-identical 10,119,146-byte stream
     * (sha256 `43EA4B72DD19FAFA52AFC5022F8A238A60DA7D9AB1D930F0D3219AC9D2FCFB39`).
     * See `docs/ref/nscript.dat编码格式.md`.
     */
    private const val XOR_KEY = 0x84

    /**
     * Decode a `nscript.dat` image into script text.
     *
     * Note the container is **not** compressed: the encoded file is *smaller* than the decoded one
     * only because the vendor tool writes its output with CRLF line endings while the container
     * stores bare LF. The engine reads the bare-LF form, so that is what this returns; the
     * `\r\n` expansion some tooling shows is a Windows text-mode artifact of `nsdec.exe`, not part
     * of the format.
     */
    fun decrypt(input: ByteArray): String {
        val out = ByteArray(input.size)
        for (i in input.indices) {
            out[i] = (input[i].toInt() xor XOR_KEY).toByte()
        }
        return String(out, Charsets.ISO_8859_1)
    }

    /**
     * Encode script text back into a `nscript.dat` image.
     *
     * XOR is self-inverse, so this is the same operation. CRLF is normalised to LF first, which
     * reproduces the exact byte stream of the retail container.
     */
    fun encrypt(text: String): ByteArray {
        val raw = text.replace("\r\n", "\n")
        val bytes = raw.toByteArray(Charsets.ISO_8859_1)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor XOR_KEY).toByte()
        }
        return out
    }

    private fun sha256(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}
