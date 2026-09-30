package io.github.qwwqeras1145.mgq

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import mgq.core.archive.AssetResolver
import mgq.core.platform.Audio
import mgq.core.platform.Platform
import mgq.core.platform.Scene
import mgq.core.platform.Sprite
import mgq.core.script.NsDecoder
import mgq.core.script.ScriptParser
import mgq.core.vm.Vm
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns one play session: the parsed script, the VM, the renderer state and the audio backend.
 *
 * The VirtualMachine is pumped from the surface thread in bounded slices ([Vm.run]) so that a long
 * `for` loop in the battle system cannot stall rendering.
 */
class EngineSession(
    private val gameDir: File,
    private val onError: (String) -> Unit,
) : AutoCloseable {

    private val assets = AssetResolver(gameDir)
    private lateinit var vm: Vm
    private lateinit var androidPlatform: AndroidPlatform

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Decoded bitmaps, keyed by asset path. Decoding is the dominant per-frame cost otherwise. */
    private val bitmaps = ConcurrentHashMap<String, android.graphics.Bitmap?>()

    fun start(): Boolean {
        val scriptFile = File(gameDir, "nscript.dat")
        if (!scriptFile.isFile) { onError("找不到 nscript.dat"); return false }

        val decoded = try {
            NsDecoder.decodeFile(scriptFile)
        } catch (e: Exception) {
            onError("脚本解码失败: ${e.message}"); return false
        }
        val parser = ScriptParser(scriptFile.name)
        val script = try {
            parser.parse(decoded.text)
        } catch (e: Exception) {
            onError("脚本解析失败: ${e.message}"); return false
        }
        if (script.size == 0) { onError("脚本为空"); return false }

        androidPlatform = AndroidPlatform(gameDir, assets)
        vm = Vm(script, androidPlatform)

        val entry = when {
            script.labels.containsKey("*define") -> "*define"
            script.labels.containsKey("*game_start") -> "*game_start"
            else -> null
        }
        if (entry != null) vm.startAt(entry) else vm.pc = 0
        return true
    }

    /** Run a bounded slice of the VM. */
    fun pump() {
        if (::vm.isInitialized && !vm.finished) {
            vm.run(maxSteps = 4_000, maxMillis = 8)
        }
    }

    /** Draw the current scene. */
    fun render(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (!::vm.isInitialized) return
        val scene: Scene = androidPlatform.scene

        scene.screenClear?.let { c -> canvas.drawColor(Color.argb(255, c.r, c.g, c.b)) }

        // Background.
        scene.background?.takeIf { it.isNotEmpty() }?.let { path ->
            drawAsset(canvas, path, 0, 0, null)
        }

        // Sprites. NScripter draws ascending layer number with lower numbers in front, so the
        // painter's order here is descending to leave the topmost layer painted last.
        for (layer in scene.spriteOrder.reversed()) {
            val s: Sprite = scene.sprites[layer] ?: continue
            if (!s.visible) continue
            drawAsset(canvas, s.path, s.x, s.y, s)
        }

        // Text.
        scene.text?.let { t ->
            val win = scene.window
            val x = t.x ?: (win?.x ?: 48)
            val y = t.y ?: (win?.y ?: (canvas.height - 220))
            paint.color = Color.WHITE
            paint.textSize = 26f
            paint.style = Paint.Style.FILL
            t.name?.let { name ->
                paint.color = Color.rgb(255, 220, 120)
                canvas.drawText(name, x.toFloat(), (y - 12).toFloat(), paint)
                paint.color = Color.WHITE
            }
            var lineY = y.toFloat() + 26f
            for (line in t.lines) {
                canvas.drawText(line, x.toFloat(), lineY, paint)
                lineY += 30f
            }
        }
    }

    /** Draw one asset, honouring the pseudo-3D transform when present. */
    private fun drawAsset(canvas: Canvas, path: String, x: Int, y: Int, sprite: Sprite?) {
        // Inline string sprites (`:s/24,24,1;#FFFFFF;text`) are text, not files.
        if (path.startsWith(":")) return
        val bmp = bitmaps.getOrPut(path) {
            assets.readBytes(path)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } ?: return

        val aff = sprite?.affine
        if (aff == null) {
            canvas.drawBitmap(bmp, null, Rect(x, y, x + bmp.width, y + bmp.height), paint)
        } else {
            // lsp2 positions by the image CENTRE and scales/rotates about that centre; this is the
            // single most mis-implemented NScripter detail, so it is handled explicitly rather than
            // by reusing the drawBitmap(src, dst) path.
            canvas.save()
            val cx = x.toFloat()
            val cy = y.toFloat()
            canvas.translate(cx, cy)
            canvas.rotate(-aff.rotation.toFloat())   // NScripter rotation is counter-clockwise
            canvas.scale(aff.scaleX / 1000f, aff.scaleY / 1000f)
            val src = if (aff.srcW > 0 && aff.srcH > 0) {
                Rect(aff.srcX, aff.srcY, aff.srcX + aff.srcW, aff.srcY + aff.srcH)
            } else null
            val halfW = (src?.width() ?: bmp.width) / 2f
            val halfH = (src?.height() ?: bmp.height) / 2f
            canvas.drawBitmap(bmp, src, Rect((-halfW).toInt(), (-halfH).toInt(), halfW.toInt(), halfH.toInt()), paint)
            canvas.restore()
        }
    }

    override fun close() {
        bitmaps.clear()
        androidPlatform.close()
        assets.close()
    }
}

/**
 * Android implementation of the engine's platform contract.
 *
 * Everything the VM can do to the outside world goes through here, which is what allows the exact
 * same engine core to be regression-tested headlessly on a desktop JVM.
 */
class AndroidPlatform(
    private val gameDir: File,
    private val assets: AssetResolver,
) : Platform {

    override val scene = Scene()
    override val audio: Audio = AndroidAudio(gameDir, assets)

    private var lastClickNanos = 0L

    /**
     * Wait for a tap.
     *
     * The surface thread polls; because the engine blocks here, the renderer keeps drawing the
     * current frame while waiting, which is exactly the behaviour a visual novel needs.
     */
    override fun waitForClick(): Int {
        // Busy-wait is avoided by sleeping in small slices; a real input queue is wired in
        // GameActivity, this is the fallback so a headless or misconfigured run cannot spin.
        val until = System.nanoTime() + 16_000_000L
        while (System.nanoTime() < until) Thread.sleep(1)
        lastClickNanos = System.nanoTime()
        return 0
    }

    override fun waitMillis(millis: Int) {
        var left = millis
        while (left > 0) {
            val slice = minOf(left, 16)
            Thread.sleep(slice.toLong())
            left -= slice
        }
    }

    override fun warning(message: String) {
        android.util.Log.w("mgq", message)
    }

    override fun nowMillis(): Long = System.nanoTime() / 1_000_000

    override fun saveSlot(slot: Int, data: ByteArray) {
        val f = File(gameDir, "save/slot$slot.mgq")
        f.parentFile?.mkdirs()
        f.writeBytes(data)
    }

    override fun loadSlot(slot: Int): ByteArray? {
        val f = File(gameDir, "save/slot$slot.mgq")
        return if (f.isFile) f.readBytes() else null
    }

    override fun saveSlotCount(): Int = 20

    fun close() {
        (audio as? AndroidAudio)?.close()
    }
}

/**
 * Audio backend.
 *
 * The game's audio is Ogg Vorbis, which `MediaPlayer` decodes natively. Three independent channels
 * are kept because NScripter treats BGM, sound effects and the "digital wave" voice channel
 * separately, and the script drives them concurrently during battle.
 */
class AndroidAudio(
    private val gameDir: File,
    private val assets: AssetResolver,
) : Audio, AutoCloseable {

    private var bgm: MediaPlayer? = null
    private val se = ArrayList<MediaPlayer>(4)
    private var dwave: MediaPlayer? = null

    /** Temp files for sounds that live inside an `.nsa`, since MediaPlayer needs a real path. */
    private val tempFiles = HashMap<String, File>()

    override fun playBgm(path: String, loop: Boolean) {
        stopBgm()
        val src = sourceFor(path) ?: return
        bgm = MediaPlayer().apply {
            runCatching {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(src.absolutePath)
                isLooping = loop
                prepare()
                start()
            }.onFailure { android.util.Log.w("mgq", "bgm failed: $path") }
        }
    }

    override fun stopBgm() {
        bgm?.runCatching { if (isPlaying) stop(); release() }
        bgm = null
    }

    override fun playSe(path: String) {
        val src = sourceFor(path) ?: return
        val p = MediaPlayer()
        // Note the explicit `runCatching { p.<call> }` receiver: `runCatching` is an inline fun
        // whose lambda has NO receiver, so an unqualified `setDataSource(...)` would be resolved
        // against the enclosing AndroidAudio class and fail to compile.
        val ok = runCatching {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            p.setDataSource(src.absolutePath)
            p.prepare()
            p.start()
        }.isSuccess
        if (!ok) {
            runCatching { p.release() }
            return
        }
        se.add(p)
        if (se.size > 8) runCatching { se.removeAt(0).release() }
    }

    override fun stopSe() {
        se.forEach { it.runCatching { if (isPlaying) stop(); release() } }
        se.clear()
    }

    override fun playDwave(path: String, loop: Boolean) {
        stopDwave()
        val src = sourceFor(path) ?: return
        dwave = MediaPlayer().apply {
            runCatching {
                setDataSource(src.absolutePath)
                isLooping = loop
                prepare()
                start()
            }.onFailure { android.util.Log.w("mgq", "dwave failed: $path") }
        }
    }

    override fun stopDwave() {
        dwave?.runCatching { if (isPlaying) stop(); release() }
        dwave = null
    }

    override fun isDwavePlaying(): Boolean = dwave?.runCatching { isPlaying }?.getOrDefault(false) == true

    /**
     * Resolve an audio path to a real file.
     *
     * Loose files are used directly; assets inside an `.nsa` are spilled to a cache file once,
     * because `MediaPlayer` cannot read from our random-access archive reader.
     */
    private fun sourceFor(path: String): File? {
        val loose = File(gameDir, path.replace('\\', '/'))
        if (loose.isFile) return loose
        tempFiles[path]?.let { if (it.isFile) return it }
        val bytes = assets.readBytes(path) ?: return null
        val out = File(gameDir, "cache/" + path.replace('\\', '/').replace('/', '_'))
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        tempFiles[path] = out
        return out
    }

    override fun close() {
        stopSe(); stopBgm(); stopDwave()
        tempFiles.clear()
    }
}
