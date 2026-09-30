package mgq.core.script

/**
 * Turns decoded `nscript.dat` text into a jump-resolved [Script].
 *
 * Parsing happens in two passes:
 *
 *  **Pass 1** splits the text into [Instruction]s, records labels, `numalias`/`stralias` and
 *  `defsub` declarations. This pass must complete before any expression can be parsed, because
 *  `numalias` determines the numeric slot a name refers to and a name may be used before its
 *  `numalias` line in file order (the real script declares its aliases inside `*define`, which is
 *  `goto`'d to first, so textual order is not enough).
 *
 *  **Pass 2** resolves control flow: `goto`/`gosub` targets, `if` block extents from indentation,
 *  `for`/`next` pairing, and `defsub` bodies.
 *
 * Everything unresolvable is recorded rather than thrown: a 382k-line script from a fan
 * translation always contains a few oddities, and the porting report needs to list them instead
 * of failing at the first one.
 */
class ScriptParser(private val sourceName: String) {

    /** Non-fatal problems found while parsing. Surfaced as a report, never as an exception. */
    val problems: MutableList<String> = ArrayList()

    /** `goto`/`gosub` targets with no matching `*label`. */
    val brokenJumps: MutableMap<String, Int> = LinkedHashMap()

    /** Commands that were used but are not built-ins and not defsubs. */
    val unknownCommands: MutableMap<String, Int> = LinkedHashMap()

    fun parse(text: String): Script {
        val script = Script(sourceName)
        pass1(text, script)
        pass2(script)
        script.unknownCommands.putAll(unknownCommands)
        return script
    }

    // ---------------------------------------------------------------------------------------
    // Pass 1: line splitting, labels, aliases, defsubs
    // ---------------------------------------------------------------------------------------

    private fun pass1(text: String, script: Script) {
        // Strip a UTF-8 BOM if the fan patch added one.
        val body = if (text.startsWith('\uFEFF')) text.substring(1) else text

        var lineNo = 0
        var i = 0
        val n = body.length
        val defsubNames = HashSet<String>()

        while (i <= n) {
            var end = body.indexOf('\n', i)
            if (end < 0) {
                if (i >= n) break
                end = n
            }
            lineNo++
            var line = body.substring(i, end)
            if (line.endsWith("\r")) line = line.dropLast(1)
            i = end + 1

            val indent = line.indexOfFirst { it != ' ' && it != '\t' }
            val trimmed = if (indent < 0) "" else line.substring(indent)

            if (trimmed.isEmpty()) {
                addEmpty(script, lineNo, line, 0)
                continue
            }
            if (trimmed[0] == ';') {
                addEmpty(script, lineNo, line, indent)
                continue
            }
            if (trimmed[0] == '*') {
                // A label line may carry an inline command: the target game contains
                //   *wind_guard_kakuritu:if %wind_guard_on=0 skip 2
                // The label is everything up to the first ':' when there is no whitespace
                // before it; the remainder is an inline command that executes on jump.
                val colon = trimmed.indexOf(':')
                val labelText = if (colon > 0) trimmed.substring(0, colon) else trimmed
                val name = labelText.trimEnd()
                val idx = script.instructions.size
                script.instructions.add(
                    Instruction(
                        index = idx, lineNumber = lineNo, indent = 0, kind = Instruction.Kind.LABEL,
                        command = null, arguments = "", trailingText = null, raw = line,
                    )
                )
                if (script.labels.containsKey(name)) {
                    problems.add("L$lineNo: duplicate label $name (first at instruction ${script.labels[name]})")
                } else {
                    script.labels[name] = idx
                }
                if (colon > 0) {
                    val inlineBody = stripComment(trimmed.substring(colon + 1))
                    if (inlineBody.isNotEmpty()) {
                        emitLine(script, lineNo, line, 0, inlineBody, defsubNames)
                    }
                }
                continue
            }

            val bodyNoComment = stripComment(trimmed)
            if (bodyNoComment.isEmpty()) {
                addEmpty(script, lineNo, line, indent)
                continue
            }
            emitLine(script, lineNo, line, indent, bodyNoComment, defsubNames, rawForText = trimmed)
        }

        // Second half of pass 1: now that every defsub is known, re-tokenise any line that was
        // classified as dialogue but which is actually a call to a defsub defined further down
        // (the real script defines its macros in `*define`, which appears *before* use, but a
        // re-tokenise is cheap insurance for patched scripts).
        retokeniseTextLines(script, defsubNames)
    }

    /**
     * Classify and emit one non-empty, comment-stripped line.
     *
     * Shared by the main loop and by label lines that carry an inline command, so both paths
     * produce identical instruction streams.
     *
     * The `if` handling below is the subtle part. NScripter accepts three shapes:
     *
     * ```
     *   if %x=1              <- block form, body is the following indented lines
     *   if %x=1: mov %y,2    <- colon-chained single line
     *   if %x=1 skip 3       <- NO colon, single line  (the target game uses this ~2000 times)
     * ```
     *
     * For the third shape the condition and the command are separated only by whitespace, so the
     * split point has to be inferred. We take the longest leading token run that parses as a valid
     * condition and treat the remainder as the command; if no prefix parses, the whole thing is
     * tried as a colon form and finally reported as a problem.
     */
    private fun emitLine(
        script: Script,
        lineNo: Int,
        raw: String,
        indent: Int,
        bodyNoComment: String,
        defsubNames: MutableSet<String>,
        rawForText: String = bodyNoComment,
    ) {
        val tok = Commands.tokenise(bodyNoComment, defsubNames)
        if (tok == null) {
            val idx = script.instructions.size
            script.instructions.add(
                Instruction(
                    index = idx, lineNumber = lineNo, indent = indent, kind = Instruction.Kind.TEXT,
                    command = null, arguments = "", trailingText = rawForText, raw = raw,
                )
            )
            return
        }

        // Record declarations that later passes depend on.
        when (tok.command) {
            "numalias" -> {
                val parts = Args.split(tok.arguments)
                if (parts.size >= 2) {
                    val name = parts[0].trim()
                    val slot = Args.toInt(parts[1], Int.MIN_VALUE)
                    if (slot != Int.MIN_VALUE) {
                        val prev = script.numAliases.put(name, slot)
                        if (prev != null && prev != slot) {
                            problems.add("L$lineNo: numalias $name redefined $prev -> $slot")
                        }
                    } else {
                        problems.add("L$lineNo: numalias $name has non-numeric slot '${parts[1]}'")
                    }
                } else {
                    problems.add("L$lineNo: numalias needs 2 arguments, got ${parts.size}")
                }
            }
            "stralias" -> {
                val parts = Args.split(tok.arguments)
                if (parts.size >= 2) {
                    script.strAliases[parts[0].trim()] = Args.toInt(parts[1])
                }
            }
            "defsub" -> {
                val name = Args.split(tok.arguments).firstOrNull()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    defsubNames.add(name)
                    script.definedSubs[name] = "*$name"
                } else {
                    problems.add("L$lineNo: defsub without a name")
                }
            }
        }

        val idx = script.instructions.size
        script.instructions.add(
            Instruction(
                index = idx, lineNumber = lineNo, indent = indent, kind = Instruction.Kind.COMMAND,
                command = tok.command, arguments = tok.arguments, trailingText = tok.trailingText,
                raw = raw,
            )
        )
    }

    /**
     * Split `if <condition> [<command> [: <command>...]]` into its two halves.
     *
     * Returns the condition text and the inline-command text (or null for a block `if`).
     *
     * Three shapes occur in the target game, and one line can combine the last two:
     *
     * ```
     *   if %x=1                          block form (2 occurrences in the whole script)
     *   if %x=1: mov %y,2                colon only
     *   if %x=1 skip 3                   no colon at all (~48k occurrences)
     *   if %x=2 mov %x,1: goto *label    no colon AND chained colon (this is the trap)
     * ```
     *
     * The last shape is why the colon cannot simply be taken as the condition/command boundary:
     * a colon may appear inside the command part. The condition is therefore found by the
     * longest-prefix rule FIRST, and only then is the remainder split on top-level colons.
     */
    private fun splitIf(arguments: String, lineNo: Int, aliases: Map<String, Int>): Pair<String, String?> {
        val text = arguments.trim()
        if (text.isEmpty()) return "" to null

        val tokens = text.split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return "" to null

        // Attempt 1 — a top-level colon is the most reliable separator when present, because the
        // condition never contains a bare `:`. This handles `if %x=1:goto *y` and the mixed form
        // `if %x=2 mov %x,1: goto *y` (where the colon sits inside the command part).
        val colon = findTopLevelColon(text)
        if (colon > 0) {
            val cand = text.substring(0, colon).trim()
            if (ExprParser.parseCondition(cand, aliases) != null) {
                val rest = text.substring(colon + 1).trim()
                return cand to rest.ifEmpty { null }
            }
        }

        // Attempt 2 — no usable colon: find the longest whitespace-separated prefix that parses as a
        // condition (`if %x=1 skip 3`).
        for (take in tokens.size downTo 1) {
            val prefix = text.splitKeepingOffset(tokens, take)
            val rawCond = prefix.text
            if (ExprParser.parseCondition(rawCond, aliases) == null) continue
            var condText = rawCond
            var rest = prefix.rest
            val boundary = findCommandBoundary(condText)
            if (boundary > 0) {
                rest = condText.substring(boundary).trim() + (if (rest.isEmpty()) "" else " " + rest)
                condText = condText.substring(0, boundary)
            }
            if (rest.startsWith(":")) rest = rest.substring(1).trim()
            return condText.trim() to rest.trim().ifEmpty { null }
        }

        // Attempt 3 — the author omitted the space between condition and command entirely
        // (`if %3083>0mov %list_num3,1`). Cut at the command token.
        val boundary = findCommandBoundary(text)
        if (boundary > 0) {
            val cand = text.substring(0, boundary).trim()
            if (ExprParser.parseCondition(cand, aliases) != null) {
                return cand to text.substring(boundary).trim().ifEmpty { null }
            }
        }

        // Nothing parsed as a condition. Fall back to a bare colon split so the caller still sees
        // a usable condition text, and record the failure.
        if (colon > 0) {
            problems.add("L$lineNo: unparseable if condition '${text.substring(0, colon).trim()}'")
            return text.substring(0, colon).trim() to text.substring(colon + 1).trim().ifEmpty { null }
        }
        problems.add("L$lineNo: unparseable if condition '$text'")
        return text to null
    }

    /**
     * Return the text of the first [take] whitespace-separated tokens plus the untouched remainder.
     *
     * Splitting on `' '` and rejoining would normalise tabs to spaces, which breaks every
     * multi-argument command (the script indents with tabs). This walks the original string instead.
     */
    private fun String.splitKeepingOffset(tokens: List<String>, take: Int): SplitResult {
        var pos = 0
        var seen = 0
        val len = length
        while (pos < len && seen < take) {
            // skip separators (both space and tab: the script indents with tabs)
            while (pos < len && (this[pos] == ' ' || this[pos] == '\t')) pos++
            if (pos >= len) break
            while (pos < len && this[pos] != ' ' && this[pos] != '\t') pos++
            seen++
        }
        val head = substring(0, pos).trimEnd()
        val tail = substring(pos).trim()
        return SplitResult(head, tail)
    }

    private class SplitResult(val text: String, val rest: String)

    companion object {
        /**
         * Matches the start of a command token inside a would-be condition, used only to repair the
         * handful of script lines where the author omitted the space before the command
         * (`if %3083>0mov %list_num3,1`).
         *
         * The left side must be a digit, `)`, `]`, or `"` — never a letter — so a variable named
         * `%movecount` can never be mistaken for the `mov` command.
         */
        private val COMMAND_BOUNDARY: Regex =
            Regex("(?<=[0-9)\\]\"])(goto|gosub|mov|add|sub|inc|dec|skip|if|notif|return|break)\\b")
    }

    /**
     * Find the index where a command token begins inside a would-be condition.
     *
     * Guards against matching inside an identifier: the token must start at the beginning, after a
     * digit, `)`, `]`, `"`, or whitespace. That is what makes `%3083>0mov` split at `mov` while
     * leaving a variable named `%movecount` alone.
     */
    private fun findCommandBoundary(text: String): Int {
        val m = COMMAND_BOUNDARY.find(text) ?: return -1
        val at = m.range.first
        return if (at <= 0) -1 else at
    }

    /** Split a command chain on top-level `:` separators. */
    private fun splitCommandChain(text: String): List<String> {
        val out = ArrayList<String>(2)
        var inQuote = false
        var depthParen = 0
        var depthBracket = 0
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            when {
                inQuote -> if (c == '"') inQuote = false
                c == '"' -> inQuote = true
                c == '(' -> depthParen++
                c == ')' -> depthParen--
                c == '[' -> depthBracket++
                c == ']' -> depthBracket--
                c == ':' && depthParen == 0 && depthBracket == 0 -> {
                    val part = text.substring(start, i).trim()
                    if (part.isNotEmpty()) out.add(part)
                    start = i + 1
                }
            }
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    /** Find a `:` that separates a condition from a chained command, ignoring quotes. */
    private fun findTopLevelColon(text: String): Int {
        var inQuote = false
        var depthParen = 0
        var depthBracket = 0
        for (i in text.indices) {
            val c = text[i]
            when {
                inQuote -> if (c == '"') inQuote = false
                c == '"' -> inQuote = true
                c == '(' -> depthParen++
                c == ')' -> depthParen--
                c == '[' -> depthBracket++
                c == ']' -> depthBracket--
                c == ':' && depthParen == 0 && depthBracket == 0 -> return i
            }
        }
        return -1
    }

    private fun addEmpty(script: Script, lineNo: Int, raw: String, indent: Int) {
        val idx = script.instructions.size
        script.instructions.add(
            Instruction(
                index = idx, lineNumber = lineNo, indent = indent, kind = Instruction.Kind.EMPTY,
                command = null, arguments = "", trailingText = null, raw = raw,
            )
        )
    }

    private fun retokeniseTextLines(script: Script, defsubNames: Set<String>) {
        if (defsubNames.isEmpty()) return
        for (ins in script.instructions) {
            if (ins.kind != Instruction.Kind.TEXT) continue
            val bodyNoComment = stripComment(ins.raw.trimStart(' ', '\t'))
            if (bodyNoComment.isEmpty()) continue
            val tok = Commands.tokenise(bodyNoComment, defsubNames) ?: continue
            if (!defsubNames.contains(tok.command)) continue
            // replace in place
            val replaced = Instruction(
                index = ins.index, lineNumber = ins.lineNumber, indent = ins.indent,
                kind = Instruction.Kind.COMMAND, command = tok.command, arguments = tok.arguments,
                trailingText = tok.trailingText, raw = ins.raw,
            )
            script.instructions[ins.index] = replaced
        }
    }

    /**
     * Strip a trailing `;` comment.
     *
     * A `;` only starts a comment when it is outside a quoted string, and NScripter treats
     * `;` at the very start of a line as a comment too (handled by the caller).
     */
    private fun stripComment(line: String): String {
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQuote = !inQuote
                c == ';' && !inQuote -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    // ---------------------------------------------------------------------------------------
    // Pass 2: control-flow resolution
    // ---------------------------------------------------------------------------------------

    private fun pass2(script: Script) {
        val ins = script.instructions
        resolveJumps(script, ins)
        resolveIfBlocks(script, ins)
        resolveForLoops(script, ins)
    }

    private fun resolveJumps(script: Script, ins: List<Instruction>) {
        for (instruction in ins) {
            val cmd = instruction.command ?: continue
            when (cmd) {
                "goto", "gosub", "jmp" -> {
                    val target = Args.split(instruction.arguments).firstOrNull()?.trim().orEmpty()
                    if (target.isEmpty()) {
                        problems.add("L${instruction.lineNumber}: $cmd without a target")
                        continue
                    }
                    val t = if (target.startsWith("*")) target else "*$target"
                    // Some jump targets are written with a trailing `:command` (the game defines
                    // `*syasei1:gosub *syasei2` and then jumps to `*syasei1`). The label map key
                    // is the part before the colon.
                    val key = t.substringBefore(':')
                    val idx = script.labels[key]
                    if (idx == null) {
                        brokenJumps[t] = (brokenJumps[t] ?: 0) + 1
                    } else {
                        instruction.flow = Flow.Jump(idx)
                    }
                }
            }
        }
    }

    /**
     * Resolve `if` blocks from indentation.
     *
     * The engine's rule is positional: an `if` body extends to the first non-empty line whose
     * indentation is less than or equal to the `if`'s own indentation.
     */
    private fun resolveIfBlocks(script: Script, ins: List<Instruction>) {
        // Snapshot the list size: creating inline instructions for the non-colon form appends to
        // the list, and those appended instructions must not be visited by this same loop.
        val originalSize = ins.size
        for (i in 0 until originalSize) {
            val instruction = ins[i]
            if (instruction.command != "if" && instruction.command != "notif") continue

            val (condText, inlineText) = splitIf(instruction.arguments, instruction.lineNumber, script.numAliases)
            val cond = ExprParser.parseCondition(condText, script.numAliases)
            if (cond == null) {
                problems.add("L${instruction.lineNumber}: unparseable if condition '$condText'")
                continue
            }

            val isNot = instruction.command == "notif"
            val effective = if (isNot) Condition.Not(cond) else cond

            // Inline commands: `if cond: cmd`, `if cond cmd`, and chains such as
            // `if %x=2 mov %x,1: goto *label`. Each chained command becomes a real instruction
            // immediately after the `if`; a false condition jumps over all of them.
            var inlineCount = 0
            if (inlineText != null) {
                for (part in splitCommandChain(inlineText)) {
                    val before = script.instructions.size
                    emitLine(
                        script, instruction.lineNumber, instruction.raw, instruction.indent,
                        part, script.definedSubs.keys.toMutableSet(),
                    )
                    if (script.instructions.size > before) inlineCount++
                }
            }

            val myIndent = instruction.indent
            var end = instruction.index + 1
            while (end < script.instructions.size) {
                val cand = script.instructions[end]
                // A label is a hard boundary: the next block always starts a new scope, and the
                // target game's one real block-`if` (`*credit_scroll`) relies on this so that the
                // `for` body inside it is still executed when the condition is false.
                if (cand.kind == Instruction.Kind.LABEL) break
                if (cand.kind == Instruction.Kind.EMPTY) { end++; continue }
                // Same or lower indentation ends the block.
                if (cand.indent <= myIndent) break
                end++
            }

            instruction.flow = Flow.If(
                condition = effective,
                blockEnd = if (inlineCount > 0) instruction.index + inlineCount + 1 else end,
                isBlock = inlineCount == 0,
                inline = null,
                blockLimit = end,
            )
        }
    }

    /** Pair `for` with its matching `next`, tracking nesting. */
    private fun resolveForLoops(script: Script, ins: List<Instruction>) {
        val open = ArrayDeque<Instruction>()
        for (instruction in ins) {
            when (instruction.command) {
                "for" -> open.addLast(instruction)
                "next" -> {
                    val f = open.removeLastOrNull()
                    if (f == null) {
                        problems.add("L${instruction.lineNumber}: next without matching for")
                    } else {
                        val parts = Args.split(f.arguments)
                        val varPart = parts.getOrNull(0)?.trim().orEmpty()
                        val varRef = parseVarRef(varPart)
                        if (varRef == null || varRef.isString) {
                            problems.add("L${f.lineNumber}: for needs a numeric variable, got '$varPart'")
                        } else {
                            // `for %i=1 to 10` is written as `for %i,1,10` in some dialects and
                            // `for %i=1 to 10` in others; accept both.
                            val range = parseForRange(f.arguments, varRef)
                            if (range == null) {
                                problems.add("L${f.lineNumber}: unparseable for range '${f.arguments}'")
                            } else {
                                val (from, to, step) = range
                                f.flow = Flow.For(
                                    variable = varRef, from = from, to = to, step = step,
                                    bodyStart = f.index + 1, nextIndex = instruction.index,
                                )
                            }
                        }
                    }
                }
            }
        }
        for (f in open) {
            problems.add("L${f.lineNumber}: for without matching next")
        }
    }

    private fun parseForRange(text: String, varRef: VarRef): Triple<Expr, Expr, Expr?>? {
        val body = text.substringAfter(',', "").ifEmpty { text }
        val eq = body.indexOf('=')
        val rest = if (eq >= 0) body.substring(eq + 1) else body
        val toIdx = rest.indexOf(" to ")
        return if (toIdx >= 0) {
            val fromS = rest.substring(0, toIdx).trim()
            var toS = rest.substring(toIdx + 4).trim()
            var stepS: String? = null
            val stepIdx = toS.indexOf(" step ")
            if (stepIdx >= 0) {
                stepS = toS.substring(stepIdx + 6).trim()
                toS = toS.substring(0, stepIdx).trim()
            }
            val f = ExprParser.parse(fromS, emptyMap()) ?: return null
            val t = ExprParser.parse(toS, emptyMap()) ?: return null
            val s = stepS?.let { ExprParser.parse(it, emptyMap()) }
            Triple(f, t, s)
        } else {
            val parts = Args.split(rest)
            if (parts.size < 2) return null
            val f = ExprParser.parse(parts[0], emptyMap()) ?: return null
            val t = ExprParser.parse(parts[1], emptyMap()) ?: return null
            val s = parts.getOrNull(2)?.let { ExprParser.parse(it, emptyMap()) }
            Triple(f, t, s)
        }
    }

    private fun parseVarRef(text: String): VarRef? {
        val t = text.trim()
        if (t.length < 2) return null
        val isString = t[0] == '$'
        if (t[0] != '%' && t[0] != '$') return null
        val name = t.substring(1).trim()
        if (name.isEmpty()) return null
        if (name.contains('[')) {
            val base = name.substringBefore('[').trim()
            val idxText = name.substringAfter('[').substringBeforeLast(']').trim()
            return VarRef(base, isString, ExprParser.parse(idxText, emptyMap()))
        }
        return VarRef(name, isString, null)
    }
}
