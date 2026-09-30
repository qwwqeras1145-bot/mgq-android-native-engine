package io.github.qwwqeras1145.mgq

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

/**
 * Launcher screen.
 *
 * The port ships **no game data**: the user points the app at the folder they already own. That keeps
 * the APK legally distributable and small, and it is also the practical design, because the game is
 * 4.26 GB and an APK containing it could not be installed from most stores anyway.
 *
 * The folder is chosen through the Storage Access Framework, so the app needs no broad storage
 * permission and works on scoped-storage Android versions.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            status.text = getString(R.string.status_no_folder)
            return@registerForActivityResult
        }
        // Persist the grant so the choice survives a reboot without re-prompting.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        launchGame(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.intro)
            textSize = 14f
            setPadding(0, 24, 0, 32)
        })
        root.addView(Button(this).apply {
            text = getString(R.string.choose_folder)
            setOnClickListener { pickFolder.launch(null) }
        })
        status = TextView(this).apply { textSize = 13f; setPadding(0, 32, 0, 0) }
        root.addView(status)
        setContentView(root)

        if (savedInstanceState == null) {
            status.text = getString(R.string.status_ready)
        }
    }

    private fun launchGame(uri: Uri) {
        val doc = DocumentFile.fromTreeUri(this, uri)
        val name = doc?.name ?: uri.lastPathSegment ?: "?"
        val hasScript = doc?.findFile("nscript.dat") != null
        status.text = getString(
            R.string.status_selected, name, if (hasScript) "nscript.dat" else getString(R.string.status_missing_script)
        )
        startActivity(
            Intent(this, GameActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
}
