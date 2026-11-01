package by.tablescan

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val KEY_PHOTO = "photoUri"
    }

    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var table: TableLayout
    private lateinit var btnCamera: Button
    private lateinit var btnGallery: Button
    private lateinit var btnRecognize: Button
    private lateinit var btnSave: Button
    private lateinit var btnShare: Button

    private var source: Bitmap? = null
    private var result: TableResult? = null
    private val edits = ArrayList<ArrayList<EditText>>()
    private var photoUri: Uri? = null
    private var busy = false

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { loadImage(it) }
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImage(uri)
    }
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        if (uri != null) saveTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        table = findViewById(R.id.table)
        btnCamera = findViewById(R.id.btnCamera)
        btnGallery = findViewById(R.id.btnGallery)
        btnRecognize = findViewById(R.id.btnRecognize)
        btnSave = findViewById(R.id.btnSave)
        btnShare = findViewById(R.id.btnShare)

        photoUri = savedInstanceState?.getString(KEY_PHOTO)?.let { Uri.parse(it) }

        btnCamera.setOnClickListener {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            photoUri = uri
            takePhoto.launch(uri)
        }
        btnGallery.setOnClickListener { pickImage.launch("image/*") }
        btnRecognize.setOnClickListener { recognize() }
        btnSave.setOnClickListener {
            createDoc.launch("table_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".xlsx")
        }
        btnShare.setOnClickListener { share() }
        updateButtons()

        if (savedInstanceState == null && intent?.action == Intent.ACTION_SEND) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            uri?.let { loadImage(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        photoUri?.let { outState.putString(KEY_PHOTO, it.toString()) }
    }

    private fun updateButtons() {
        btnCamera.isEnabled = !busy
        btnGallery.isEnabled = !busy
        btnRecognize.isEnabled = !busy && source != null
        btnSave.isEnabled = !busy && edits.isNotEmpty()
        btnShare.isEnabled = !busy && edits.isNotEmpty()
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun loadImage(uri: Uri) {
        try {
            val bmp = ImageUtils.load(this, uri)
            source = bmp
            preview.setImageBitmap(bmp)
            status.text = "Фото загружено (${bmp.width}×${bmp.height}). Нажмите «Распознать»."
        } catch (e: Exception) {
            status.text = "Не удалось открыть изображение: ${e.message}"
        }
        updateButtons()
    }

    private fun recognize() {
        val src = source ?: return
        busy = true
        updateButtons()
        progress.isIndeterminate = true
        status.text = "Поиск таблицы…"
        Thread {
            try {
                val res = Recognizer.run(applicationContext, src) { done, total ->
                    runOnUiThread {
                        progress.isIndeterminate = false
                        progress.max = total
                        progress.progress = done
                        status.text = "Распознавание ячеек: $done из $total"
                    }
                }
                runOnUiThread {
                    result = res
                    busy = false
                    showTable(res)
                    status.text = if (res.gridFound)
                        "Готово: ${res.rows} строк × ${res.cols} столбцов. Проверьте значения (можно править прямо в ячейках) и сохраните."
                    else
                        "Линии таблицы не найдены — текст разбит по строкам и промежуткам. Проверьте результат."
                    updateButtons()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    busy = false
                    status.text = "Ошибка распознавания: ${t.message}"
                    updateButtons()
                }
            }
        }.start()
    }

    private fun showTable(res: TableResult) {
        table.removeAllViews()
        edits.clear()
        val covered = HashSet<Long>()
        for (m in res.merges) for (r in m.r until m.r + m.rs) for (c in m.c until m.c + m.cs)
            if (r != m.r || c != m.c) covered.add(r * 100_000L + c)
        val dp = resources.displayMetrics.density
        for (r in 0 until res.rows) {
            val tr = TableRow(this)
            val rowEdits = ArrayList<EditText>()
            for (c in 0 until res.cols) {
                val et = EditText(this)
                et.setText(res.cells[r].getOrElse(c) { "" })
                et.textSize = 13f
                et.minWidth = (70 * dp).toInt()
                et.maxWidth = (240 * dp).toInt()
                et.setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
                if ((r * 100_000L + c) in covered) {
                    et.isEnabled = false
                    et.setBackgroundResource(R.drawable.cell_merged)
                } else {
                    et.setBackgroundResource(R.drawable.cell_bg)
                }
                tr.addView(et)
                rowEdits.add(et)
            }
            table.addView(tr)
            edits.add(rowEdits)
        }
    }

    private fun currentRows(): List<List<String>> = edits.map { row -> row.map { it.text.toString().trim() } }

    private fun saveTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use {
                XlsxWriter.write(it, currentRows(), result?.merges ?: emptyList())
            } ?: throw IllegalStateException("нет доступа к файлу")
            Toast.makeText(this, "Файл сохранён", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun share() {
        try {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            val f = File(dir, "table_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".xlsx")
            f.outputStream().use { XlsxWriter.write(it, currentRows(), result?.merges ?: emptyList()) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND)
                .setType(XLSX_MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Отправить XLSX"))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
