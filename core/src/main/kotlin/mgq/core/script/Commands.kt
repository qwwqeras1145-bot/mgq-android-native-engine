package mgq.core.script

/**
 * The NScripter built-in command table.
 *
 * This table drives three things:
 *  1. tokenising a line into (command, arguments, trailing text),
 *  2. deciding which arguments are numeric vs string,
 *  3. reporting unrecognised tokens so the porting effort can be measured rather than guessed.
 *
 * Membership here was established by reading the actual decompiled script of the target game
 * (~382k lines) and cross-checking against the ONScripter command set, not by copying a manual
 * wholesale. Commands that appear in the script but NOT here are recorded by the parser and
 * surface in `Script.unknownCommands`.
 */
object Commands {

    /**
     * Commands whose trailing text is dialogue rather than data.
     *
     * In NScripter a line like `name 爱丽丝` or `print 3` is followed, on the *next* line, by the
     * text to show. But the compact form `name 爱丽丝　こんにちは` puts the text on the same line.
     * The engine distinguishes the two by looking at whether the remainder parses as pure
     * arguments; this set marks the commands where a trailing run after the numeric arguments is
     * text.
     */
    val STATEMENT_COMMANDS: Set<String> = setOf(
        "print", "name", "mesbox", "br", "textclear", "texton", "textoff",
    )

    /**
     * Every built-in command token the VM understands.
     *
     * Sorted longest-first at class-init to support longest-match tokenisation: NScripter has
     * overlapping prefixes (`print`/`print2`, `csp`/`csp2`/`cspl`, `btn`/`btndef`/`btnwait`),
     * and the original engine matches the longest registered name.
     */
    val BUILTIN: Set<String> = setOf(
        // ---- control flow -------------------------------------------------
        "goto", "gosub", "return", "jmp", "if", "for", "next", "break", "notif",
        "defsub", "getparam", "getret", "resettimer",

        // ---- variables / arithmetic ---------------------------------------
        "mov", "add", "sub", "inc", "dec", "mul", "div", "mod",
        "itoa", "itoa2", "atoi", "len", "rnd", "rnd2", "split",
        "numalias", "stralias", "dim", "getini",

        // ---- text / message window ----------------------------------------
        "print", "texton", "textoff", "textclear", "br", "mesbox",
        "setwindow", "setwindow2", "setwindow3", "windoweffect",
        "name", "click", "yesnobox", "lrclick", "flushout",

        // ---- buttons / selection ------------------------------------------
        "btndef", "btn", "btnwait", "btnwait2", "btndown", "spbtn",
        "exbtn", "csel", "cselstr", "getcselstr", "getspmode",

        // ---- graphics ------------------------------------------------------
        "bg", "bgmonce", "lsp", "lsp2", "csp", "csp2", "cspl", "msp2", "amsp",
        "vsp", "vspl", "quake", "shadedistance", "monocro",
        "screen_clear", "screen_clear2", "screen_vanish", "screen_appear",
        "effect", "mpegplay", "nega", "glass", "bar", "barclear",
        "cell", "cellchecks", "humanz", "transmode",

        // ---- audio ----------------------------------------------------------
        "bgm", "bgmstop", "wave", "dwave", "dwavestop", "dwaveon", "dwaveoff",
        "mp3", "mp3stop", "se", "wait",

        // ---- save / system ---------------------------------------------------
        "save", "load", "saveoff", "savenumber", "game", "caption",
        "skip", "skipoff", "isskip", "delay", "checkpage", "reset",
        "end", "quit", "textspeed", "automode", "cdfadeout",

        // ---- NScripter array declaration ------------------------------------
        // `?name[n]` declares a numeric array. It is a command in its own right, not a
        // variable sigil, and the target game uses exactly this form (`dim ?mon_labo_var[9]`
        // appears in its define block). Handling it here keeps `?`-prefixed lines from being
        // misclassified as dialogue.
        "?",

        // ---- present in the target script but absent from the compact ONScripter table ----
        // These were identified by exhaustive command counting over the real 382k-line script
        // (see docs/ref/脚本指令使用画像.md, section 3). They are genuine NScripter commands, not
        // parser artifacts, so they must tokenise as commands even where the port only stubs their
        // effect. Frequencies in the target script are noted for prioritisation.
        "bgm", "bgmstop", "autoclick", "stop", "dwaveloop", "quakex", "bgmvol",
        "sevol", "strsp", "prnumclear", "cselgoto", "getpage", "getlog",
        "systemcall", "texec", "seteffectspeed", "bgmfadeout", "saveon",
        "savefileexist", "mid", "kidokumode", "nsa", "filelog", "labellog",
        "textgosub", "globalon", "usewheel", "defaultspeed", "maxkaisoupage",
        "rubyon", "deletemenu", "savedir", "humanz", "windowback", "autosaveoff",
        "kidokuskip", "loadgosub", "effectcut", "mode_wave_demo", "exec_dll",
        "spi", "erasetextwindow", "lookbackflush", "waittimer", "getcselnum",
        "gettext", "getscreenshot", "ispage", "getcursorpos", "getenter",
        "getcursor", "getskipoff", "textbtnwait", "automode_time", "input",
        "savescreenshot2", "savegame", "savetime", "loadgame", "menu_click_def",
        "menu_click_page", "menu_window", "menu_full", "movl", "waveloop",
        "mp3loop", "wavestop", "avi", "quakey", "lsph", "msp",
        "monocrooff", "vsp2", "cselstr", "getspmode", "se", "jmp",
    )

    /** Commands that accept no arguments at all. */
    val NO_ARG: Set<String> = setOf(
        "return", "break", "texton", "textoff", "textclear", "click", "lrclick",
        "bgmstop", "dwavestop", "dwaveon", "dwaveoff", "mp3stop", "se",
        "skip", "skipoff", "reset", "end", "quit", "flushout", "nega", "glass",
        "barclear", "screen_clear2", "monocro",
    )

    /** Longest-first ordering of [BUILTIN], used by the tokeniser. */
    val BY_LENGTH_DESC: List<String> = BUILTIN.sortedByDescending { it.length }

    fun isBuiltin(token: String): Boolean = BUILTIN.contains(token)

    /**
     * Tokenise a command line body.
     *
     * @param body a line with indentation, comments and the trailing CR already removed.
     * @param defsubs names registered by `defsub`, which behave like commands.
     * @return null when [body] is not a command line at all.
     */
    fun tokenise(body: String, defsubs: Set<String>): Tokenised? {
        if (body.isEmpty()) return null
        val c0 = body[0]
        if (c0 == '*') return null
        // A digit or quote at the start means dialogue continuation, never a command.
        if (c0.isDigit() || c0 == '"' || c0 == '@' || c0 == '\\' || c0 == ';') return null

        val token = readToken(body) ?: return null
        if (!isBuiltin(token) && !defsubs.contains(token)) return null

        val rest = body.substring(token.length)
        val argsPart = if (rest.startsWith(" ") || rest.startsWith("\t")) rest.trimStart(' ', '\t') else {
            // Command immediately followed by something that is not a separator: not a command
            // after all (e.g. dialogue beginning with a word that happens to be a command).
            if (rest.isNotEmpty()) return null else ""
        }

        val (args, trailing) = if (token in STATEMENT_COMMANDS) splitStatement(argsPart) else (argsPart to null)
        return Tokenised(token, args, trailing)
    }

    class Tokenised(val command: String, val arguments: String, val trailingText: String?)

    /**
     * Read the leading command token.
     *
     * NScripter command names are ASCII letters, digits and underscores. We scan the maximal
     * ASCII-alphanumeric run, then fall back to the longest registered prefix so that
     * `lsp2` is not mis-read as `lsp`, and `print 3` is not mis-read as `print3` being unknown.
     */
    private fun readToken(body: String): String? {
        var end = 0
        while (end < body.length) {
            val c = body[end]
            if (c.isLetter() && c.code < 128 || c == '_' || (end > 0 && c.isDigit() && c.code < 128)) {
                end++
            } else {
                break
            }
        }
        if (end == 0) return null
        val run = body.substring(0, end)
        if (isBuiltin(run)) return run

        // Overlapping-prefix fallback: try progressively shorter prefixes, longest first.
        for (len in run.length - 1 downTo 1) {
            val prefix = run.substring(0, len)
            if (isBuiltin(prefix)) return prefix
        }
        return run
    }

    /**
     * Separate `<numeric args>` from `<trailing dialogue>` for the statement commands.
     *
     * `print 10,3000` and `print` are pure statements; `lsp 700,":s/...#FFFFFF;text",152,83` is
     * pure arguments (the text lives inside the quoted inline string); `name 爱丽丝` has a
     * numeric/string argument set followed by nothing. The ambiguous case is a dialogue line such
     * as `print 3 这是对白`, where the trailing run after the last numeric argument is the text.
     */
    private fun splitStatement(argsPart: String): Pair<String, String?> {
        if (argsPart.isEmpty()) return "" to null
        val parts = Args.split(argsPart)
        var lastArgEnd = -1
        var consumed = true
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty()) break
            if (!isPureArgument(p)) { consumed = false; break }
            lastArgEnd = i
        }
        if (!consumed || lastArgEnd < 0) {
            return "" to argsPart
        }
        // Rebuild the argument prefix textually so the VM keeps the original spacing.
        var idx = 0
        var count = 0
        var inQuote = false
        var depth = 0
        while (idx < argsPart.length) {
            val c = argsPart[idx]
            when {
                inQuote -> if (c == '"') inQuote = false
                c == '"' -> inQuote = true
                c == '[' -> depth++
                c == ']' -> depth--
                c == ',' && depth == 0 -> count++
            }
            if (count > lastArgEnd + 1) break
            idx++
        }
        // idx now sits just past the comma that ends the last pure argument (or at the end).
        val cut = if (count > lastArgEnd + 1) idx - 1 else argsPart.length
        val args = argsPart.substring(0, cut).trimEnd()
        val trailing = argsPart.substring(cut).trimStart(',').trim()
        return args to if (trailing.isEmpty()) null else trailing
    }

    /** True when [s] is a bare number, a variable reference, or a quoted literal. */
    fun isPureArgument(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (Args.isQuoted(t)) return true
        if (t.startsWith(":")) return false
        if (t.startsWith("%") || t.startsWith("$")) return true
        if (t.startsWith("#")) return true
        // numbers, including negatives and 0x
        if (t.matches(Regex("^[+-]?(0[xX][0-9a-fA-F]+|[0-9]+)$"))) return true
        // arithmetic expressions over numbers/variables
        if (t.matches(Regex("^[0-9a-zA-Z_%$\\[\\]()+\\-*/%<>=!&| ]+$")) &&
            !t.contains(' ') && t.any { it.isDigit() || it == '%' || it == '$' }
        ) {
            return true
        }
        return false
    }
}
