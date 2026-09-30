package io.github.qwwqeras1145.mgq

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import mgq.core.platform.Platform
import java.io.File

/**
 * The game surface.
 *
 * Data flow: the Storage Access Framework hands us a tree URI; [UriGameStorage] mirrors the parts the
 * engine needs onto local storage once, because `nscript.dat` and the `.nsa` archives are read with
 * random access, which `ContentResolver` streams do not support. The mirror is cached and reused on
 * later launches.
 *
 * The engine itself (`:core`) has no Android dependency. This activity supplies the
 * [mgq.core.platform.Platform] implementation and pumps the VM on a background thread.
 */
class GameActivity : AppCompatActivity() {

    private var gameView: GameView? = null
    private lateinit var overlay: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        overlay = TextView(this).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.WHITE)
            setPadding(32, 24, 32, 24)
            textSize = 13f
            visibility = View.GONE
        }
        root.addView(
            GameView(this, intent?.data).also { gameView = it },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(root)
    }

    override fun onResume() { super.onResume(); gameView?.resume() }

    override fun onPause() { gameView?.pause(); super.onPause() }

    /** Show a diagnostic message; used for missing data, unsupported archives, script errors. */
    fun showMessage(text: String) = runOnUiThread {
        overlay.text = text
        overlay.visibility = View.VISIBLE
    }

    class GameView(context: android.content.Context, private val tree: Uri?) :
        SurfaceView(context), SurfaceHolder.Callback {

        private var thread: GameThread? = null

        init {
            holder.addCallback(this)
        }

        override fun surfaceCreated(h: SurfaceHolder) {
            val t = GameThread(this, h, tree, (context as GameActivity))
            thread = t
            t.start()
        }

        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}

        override fun surfaceDestroyed(h: SurfaceHolder) {
            thread?.shutdown()
            thread = null
        }

        fun resume() { thread?.paused = false }

        fun pause() { thread?.paused = true }
    }

    /**
     * Drives the engine. Runs off the UI thread; the VM is single-threaded and this thread *is* that
     * thread, which is why no synchronisation appears between here and the renderer.
     */
    private class GameThread(
        private val view: SurfaceView,
        private val holder: SurfaceHolder,
        private val tree: Uri?,
        private val activity: GameActivity,
    ) : Thread("mgq-engine") {

        @Volatile var paused = false
        @Volatile private var running = true

        fun shutdown() { running = false; interrupt() }

        override fun run() {
            if (tree == null) {
                activity.showMessage(activity.getString(R.string.status_no_folder))
                return
            }
            val storage = UriGameStorage(activity, tree)
            val gameDir: File = try {
                storage.materialise { msg -> activity.showMessage(msg) }
            } catch (e: Exception) {
                activity.showMessage("无法读取游戏目录: ${e.message}")
                return
            }

            val engine = EngineSession(gameDir) { msg -> activity.showMessage(msg) }
            if (!engine.start()) return

            val frameNs = 16_000_000L
            while (running) {
                if (paused) { sleep(80); continue }
                val begin = System.nanoTime()
                engine.pump()
                val canvas = holder.lockCanvas() ?: continue
                try {
                    engine.render(canvas)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
                val elapsed = System.nanoTime() - begin
                val sleep = (frameNs - elapsed) / 1_000_000
                if (sleep > 0) sleep(sleep)
            }
            engine.close()
        }
    }
}

/** Placeholder paint holder so the renderer does not allocate per frame. */
internal val REUSED_PAINT = Paint(Paint.ANTI_ALIAS_FLAG)
