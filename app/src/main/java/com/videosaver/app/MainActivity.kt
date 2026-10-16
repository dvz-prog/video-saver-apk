package com.videosaver.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL

class MainActivity : AppCompatActivity() {

    private val modes = listOf("best", "1080", "720", "480", "audio")

    private lateinit var etUrl: EditText
    private lateinit var spMode: Spinner
    private lateinit var cbPlaylist: CheckBox
    private lateinit var tvDir: TextView
    private lateinit var tvStatus: TextView
    private lateinit var jobsBox: LinearLayout

    private val listener: () -> Unit = { render() }

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                Storage.prefs(this).edit().putString("tree", uri.toString()).apply()
                showDir()
            }
        }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "Video Saver"

        etUrl = findViewById(R.id.etUrl)
        spMode = findViewById(R.id.spMode)
        cbPlaylist = findViewById(R.id.cbPlaylist)
        tvDir = findViewById(R.id.tvDir)
        tvStatus = findViewById(R.id.tvStatus)
        jobsBox = findViewById(R.id.jobsBox)

        val adapter = ArrayAdapter.createFromResource(this, R.array.modes, android.R.layout.simple_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spMode.adapter = adapter

        findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnPick).setOnClickListener { pickTree.launch(null) }
        findViewById<Button>(R.id.btnDefault).setOnClickListener {
            Storage.prefs(this).edit().remove("tree").apply()
            showDir()
        }
        findViewById<Button>(R.id.btnGo).setOnClickListener { startDownload() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { updateYtDlp() }

        handleIntent(intent)
        showDir()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        JobStore.addListener(listener)
        render()
    }

    override fun onStop() {
        JobStore.removeListener(listener)
        super.onStop()
    }

    /** Link recebido pelo botão "Compartilhar" de outro app. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            extractUrl(text)?.let { etUrl.setText(it) }
        }
    }

    private fun extractUrl(text: String): String? = Regex("https?://\\S+").find(text)?.value

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val url = extractUrl(text)
        if (url != null) etUrl.setText(url) else tvStatus.text = "Não há link na área de transferência."
    }

    private fun showDir() {
        val tree = Storage.prefs(this).getString("tree", null)
        tvDir.text = if (tree == null) {
            "Salvar em: Downloads/Video Saver (padrão)"
        } else {
            "Salvar em: " + (DocumentFile.fromTreeUri(this, Uri.parse(tree))?.name ?: "pasta escolhida")
        }
    }

    private fun startDownload() {
        val url = extractUrl(etUrl.text.toString())
        if (url == null) {
            tvStatus.text = "Cole um link válido (começando com https://)."
            return
        }
        tvStatus.text = ""
        val intent = Intent(this, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EXTRA_URL, url)
            .putExtra(DownloadService.EXTRA_MODE, modes[spMode.selectedItemPosition])
            .putExtra(DownloadService.EXTRA_PLAYLIST, cbPlaylist.isChecked)
        ContextCompat.startForegroundService(this, intent)
        etUrl.setText("")
    }

    private fun updateYtDlp() {
        tvStatus.text = "Atualizando o yt-dlp… (precisa de internet)"
        Thread {
            try {
                App.ready.await()
                val result = YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                runOnUiThread { tvStatus.text = "yt-dlp atualizado: $result" }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "Falha ao atualizar: " + (e.message ?: e.toString()).take(200) }
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        jobsBox.removeAllViews()
        for (j in JobStore.snapshot()) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            box.addView(TextView(this).apply {
                text = j.url
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
            })
            box.addView(TextView(this).apply {
                text = if (j.status == "done") "✔ " + (j.file ?: "Concluído") else j.text
                if (j.status == "error") setTextColor(Color.RED)
            })
            if (j.status == "running") {
                box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = j.percent.toInt()
                    isIndeterminate = j.percent <= 0f
                })
                box.addView(Button(this).apply {
                    text = "Cancelar"
                    setOnClickListener {
                        ContextCompat.startForegroundService(
                            this@MainActivity,
                            Intent(this@MainActivity, DownloadService::class.java)
                                .setAction(DownloadService.ACTION_CANCEL)
                                .putExtra(DownloadService.EXTRA_ID, j.id)
                        )
                    }
                })
            }
            jobsBox.addView(box)
        }
    }
}
