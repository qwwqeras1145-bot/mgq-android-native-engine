package mgq.core.script

/** Scratch harness for isolating parser bugs against real script snippets. */
object Scratch {

    @JvmStatic
    fun main(argv: Array<String>) {
        nsaHeaderProbe()
        if (argv.isNotEmpty() && argv[0] == "nsa") return
        parserSamples()
    }

    /** Print the first 72 bytes of a real .nsa as DECIMAL, plus every candidate header field. */
    private fun nsaHeaderProbe() {
        val f = java.io.File("D:\\游戏\\勇者大战魔物娘三章剧情汉化整合版\\arc4.nsa")
        if (!f.isFile) { println("arc4.nsa not found"); return }
        val b = f.readBytes()
        println("=== arc4.nsa filesize=${b.size} ===")
        print("bytes[0..71] DECIMAL: ")
        for (i in 0 until 72) {
            print("%3d ".format(b[i].toInt() and 0xFF))
            if (i % 12 == 11) print("\n                     ")
        }
        println()
        fun u16(at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
        println("candidate fields:")
        println("  u16@0 = ${u16(0)}     u16@1 = ${u16(1)}")
        println("  u16@2 = ${u16(2)}     u16@4 = ${u16(4)}")
        println("  u32@0 = ${u32(0)}  u32@4 = ${u32(4)}")
        println("  name1 '系统\\m_title_wuyu.jpg' gbk at byte ${indexOfGbk(b, "系统\\m_title_wuyu.jpg")}")
        println("  data offset 68 -> bytes ${b[68]} ${b[69]} ${b[70]} ${b[71]} (expect 255 216 255 225)")

        // Now actually exercise the parser and print what it concluded.
        val parser = ScriptParser("probe")
        parser.parse("")
        println("\nNsaArchive probe:")
        runCatching {
            mgq.core.archive.NsaArchive(f).use { a ->
                println("  entries=${a.entries.size}")
                a.entries.take(5).forEach { e -> println("    size=${e.size} off=${e.offset} name=${e.name}") }
            }
        }.onFailure { println("  EXCEPTION: $it") }

        // And the raw index-decoding attempt, printed step by step for record 0 and 1.
        println("\nrecord walk (RECORD_SIZE=28, base=8):")
        for (i in 0 until 3) {
            val rec = 8 + i * 28
            if (rec + 28 > b.size) break
            val nameBytes = b.copyOfRange(rec, rec + 24)
            val z = nameBytes.indexOf(0.toByte()).let { if (it < 0) 24 else it }
            val nm = String(nameBytes, 0, z, mgq.core.archive.NsaArchive.GBK)
            val size = u32(rec + 24)
            println("  rec$i at $rec: name='$nm' size=$size")
        }
    }

    private fun indexOfGbk(hay: ByteArray, s: String): Int {
        val pat = s.toByteArray(mgq.core.archive.NsaArchive.GBK)
        outer@ for (i in 0..hay.size - pat.size) {
            for (j in pat.indices) if (hay[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun parserSamples() {
        println("\n=== ExprParser.parseCondition on real samples ===")
        val samples = listOf(
            "%zukanon=2",
            "%zukanon=2 mov %zukanon,1",
            "%cmd1=-1 gosub *menu",
            "%ivent05=0 lsp 700,\":a;chara\\mob_syounen.bmp\",-220,0",
            "%hanyo1=1 && %hanyo2=1 && %hanyo3=1",
            "%sentaku=1",
            "%game_clear<3",
            "%continue=1",
            "%hanyo1>0",
        )
        for (s in samples) {
            val c = ExprParser.parseCondition(s, emptyMap())
            println("  ${if (c != null) "OK  " else "FAIL"}  '$s'  -> ${c?.javaClass?.simpleName}")
        }

        println("\n=== tokenise on real lines ===")
        val lines = listOf(
            "if %zukanon=2 mov %zukanon,1",
            "\tif %zukanon=2 mov %zukanon,1",
            "if %cmd1=-1 gosub *menu",
            "if %sentaku=1 skip 91",
            "if %continue=1 lsp 700,\":a;chara\\iriasu_st01.bmp\",0,0",
        )
        for (l in lines) {
            val t = Commands.tokenise(l.trim(), emptySet())
            println("  '${l.trim()}'")
            println("      -> ${if (t == null) "null" else "cmd=${t.command} args='${t.arguments}' trailing=${t.trailingText}"}")
        }

        println("\n=== readToken check for 'if' ===")
        println("  isBuiltin(if) = ${Commands.isBuiltin("if")}")
        println("  BY_LENGTH_DESC contains if = ${Commands.BY_LENGTH_DESC.contains("if")}")

        println("\n=== real failing lines, full pipeline ===")
        val tough = listOf(
            "if %3083>0mov %list_num3,1",
            "if %hanyo1=1:goto *nekomata_hb",
            "if %hanyo1=0:mov %ikigoe,1:gosub *ikigoe",
            "\tif %zukanon=2 mov %zukanon,1:goto *zukan_commonkaisou0",
            "if %ivent05=0 lsp 700,\":a;chara\\mob_syounen.bmp\",-220,0:print 10,500",
        )
        for (l in tough) {
            val parser = ScriptParser("scratch")
            val script = parser.parse(l + "\n")
            println("  input : ${l.replace("\t", "<TAB>")}")
            println("  instr : ${script.size}  problems=${parser.problems}")
            for (ins in script.instructions) {
                println("      ${ins.kind} cmd=${ins.command} args='${ins.arguments}' flow=${ins.flow?.javaClass?.simpleName}")
            }
        }
    }
}
