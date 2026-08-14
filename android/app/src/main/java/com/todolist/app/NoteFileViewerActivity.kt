package com.todolist.app

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import android.view.Gravity
import android.util.Base64
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.thread

class NoteFileViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "note_file_path"
        const val EXTRA_DISPLAY_NAME = "note_file_display_name"
        const val EXTRA_MIME = "note_file_mime"

        private val TEXT_EXTS = setOf(
            "txt", "md", "markdown", "json", "xml", "csv", "log", "rtf",
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "html", "htm", "css", "scss",
            "c", "cc", "cpp", "h", "hpp", "go", "rs", "swift", "dart", "sh", "bash", "zsh",
            "yaml", "yml", "toml", "ini", "properties", "gradle", "sql"
        )
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus")
        private val VIDEO_EXTS = setOf("mp4", "m4v", "webm", "3gp", "mkv")
        private val NEVER_INTERNAL_EXTS = setOf(
            "apk", "apks", "xapk", "aab", "pk", "exe", "msi", "dmg", "iso",
            "ppt", "pptx", "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
            "psd", "ai", "dwg", "dxf", "jar", "class", "so", "dll"
        )

        fun supportsInternalPreview(displayName: String, mime: String): Boolean {
            val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext in NEVER_INTERNAL_EXTS || mime == "application/vnd.android.package-archive") return false
            return ext in TEXT_EXTS || ext == "pdf" || ext == "docx" || ext == "xlsx" ||
                ext in IMAGE_EXTS || ext in AUDIO_EXTS || ext in VIDEO_EXTS ||
                mime.startsWith("text/") || mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") ||
                mime == "application/pdf" ||
                mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
    }

    private lateinit var file: File
    private var displayName: String = "附件"
    private var mime: String = "application/octet-stream"
    private lateinit var contentHost: FrameLayout
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var audioSeekBar: SeekBar? = null
    private var audioTime: TextView? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private data class PdfPageHolder(
        val index: Int,
        val frame: FrameLayout,
        val image: ZoomImageView,
        var bitmap: Bitmap? = null,
        var loading: Boolean = false
    )
    private var pdfScroll: ScrollView? = null
    private val pdfHolders = mutableListOf<PdfPageHolder>()
    private var pdfDestroyed = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        file = File(path)
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty().ifBlank { file.name }
        mime = intent.getStringExtra(EXTRA_MIME).orEmpty().let { incoming ->
            if (incoming.isBlank() || incoming == "application/octet-stream") guessMime(displayName) else incoming
        }
        if (!file.exists()) { finish(); return }

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.parseColor("#F6F0E4")
        if (!supportsInternalPreview(displayName, mime)) {
            openExternally()
            finish()
            return
        }
        buildShell()
        renderFile()
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F0E4"))
            setPadding(dp(12), dp(28), dp(12), dp(10))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(244, 255, 254, 250), 22)
            elevation = dp(6).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val back = Button(this).apply {
            text = "←"; textSize = 20f; setTextColor(Color.parseColor("#5B817F")); background = rounded(Color.TRANSPARENT, 14)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))

        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5), 0, dp(5), 0) }
        titleBox.addView(TextView(this).apply {
            text = displayName; textSize = 16f; setTextColor(Color.parseColor("#39332E")); setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        titleBox.addView(TextView(this).apply {
            text = viewerSubtitle(); textSize = 11f; setTextColor(Color.parseColor("#978C82")); maxLines = 1
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val external = Button(this).apply {
            text = "其他应用"; textSize = 11f; setTextColor(Color.parseColor("#557A9B")); background = rounded(Color.parseColor("#EEF4F8"), 14)
            setOnClickListener { openExternally() }
        }
        header.addView(external, LinearLayout.LayoutParams(dp(78), dp(42)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        contentHost = FrameLayout(this).apply {
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun viewerSubtitle(): String {
        val ext = displayName.substringAfterLast('.', "FILE").uppercase(Locale.ROOT)
        val fast = if (ext == "DOCX" || ext == "XLSX") " · 快速预览" else ""
        return "$ext$fast · ${sizeLabel(file.length())}"
    }

    private fun renderFile() {
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        when {
            ext == "pdf" || mime == "application/pdf" -> showPdf()
            ext == "docx" || mime.contains("wordprocessingml") -> showDocx()
            ext == "xlsx" || mime.contains("spreadsheetml") -> showXlsx()
            ext == "csv" -> showCsv()
            ext in IMAGE_EXTS || mime.startsWith("image/") -> showImage()
            ext in AUDIO_EXTS || mime.startsWith("audio/") -> showAudio()
            ext in VIDEO_EXTS || mime.startsWith("video/") -> showVideo()
            ext in TEXT_EXTS || mime.startsWith("text/") -> showText(ext)
            else -> showUnsupported()
        }
    }

    private fun showText(ext: String) {
        thread {
            val text = readUtf8Safely(file, 5 * 1024 * 1024)
            val html = when (ext) {
                "md", "markdown" -> markdownToHtml(text)
                "csv" -> csvToHtml(text)
                else -> "<pre class=\"code\">${escapeHtml(text)}</pre>"
            }
            runOnUiThread { showHtmlDocument(html, ext !in setOf("md", "markdown")) }
        }
    }

    private fun showCsv() {
        thread {
            val text = readUtf8Safely(file, 4 * 1024 * 1024)
            val html = csvToHtml(text)
            runOnUiThread { showHtmlDocument(html, false) }
        }
    }

    private fun showDocx() {
        showLoading("正在解析 DOCX…")
        thread {
            val html = try { docxToHtml(file) } catch (e: Exception) { "<div class=\"notice\">快速预览失败。可点右上角“其他应用”使用 WPS / Office 打开。</div>" }
            runOnUiThread { showHtmlDocument("<div class=\"notice\">DOCX 快速预览会尽量保留标题、段落、列表、表格和基础文字样式；复杂分页、浮动对象等请使用专业 Office 应用核对。</div>$html", false) }
        }
    }

    private fun showXlsx() {
        showLoading("正在解析 XLSX…")
        thread {
            val html = try { xlsxToHtml(file) } catch (e: Exception) { "<div class=\"notice\">快速预览失败。可点右上角“其他应用”使用 WPS / Office 打开。</div>" }
            runOnUiThread { showHtmlDocument("<div class=\"notice\">XLSX 快速预览用于查看单元格内容；复杂公式、图表、条件格式请使用专业表格应用核对。</div>$html", false) }
        }
    }

    private fun showHtmlDocument(body: String, mono: Boolean) {
        contentHost.removeAllViews()
        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        val font = if (mono) "ui-monospace,SFMono-Regular,Menlo,monospace" else "system-ui,-apple-system,sans-serif"
        val html = """
            <!doctype html><html><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=3">
            <style>
            body{margin:0;padding:18px 15px 50px;background:#fbf7ee;color:#403832;font-family:$font;line-height:1.72;font-size:15px;word-break:break-word}
            h1,h2,h3,h4{line-height:1.28;color:#332d29;margin:1.1em 0 .55em}h1{font-size:27px}h2{font-size:22px}h3{font-size:18px}
            pre.code,pre{white-space:pre-wrap;background:#f2eee7;border:1px solid #e8e1d7;border-radius:16px;padding:14px;overflow:auto;font-family:ui-monospace,monospace;font-size:13px;line-height:1.65}
            code{background:#f2eee7;border-radius:6px;padding:2px 5px;font-family:ui-monospace,monospace}
            blockquote{margin:12px 0;padding:8px 13px;border-left:4px solid #49bfae;background:#f0f8f5;border-radius:0 12px 12px 0;color:#665e57}
            table{border-collapse:separate;border-spacing:0;width:max-content;min-width:100%;font-size:13px;background:white;border:1px solid #e5ded4;border-radius:14px;overflow:hidden}th,td{padding:9px 10px;border-right:1px solid #eee7de;border-bottom:1px solid #eee7de;min-width:80px;max-width:260px}th{background:#eef7f4;font-weight:800}tr:last-child td{border-bottom:0}
            .table-wrap{overflow:auto;margin:12px 0 22px;border-radius:14px}.notice{padding:11px 13px;margin:0 0 15px;border-radius:14px;background:linear-gradient(135deg,#eef9f5,#eef4fb);color:#6e746f;font-size:12px;line-height:1.6;border:1px solid #e3efea}.sheet-title{font-size:17px;font-weight:850;margin:18px 0 8px}.doc-img{max-width:100%;height:auto;border-radius:12px}
            </style><body>$body</body></html>
        """.trimIndent()
        web.loadDataWithBaseURL("https://preview.local/", html, "text/html", "utf-8", null)
        contentHost.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showImage() {
        contentHost.removeAllViews()
        val image = ZoomImageView(this).apply {
            setBackgroundColor(Color.parseColor("#F6F0E4"))
            scaleType = ImageView.ScaleType.MATRIX
            setImageURI(Uri.fromFile(file))
        }
        contentHost.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        image.post { image.fitCenter() }
    }

    private fun showVideo() {
        contentHost.removeAllViews()
        val video = VideoView(this).apply {
            setVideoPath(file.absolutePath)
            val controls = MediaController(this@NoteFileViewerActivity)
            controls.setAnchorView(this)
            setMediaController(controls)
            setOnPreparedListener { it.isLooping = false; seekTo(1) }
            setOnErrorListener { _, _, _ -> showUnsupported(); true }
        }
        contentHost.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showAudio() {
        contentHost.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
            background = rounded(Color.parseColor("#FBF7EE"), 24)
        }
        box.addView(TextView(this).apply { text = "♪"; textSize = 64f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#49BFAE")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)))
        box.addView(TextView(this).apply { text = displayName; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#3E3732")); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines=2 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val seek = SeekBar(this); audioSeekBar = seek
        box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val time = TextView(this).apply { text="00:00 / 00:00"; gravity=Gravity.CENTER; textSize=11f; setTextColor(Color.parseColor("#93877D")) }; audioTime=time
        box.addView(time)
        val button = Button(this).apply { text="播放"; textSize=14f; background=rounded(Color.parseColor("#2EC4B6"),16); setTextColor(Color.WHITE) }
        box.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply{topMargin=dp(16)})
        contentHost.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply{setMargins(dp(8),dp(12),dp(8),dp(8))})

        val player = MediaPlayer(); mediaPlayer=player
        try {
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                seek.max = it.duration.coerceAtLeast(1)
                time.text = "00:00 / ${formatMs(it.duration)}"
            }
            player.setOnCompletionListener { button.text="播放"; seek.progress=seek.max }
            player.setOnErrorListener { _, _, _ -> showUnsupported(); true }
            player.prepareAsync()
            button.setOnClickListener {
                if (player.isPlaying) { player.pause(); button.text="播放" } else { player.start(); button.text="暂停"; tickAudio() }
            }
            seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if(fromUser) player.seekTo(progress) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        } catch (_:Exception) { showUnsupported() }
    }

    private fun tickAudio() {
        val p=mediaPlayer ?: return
        if (!p.isPlaying) return
        audioSeekBar?.progress = p.currentPosition
        audioTime?.text = "${formatMs(p.currentPosition)} / ${formatMs(p.duration)}"
        handler.postDelayed({ tickAudio() }, 500)
    }

    private fun showPdf() {
        contentHost.removeAllViews()
        showLoading("正在准备 PDF…")
        try {
            pdfDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pdfDescriptor!!)
        } catch (_: Exception) {
            showUnsupported()
            return
        }
        val renderer = pdfRenderer ?: return
        thread {
            val ratios = mutableListOf<Float>()
            try {
                for (i in 0 until renderer.pageCount) {
                    val ratio = synchronized(renderer) {
                        val page = renderer.openPage(i)
                        try { page.height.toFloat() / page.width.toFloat() } finally { page.close() }
                    }
                    ratios += ratio.coerceIn(0.45f, 3.2f)
                }
            } catch (_: Exception) {
                runOnUiThread { showUnsupported() }
                return@thread
            }
            runOnUiThread { buildVerticalPdf(ratios) }
        }
    }

    private fun buildVerticalPdf(ratios: List<Float>) {
        if (isFinishing || pdfDestroyed) return
        contentHost.removeAllViews()
        pdfHolders.forEach { it.bitmap?.recycle() }
        pdfHolders.clear()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(dp(6), dp(4), dp(6), dp(24))
            setBackgroundColor(Color.parseColor("#E9E4DD"))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentHost.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pdfScroll = scroll

        val targetWidth = (resources.displayMetrics.widthPixels - dp(36)).coerceAtLeast(dp(240))
        ratios.forEachIndexed { index, ratio ->
            val pageHeight = (targetWidth * ratio).toInt().coerceAtLeast(dp(220))
            val frame = FrameLayout(this).apply {
                background = rounded(Color.WHITE, 5)
                elevation = dp(1).toFloat()
            }
            val image = ZoomImageView(this, nestedInVerticalScroll = true).apply {
                scaleType = ImageView.ScaleType.MATRIX
                setBackgroundColor(Color.WHITE)
            }
            frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(TextView(this).apply {
                text = "${index + 1}"
                textSize = 10f
                setTextColor(Color.parseColor("#948A82"))
                gravity = Gravity.CENTER
                background = rounded(Color.argb(220, 248, 245, 240), 10)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(7)
            })
            column.addView(frame, LinearLayout.LayoutParams(targetWidth, pageHeight).apply {
                topMargin = if (index == 0) dp(4) else dp(10)
            })
            pdfHolders += PdfPageHolder(index, frame, image)
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> scheduleVisiblePdfPages() }
        scroll.post { scheduleVisiblePdfPages() }
    }

    private fun scheduleVisiblePdfPages() {
        val scroll = pdfScroll ?: return
        val viewportTop = scroll.scrollY
        val viewportBottom = viewportTop + scroll.height
        val preload = scroll.height.coerceAtLeast(dp(400))
        val keep = preload * 2
        pdfHolders.forEach { holder ->
            val top = holder.frame.top
            val bottom = holder.frame.bottom
            val near = bottom >= viewportTop - preload && top <= viewportBottom + preload
            val far = bottom < viewportTop - keep || top > viewportBottom + keep
            if (near && holder.bitmap == null && !holder.loading) renderPdfHolder(holder)
            if (far && holder.bitmap != null && !holder.loading) {
                holder.image.setImageDrawable(null)
                holder.bitmap?.recycle()
                holder.bitmap = null
            }
        }
    }

    private fun renderPdfHolder(holder: PdfPageHolder) {
        val renderer = pdfRenderer ?: return
        holder.loading = true
        val targetWidth = holder.frame.layoutParams.width.coerceAtLeast(dp(240))
        thread {
            var bitmap: Bitmap? = null
            try {
                bitmap = synchronized(renderer) {
                    val page = renderer.openPage(holder.index)
                    try {
                        val scale = targetWidth.toFloat() / page.width.toFloat()
                        val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { bmp ->
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    } finally { page.close() }
                }
            } catch (_: Exception) {}
            runOnUiThread {
                holder.loading = false
                if (pdfDestroyed || isFinishing || bitmap == null) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                holder.bitmap?.recycle()
                holder.bitmap = bitmap
                holder.image.setImageBitmap(bitmap)
                holder.image.post { holder.image.fitCenter() }
                scheduleVisiblePdfPages()
            }
        }
    }

    private fun showLoading(label:String){
        contentHost.removeAllViews();contentHost.addView(TextView(this).apply{text=label;gravity=Gravity.CENTER;textSize=14f;setTextColor(Color.parseColor("#8C8178"))},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showUnsupported() {
        contentHost.removeAllViews()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(28),dp(28),dp(28),dp(28));background=rounded(Color.parseColor("#FBF7EE"),24)}
        box.addView(TextView(this).apply{text="这个格式更适合专业应用";textSize=18f;gravity=Gravity.CENTER;setTextColor(Color.parseColor("#403832"));setTypeface(typeface,android.graphics.Typeface.BOLD)})
        box.addView(TextView(this).apply{text="To-Do 会保留附件本身，你可以交给 WPS、Office、系统播放器等专业应用完整打开。";textSize=13f;gravity=Gravity.CENTER;setTextColor(Color.parseColor("#8F8379"));setPadding(0,dp(10),0,dp(16))})
        box.addView(Button(this).apply{text="选择其他应用打开";setTextColor(Color.WHITE);background=rounded(Color.parseColor("#2EC4B6"),16);setOnClickListener{openExternally()}},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52)))
        contentHost.addView(box,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT).apply{setMargins(dp(8),dp(12),dp(8),dp(8))})
    }

    private fun openExternally() {
        try {
            val uri=FileProvider.getUriForFile(this,"$packageName.fileprovider",file)
            val intent=Intent(Intent.ACTION_VIEW).apply{
                setDataAndType(uri,mime.ifBlank{guessMime(displayName)})
                clipData=ClipData.newRawUri("note_attachment",uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent,"使用其他应用打开"))
        }catch(_:Exception){}
    }

    private fun readUtf8Safely(file:File,maxBytes:Int):String {
        val bytes = file.inputStream().buffered().use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
            val buffer = ByteArray(16 * 1024)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            out.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)+(if(file.length()>maxBytes)"\n\n…（快速预览已截断，完整内容请使用其他应用打开）" else "")
    }
    private fun escapeHtml(s:String):String=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun inlineMd(s:String):String {
        var x=escapeHtml(s)
        x=x.replace(Regex("`([^`]+)`"),"<code>$1</code>")
        x=x.replace(Regex("\\*\\*([^*]+)\\*\\*"),"<strong>$1</strong>")
        x=x.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)"),"<em>$1</em>")
        return x
    }
    private fun markdownToHtml(text:String):String {
        val out=StringBuilder();var inCode=false;val code=StringBuilder();var listOpen=false;var ordered=false
        fun closeList(){if(listOpen){out.append(if(ordered)"</ol>" else "</ul>");listOpen=false}}
        for(raw in text.lines()){
            val line=raw.trimEnd()
            if(line.trimStart().startsWith("```")){closeList();if(inCode){out.append("<pre><code>${escapeHtml(code.toString())}</code></pre>");code.clear();inCode=false}else inCode=true;continue}
            if(inCode){code.append(line).append('\n');continue}
            when{
                line.startsWith("### ")-> {closeList();out.append("<h3>${inlineMd(line.drop(4))}</h3>")}
                line.startsWith("## ")-> {closeList();out.append("<h2>${inlineMd(line.drop(3))}</h2>")}
                line.startsWith("# ")-> {closeList();out.append("<h1>${inlineMd(line.drop(2))}</h1>")}
                line.startsWith("> ")-> {closeList();out.append("<blockquote>${inlineMd(line.drop(2))}</blockquote>")}
                Regex("^[-*+] ").containsMatchIn(line)-> {if(!listOpen||ordered){closeList();out.append("<ul>");listOpen=true;ordered=false};out.append("<li>${inlineMd(line.drop(2))}</li>")}
                Regex("^\\d+[.)] ").containsMatchIn(line)-> {if(!listOpen||!ordered){closeList();out.append("<ol>");listOpen=true;ordered=true};out.append("<li>${inlineMd(line.replaceFirst(Regex("^\\d+[.)] "),""))}</li>")}
                line.matches(Regex("^[-*_ ]{3,}$"))-> {closeList();out.append("<hr>")}
                line.isBlank()-> {closeList();out.append("<div style=\"height:8px\"></div>")}
                else->{closeList();out.append("<p>${inlineMd(line)}</p>")}
            }
        }
        if(inCode)out.append("<pre><code>${escapeHtml(code.toString())}</code></pre>");closeList();return out.toString()
    }
    private fun parseCsvLine(line:String):List<String>{
        val result=mutableListOf<String>();val cur=StringBuilder();var quoted=false;var i=0
        while(i<line.length){val c=line[i];if(c=='\"'){if(quoted&&i+1<line.length&&line[i+1]=='\"'){cur.append('\"');i++}else quoted=!quoted}else if(c==','&&!quoted){result+=cur.toString();cur.clear()}else cur.append(c);i++};result+=cur.toString();return result
    }
    private fun csvToHtml(text:String):String{
        val rows=text.lines().take(400).filter{it.isNotEmpty()}.map{parseCsvLine(it).take(50)}
        if(rows.isEmpty())return "<div class=\"notice\">空表格</div>"
        val sb=StringBuilder("<div class=\"table-wrap\"><table>")
        rows.forEachIndexed{ri,row->sb.append("<tr>");row.forEach{cell->if(ri==0)sb.append("<th>${escapeHtml(cell)}</th>") else sb.append("<td>${escapeHtml(cell)}</td>")};sb.append("</tr>")};sb.append("</table></div>");return sb.toString()
    }

    private fun parseXml(input: java.io.InputStream)=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(input)
    private fun nodeText(node:Node):String{
        val sb=StringBuilder();fun walk(n:Node){if(n.nodeType==Node.TEXT_NODE)sb.append(n.nodeValue);else{if(n.localName=="tab")sb.append("    ");if(n.localName=="br")sb.append('\n');for(i in 0 until n.childNodes.length)walk(n.childNodes.item(i))}};walk(node);return sb.toString()
    }
    private fun findDocxEmbed(node:Node):String? {
        if(node.localName=="blip") {
            val attrs=node.attributes
            if(attrs!=null){for(i in 0 until attrs.length){val a=attrs.item(i);if(a.localName=="embed"||a.nodeName.endsWith(":embed"))return a.nodeValue}}
        }
        for(i in 0 until node.childNodes.length){findDocxEmbed(node.childNodes.item(i))?.let{return it}}
        return null
    }
    private fun docxRelationships(zip:ZipFile):Map<String,String>{
        val e=zip.getEntry("word/_rels/document.xml.rels")?:return emptyMap();val doc=zip.getInputStream(e).use{parseXml(it)};val rels=doc.getElementsByTagNameNS("*","Relationship");val map=mutableMapOf<String,String>();for(i in 0 until rels.length){val n=rels.item(i);val id=n.attributes?.getNamedItem("Id")?.nodeValue;val target=n.attributes?.getNamedItem("Target")?.nodeValue;if(!id.isNullOrBlank()&&!target.isNullOrBlank())map[id]=target};return map
    }
    private fun docxImageHtml(zip:ZipFile,target:String):String{
        val cleaned=target.replace("\\","/").removePrefix("/");val entryName=if(cleaned.startsWith("word/"))cleaned else "word/$cleaned";val e=zip.getEntry(entryName)?:return "";val bytes=zip.getInputStream(e).use{it.readBytes()};if(bytes.size>8*1024*1024)return "<div class=\"notice\">[图片过大，专业应用中查看]</div>";val ext=entryName.substringAfterLast('.',"").lowercase(Locale.ROOT);val mime=when(ext){"png"->"image/png";"gif"->"image/gif";"webp"->"image/webp";else->"image/jpeg"};return "<p><img class=\"doc-img\" src=\"data:$mime;base64,${Base64.encodeToString(bytes,Base64.NO_WRAP)}\"></p>"
    }
    private fun docxParagraphHtml(p:Node,zip:ZipFile,rels:Map<String,String>):String{
        var style="";val runs=StringBuilder()
        for(i in 0 until p.childNodes.length){val n=p.childNodes.item(i);if(n.localName=="pPr"){for(j in 0 until n.childNodes.length){val c=n.childNodes.item(j);if(c.localName=="pStyle")style=c.attributes?.getNamedItemNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","val")?.nodeValue ?: c.attributes?.item(0)?.nodeValue.orEmpty()}};if(n.localName=="r"){var txt="";var bold=false;var italic=false;var strike=false;for(j in 0 until n.childNodes.length){val c=n.childNodes.item(j);if(c.localName=="rPr"){for(k in 0 until c.childNodes.length){when(c.childNodes.item(k).localName){"b"->bold=true;"i"->italic=true;"strike"->strike=true}}}else txt+=nodeText(c)};var e=escapeHtml(txt);if(bold)e="<strong>$e</strong>";if(italic)e="<em>$e</em>";if(strike)e="<s>$e</s>";runs.append(e);findDocxEmbed(n)?.let{rid->rels[rid]?.let{target->runs.append(docxImageHtml(zip,target))}}} }
        val t=runs.toString().ifBlank{"&nbsp;"};val lower=style.lowercase(Locale.ROOT);return when{lower.contains("title")||lower.contains("heading1")->"<h1>$t</h1>";lower.contains("heading2")->"<h2>$t</h2>";lower.contains("heading3")->"<h3>$t</h3>";else->"<p>$t</p>"}
    }
    private fun docxTableHtml(tbl:Node):String{
        val sb=StringBuilder("<div class=\"table-wrap\"><table>");for(i in 0 until tbl.childNodes.length){val tr=tbl.childNodes.item(i);if(tr.localName!="tr")continue;sb.append("<tr>");for(j in 0 until tr.childNodes.length){val tc=tr.childNodes.item(j);if(tc.localName!="tc")continue;sb.append("<td>${escapeHtml(nodeText(tc).trim())}</td>")};sb.append("</tr>")};sb.append("</table></div>");return sb.toString()
    }
    private fun docxToHtml(file:File):String{
        ZipFile(file).use{zip->val entry=zip.getEntry("word/document.xml")?:return "<div class=\"notice\">无法读取正文。</div>";val rels=docxRelationships(zip);val doc=zip.getInputStream(entry).use{parseXml(it)};val body=doc.getElementsByTagNameNS("*","body").item(0)?:return "";val sb=StringBuilder();for(i in 0 until body.childNodes.length){val n=body.childNodes.item(i);when(n.localName){"p"->sb.append(docxParagraphHtml(n,zip,rels));"tbl"->sb.append(docxTableHtml(n))}};return sb.toString()}
    }
    private fun xlsxSharedStrings(zip:ZipFile):List<String>{
        val e=zip.getEntry("xl/sharedStrings.xml")?:return emptyList();val doc=zip.getInputStream(e).use{parseXml(it)};val si=doc.getElementsByTagNameNS("*","si");return (0 until si.length).map{nodeText(si.item(it))}
    }
    private fun colIndex(ref:String):Int{var n=0;for(c in ref.takeWhile{it.isLetter()}.uppercase(Locale.ROOT)){n=n*26+(c-'A'+1)};return (n-1).coerceAtLeast(0)}
    private fun xlsxToHtml(file:File):String{
        ZipFile(file).use{zip->val shared=xlsxSharedStrings(zip);val sheets=zip.entries().asSequence().filter{it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))}.toList().sortedBy{it.name}.take(6);if(sheets.isEmpty())return "<div class=\"notice\">没有可预览的工作表。</div>";val all=StringBuilder();sheets.forEachIndexed{si,e->val doc=zip.getInputStream(e).use{parseXml(it)};val rows=doc.getElementsByTagNameNS("*","row");all.append("<div class=\"sheet-title\">工作表 ${si+1}</div><div class=\"table-wrap\"><table>");for(ri in 0 until minOf(rows.length,250)){val row=rows.item(ri);val cells=row.childNodes;val map=mutableMapOf<Int,String>();var max=0;for(ci in 0 until cells.length){val c=cells.item(ci);if(c.localName!="c")continue;val ref=c.attributes?.getNamedItem("r")?.nodeValue.orEmpty();val idx=colIndex(ref).coerceAtMost(39);max=maxOf(max,idx);val type=c.attributes?.getNamedItem("t")?.nodeValue.orEmpty();var value="";for(k in 0 until c.childNodes.length){val child=c.childNodes.item(k);if(child.localName=="v")value=child.textContent;if(child.localName=="is")value=nodeText(child)};if(type=="s")value=value.toIntOrNull()?.let{shared.getOrNull(it)}?:value;map[idx]=value};all.append("<tr>");for(ci in 0..max)all.append("<td>${escapeHtml(map[ci].orEmpty())}</td>");all.append("</tr>")};all.append("</table></div>")};return all.toString()}
    }
    private fun guessMime(name:String):String{val ext=name.substringAfterLast('.',"").lowercase(Locale.ROOT);return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?:when(ext){"md","markdown"->"text/markdown";"json"->"application/json";"csv"->"text/csv";else->"application/octet-stream"}}
    private fun sizeLabel(bytes:Long):String=when{bytes<1024->"$bytes B";bytes<1024*1024->String.format(Locale.US,"%.1f KB",bytes/1024.0);bytes<1024L*1024*1024->String.format(Locale.US,"%.1f MB",bytes/1024.0/1024);else->String.format(Locale.US,"%.1f GB",bytes/1024.0/1024/1024)}
    private fun formatMs(ms:Int):String{val total=ms.coerceAtLeast(0)/1000;return "%02d:%02d".format(total/60,total%60)}

    override fun onDestroy() {
        pdfDestroyed = true
        handler.removeCallbacksAndMessages(null)
        try{mediaPlayer?.release()}catch(_:Exception){}
        pdfHolders.forEach { holder -> holder.image.setImageDrawable(null); holder.bitmap?.recycle(); holder.bitmap = null }
        pdfHolders.clear()
        try { pdfRenderer?.let { renderer -> synchronized(renderer) { renderer.close() } } } catch (_: Exception) {}
        try{pdfDescriptor?.close()}catch(_:Exception){}
        super.onDestroy()
    }

    class ZoomImageView(
        context: android.content.Context,
        private val nestedInVerticalScroll: Boolean = false
    ): androidx.appcompat.widget.AppCompatImageView(context) {
        private val matrix = android.graphics.Matrix()
        private var zoom = 1f
        private var baseScale = 1f
        private var tx = 0f
        private var ty = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L

        private val detector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val oldZoom = zoom.coerceAtLeast(0.0001f)
                    val minZoom = if (nestedInVerticalScroll) 1f else 0.75f
                    val nextZoom = (oldZoom * detector.scaleFactor).coerceIn(minZoom, 5f)
                    val factor = nextZoom / oldZoom

                    // Keep the content under the fingers stable while scaling instead of
                    // always zooming toward the geometric center of the page/image.
                    tx = detector.focusX - (detector.focusX - tx) * factor
                    ty = detector.focusY - (detector.focusY - ty) * factor
                    zoom = nextZoom
                    applyMatrix()
                    return true
                }
            }
        )

        fun fitCenter() {
            val d = drawable ?: return
            val vw = width.toFloat()
            val vh = height.toFloat()
            val dw = d.intrinsicWidth.coerceAtLeast(1).toFloat()
            val dh = d.intrinsicHeight.coerceAtLeast(1).toFloat()
            if (vw <= 0f || vh <= 0f) return

            baseScale = minOf(vw / dw, vh / dh)
            zoom = 1f
            tx = (vw - dw * baseScale) / 2f
            ty = (vh - dh * baseScale) / 2f
            applyMatrix()
        }

        private fun applyMatrix() {
            val d = drawable ?: return
            matrix.reset()
            val actualScale = baseScale * zoom
            matrix.postScale(actualScale, actualScale)

            val w = d.intrinsicWidth.coerceAtLeast(1) * actualScale
            val h = d.intrinsicHeight.coerceAtLeast(1) * actualScale
            val centeredX = (width - w) / 2f
            val centeredY = (height - h) / 2f
            val minX = if (w <= width) centeredX else width - w
            val maxX = if (w <= width) centeredX else 0f
            val minY = if (h <= height) centeredY else height - h
            val maxY = if (h <= height) centeredY else 0f

            tx = tx.coerceIn(minX, maxX)
            ty = ty.coerceIn(minY, maxY)
            matrix.postTranslate(tx, ty)
            imageMatrix = matrix
        }

        private fun isZoomed(): Boolean = zoom > 1.01f

        override fun onTouchEvent(event: MotionEvent): Boolean {
            detector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                    if (nestedInVerticalScroll && isZoomed()) parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // A two-finger gesture belongs to the image, not the surrounding PDF ScrollView.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !detector.isInProgress) {
                        if (!nestedInVerticalScroll || isZoomed()) {
                            if (nestedInVerticalScroll) parent?.requestDisallowInterceptTouchEvent(true)
                            tx += event.x - lastX
                            ty += event.y - lastY
                            applyMatrix()
                        } else {
                            // At base scale, let a vertical drag bubble up to the PDF ScrollView.
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        lastX = event.x
                        lastY = event.y
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    if (remainingIndex < event.pointerCount) {
                        lastX = event.getX(remainingIndex)
                        lastY = event.getY(remainingIndex)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (nestedInVerticalScroll) {
                        if (zoom <= 1.01f) fitCenter()
                    } else {
                        val quickTap = event.eventTime - downTime < 220L &&
                            kotlin.math.abs(event.x - downX) < 15f &&
                            kotlin.math.abs(event.y - downY) < 15f
                        if (quickTap && zoom > 1.05f) fitCenter()
                    }
                    parent?.requestDisallowInterceptTouchEvent(false)
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (nestedInVerticalScroll && zoom <= 1.01f) fitCenter()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
    }
}
