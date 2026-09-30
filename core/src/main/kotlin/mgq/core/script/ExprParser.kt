package mgq.core.script

/**
 * Recursive-descent parser for NScripter expressions.
 *
 * The grammar is small and C-like. Precedence, lowest first:
 *
 * ```
 *   or  ||  |
 *   and &&  &
 *   = == != <> > < >= <=
 *   + -
 *   * / %
 *   unary - !
 *   primary: number | "string" | %var | $var | %var[expr] | *label | identifier (bare name)
 * ```
 *
 * Notes on the original engine's quirks, which this parser preserves:
 *
 *  - `=` is comparison inside a condition (`if %x=1`), never assignment. Assignment is `mov`.
 *  - A bare identifier that is not a variable is a *string constant*, which is how
 *    `name alice` style arguments work. It is emitted as [Expr.Str].
 *  - `*label` as an operand evaluates to the label index; this is how `gosub` arguments and
 *    `getparam`-based dispatch are written.
 *  - Unary `!` and `not` are both accepted.
 */
class ExprParser(private val src: String, private val aliases: Map<String, Int>) {
    private var pos = 0

    companion object {
        /** Parse a full expression, or null when the text is not a valid expression. */
        fun parse(text: String, aliases: Map<String, Int> = emptyMap()): Expr? =
            try {
                val p = ExprParser(text, aliases)
                val e = p.parseOr()
                p.skipWs()
                if (p.pos < p.src.length) null else e
            } catch (_: Exception) {
                // Deliberately catch broad: a malformed fragment must make this candidate prefix
                // fail, not abort the whole `if` parse. `ExprParser` throws ParseException, but
                // indexing edge cases can surface as other runtime exceptions.
                null
            }

        /** Parse a condition, or null when the text is not a valid condition. */
        fun parseCondition(text: String, aliases: Map<String, Int> = emptyMap()): Condition? =
            try {
                parseConditionInner(text, aliases)
            } catch (_: Exception) {
                null
            }

        private fun parseConditionInner(text: String, aliases: Map<String, Int>): Condition? {
            val t = text.trim()
            if (t.isEmpty()) return null

            // NScripter's `if` accepts a boolean composition of comparisons:
            //     if %hanyo1=1 && %hanyo2=1 && %hanyo3=1 goto *x
            //     if %a=1 || %b=2 mov %c,3
            // The `&&` / `||` operators have lower precedence than the comparisons, so they must be
            // split out FIRST. Missing this was a real bug: `%a=1 && %b=1` was being split at the
            // first `=`, producing the nonsense condition `%a  ==  (1 && %b=1)`.
            val logic = splitTopLevelLogic(t)
            if (logic != null) {
                val (left, op, right) = logic
                val lc = parseCondition(left, aliases) ?: return null
                val rc = parseCondition(right, aliases) ?: return null
                return if (op == "&&") Condition.And(lc, rc) else Condition.Or(lc, rc)
            }

            // Fast path: a single comparison of the form <expr> <op> <expr>.
            val cmp = splitTopLevelComparison(t)
            if (cmp != null) {
                val (l, op, r) = cmp
                val le = parse(l, aliases) ?: return null
                val re = parse(r, aliases) ?: return null
                return Condition.Compare(le, op, re)
            }
            val e = parse(t, aliases) ?: return null
            return Condition.Truthy(e)
        }

        /**
         * Find a top-level `&&` or `||`.
         *
         * Scans left to right and returns at the FIRST logical operator found, which makes the
         * result left-associative, matching the engine's behaviour of evaluating the leftmost
         * condition first.
         */
        private fun splitTopLevelLogic(t: String): Triple<String, String, String>? {
            var depthParen = 0
            var depthBracket = 0
            var inQuote = false
            var i = 0
            while (i < t.length) {
                val c = t[i]
                when {
                    inQuote -> if (c == '"') inQuote = false
                    c == '"' -> inQuote = true
                    c == '(' -> depthParen++
                    c == ')' -> depthParen--
                    c == '[' -> depthBracket++
                    c == ']' -> depthBracket--
                    depthParen == 0 && depthBracket == 0 -> {
                        val two = if (i + 1 < t.length) t.substring(i, i + 2) else ""
                        if (two == "&&" || two == "||") {
                            val left = t.substring(0, i).trim()
                            val right = t.substring(i + 2).trim()
                            if (left.isNotEmpty() && right.isNotEmpty()) {
                                return Triple(left, two, right)
                            }
                        }
                    }
                }
                i++
            }
            return null
        }

        /**
         * Find a comparison operator at bracket depth 0, scanning left to right and preferring
         * the earliest operator (NScripter evaluates the first comparison it meets).
         */
        private fun splitTopLevelComparison(t: String): Triple<String, String, String>? {
            var depthParen = 0
            var depthBracket = 0
            var inQuote = false
            var i = 0
            while (i < t.length) {
                val c = t[i]
                when {
                    inQuote -> if (c == '"') inQuote = false
                    c == '"' -> inQuote = true
                    c == '(' -> depthParen++
                    c == ')' -> depthParen--
                    c == '[' -> depthBracket++
                    c == ']' -> depthBracket--
                    depthParen == 0 && depthBracket == 0 -> {
                        val two = if (i + 1 < t.length) t.substring(i, i + 2) else ""
                        when {
                            two == "==" || two == "!=" || two == "<>" || two == ">=" || two == "<=" -> {
                                return Triple(t.substring(0, i), two, t.substring(i + 2))
                            }
                            c == '=' || c == '>' || c == '<' -> {
                                return Triple(t.substring(0, i), c.toString(), t.substring(i + 1))
                            }
                        }
                    }
                }
                i++
            }
            return null
        }
    }

    class ParseException(msg: String) : RuntimeException(msg)

    private fun skipWs() {
        while (pos < src.length && src[pos] == ' ') pos++
    }

    private fun peek(): Char? = if (pos < src.length) src[pos] else null

    private fun eat(s: String): Boolean {
        if (src.startsWith(s, pos)) { pos += s.length; return true }
        return false
    }

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (true) {
            skipWs()
            if (eat("||")) { left = Expr.Bin("||", left, parseAnd()) }
            else if (peek() == '|') { pos++; left = Expr.Bin("|", left, parseAnd()) }
            else return left
        }
    }

    private fun parseAnd(): Expr {
        var left = parseComparison()
        while (true) {
            skipWs()
            if (eat("&&")) { left = Expr.Bin("&&", left, parseComparison()) }
            else if (peek() == '&') { pos++; left = Expr.Bin("&", left, parseComparison()) }
            else return left
        }
    }

    private fun parseComparison(): Expr {
        var left = parseAdditive()
        while (true) {
            skipWs()
            val saved = pos
            val op = when {
                eat("==") -> "=="
                eat("!=") -> "!="
                eat("<>") -> "!="
                eat(">=") -> ">="
                eat("<=") -> "<="
                peek() == '=' -> { pos++; "=" }
                peek() == '>' -> { pos++; ">" }
                peek() == '<' -> { pos++; "<" }
                else -> { pos = saved; return left }
            }
            left = Expr.Bin(op, left, parseAdditive())
        }
    }

    private fun parseAdditive(): Expr {
        var left = parseMultiplicative()
        while (true) {
            skipWs()
            when (peek()) {
                '+' -> { pos++; left = Expr.Bin("+", left, parseMultiplicative()) }
                '-' -> { pos++; left = Expr.Bin("-", left, parseMultiplicative()) }
                else -> return left
            }
        }
    }

    private fun parseMultiplicative(): Expr {
        var left = parseUnary()
        while (true) {
            skipWs()
            when (peek()) {
                '*' -> { pos++; left = Expr.Bin("*", left, parseUnary()) }
                '/' -> { pos++; left = Expr.Bin("/", left, parseUnary()) }
                '%' -> {
                    // `%` is ambiguous: modulo here, but a variable sigil at the start of a
                    // primary. It is modulo only when it directly follows an operand.
                    pos++; left = Expr.Bin("%", left, parseUnary())
                }
                else -> return left
            }
        }
    }

    private fun parseUnary(): Expr {
        skipWs()
        when (peek()) {
            '-' -> { pos++; return Expr.Un("-", parseUnary()) }
            '+' -> { pos++; return parseUnary() }
            '!' -> { pos++; return Expr.Un("!", parseUnary()) }
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        skipWs()
        val c = peek() ?: throw ParseException("unexpected end of expression in '$src'")

        if (c == '(') {
            pos++
            val inner = parseOr()
            skipWs()
            if (peek() != ')') throw ParseException("missing ) in '$src'")
            pos++
            return inner
        }

        if (c == '"') {
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos]
                if (ch == '"') {
                    if (pos + 1 < src.length && src[pos + 1] == '"') { sb.append('"'); pos += 2; continue }
                    pos++
                    return Expr.Str(sb.toString())
                }
                sb.append(ch); pos++
            }
            throw ParseException("unterminated string in '$src'")
        }

        if (c == '*' ) {
            // *label used as an operand
            pos++
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            if (pos == start) throw ParseException("empty label reference in '$src'")
            return Expr.Str("*" + src.substring(start, pos))
        }

        if (c == '%' || c == '$') {
            val isString = c == '$'
            pos++
            // `%%name` / `$$name` is indirect: the named variable's *value* is used as the variable
            // number. The skill-counting macros depend on this.
            var indirect = false
            if (peek() == c) { indirect = true; pos++ }
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            if (pos == start) throw ParseException("empty variable name in '$src'")
            val name = src.substring(start, pos)
            skipWs()
            var idx: Expr? = null
            if (peek() == '[') {
                pos++
                idx = parseOr()
                skipWs()
                if (peek() != ']') throw ParseException("missing ] in '$src'")
                pos++
            }
            return Expr.Var(VarRef(name, isString, idx, indirect))
        }

        if (c.isDigit()) {
            val start = pos
            if (src.startsWith("0x", pos) || src.startsWith("0X", pos)) {
                pos += 2
                while (pos < src.length && src[pos].isLetterOrDigit()) pos++
                val text = src.substring(start + 2, pos)
                return Expr.Num(text.toIntOrNull(16) ?: throw ParseException("bad hex '$text'"))
            }
            while (pos < src.length && src[pos].isDigit()) pos++
            return Expr.Num(src.substring(start, pos).toInt())
        }

        if (c.isLetter() || c == '_') {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos)
            skipWs()
            // A bare name followed by ( is a built-in function call such as `rnd`, `len`, `atoi`.
            if (peek() == '(') {
                pos++
                val args = ArrayList<Expr>()
                skipWs()
                if (peek() != ')') {
                    args.add(parseOr())
                    skipWs()
                    while (peek() == ',') { pos++; args.add(parseOr()); skipWs() }
                }
                if (peek() != ')') throw ParseException("missing ) after $name in '$src'")
                pos++
                return Expr.Call(name, args)
            }
            return Expr.Str(name)
        }

        throw ParseException("unexpected character '$c' in '$src'")
    }
}
