package mgq.core.script

import mgq.core.platform.Affine

/**
 * A parsed `nscript.dat`.
 *
 * The original file is flat: labels are lines beginning with `*`, indentation gives nesting for
 * `if`/`for` blocks, and everything else is either a command or a line of dialogue. There is no
 * separate AST pass beyond that, because NScripter is interpreted by a line-oriented VM whose
 * control flow is expressed with `goto`/`gosub` labels, not with structured jumps.
 */
class Script(val sourceName: String) {

    /** Every meaningful line, in file order. */
    val instructions: MutableList<Instruction> = ArrayList()

    /** `*label` name -> index into [instructions]. Case-sensitive, as in the original engine. */
    val labels: MutableMap<String, Int> = HashMap()

    /** `defsub <name>` -> index of the `*name` label that implements it. */
    val definedSubs: MutableMap<String, String> = HashMap()

    /** `numalias <name>,<n>` and `stralias` resolved into variable slots. */
    val numAliases: MutableMap<String, Int> = HashMap()
    val strAliases: MutableMap<String, Int> = HashMap()

    /** Virtual screen size, taken from the `;$V...S800,600` header or defaulted. */
    var screenWidth: Int = 800
    var screenHeight: Int = 600

    /** Every distinct command token that was not recognised as a built-in. */
    val unknownCommands: MutableMap<String, Int> = LinkedHashMap()

    val size: Int get() = instructions.size

    operator fun get(index: Int): Instruction = instructions[index]

    fun labelIndex(name: String): Int? = labels[name]
}

/** One executable line. */
class Instruction(
    /** Index into `Script.instructions`. */
    val index: Int,
    /** 1-based line number in the decoded script, for diagnostics. */
    val lineNumber: Int,
    /** Leading whitespace width; block structure is expressed with indentation. */
    val indent: Int,
    /** What this line is. */
    val kind: Kind,
    /** Command token as written, or null for labels / dialogue / blanks. */
    val command: String?,
    /** Everything after the command token, verbatim. */
    val arguments: String,
    /** Trailing dialogue text for the statement commands, verbatim, or null. */
    val trailingText: String?,
    /** Populated for `if` / `for` / `goto` / `gosub` so the VM does not re-parse each execution. */
    var flow: Flow? = null,
    /** Raw decoded source line, kept for diagnostics and for tooling. */
    val raw: String,
) {
    enum class Kind {
        /** A `*label` definition. Never executed; it is a jump target only. */
        LABEL,

        /** A command line. */
        COMMAND,

        /** A bare line of dialogue. */
        TEXT,

        /** Blank or comment-only line. */
        EMPTY,
        }

    val isCommand: Boolean get() = kind == Kind.COMMAND

    override fun toString(): String = "L$lineNumber:${kind}:${raw.trim()}"
}

/**
 * Pre-resolved control-flow information.
 *
 * Resolving `goto` targets, block boundaries and `if` conditions at parse time keeps the hot VM
 * loop free of string work, which matters: the real script is ~382k lines and the battle system
 * gosubs deeply on every turn.
 */
sealed class Flow {
    /** Jump target for `goto` / `gosub` / `jmp` / `defsub` bodies. */
    class Jump(var target: Int) : Flow()

    /**
     * `if <cond>`.
     *
     * [blockEnd] is the index of the first line at indentation <= the `if`'s own indentation,
     * i.e. where execution continues when the condition is false. [isBlock] is false for the
     * colon-chained single-line form `if cond: cmd`, in which case [inline] holds the chained
     * command and [blockEnd] is ignored.
     */
    class If(
        val condition: Condition,
        var blockEnd: Int,
        val isBlock: Boolean,
        val inline: Instruction?,
        /** Index one past the last line of the block, used when the condition is true. */
        val blockLimit: Int,
    ) : Flow()

    /** `for %i=start to end` — pre-resolved loop bounds and the matching `next`. */
    class For(
        val variable: VarRef,
        val from: Expr,
        val to: Expr,
        val step: Expr?,
        var bodyStart: Int,
        var nextIndex: Int,
    ) : Flow()
}

/**
 * A reference to a numeric (`%x`) or string (`$x`) variable, possibly with an array index.
 *
 * [indirect] models NScripter's double-sigil form `%%name` / `$$name`. There, the *value* of
 * `name` is itself used as the variable number to read:
 *
 * ```
 *   getparam %skill_num      ; %skill_num now holds, say, 3083
 *   if %%skill_num=0 mov ...  ; tests the variable whose slot is 3083
 * ```
 *
 * The target game's skill-counting macros (`skillcount1/2/3`) are built on exactly this, so
 * treating `%%name` as an ordinary name would silently break the battle system.
 */
data class VarRef(
    val name: String,
    val isString: Boolean,
    val index: Expr? = null,
    val indirect: Boolean = false,
) {
    override fun toString(): String =
        (if (isString) "$" else "%") + (if (indirect) "$" else "") + name +
            (index?.let { "[$it]" } ?: "")
}

/** A minimal expression tree. Enough for NScripter's arithmetic and comparison forms. */
sealed class Expr {
    data class Num(val value: Int) : Expr()
    data class Str(val value: String) : Expr()
    data class Var(val ref: VarRef) : Expr()
    data class Bin(val op: String, val left: Expr, val right: Expr) : Expr()
    data class Un(val op: String, val operand: Expr) : Expr()
    data class Call(val name: String, val args: List<Expr>) : Expr()

    override fun toString(): String = when (this) {
        is Num -> value.toString()
        is Str -> "\"$value\""
        is Var -> ref.toString()
        is Bin -> "($left $op $right)"
        is Un -> "$op$operand"
        is Call -> "$name(${args.joinToString(",")})"
    }
}

/** A parsed condition. Kept separate from [Expr] so the VM can short-circuit `and`/`or`. */
sealed class Condition {
    data class Compare(val left: Expr, val op: String, val right: Expr) : Condition()
    data class Truthy(val value: Expr) : Condition()
    data class Not(val inner: Condition) : Condition()
    data class And(val left: Condition, val right: Condition) : Condition()
    data class Or(val left: Condition, val right: Condition) : Condition()
    object Always : Condition()
}

/**
 * Argument helpers.
 *
 * NScripter command arguments are comma separated, but commas also appear inside quoted
 * strings (`print` text, `lsp` inline-string sprites like `":s/20,20,1;#FFFFFF;text"`) and
 * inside array subscripts. Splitting naively on `,` corrupts both cases, so the splitter below
 * is quote- and bracket-aware.
 */
object Args {

    /**
     * Split a raw argument string on top-level commas.
     *
     * Handles: double-quoted strings (with `""` as an escaped quote), square-bracket array
     * subscripts, and parenthesised expressions.
     */
    fun split(raw: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var depthBracket = 0
        var depthParen = 0
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                inQuote -> {
                    if (c == '"') {
                        // "" inside a quoted string is an escaped double quote.
                        if (i + 1 < raw.length && raw[i + 1] == '"') {
                            sb.append('"'); i++
                        } else {
                            inQuote = false; sb.append(c)
                        }
                    } else {
                        sb.append(c)
                    }
                }
                c == '"' -> { inQuote = true; sb.append(c) }
                c == '[' -> { depthBracket++; sb.append(c) }
                c == ']' -> { depthBracket--; sb.append(c) }
                c == '(' -> { depthParen++; sb.append(c) }
                c == ')' -> { depthParen--; sb.append(c) }
                c == ',' && depthBracket == 0 && depthParen == 0 -> {
                    out.add(sb.toString().trim()); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    /** Strip surrounding double quotes and unescape doubled quotes. */
    fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length - 1).replace("\"\"", "\"")
        }
        return t
    }

    fun isQuoted(s: String): Boolean {
        val t = s.trim()
        return t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")
    }

    /** Parse an integer argument, tolerating `0x` prefixes and a leading `+`. */
    fun toInt(s: String, default: Int = 0): Int {
        val t = s.trim()
        if (t.isEmpty()) return default
        return try {
            when {
                t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).toInt(16)
                else -> t.toInt()
            }
        } catch (_: NumberFormatException) {
            default
        }
    }

    /**
     * NScripter colour literals: `#RRGGBB` or `#AARRGGBB`.
     * Returns (r, g, b, a) with a in 0..255; alpha defaults to 255.
     */
    fun toColor(s: String): IntArray? {
        val t = s.trim()
        if (!t.startsWith("#")) return null
        val hex = t.substring(1)
        return try {
            when (hex.length) {
                6 -> intArrayOf(
                    hex.substring(0, 2).toInt(16),
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
                    255,
                )
                8 -> intArrayOf(
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
                    hex.substring(6, 8).toInt(16),
                    hex.substring(0, 2).toInt(16),
                )
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

/** Convenience: does this command draw into a sprite layer? */
fun usesAffine(command: String): Boolean = command == "lsp2" || command == "msp2" || command == "amsp"

/** Blank affine, used when a renderer needs a default. */
val IDENTITY_AFFINE = Affine()
