package mgq.core.platform

/**
 * The complete set of side effects the NScripter VM is allowed to perform.
 *
 * The engine core (`:core`) never touches a display, a speaker, a file system or a clock
 * directly. Everything goes through this interface, which is why the exact same VM code can
 * be driven by:
 *
 *  - `mgq.core.cli` on a desktop JVM, rendering to a text transcript (this is how the port
 *    is regression-tested against the real 380k-line script), and
 *  - the Android app, rendering to a `Canvas` and playing through `AudioTrack`.
 *
 * Every method is called from the single VM thread. Implementations are not required to be
 * thread-safe.
 */
interface Platform {

    /** Mutable, observable description of what should be on screen right now. */
    val scene: Scene

    val audio: Audio

    /**
     * Block the VM until the player signals "advance" (tap / click / key).
     *
     * NScripter blocks after printing a page of text, after `click`, after `waitforclick`,
     * and while a `btnwait` selection is pending.
     *
     * @return the id of the clicked button, or 0 when the player clicked a non-button area.
     */
    fun waitForClick(): Int

    /** Block for [millis] of wall-clock time, cooperatively (must honour cancellation). */
    fun waitMillis(millis: Int)

    /** Report a non-fatal script-level problem; must never throw. */
    fun warning(message: String)

    /** Current monotonic time in milliseconds, used by `resettimer` / `getini`-style logic. */
    fun nowMillis(): Long

    /** Persist one save slot. [data] is an opaque engine-produced blob. */
    fun saveSlot(slot: Int, data: ByteArray)

    /** Read one save slot, or null when empty. */
    fun loadSlot(slot: Int): ByteArray?

    /** Number of save slots exposed to the script (`savenumber`). */
    fun saveSlotCount(): Int
}

/**
 * Audio backend. Implementations that cannot play a given format must degrade gracefully
 * (log a warning, continue) rather than throwing: a missing BGM must never break the script.
 */
interface Audio {
    /** Start looping background music from [path] (a game-relative path). */
    fun playBgm(path: String, loop: Boolean)

    /** Stop background music. */
    fun stopBgm()

    /** Play a one-shot sound effect. */
    fun playSe(path: String)

    /** Stop every currently playing sound effect. */
    fun stopSe()

    /**
     * Start a "digital wave" — the voice channel. [loop] is normally false.
     * `dwave`/`dwaveon`/`dwaveoff` and `dwavestop` drive this channel.
     */
    fun playDwave(path: String, loop: Boolean)

    fun stopDwave()

    /** True while the digital-wave (voice) channel is still playing. */
    fun isDwavePlaying(): Boolean
}

/**
 * Everything that can appear on screen.
 *
 * This mirrors NScripter's data model rather than a general-purpose scene graph, because the
 * script addresses layers by number and relies on exact layer semantics:
 *
 *  - slot 0 is the background ([background])
 *  - `lsp` / `lsp2` / `vsp` / `amsp` write into [sprites], keyed by layer number
 *  - `csp n` clears layer n, `csp -1` clears every layer
 *  - `quake`, `monocro`, `shadedistance` and `screen_clear` are full-screen effects
 *
 * A renderer walks [spriteOrder] so the z-order stays deterministic.
 */
class Scene {
    /** Background image path, or null for "nothing drawn yet". */
    var background: String? = null

    /** Background transition: 0 = instant, 1 = fade, 2 = crossfade, 3 = slide. */
    var backgroundEffect: Int = 0

    /** Layer number -> sprite state. */
    val sprites: MutableMap<Int, Sprite> = LinkedHashMap()

    /**
     * Layer paint order. NScripter paints strictly by layer number, ascending, so this is
     * normally just `sprites.keys.sorted()`, but keeping it explicit makes the ordering rule
     * visible instead of implicit.
     */
    val spriteOrder: List<Int> get() = sprites.keys.sorted()

    /** Full-screen tint set by `monocro`; null means no tint. */
    var monochrome: Monochrome? = null

    /** Screen shake set by `quake`. */
    var quake: Quake? = null

    /** `screen_clear` / `screen_clear2` fill; null means "not cleared". */
    var screenClear: Monochrome? = null

    /** Text window definition from `setwindow`. */
    var window: TextWindow? = null

    /** Text currently being shown. */
    var text: ShownText? = null

    /** `shadedistance` — minimum z gap NScripter uses for shade effects between layers. */
    var shadeDistance: Int = 0

    fun clearAll() {
        sprites.clear()
        text = null
        monochrome = null
        quake = null
        screenClear = null
    }

    fun removeSprite(layer: Int) {
        sprites.remove(layer)
    }
}

/** One image layer. */
data class Sprite(
    /** Image path, game-relative, using the backslash convention of the original script. */
    var path: String,
    /** Left edge in virtual pixels. */
    var x: Int,
    /** Top edge in virtual pixels. */
    var y: Int,
    /** Visible flag, toggled by `vsp` (visible) rather than removed. */
    var visible: Boolean = true,
    /** Pseudo-3D state; null for plain `lsp`. */
    var affine: Affine? = null,
    /** `amsp` alpha, 0..255 (255 = opaque). */
    var alpha: Int = 255,
    /** Blend mode id set by `monocro`-adjacent commands; 0 = normal. */
    var blend: Int = 0,
)

/**
 * Pseudo-3D transform used by `lsp2` / `msp2`.
 *
 * The parameter names follow the ONScripter convention. A null [Affine] means "identity",
 * which is what plain `lsp` produces, so renderers only have to take the slow path when a
 * transform is actually present.
 */
data class Affine(
    /** Source rectangle inside the image, in image pixels. */
    var srcX: Int = 0,
    var srcY: Int = 0,
    var srcW: Int = 0,
    var srcH: Int = 0,
    /** Horizontal/vertical scale, 1000 = 1.0. */
    var scaleX: Int = 1000,
    var scaleY: Int = 1000,
    /** Rotation in degrees. */
    var rotation: Int = 0,
    /** Rotation centre in image pixels. */
    var centerX: Int = 0,
    var centerY: Int = 0,
    /** Cosine/sine of the rotation angle, fixed point (1000 = 1.0). */
    var cos: Int = 1000,
    var sin: Int = 0,
)

/** A full-screen or text-window tint. */
data class Monochrome(
    var r: Int,
    var g: Int,
    var b: Int,
    var alpha: Int,
)

/** Screen shake state. */
data class Quake(
    var amplitudeX: Int,
    var amplitudeY: Int,
    var durationMs: Int,
)

/** The text window rectangle defined by `setwindow`. */
data class TextWindow(
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    var marginLeft: Int = 0,
    var marginTop: Int = 0,
)

/**
 * The page of text currently presented.
 *
 * [lines] holds the already-wrapped text lines; wrapping is done by the engine so that the
 * renderer only has to draw glyphs.
 */
data class ShownText(
    /** Speaker label, set by the `name` command; null when nobody is speaking. */
    var name: String? = null,
    /** Wrapped body lines. */
    var lines: MutableList<String> = mutableListOf(),
    /** Absolute text origin, or null to use the text window origin. */
    var x: Int? = null,
    var y: Int? = null,
    /** True while the text is still being "typed out" by the typewriter effect. */
    var typing: Boolean = false,
)

/** Virtual screen geometry. The game declares 800x600 via the leading `;$V...S800,600` header. */
data class ScreenSize(val width: Int, val height: Int) {
    companion object {
        val DEFAULT = ScreenSize(800, 600)
    }
}
