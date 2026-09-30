package mgq.core.cli

import mgq.core.archive.NsaArchive
import mgq.core.script.Args
import mgq.core.script.Instruction
import mgq.core.script.NsDecoder
import mgq.core.script.Script
import mgq.core.script.ScriptParser
import java.io.File

/**
 * Desktop harness for the engine core.
 *
 * This exists so the port can be validated **without a phone**. The engine core has no Android
 * dependency, so this tool feeds it the real decrypted script of the target game and exercises the
 * parser and VM on the actual 382k-line input. Every claim in the porting report that says "works"
 * or "fails" is produced by this command, not by assertion.
 *
 * Usage:
 * ```
 *   dump   <script-file>              structural report: labels, commands, problems
 *   run    <script-file> [steps]      headless VM run, prints the transcript
 *   decoder <nscript.dat> <out.txt>   decode and compare against a reference
 *   resolve <script-file> <asset-dir> check that referenced assets exist on disk
 * ```
 */
object Main {

    @JvmStatic
    fun main(argv: Array<String>) {
        if (argv.isEmpty()) { usage(); return }
        when (argv[0]) {
            "dump" -> dump(argv.drop(1))
            "run" -> run(argv.drop(1))
            "decoder" -> decoder(argv.drop(1))
            "resolve" -> resolve(argv.drop(1))
            "nsa" -> nsa(argv.drop(1))
            else -> usage()
        }
    }

    private fun usage() {
        println(
            """
            mgq-core CLI — NScripter engine harness

              dump    <script-file>               structural report
              run     <script-file> [max-steps]   headless VM transcript
              decoder <nscript.dat> <out-file>    decode and verify
              resolve <script-file> <asset-dir>   asset reference check
              nsa     <archive.nsa> [out-dir]     list / extract an .nsa archive
            """.trimIndent()
        )
    }

    // -------------------------------------------------------------------------------------

    /**
     * Validate the `.nsa` reader against a real archive.
     *
     * Without an output directory this only lists the index; with one it extracts every entry and
     * verifies the declared sizes against the bytes actually written, which is what proves the
     * index layout is right rather than merely plausible.
     */
    private fun nsa(args: List<String>) {
        if (args.isEmpty()) { usage(); return }
        val archive = File(args[0])
        require(archive.isFile) { "not a file: ${archive.absolutePath}" }
        NsaArchive(archive).use { a ->
            println("archive      : ${archive.name} (${archive.length()} bytes)")
            println("entries      : ${a.entries.size}")
            val total = a.entries.sumOf { it.size.toLong() }
            println("declared size: $total bytes (%.1f MB)".format(total / 1024.0 / 1024.0))
            // Structural self-test: the chosen layout must account for every byte in the file.
            println("invariant    : ${a.describe()}")
            println("\nfirst 15 entries:")
            a.entries.take(15).forEach { e ->
                println("  %10d  @%-12d  %s".format(e.size, e.offset, e.name))
            }

            val outArg = args.getOrNull(1)
            if (outArg != null) {
                val outDir = File(outArg)
                outDir.mkdirs()
                var ok = 0
                var bad = 0
                for (e in a.entries) {
                    val bytes = a.read(e)
                    val target = File(outDir, e.name)
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                    if (bytes.size == e.size && target.length() == e.size.toLong()) ok++ else bad++
                }
                println("\nextracted to : ${outDir.absolutePath}")
                println("exact match  : $ok ok, $bad mismatched")
            }
        }
    }

    // -------------------------------------------------------------------------------------

    private fun loadScript(path: String): Script {
        val file = File(path)
        require(file.isFile) { "not a file: $path" }
        val raw = file.readBytes()
        // A plain-text script (e.g. the vendor tool's output) is used directly; a real
        // nscript.dat goes through the decoder.
        val text = if (looksPlain(raw)) String(raw, Charsets.ISO_8859_1) else NsDecoder.decode(raw).text
        val parser = ScriptParser(file.name)
        val script = parser.parse(text)
        println("parsed ${file.name}: ${script.size} instructions")
        if (parser.problems.isNotEmpty()) {
            println("parser problems: ${parser.problems.size}")
        }
        return script
    }

    private fun looksPlain(raw: ByteArray): Boolean {
        val head = raw.copyOfRange(0, minOf(64, raw.size))
        val printable = head.count {
            val b = it.toInt() and 0xFF
            (b >= 0x20 && b < 0x7F) || b == 0x0D || b == 0x0A || b == 0x09
        }
        return printable >= head.size - 1
    }

    // -------------------------------------------------------------------------------------

    private fun dump(args: List<String>) {
        if (args.isEmpty()) { usage(); return }
        val file = File(args[0])
        val parser = ScriptParser(file.name)
        val script = parser.parse(String(file.readBytes(), Charsets.ISO_8859_1))

        println("=== structure ===")
        println("instructions : ${script.size}")
        println("labels       : ${script.labels.size}")
        println("defsubs      : ${script.definedSubs.size}")
        println("numalias     : ${script.numAliases.size}")
        println("stralias     : ${script.strAliases.size}")

        val kinds = script.instructions.groupingBy { it.kind }.eachCount()
        println("by kind      : $kinds")

        val cmdCounts = HashMap<String, Int>()
        for (i in script.instructions) {
            val c = i.command ?: continue
            cmdCounts[c] = (cmdCounts[c] ?: 0) + 1
        }
        println("\n=== commands (${cmdCounts.size} distinct) ===")
        cmdCounts.entries.sortedByDescending { it.value }.take(60)
            .forEach { (k, v) -> println("  %8d  %s".format(v, k)) }

        if (script.unknownCommands.isNotEmpty()) {
            println("\n=== UNKNOWN commands ===")
            script.unknownCommands.entries.sortedByDescending { it.value }
                .forEach { (k, v) -> println("  %8d  %s".format(v, k)) }
        } else {
            println("\n=== UNKNOWN commands: none ===")
        }

        if (parser.problems.isNotEmpty()) {
            println("\n=== parser problems (first 40 of ${parser.problems.size}) ===")
            parser.problems.take(40).forEach { println("  $it") }
        }
        if (parser.brokenJumps.isNotEmpty()) {
            println("\n=== broken goto/gosub targets (${parser.brokenJumps.size}) ===")
            parser.brokenJumps.entries.sortedByDescending { it.value }.take(40)
                .forEach { (k, v) -> println("  %8d  %s".format(v, k)) }
        }
    }

    // -------------------------------------------------------------------------------------

    private fun run(args: List<String>) {
        if (args.isEmpty()) { usage(); return }
        val file = File(args[0])
        val maxSteps = args.getOrNull(1)?.toIntOrNull() ?: 5000
        val parser = ScriptParser(file.name)
        val script = parser.parse(String(file.readBytes(), Charsets.ISO_8859_1))

        val platform = TranscriptPlatform()
        val vm = mgq.core.vm.Vm(script, platform)

        val entry = when {
            script.labels.containsKey("*define") -> "*define"
            script.labels.containsKey("*game_start") -> "*game_start"
            else -> null
        }
        if (entry != null) vm.startAt(entry) else vm.pc = 0

        var executed = 0
        while (executed < maxSteps && !vm.finished) {
            val status = vm.run(maxSteps = 200, maxMillis = 50)
            executed += 200
            if (status == mgq.core.vm.Vm.Status.FINISHED) break
        }

        println("\n=== transcript (${platform.lines.size} lines captured) ===")
        platform.lines.take(120).forEach { println(it) }
        println("\n=== vm stats ===")
        println("steps        : ${vm.stepCount}")
        println("pc           : ${vm.pc}")
        println("finished     : ${vm.finished}")
        println("variables    : ${vm.vars.stats()}")
        println("lastButton   : ${vm.lastButton}")
        if (vm.unimplemented.isNotEmpty()) {
            println("unimplemented: ${vm.unimplemented.keys.sorted()}")
        }
        println("warnings     : ${platform.warnings.size}")
        platform.warnings.take(30).forEach { println("  $it") }
    }

    // -------------------------------------------------------------------------------------

    private fun decoder(args: List<String>) {
        if (args.size < 2) { usage(); return }
        val input = File(args[0])
        val output = File(args[1])
        val result = NsDecoder.decodeFile(input)
        output.writeText(result.text, Charsets.ISO_8859_1)
        println("decoded ${input.name}: encrypted=${result.wasEncrypted} bytes=${input.length()}")
        println("wrote ${output.absolutePath} (${output.length()} bytes)")
        println("cipher sha256 : ${result.sha256}")

        // Optional third argument: a reference plaintext to verify against byte-for-byte.
        val reference = args.getOrNull(2)?.let(::File)
        if (reference != null) {
            val want = reference.readText(Charsets.ISO_8859_1)
            val got = result.text
            // The vendor tool writes CRLF; the container stores LF. Compare after normalising.
            val normWant = want.replace("\r\n", "\n")
            val normGot = got.replace("\r\n", "\n")
            println("plain sha256  : ${sha256Hex(normGot.toByteArray(Charsets.ISO_8859_1))}")
            println("ref   sha256  : ${sha256Hex(normWant.toByteArray(Charsets.ISO_8859_1))}")
            if (normGot == normWant) {
                println("VERIFY        : byte-identical after EOL normalisation (100%)")
            } else {
                val n = minOf(normGot.length, normWant.length)
                var pos = 0
                while (pos < n && normGot[pos] == normWant[pos]) pos++
                println("VERIFY        : DIVERGE at char $pos (len ${normGot.length} vs ${normWant.length})")
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // -------------------------------------------------------------------------------------

    private fun resolve(args: List<String>) {
        if (args.size < 2) { usage(); return }
        val scriptFile = File(args[0])
        val assetRoot = File(args[1])
        val script = ScriptParser(scriptFile.name)
            .parse(String(scriptFile.readBytes(), Charsets.ISO_8859_1))

        val refs = LinkedHashMap<String, Int>()
        val quoted = Regex("\"([^\"]{1,200}?)\"")
        for (ins in script.instructions) {
            for (m in quoted.findAll(ins.arguments)) {
                val s = m.groupValues[1]
                if (s.startsWith(":")) continue
                if (!s.contains('.')) continue
                refs[s] = (refs[s] ?: 0) + 1
            }
        }
        var missing = 0
        var present = 0
        val missingList = ArrayList<String>()
        for ((ref, count) in refs) {
            val rel = ref.replace('\\', File.separatorChar)
            val f = File(assetRoot, rel)
            if (f.isFile) present++ else { missing++; if (missingList.size < 60) missingList.add("$ref (x$count)") }
        }
        println("asset references : ${refs.size}")
        println("present on disk  : $present")
        println("missing on disk  : $missing")
        println("\n=== missing (first 60) ===")
        missingList.forEach { println("  $it") }
    }
}

/**
 * A [mgq.core.platform.Platform] that records everything into a text transcript.
 *
 * Used by `run` to prove the VM executes real game logic. It auto-advances clicks so a headless
 * run does not stall on the first dialogue box.
 */
class TranscriptPlatform(val autoAdvanceAfter: Int = 1) : mgq.core.platform.Platform {
    override val scene = mgq.core.platform.Scene()
    override val audio = SilentAudio()
    val lines = ArrayList<String>()
    val warnings = ArrayList<String>()
    private var clickCount = 0

    override fun waitForClick(): Int {
        clickCount++
        scene.text?.let { t ->
            val prefix = t.name?.let { "$it: " } ?: ""
            t.lines.forEach { lines.add(prefix + it) }
        }
        scene.text = null
        return 0
    }

    override fun waitMillis(millis: Int) { /* headless: time does not pass */ }

    override fun warning(message: String) { if (warnings.size < 500) warnings.add(message) }

    override fun nowMillis(): Long = System.nanoTime() / 1_000_000

    override fun saveSlot(slot: Int, data: ByteArray) { lines.add("[save slot $slot, ${data.size} bytes]") }

    override fun loadSlot(slot: Int): ByteArray? = null

    override fun saveSlotCount(): Int = 9
}

class SilentAudio : mgq.core.platform.Audio {
    val log = ArrayList<String>()
    override fun playBgm(path: String, loop: Boolean) { log.add("bgm $path loop=$loop") }
    override fun stopBgm() { log.add("bgmstop") }
    override fun playSe(path: String) { log.add("se $path") }
    override fun stopSe() {}
    override fun playDwave(path: String, loop: Boolean) { log.add("dwave $path") }
    override fun stopDwave() {}
    override fun isDwavePlaying(): Boolean = false
}
