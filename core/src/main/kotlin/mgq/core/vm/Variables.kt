package mgq.core.vm

/**
 * Variable storage for the VM.
 *
 * The original game declares **1200 `numalias` names** and uses the NScripter `?name[n]` array
 * form. Rather than modelling a 1200-slot global array (which would be both fragile and
 * needlessly large), slots are backed by an [IntArray] sized to the highest alias slot actually
 * declared, and values live in a sparse map keyed by the resolved slot number. That keeps
 * `mov %exp,0` O(1) while remaining robust to a script that declares aliases out of order.
 */
class Variables {

    /**
     * Alias name -> slot number, from `numalias`.
     *
     * Note the engine resolves aliases globally in `*define`, so a name may be used before the
     * `numalias` line textually; [ScriptParser] collects them all in pass 1 to make that work.
     */
    private val numAlias: MutableMap<String, Int> = HashMap()
    private val strAlias: MutableMap<String, Int> = HashMap()

    /** Dense numeric slots. Grown on demand. */
    private var num: IntArray = IntArray(SLOTS)

    /** String slots, sparse because strings are comparatively rare. */
    private val str: MutableMap<Int, String> = HashMap()

    /** `?name[n]` arrays: array name -> values indexed by subscript. */
    private val arrays: MutableMap<String, IntArray> = HashMap()

    /** String arrays (`$name[n]`). */
    private val strArrays: MutableMap<String, Array<String>> = HashMap()

    /** `%name`/`$name` used without an alias, hashed into a stable slot. */
    private val namedNum: MutableMap<String, Int> = HashMap()
    private val namedStr: MutableMap<String, String> = HashMap()

    private var nextNamedSlot = SLOTS

    fun declareNumAlias(name: String, slot: Int) {
        numAlias[name] = slot
        if (slot >= num.size) num = num.copyOf(maxOf(slot + 1, num.size * 2))
    }

    fun declareStrAlias(name: String, slot: Int) {
        strAlias[name] = slot
    }

    fun numAliases(): Map<String, Int> = numAlias

    /** Resolve a `%name` to a slot, allocating a stable synthetic slot when undeclared. */
    fun resolveNumSlot(name: String): Int {
        numAlias[name]?.let { return it }
        return namedNum.getOrPut(name) {
            val s = nextNamedSlot++
            if (s >= num.size) num = num.copyOf(s + 1)
            s
        }
    }

    /** Resolve a `$name` to a slot. */
    fun resolveStrSlot(name: String): Int {
        strAlias[name]?.let { return it }
        return namedNum.getOrPut("#$name") { nextNamedSlot++ }
    }

    // ---- numeric -------------------------------------------------------------------------

    fun getNum(name: String): Int {
        val slot = resolveNumSlot(name)
        return if (slot < num.size) num[slot] else 0
    }

    fun setNum(name: String, value: Int) {
        val slot = resolveNumSlot(name)
        if (slot >= num.size) num = num.copyOf(slot + 1)
        num[slot] = value
    }

    fun addNum(name: String, delta: Int) = setNum(name, getNum(name) + delta)

    // ---- string --------------------------------------------------------------------------

    fun getStr(name: String): String = str[resolveStrSlot(name)] ?: ""

    fun setStr(name: String, value: String) {
        str[resolveStrSlot(name)] = value
    }

    // ---- arrays --------------------------------------------------------------------------

    /** `dim ?name[n]` — allocate a numeric array of [size] elements. */
    fun dimNumArray(name: String, size: Int) {
        arrays[name] = IntArray(size.coerceAtLeast(1))
    }

    fun dimStrArray(name: String, size: Int) {
        strArrays[name] = Array(size.coerceAtLeast(1)) { "" }
    }

    fun getNumArray(name: String, index: Int): Int {
        val arr = arrays[name] ?: return 0
        if (index < 0) return 0
        if (index >= arr.size) return 0
        return arr[index]
    }

    fun setNumArray(name: String, index: Int, value: Int) {
        if (index < 0) return
        val arr = arrays.getOrPut(name) { IntArray(DEFAULT_ARRAY_SIZE) }
        if (index >= arr.size) {
            arrays[name] = arr.copyOf(maxOf(index + 1, arr.size * 2))
        }
        arrays[name]!![index] = value
    }

    fun getStrArray(name: String, index: Int): String {
        val arr = strArrays[name] ?: return ""
        if (index < 0 || index >= arr.size) return ""
        return arr[index]
    }

    fun setStrArray(name: String, index: Int, value: String) {
        if (index < 0) return
        var arr = strArrays[name]
        if (arr == null) {
            arr = Array(DEFAULT_ARRAY_SIZE) { "" }
            strArrays[name] = arr
        }
        if (index >= arr.size) {
            val grown = Array(maxOf(index + 1, arr.size * 2)) { i -> if (i < arr.size) arr!![i] else "" }
            strArrays[name] = grown
            grown[index] = value
            return
        }
        arr[index] = value
    }

    fun hasNumArray(name: String): Boolean = arrays.containsKey(name)

    fun numArraySize(name: String): Int = arrays[name]?.size ?: 0

    // ---- lifecycle ------------------------------------------------------------------------

    /** `game` — restart the story: every variable returns to its initial value. */
    fun reset() {
        num.fill(0)
        str.clear()
        arrays.clear()
        strArrays.clear()
        namedNum.clear()
        namedStr.clear()
        nextNamedSlot = SLOTS
    }

    /** Diagnostics: how many dense slots are actually in use. */
    fun stats(): String {
        val used = num.count { it != 0 }
        return "numSlots=${num.size} nonZero=$used strings=${str.size} " +
            "arrays=${arrays.size} namedSlots=${namedNum.size} aliases=${numAlias.size}"
    }

    companion object {
        /** Slot count for `numalias` targets; the real script uses slots up to ~1200. */
        private const val SLOTS = 2048

        /** Default `?arr[n]` size when a script reads an array it never declared with `dim`. */
        private const val DEFAULT_ARRAY_SIZE = 256
    }
}
