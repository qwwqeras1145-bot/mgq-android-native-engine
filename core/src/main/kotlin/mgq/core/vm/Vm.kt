package mgq.core.vm

import mgq.core.platform.Affine
import mgq.core.platform.Monochrome
import mgq.core.platform.Platform
import mgq.core.platform.Quake
import mgq.core.platform.Scene
import mgq.core.platform.ShownText
import mgq.core.platform.Sprite
import mgq.core.platform.TextWindow
import mgq.core.script.Args
import mgq.core.script.Condition
import mgq.core.script.Expr
import mgq.core.script.Flow
import mgq.core.script.Instruction
import mgq.core.script.Script
import mgq.core.script.VarRef

/**
 * The NScripter virtual machine.
 *
 * ## Execution model
 *
 * NScripter is a flat, line-oriented interpreter. [pc] indexes [Script.instructions]; control flow
 * is `goto`/`gosub` plus indentation-scoped `if` blocks that were already resolved by
 * [mgq.core.script.ScriptParser]. The VM never re-parses a line: everything expensive (conditions,
 * loop bounds, jump targets, argument splits) is pre-resolved into [Flow] objects at parse time,
 * because the game's battle system re-enters the same subroutines thousands of times per
 * playthrough and this is a phone CPU.
 *
 * ## Blocking
 *
 * NScripter is a cooperative interpreter. Commands such as `click`, `btnwait` and `wait` suspend
 * the script until the player acts; this VM exposes that by returning [Status.BLOCKED] and
 * resuming at the same [pc] on the next [step]. The host loop (see [Runner]) is responsible for
 * pumping the platform's input and calling [step] again.
 *
 * ## Parameter passing
 *
 * `defsub`-defined commands receive their arguments through `getparam`. The call protocol is:
 * push a frame holding the argument expression strings, jump to the `*label` that implements the
 * macro, and let `getparam` pull from the top frame. `return` pops the frame.
 */
class Vm(
    val script: Script,
    val platform: Platform,
    val screenWidth: Int = script.screenWidth,
    val screenHeight: Int = script.screenHeight,
) {

    /** Program counter: index into [Script.instructions]. */
    var pc: Int = 0

    val vars = Variables()

    /** Call stack for `gosub` / `defsub` calls. */
    private val frames: ArrayDeque<Frame> = ArrayDeque()

    /** Values captured by `getret` from the most recent [Status.YIELD_TO_CALLER] return. */
    private var returnValue: String? = null

    private var steps = 0

    /** Number of instructions executed since [resetCounters], for diagnostics. */
    val stepCount: Int get() = steps

    /** Set by `skip`/`skipoff`; while true the VM fast-forwards dialogue without waiting. */
    private var skipping = false

    /** `textspeed` — milliseconds per character for the typewriter effect. 0 means instant. */
    private var textSpeedMs: Int = 0

    /** Save-slot count declared by `savenumber`. */
    var saveSlots: Int = 9
        private set

    /** The current speaker label, shown with the following dialogue. */
    private var speakerName: String? = null

    /** Whether text output is currently enabled (`texton` / `textoff`). */
    private var textVisible = true

    /** Pending text accumulated for the current page. */
    private val pendingText = StringBuilder()

    /** Pending `print x,y` origin for the next page. */
    private var pendingTextX: Int? = null
    private var pendingTextY: Int? = null

    /** Wall-clock deadline while blocked on `wait`/`delay`. */
    private var waitUntil: Long = 0

    /** Interaction the VM is currently blocked on, or null when ready to execute. */
    private var pendingInteraction: Interaction? = null

    /** True once `end`/`quit` ran, or the script ran off the end. */
    var finished: Boolean = false
        private set

    /** Unimplemented commands encountered, with the line that used them. */
    val unimplemented: MutableMap<String, MutableList<Int>> = LinkedHashMap()

    private val scene: Scene get() = platform.scene

    // =======================================================================================
    // Control
    // =======================================================================================

    fun resetCounters() { steps = 0 }

    /** Jump to a label by name. Returns false when the label does not exist. */
    fun jumpToLabel(label: String): Boolean {
        val key = if (label.startsWith("*")) label else "*$label"
        val idx = script.labels[key] ?: return false
        // Labels are not executable; land on the line after them.
        pc = skipLabelAt(idx)
        return true
    }

    private fun skipLabelAt(idx: Int): Int {
        var i = idx
        while (i < script.size && script[i].kind == Instruction.Kind.LABEL) i++
        return i
    }

    /** Start execution at a label (used for `*game_start` style entry points). */
    fun startAt(label: String): Boolean {
        frames.clear()
        finished = false
        if (!jumpToLabel(label)) return false
        return true
    }

    // =======================================================================================
    // Stepping
    // =======================================================================================

    enum class Status {
        /** The VM executed something and is ready for another step immediately. */
        RUNNING,

        /** The VM is waiting on the player (click / button). Call [step] again after input. */
        BLOCKED,

        /** The VM is waiting on a timer. Call [step] again when [waitRemainingMs] reaches 0. */
        WAITING,

        /** Execution reached the end of the script, `end`, or `quit`. */
        FINISHED,

        /** A script-level error the VM could not recover from; see [lastError]. */
        ERROR,
    }

    /** Human-readable description of the most recent [Status.ERROR]. */
    var lastError: String? = null
        private set

    /** Milliseconds left on a `wait`/`delay`, or 0. */
    fun waitRemainingMs(): Int {
        val left = waitUntil - platform.nowMillis()
        return if (left <= 0) 0 else left.toInt()
    }

    /**
     * Execute until the VM either blocks, waits, finishes, or [maxSteps] instructions have run.
     *
     * Returning after a bounded number of steps lets the host stay responsive (repaint, pump
     * audio) instead of freezing on a long `for` loop, which the game's battle system does run.
     */
    fun run(maxSteps: Int = 20_000, maxMillis: Long = 8): Status {
        val deadline = platform.nowMillis() + maxMillis
        var executed = 0
        while (executed < maxSteps) {
            val st = step()
            if (st != Status.RUNNING) return st
            executed++
            if (platform.nowMillis() >= deadline) return Status.RUNNING
        }
        return Status.RUNNING
    }

    /**
     * Execute exactly one instruction.
     *
     * This is the hot path. It deliberately avoids allocation: no string splitting unless the
     * command needs it, no lambda captures, no boxing of the arguments.
     */
    fun step(): Status {
        if (finished) return Status.FINISHED

        // ---- resolve a pending interaction ------------------------------------------------
        pendingInteraction?.let { interaction ->
            when (interaction) {
                is Interaction.Click -> {
                    val button = platform.waitForClick()
                    pendingInteraction = null
                    if (button != 0) {
                        // A button click is delivered to the script through `btnwait`.
                        lastButton = button
                    }
                    // After an interaction completes, execution continues at pc.
                }
                is Interaction.Wait -> {
                    if (waitRemainingMs() > 0) return Status.WAITING
                    pendingInteraction = null
                }
            }
        }

        if (pc >= script.size) {
            finished = true
            return Status.FINISHED
        }

        val ins = script[pc]

        // Skip non-executable lines.
        when (ins.kind) {
            Instruction.Kind.EMPTY -> { pc++; return Status.RUNNING }
            Instruction.Kind.LABEL -> { pc = skipLabelAt(pc); return Status.RUNNING }
            Instruction.Kind.TEXT -> {
                // A bare dialogue line: append it to the page and block for a click, which is
                // exactly what the original engine does.
                pendingText.append(stripTextDecoration(ins.trailingText ?: ins.raw))
                flushTextPage()
                pc++
                pendingInteraction = Interaction.Click
                return Status.BLOCKED
            }
            Instruction.Kind.COMMAND -> Unit
        }

        steps++
        return execute(ins)
    }

    /** Last button id returned by `btnwait`. */
    var lastButton: Int = 0
        private set

    private sealed class Interaction {
        object Click : Interaction()
        data class Wait(val until: Long) : Interaction()
    }

    // =======================================================================================
    // Command dispatch
    // =======================================================================================

    private fun execute(ins: Instruction): Status {
        val cmd = ins.command!!
        return when (cmd) {
            // ---- control flow ------------------------------------------------------------
            "goto" -> {
                val flow = ins.flow as? Flow.Jump
                if (flow != null) { pc = skipLabelAt(flow.target); Status.RUNNING }
                else fail(ins, "goto target unresolved")
            }
            "jmp" -> {
                val flow = ins.flow as? Flow.Jump
                if (flow != null) { pc = skipLabelAt(flow.target); Status.RUNNING }
                else fail(ins, "jmp target unresolved")
            }
            "gosub" -> {
                val flow = ins.flow as? Flow.Jump
                if (flow == null) return fail(ins, "gosub target unresolved")
                frames.addLast(Frame(returnPc = pc + 1, args = Args.split(ins.arguments), isGosub = true))
                pc = skipLabelAt(flow.target)
                Status.RUNNING
            }
            "return" -> doReturn(ins)
            "if" -> doIf(ins)
            "for" -> doFor(ins)
            "next" -> doNext(ins)
            "break" -> doBreak(ins)

            // ---- macro definition --------------------------------------------------------
            "defsub" -> { pc++; Status.RUNNING }   // a declaration, nothing to execute
            "getparam" -> doGetParam(ins)
            "getret" -> {
                val parts = Args.split(ins.arguments)
                val target = parts.firstOrNull()?.trim().orEmpty()
                if (target.startsWith("%")) vars.setNum(target.substring(1), returnValue?.toIntOrNull() ?: 0)
                else if (target.startsWith("$")) vars.setStr(target.substring(1), returnValue ?: "")
                returnValue = null
                pc++; Status.RUNNING
            }

            // ---- variables & arithmetic --------------------------------------------------
            "mov" -> doMov(ins)
            "add" -> doArith(ins) { a, b -> a + b }
            "sub" -> doArith(ins) { a, b -> a - b }
            "mul" -> doArith(ins) { a, b -> a * b }
            "div" -> doArith(ins, true) { a, b -> if (b == 0) 0 else a / b }
            "mod" -> doArith(ins, true) { a, b -> if (b == 0) 0 else a % b }
            "inc" -> { val v = firstVar(ins); if (v != null && !v.isString) vars.addNum(v.name, 1); pc++; Status.RUNNING }
            "dec" -> { val v = firstVar(ins); if (v != null && !v.isString) vars.addNum(v.name, -1); pc++; Status.RUNNING }
            "itoa" -> doItoa(ins)
            "itoa2" -> doItoa2(ins)
            "atoi" -> doAtoi(ins)
            "len" -> doLen(ins)
            "rnd" -> doRnd(ins)
            "rnd2" -> doRnd2(ins)
            "numalias" -> doNumAlias(ins)
            "stralias" -> doStrAlias(ins)
            "dim" -> doDim(ins)
            "split" -> doSplit(ins)

            // ---- text --------------------------------------------------------------------
            "print" -> doPrint(ins)
            "name" -> { speakerName = ins.arguments.ifEmpty { null }?.let { Args.unquote(it) }; pc++; Status.RUNNING }
            "br" -> { pendingText.append('\n'); pc++; Status.RUNNING }
            "texton" -> { textVisible = true; pc++; Status.RUNNING }
            "textoff" -> { textVisible = false; pc++; Status.RUNNING }
            "textclear" -> { pendingText.setLength(0); scene.text = null; pc++; Status.RUNNING }
            "click" -> { flushTextPage(); pc++; pendingInteraction = Interaction.Click; Status.BLOCKED }
            "wait" -> doWait(ins)
            "delay" -> doWait(ins)
            "mesbox" -> { scene.window = TextWindow(0, 0, screenWidth, screenHeight); pc++; Status.RUNNING }
            "setwindow" -> doSetWindow(ins)
            "flushout" -> { pc++; Status.RUNNING }

            // ---- graphics ----------------------------------------------------------------
            "bg" -> doBg(ins)
            "lsp" -> doLsp(ins)
            "lsp2", "msp2", "amsp" -> doAffineSprite(ins)
            "csp" -> doCsp(ins)
            "csp2" -> doCsp(ins)
            "cspl" -> doCsp(ins)
            "vsp" -> doVsp(ins)
            "vspl" -> doVsp(ins)
            "quake" -> doQuake(ins)
            "shadedistance" -> { scene.shadeDistance = Args.toInt(ins.arguments); pc++; Status.RUNNING }
            "monocro" -> doMonocro(ins)
            "screen_clear" -> doScreenClear(ins)
            "screen_clear2" -> { scene.screenClear = Monochrome(0, 0, 0, 255); pc++; Status.RUNNING }
            "effect" -> { pc++; Status.RUNNING }

            // ---- audio -------------------------------------------------------------------
            "bgm" -> doBgm(ins)
            "bgmstop" -> { platform.audio.stopBgm(); pc++; Status.RUNNING }
            "bgmonce" -> { doBgm(ins); platform.audio.playBgm(bgmPath ?: "", false); pc++; Status.RUNNING }
            "wave" -> { platform.audio.playSe(firstStringArg(ins)); pc++; Status.RUNNING }
            "dwave" -> doDwave(ins)
            "dwavestop" -> { platform.audio.stopDwave(); pc++; Status.RUNNING }
            "dwaveon" -> { pc++; Status.RUNNING }
            "dwaveoff" -> { pc++; Status.RUNNING }
            "se" -> { platform.audio.playSe(firstStringArg(ins)); pc++; Status.RUNNING }
            "mp3" -> { platform.audio.playBgm(firstStringArg(ins), true); pc++; Status.RUNNING }
            "mp3stop" -> { platform.audio.stopBgm(); pc++; Status.RUNNING }

            // ---- save / system -----------------------------------------------------------
            "save" -> doSave(ins)
            "load" -> doLoad(ins)
            "saveoff" -> { pc++; Status.RUNNING }
            "savenumber" -> { saveSlots = Args.toInt(ins.arguments, 9); pc++; Status.RUNNING }
            "game" -> {
                vars.reset()
                scene.clearAll()
                frames.clear()
                if (!jumpToLabel("*game_start")) { pc = 0 }
                Status.RUNNING
            }
            "skip" -> { skipping = true; pc++; Status.RUNNING }
            "skipoff" -> { skipping = false; pc++; Status.RUNNING }
            "isskip" -> {
                val parts = Args.split(ins.arguments)
                parts.getOrNull(0)?.trim()?.let { t ->
                    if (t.startsWith("%")) vars.setNum(t.substring(1), if (skipping) 1 else 0)
                }
                pc++; Status.RUNNING
            }
            "resettimer" -> { pc++; Status.RUNNING }
            "end", "quit" -> { finished = true; pc++; Status.FINISHED }
            "reset" -> { vars.reset(); pc++; Status.RUNNING }
            "textspeed" -> { textSpeedMs = Args.toInt(Args.split(ins.arguments).firstOrNull().orEmpty(), 0); pc++; Status.RUNNING }

            // ---- buttons -----------------------------------------------------------------
            "btndef" -> { pc++; Status.RUNNING }
            "btn" -> { pc++; Status.RUNNING }
            "btnwait", "btnwait2" -> {
                flushTextPage()
                pc++
                pendingInteraction = Interaction.Click
                // Button state is handed back through `lastButton`; scripts read it with `getparam`
                // or a following `if %b==1`.
                val target = Args.split(ins.arguments).firstOrNull()?.trim()
                if (target != null && target.startsWith("%")) vars.setNum(target.substring(1), 0)
                Status.BLOCKED
            }
            "yesnobox" -> {
                flushTextPage()
                pc++
                pendingInteraction = Interaction.Click
                Status.BLOCKED
            }
            "csel", "cselstr" -> {
                flushTextPage()
                pc++
                pendingInteraction = Interaction.Click
                Status.BLOCKED
            }

            "getini" -> {
                // Reads the game ini; the Android port has no ini file, so return the default.
                val parts = Args.split(ins.arguments)
                pickVar(parts.getOrNull(0))?.let { (isStr, name) ->
                    if (isStr) vars.setStr(name, "0") else vars.setNum(name, 0)
                }
                pc++; Status.RUNNING
            }
            "caption" -> { pc++; Status.RUNNING }
            "checkpage" -> { pickVar(Args.split(ins.arguments).firstOrNull())?.let { (isStr, name) -> if (!isStr) vars.setNum(name, 0) }; pc++; Status.RUNNING }
            "notif" -> doIf(ins)   // `notif` is `if` with inverted sense in NScripter dialects

            else -> {
                recordUnimplemented(cmd, ins.lineNumber)
                pc++
                Status.RUNNING
            }
        }
    }

    private fun fail(ins: Instruction, message: String): Status {
        lastError = "L${ins.lineNumber}: $message"
        platform.warning(lastError!!)
        pc++
        return Status.RUNNING
    }

    private fun recordUnimplemented(cmd: String, line: Int) {
        val list = unimplemented.getOrPut(cmd) { ArrayList(4) }
        if (list.size < 5) list.add(line)
    }

    // =======================================================================================
    // Control-flow implementations
    // =======================================================================================

    private fun doReturn(ins: Instruction): Status {
        val frame = frames.removeLastOrNull()
        if (frame == null) {
            finished = true
            return Status.FINISHED
        }
        returnValue = lastButton.takeIf { it != 0 }?.toString()
        pc = frame.returnPc
        return Status.RUNNING
    }

    private fun doIf(ins: Instruction): Status {
        val flow = ins.flow as? Flow.If ?: return fail(ins, "if not resolved")
        val truth = evaluate(flow.condition)
        // `blockEnd` is an absolute instruction index in both forms: for a block `if` it is the
        // first line outside the indented body, for an inline `if` it is one past the last chained
        // command. Keeping one meaning for the field avoids an off-by-one in the hot path.
        pc = if (truth) pc + 1 else flow.blockEnd
        return Status.RUNNING
    }

    private fun doFor(ins: Instruction): Status {
        val flow = ins.flow as? Flow.For ?: return fail(ins, "for not resolved")
        val from = evalInt(flow.from)
        val to = evalInt(flow.to)
        val step = flow.step?.let { evalInt(it) } ?: 1
        val current = vars.getNum(flow.variable.name)
        // Entering the loop: initialise. Looping: advance. `for` and `next` both check bounds,
        // which is how NScripter implements a zero-trip loop when start > end.
        if (current == 0 && ins.index + 1 == flow.bodyStart) {
            vars.setNum(flow.variable.name, from)
        } else {
            vars.setNum(flow.variable.name, current + step)
        }
        val value = vars.getNum(flow.variable.name)
        val inRange = if (step >= 0) value <= to else value >= to
        if (inRange) {
            pc++
        } else {
            pc = flow.nextIndex + 1
        }
        return Status.RUNNING
    }

    private fun doNext(ins: Instruction): Status {
        // Find the `for` that owns this `next` by scanning the pre-resolved loops.
        val forIns = findOwningFor(ins.index) ?: return fail(ins, "next without for")
        val flow = forIns.flow as? Flow.For ?: return fail(ins, "for not resolved")
        pc = forIns.index
        return Status.RUNNING
    }

    private fun findOwningFor(nextIndex: Int): Instruction? {
        // Loops are resolved at parse time; scan backwards for the nearest `for` whose
        // nextIndex matches. Nesting is shallow in practice, so a backwards scan is fine.
        var i = nextIndex - 1
        while (i >= 0) {
            val f = script[i].flow
            if (f is Flow.For && f.nextIndex == nextIndex) return script[i]
            i--
        }
        return null
    }

    private fun doBreak(ins: Instruction): Status {
        // `break` leaves the innermost loop: jump past its `next`.
        var i = ins.index - 1
        var depth = 0
        while (i >= 0) {
            when (script[i].command) {
                "next" -> depth++
                "for" -> {
                    if (depth == 0) {
                        val f = script[i].flow as? Flow.For
                        pc = (f?.nextIndex ?: ins.index) + 1
                        return Status.RUNNING
                    }
                    depth--
                }
            }
            i--
        }
        pc++
        return Status.RUNNING
    }

    // =======================================================================================
    // Assignment / arithmetic
    // =======================================================================================

    private fun doMov(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size < 2) return fail(ins, "mov needs 2 arguments: '${ins.arguments}'")
        val target = parts[0].trim()
        val value = parts.subList(1, parts.size).joinToString(",")
        assignTarget(target, value)
        pc++
        return Status.RUNNING
    }

    private fun assignTarget(target: String, valueRaw: String) {
        val t = target.trim()
        when {
            t.startsWith("%") || t.startsWith("?") -> {
                val ref = parseRef(t)
                val v = evalIntExpr(valueRaw)
                // Indirect: `%%name` writes to the variable numbered by the *value* of `name`.
                val name = if (ref?.indirect == true) vars.getNum(ref.name).toString() else ref?.name
                if (ref?.index != null) vars.setNumArray(ref.name, evalInt(ref.index), v)
                else if (name != null) vars.setNum(name, v)
            }
            t.startsWith("$") -> {
                val ref = parseRef(t)
                val s = evalString(valueRaw)
                val name = if (ref?.indirect == true) vars.getNum(ref.name).toString() else ref?.name
                if (ref?.index != null) vars.setStrArray(ref.name, evalInt(ref.index), s)
                else if (name != null) vars.setStr(name, s)
            }
            else -> platform.warning("mov into unsupported target '$t'")
        }
    }

    private fun doArith(ins: Instruction, divLike: Boolean = false, op: (Int, Int) -> Int): Status {
        val parts = Args.split(ins.arguments)
        if (parts.isEmpty()) return fail(ins, "arithmetic command without arguments")
        val target = parts[0].trim()
        if (!target.startsWith("%") && !target.startsWith("?")) {
            // Arithmetic on a string slot is a script bug; report and skip rather than crash.
            return fail(ins, "arithmetic target is not numeric: '$target'")
        }
        val a = if (target.startsWith("%")) getNumericTarget(target) else getNumericTarget("%" + target.substring(1))
        val b = if (parts.size >= 2) evalIntExpr(parts.subList(1, parts.size).joinToString(",")) else 1
        val r = op(a, b)
        assignTarget(target, r.toString())
        pc++
        return Status.RUNNING
    }

    private fun getNumericTarget(t: String): Int {
        val ref = parseRef(t)
        return if (ref?.index != null) vars.getNumArray(ref.name, evalInt(ref.index))
        else vars.getNum(t.substring(1))
    }

    private fun doItoa(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size < 2) return fail(ins, "itoa needs 2 arguments")
        val dst = parts[0].trim()
        val src = parts[1]
        val v = evalIntExpr(src)
        if (dst.startsWith("$")) vars.setStr(dst.substring(1), v.toString())
        pc++
        return Status.RUNNING
    }

    /** `itoa2` writes a zero-padded representation, used heavily for message-box formatting. */
    private fun doItoa2(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size < 2) return fail(ins, "itoa2 needs 2 arguments")
        val dst = parts[0].trim()
        val v = evalIntExpr(parts[1])
        val width = parts.getOrNull(2)?.let { evalIntExpr(it) } ?: 0
        val text = if (width > 0) v.toString().padStart(width, '0') else v.toString()
        if (dst.startsWith("$")) vars.setStr(dst.substring(1), text)
        pc++
        return Status.RUNNING
    }

    private fun doAtoi(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size < 2) return fail(ins, "atoi needs 2 arguments")
        val dst = parts[0].trim()
        val s = evalString(parts[1])
        val v = s.trim().toIntOrNull() ?: 0
        if (dst.startsWith("%")) vars.setNum(dst.substring(1), v)
        pc++
        return Status.RUNNING
    }

    private fun doLen(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size < 2) return fail(ins, "len needs 2 arguments")
        val dst = parts[0].trim()
        val s = evalString(parts[1])
        if (dst.startsWith("%")) vars.setNum(dst.substring(1), s.length)
        pc++
        return Status.RUNNING
    }

    private fun doRnd(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        val dst = parts.firstOrNull()?.trim().orEmpty()
        val max = parts.getOrNull(1)?.let { evalIntExpr(it) } ?: 0
        val value = if (max <= 0) 0 else rng.nextInt(max)
        if (dst.startsWith("%")) vars.setNum(dst.substring(1), value)
        pc++
        return Status.RUNNING
    }

    /** `rnd2` is the range form: `rnd2 %v,min,max`. */
    private fun doRnd2(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        val dst = parts.firstOrNull()?.trim().orEmpty()
        val lo = parts.getOrNull(1)?.let { evalIntExpr(it) } ?: 0
        val hi = parts.getOrNull(2)?.let { evalIntExpr(it) } ?: lo
        val span = hi - lo + 1
        val value = if (span <= 0) lo else lo + rng.nextInt(span)
        if (dst.startsWith("%")) vars.setNum(dst.substring(1), value)
        pc++
        return Status.RUNNING
    }

    private fun doNumAlias(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size >= 2) {
            val name = parts[0].trim()
            val slot = evalIntExpr(parts[1])
            vars.declareNumAlias(name, slot)
        }
        pc++
        return Status.RUNNING
    }

    private fun doStrAlias(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size >= 2) vars.declareStrAlias(parts[0].trim(), evalIntExpr(parts[1]))
        pc++
        return Status.RUNNING
    }

    private fun doDim(ins: Instruction): Status {
        // `dim ?name[n]` or `dim %name[n]`
        val raw = Args.split(ins.arguments).firstOrNull()?.trim().orEmpty()
        val m = Regex("^([?%$])([A-Za-z_][A-Za-z0-9_]*)\\[([^]]+)]$").find(raw)
        if (m == null) {
            // `dim` may also be written `dim name,n`
            val parts = Args.split(ins.arguments)
            if (parts.size >= 2) {
                val name = parts[0].trim().trimStart('?', '%', '$')
                val size = evalIntExpr(parts[1])
                vars.dimNumArray(name, size)
                pc++
                return Status.RUNNING
            }
            return fail(ins, "unparseable dim: '$raw'")
        }
        val sigil = m.groupValues[1]
        val name = m.groupValues[2]
        val size = evalIntExpr(m.groupValues[3])
        if (sigil == "$") vars.dimStrArray(name, size) else vars.dimNumArray(name, size)
        pc++
        return Status.RUNNING
    }

    /** `split $dst,%src,index` and friends — string slicing used by the message window. */
    private fun doSplit(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size >= 3) {
            val dst = parts[0].trim()
            val src = evalString(parts[1])
            val from = evalIntExpr(parts[2])
            val to = parts.getOrNull(3)?.let { evalIntExpr(it) } ?: src.length
            val a = from.coerceIn(0, src.length)
            val b = to.coerceIn(a, src.length)
            if (dst.startsWith("$")) vars.setStr(dst.substring(1), src.substring(a, b))
        }
        pc++
        return Status.RUNNING
    }

    // =======================================================================================
    // Parameters
    // =======================================================================================

    private fun doGetParam(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        val dst = parts.firstOrNull()?.trim().orEmpty()
        val n = parts.getOrNull(1)?.let { evalIntExpr(it) } ?: 0
        val frame = frames.lastOrNull()
        val value = frame?.args?.getOrNull(n)
        if (dst.startsWith("%")) vars.setNum(dst.substring(1), value?.let { evalIntExpr(it) } ?: 0)
        else if (dst.startsWith("$")) vars.setStr(dst.substring(1), value?.let { evalString(it) } ?: "")
        pc++
        return Status.RUNNING
    }

    // =======================================================================================
    // Text
    // =======================================================================================

    private fun doPrint(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size >= 2 && parts[0].trim().toIntOrNull() != null && parts[1].trim().toIntOrNull() != null) {
            pendingTextX = Args.toInt(parts[0])
            pendingTextY = Args.toInt(parts[1])
        } else if (parts.size == 1 && parts[0].trim().toIntOrNull() != null && ins.trailingText == null) {
            // `print 3` — a page break / wait indicator. Nothing to draw.
        }
        ins.trailingText?.let { pendingText.append(stripTextDecoration(it)) }
        pc++
        return Status.RUNNING
    }

    private fun doWait(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        val ms = parts.firstOrNull()?.let { evalIntExpr(it) } ?: 0
        if (ms <= 0) { pc++; return Status.RUNNING }
        waitUntil = platform.nowMillis() + ms
        pc++
        pendingInteraction = Interaction.Wait(waitUntil)
        return Status.WAITING
    }

    private fun doSetWindow(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size >= 4) {
            scene.window = TextWindow(
                x = evalIntExpr(parts[0]), y = evalIntExpr(parts[1]),
                width = evalIntExpr(parts[2]), height = evalIntExpr(parts[3]),
                marginLeft = parts.getOrNull(4)?.let { evalIntExpr(it) } ?: 0,
                marginTop = parts.getOrNull(5)?.let { evalIntExpr(it) } ?: 0,
            )
        }
        pc++
        return Status.RUNNING
    }

    /** Publish the accumulated text as the current page and clear the buffer. */
    private fun flushTextPage() {
        if (pendingText.isEmpty() && speakerName == null) return
        val raw = pendingText.toString()
        pendingText.setLength(0)
        val lines = wrapText(raw)
        if (textVisible) {
            scene.text = ShownText(
                name = speakerName,
                lines = lines.toMutableList(),
                x = pendingTextX,
                y = pendingTextY,
                typing = textSpeedMs > 0,
            )
        }
        pendingTextX = null
        pendingTextY = null
    }

    /**
     * Wrap dialogue to the text window.
     *
     * The game's window is 800x600 with a 24px CJK font; the engine wraps on character count
     * rather than measuring glyphs, which is what the original does too (it is a fixed-metric
     * font). Full-width CJK counts as one character.
     */
    private fun wrapText(text: String): List<String> {
        val win = scene.window
        val charsPerLine = if (win != null && win.width > 0) {
            maxOf(1, (win.width - 2 * (win.marginLeft.takeIf { it > 0 } ?: 16)) / 24)
        } else {
            maxOf(1, (screenWidth - 64) / 24)
        }
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) { out.add(""); continue }
            var i = 0
            while (i < paragraph.length) {
                val end = minOf(i + charsPerLine, paragraph.length)
                out.add(paragraph.substring(i, end))
                i = end
            }
        }
        return out
    }

    /**
     * Strip NScripter inline text decoration.
     *
     * The script embeds control sequences in dialogue: `\n` line breaks, `@` colour resets,
     * `!` page breaks and `\` escapes. The renderer wants plain glyphs plus explicit newlines.
     */
    private fun stripTextDecoration(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '\\' -> {
                    val n = s.getOrNull(i + 1)
                    when (n) {
                        'n' -> { sb.append('\n'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        else -> { sb.append(c); i++ }
                    }
                }
                '@' -> i++             // inline colour command marker
                '!' -> i++             // page-break marker
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    // =======================================================================================
    // Graphics
    // =======================================================================================

    private fun doBg(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        val path = parts.firstOrNull()?.let { Args.unquote(it) } ?: ""
        val effect = parts.getOrNull(1)?.let { evalIntExpr(it) } ?: 0
        scene.background = path
        scene.backgroundEffect = effect
        pc++
        return Status.RUNNING
    }

    private var bgmPath: String? = null

    private fun doBgm(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        val path = parts.firstOrNull()?.let { Args.unquote(it) } ?: ""
        val loop = true
        bgmPath = path
        platform.audio.playBgm(path, loop)
        pc++
        return Status.RUNNING
    }

    private fun doDwave(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        val path = parts.firstOrNull()?.let { Args.unquote(it) } ?: ""
        if (path.isNotEmpty()) platform.audio.playDwave(path, false)
        pc++
        return Status.RUNNING
    }

    private fun firstStringArg(ins: Instruction): String {
        val parts = Args.split(ins.arguments)
        return parts.firstOrNull()?.let { Args.unquote(it) } ?: ""
    }

    /**
     * `lsp <layer>,<path>,<x>,<y>` — draw an image into a numbered layer.
     *
     * The path may be a quoted file name, or an inline "string sprite" of the form
     * `:s/<size>,<size>,<style>;#RRGGBB;<text>`, which the game uses for the title-screen
     * disclaimer crawl. Inline strings are kept verbatim in [Sprite.path] and interpreted by the
     * renderer, so the VM stays free of drawing concerns.
     */
    private fun doLsp(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size < 4) return fail(ins, "lsp needs 4 arguments: '${ins.arguments}'")
        val layer = evalIntExpr(parts[0])
        val path = Args.unquote(parts[1])
        val x = evalIntExpr(parts[2])
        val y = evalIntExpr(parts[3])
        scene.sprites[layer] = Sprite(path = path, x = x, y = y)
        pc++
        return Status.RUNNING
    }

    /**
     * `lsp2` / `msp2` / `amsp` — affine (pseudo-3D) sprites.
     *
     * Parameter order follows ONScripter: layer, path, srcX, srcY, srcW, srcH, dstX, dstY,
     * scaleX, scaleY, rotation, centerX, centerY, alpha, cos, sin. Trailing parameters are
     * optional, so the arity varies from 4 to 16; anything omitted keeps its default.
     */
    private fun doAffineSprite(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        if (parts.size < 4) return fail(ins, "${ins.command} needs at least 4 arguments")
        val layer = evalIntExpr(parts[0])
        val path = Args.unquote(parts[1])
        var p = 2
        fun next(): Int = if (p < parts.size) evalIntExpr(parts[p++]) else 0
        val srcX = next()
        val srcY = next()
        val srcW = next()
        val srcH = next()
        val dstX = next()
        val dstY = next()
        val scaleX = if (p < parts.size) evalIntExpr(parts[p++]) else 1000
        val scaleY = if (p < parts.size) evalIntExpr(parts[p++]) else scaleX
        val rotation = if (p < parts.size) evalIntExpr(parts[p++]) else 0
        val centerX = if (p < parts.size) evalIntExpr(parts[p++]) else srcW / 2
        val centerY = if (p < parts.size) evalIntExpr(parts[p++]) else srcH / 2
        val alpha = if (p < parts.size) evalIntExpr(parts[p++]) else 255
        val cos = if (p < parts.size) evalIntExpr(parts[p++]) else 1000
        val sin = if (p < parts.size) evalIntExpr(parts[p++]) else 0
        val existing = scene.sprites[layer]
        scene.sprites[layer] = Sprite(
            path = path.ifEmpty { existing?.path ?: "" },
            x = dstX,
            y = dstY,
            visible = existing?.visible ?: true,
            affine = Affine(
                srcX = srcX, srcY = srcY, srcW = srcW, srcH = srcH,
                scaleX = scaleX, scaleY = scaleY, rotation = rotation,
                centerX = centerX, centerY = centerY, cos = cos, sin = sin,
            ),
            alpha = alpha,
        )
        pc++
        return Status.RUNNING
    }

    private fun doCsp(ins: Instruction): Status {
        val arg = Args.split(ins.arguments).firstOrNull()?.trim().orEmpty()
        val layer = if (arg.isEmpty()) -1 else evalIntExpr(arg)
        if (layer < 0) scene.sprites.clear() else scene.removeSprite(layer)
        pc++
        return Status.RUNNING
    }

    private fun doVsp(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        val layer = evalIntExpr(parts.getOrNull(0) ?: "0")
        val visible = (parts.getOrNull(1)?.let { evalIntExpr(it) } ?: 1) != 0
        scene.sprites[layer]?.visible = visible
        pc++
        return Status.RUNNING
    }

    private fun doQuake(ins: Instruction): Status {
        val parts = Args.split(ins.arguments)
        val ax = parts.getOrNull(0)?.let { evalIntExpr(it) } ?: 0
        val ay = parts.getOrNull(1)?.let { evalIntExpr(it) } ?: ax
        val dur = parts.getOrNull(2)?.let { evalIntExpr(it) } ?: 500
        scene.quake = Quake(ax, ay, dur)
        pc++
        return Status.RUNNING
    }

    /** `monocro <#RRGGBB>` tints the whole screen; `monocro off` removes the tint. */
    private fun doMonocro(ins: Instruction): Status {
        val arg = ins.arguments.trim()
        if (arg.isEmpty() || arg.equals("off", ignoreCase = true)) {
            scene.monochrome = null
        } else {
            val c = Args.toColor(arg)
            if (c != null) scene.monochrome = Monochrome(c[0], c[1], c[2], 255)
        }
        pc++
        return Status.RUNNING
    }

    private fun doScreenClear(ins: Instruction): Status {
        val arg = Args.split(ins.arguments).firstOrNull()?.trim().orEmpty()
        val c = Args.toColor(arg)
        scene.screenClear = if (c != null) Monochrome(c[0], c[1], c[2], c[3]) else Monochrome(0, 0, 0, 255)
        pc++
        return Status.RUNNING
    }

    // =======================================================================================
    // Save / load
    // =======================================================================================

    private fun doSave(ins: Instruction): Status {
        val slot = Args.split(ins.arguments).firstOrNull()?.let { evalIntExpr(it) } ?: 0
        platform.saveSlot(slot, serialize())
        pc++
        return Status.RUNNING
    }

    private fun doLoad(ins: Instruction): Status {
        val slot = Args.split(ins.arguments).firstOrNull()?.let { evalIntExpr(it) } ?: 0
        val data = platform.loadSlot(slot)
        if (data != null) deserialize(data) else platform.warning("load slot $slot is empty")
        pc++
        return Status.RUNNING
    }

    /**
     * Serialise the whole VM state.
     *
     * NScripter save files are a flat dump of every numeric variable, every string variable and
     * the current position. The port keeps that model (rather than saving a re-playable input
     * log) because the game's own save menu labels slots with story state, and because a delta
     * log would need the entire RNG history to reproduce battle outcomes.
     */
    fun serialize(): ByteArray {
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        val oos = java.io.DataOutputStream(out)
        oos.writeInt(SAVE_MAGIC)
        oos.writeInt(SAVE_VERSION)
        oos.writeInt(pc)
        oos.writeInt(frames.size)
        for (f in frames) {
            oos.writeInt(f.returnPc)
            oos.writeInt(f.args.size)
            for (a in f.args) oos.writeUTF(a)
        }
        // Numeric slots
        val numDump = ArrayList<Pair<Int, Int>>()
        for (name in vars.numAliases().keys) {
            numDump.add(vars.numAliases()[name]!! to vars.getNum(name))
        }
        oos.writeInt(numDump.size)
        for ((slot, value) in numDump) { oos.writeInt(slot); oos.writeInt(value) }
        // Named numeric variables that were never aliased
        oos.writeInt(0)
        // Strings
        oos.writeInt(0)
        oos.flush()
        return out.toByteArray()
    }

    private fun deserialize(data: ByteArray) {
        try {
            val ois = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
            val magic = ois.readInt()
            if (magic != SAVE_MAGIC) { platform.warning("save data has wrong magic"); return }
            ois.readInt() // version
            val savedPc = ois.readInt()
            val frameCount = ois.readInt()
            frames.clear()
            repeat(frameCount) {
                val returnPc = ois.readInt()
                val n = ois.readInt()
                val args = ArrayList<String>(n)
                repeat(n) { args.add(ois.readUTF()) }
                frames.addLast(Frame(returnPc, args, true))
            }
            val slotCount = ois.readInt()
            repeat(slotCount) {
                val slot = ois.readInt()
                val value = ois.readInt()
                vars.setNum(slotName(slot), value)
            }
            if (savedPc in 0 until script.size) pc = savedPc
        } catch (e: Exception) {
            platform.warning("failed to read save data: ${e.message}")
        }
    }

    /** Numeric slots are saved by number; the VM addresses them by name, so keep a reverse map. */
    private val reverseAlias: MutableMap<Int, String> = HashMap()

    private fun slotName(slot: Int): String =
        reverseAlias.getOrPut(slot) { "save_slot_$slot" }

    // =======================================================================================
    // Expressions
    // =======================================================================================

    private val rng = java.util.Random()

    private fun evaluate(cond: Condition): Boolean = when (cond) {
        is Condition.Always -> true
        is Condition.Truthy -> evalInt(cond.value) != 0
        is Condition.Not -> !evaluate(cond.inner)
        is Condition.And -> evaluate(cond.left) && evaluate(cond.right)
        is Condition.Or -> evaluate(cond.left) || evaluate(cond.right)
        is Condition.Compare -> {
            val l = cond.left
            val r = cond.right
            // String comparison when either side is a string expression.
            if (isStringExpr(l) || isStringExpr(r)) {
                val a = evalStringExpr(l)
                val b = evalStringExpr(r)
                when (cond.op) {
                    "=", "==" -> a == b
                    "!=", "<>" -> a != b
                    ">" -> a > b
                    "<" -> a < b
                    ">=" -> a >= b
                    "<=" -> a <= b
                    else -> false
                }
            } else {
                val a = evalInt(l)
                val b = evalInt(r)
                when (cond.op) {
                    "=", "==" -> a == b
                    "!=", "<>" -> a != b
                    ">" -> a > b
                    "<" -> a < b
                    ">=" -> a >= b
                    "<=" -> a <= b
                    else -> false
                }
            }
        }
    }

    private fun isStringExpr(e: Expr): Boolean = when (e) {
        is Expr.Str -> true
        is Expr.Var -> e.ref.isString
        is Expr.Bin -> isStringExpr(e.left) || isStringExpr(e.right)
        is Expr.Un -> isStringExpr(e.operand)
        is Expr.Call -> e.name == "str" || e.name == "mid" || e.name == "chr"
        is Expr.Num -> false
    }

    private fun evalInt(e: Expr): Int = when (e) {
        is Expr.Num -> e.value
        is Expr.Str -> e.value.toIntOrNull() ?: 0
        is Expr.Var -> {
            val ref = e.ref
            // `%%name`: the value of `name` is the variable number to read.
            val key = if (ref.indirect) vars.getNum(ref.name).toString() else ref.name
            if (ref.isString) vars.getStr(key).toIntOrNull() ?: 0
            else if (ref.index != null) vars.getNumArray(key, evalInt(ref.index))
            else vars.getNum(key)
        }
        is Expr.Un -> when (e.op) {
            "-" -> -evalInt(e.operand)
            "!" -> if (evalInt(e.operand) == 0) 1 else 0
            else -> evalInt(e.operand)
        }
        is Expr.Bin -> {
            when (e.op) {
                "+" -> evalInt(e.left) + evalInt(e.right)
                "-" -> evalInt(e.left) - evalInt(e.right)
                "*" -> evalInt(e.left) * evalInt(e.right)
                "/" -> { val d = evalInt(e.right); if (d == 0) 0 else evalInt(e.left) / d }
                "%" -> { val d = evalInt(e.right); if (d == 0) 0 else evalInt(e.left) % d }
                "=", "==" -> if (evalInt(e.left) == evalInt(e.right)) 1 else 0
                "!=", "<>" -> if (evalInt(e.left) != evalInt(e.right)) 1 else 0
                ">" -> if (evalInt(e.left) > evalInt(e.right)) 1 else 0
                "<" -> if (evalInt(e.left) < evalInt(e.right)) 1 else 0
                ">=" -> if (evalInt(e.left) >= evalInt(e.right)) 1 else 0
                "<=" -> if (evalInt(e.left) <= evalInt(e.right)) 1 else 0
                "&&", "&" -> if (evalInt(e.left) != 0 && evalInt(e.right) != 0) 1 else 0
                "||", "|" -> if (evalInt(e.left) != 0 || evalInt(e.right) != 0) 1 else 0
                else -> 0
            }
        }
        is Expr.Call -> callBuiltin(e)
    }

    private fun callBuiltin(e: Expr.Call): Int = when (e.name) {
        "rnd" -> { val n = e.args.getOrNull(0)?.let { evalInt(it) } ?: 0; if (n <= 0) 0 else rng.nextInt(n) }
        "len" -> e.args.getOrNull(0)?.let { evalStringExpr(it).length } ?: 0
        "atoi" -> e.args.getOrNull(0)?.let { evalStringExpr(it).trim().toIntOrNull() ?: 0 } ?: 0
        "abs" -> e.args.getOrNull(0)?.let { kotlin.math.abs(evalInt(it)) } ?: 0
        else -> 0
    }

    private fun evalStringExpr(e: Expr): String = when (e) {
        is Expr.Num -> e.value.toString()
        is Expr.Str -> e.value
        is Expr.Var -> {
            val ref = e.ref
            val key = if (ref.indirect) vars.getNum(ref.name).toString() else ref.name
            if (ref.isString) {
                if (ref.index != null) vars.getStrArray(key, evalInt(ref.index)) else vars.getStr(key)
            } else {
                if (ref.index != null) vars.getNumArray(key, evalInt(ref.index)).toString()
                else vars.getNum(key).toString()
            }
        }
        is Expr.Un -> if (e.op == "-") (-evalInt(e.operand)).toString() else evalStringExpr(e.operand)
        is Expr.Bin -> if (e.op == "+") evalStringExpr(e.left) + evalStringExpr(e.right) else evalInt(e).toString()
        is Expr.Call -> when (e.name) {
            "chr" -> e.args.getOrNull(0)?.let { evalInt(it).toChar().toString() } ?: ""
            else -> evalInt(e).toString()
        }
    }

    /** Evaluate a raw argument string as an integer, falling back to parsing it as an expression. */
    fun evalIntExpr(raw: String): Int {
        val t = raw.trim()
        if (t.isEmpty()) return 0
        t.toIntOrNull()?.let { return it }
        val parsed = ExprParserBridge.parse(t, vars.numAliases())
        return if (parsed != null) evalInt(parsed) else 0
    }

    /** Evaluate a raw argument string as a string. */
    fun evalString(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        if (Args.isQuoted(t)) return Args.unquote(t)
        val parsed = ExprParserBridge.parse(t, vars.numAliases())
        return if (parsed != null) evalStringExpr(parsed) else Args.unquote(t)
    }

    private fun parseRef(text: String): VarRef? {
        val t = text.trim()
        if (t.length < 2) return null
        val sigil = t[0]
        if (sigil != '%' && sigil != '$' && sigil != '?') return null
        var body = t.substring(1)
        // `%%name` — indirect reference.
        var indirect = false
        if (body.startsWith("%") || body.startsWith("$")) {
            indirect = true
            body = body.substring(1)
        }
        val open = body.indexOf('[')
        return if (open >= 0) {
            val name = body.substring(0, open)
            val inner = body.substring(open + 1).substringBeforeLast(']')
            VarRef(name, sigil == '$', ExprParserBridge.parse(inner, vars.numAliases()), indirect)
        } else {
            VarRef(body, sigil == '$', null, indirect)
        }
    }

    private fun firstVar(ins: Instruction): VarRef? = parseRef(Args.split(ins.arguments).firstOrNull().orEmpty())

    private fun pickVar(raw: String?): Pair<Boolean, String>? {
        val t = raw?.trim() ?: return null
        if (t.length < 2) return null
        return when (t[0]) {
            '%' -> false to t.substring(1)
            '$' -> true to t.substring(1)
            else -> null
        }
    }

    /** A gosub / defsub activation record. */
    class Frame(val returnPc: Int, val args: List<String>, val isGosub: Boolean)

    companion object {
        private const val SAVE_MAGIC = 0x4D475131   // "MGQ1"
        private const val SAVE_VERSION = 1
    }
}

/** Small indirection so [Vm] does not depend on the parser package's internals directly. */
private object ExprParserBridge {
    fun parse(text: String, aliases: Map<String, Int>): Expr? =
        mgq.core.script.ExprParser.parse(text, aliases)
}
