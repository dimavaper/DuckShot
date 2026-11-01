package com.custom.duckshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var ocrEditText: EditText
    private lateinit var suffixEditText: EditText
    private lateinit var exactMatchCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var searchBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("duckshot_prefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        val title = TextView(this).apply {
            text = "🦆 DuckShot"
            textSize = 20f
            setTextColor(Color.parseColor("#FFCC00"))
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        statusText = TextView(this).apply {
            text = "Распознавание текста (RU + EN)..."
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(statusText)

        val labelOcr = TextView(this).apply {
            text = "Текст со скриншота:"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        layout.addView(labelOcr)

        ocrEditText = EditText(this).apply {
            hint = "Здесь появится текст..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            minLines = 2
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(ocrEditText)

        exactMatchCheck = CheckBox(this).apply {
            text = "Точное совпадение (взять в \"кавычки\")"
            setTextColor(Color.LTGRAY)
            isChecked = prefs.getBoolean("exact_match", true)
        }
        layout.addView(exactMatchCheck)

        val labelSuffix = TextView(this).apply {
            text = "Дописать после текста:"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        layout.addView(labelSuffix)

        suffixEditText = EditText(this).apply {
            hint = "Например: отзывы, скачать, site:4pda.to..."
            setText(prefs.getString("saved_suffix", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        layout.addView(suffixEditText)

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 24, 0, 0)
        }

        val cancelBtn = Button(this).apply {
            text = "Отмена"
            setOnClickListener { finish() }
        }
        btnLayout.addView(cancelBtn)

        searchBtn = Button(this).apply {
            text = "Искать в DDG"
            setOnClickListener {
                val mainText = ocrEditText.text.toString().trim()
                val suffix = suffixEditText.text.toString().trim()
                val useQuotes = exactMatchCheck.isChecked

                prefs.edit()
                    .putString("saved_suffix", suffix)
                    .putBoolean("exact_match", useQuotes)
                    .apply()

                if (mainText.isNotEmpty() || suffix.isNotEmpty()) {
                    val formattedMain = if (useQuotes && mainText.isNotEmpty()) "\"$mainText\"" else mainText
                    val finalQuery = listOf(formattedMain, suffix).filter { it.isNotEmpty() }.joinToString(" ")
                    openDuckDuckGo(finalQuery)
                } else {
                    Toast.makeText(this@MainActivity, "Пустой запрос!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnLayout.addView(searchBtn)
        layout.addView(btnLayout)

        setContentView(layout)

        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val imageUri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
            if (imageUri != null) {
                runOcr(imageUri)
            } else {
                statusText.text = "Ошибка получения скриншота"
            }
        } else {
            statusText.text = "Готово к работе! Сделай скриншот -> Отправить -> DuckShot"
            ocrEditText.visibility = View.GONE
            searchBtn.text = "Сохранить шаблон"
            searchBtn.setOnClickListener {
                getSharedPreferences("duckshot_prefs", Context.MODE_PRIVATE).edit()
                    .putString("saved_suffix", suffixEditText.text.toString().trim())
                    .putBoolean("exact_match", exactMatchCheck.isChecked)
                    .apply()
                Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun runOcr(uri: Uri) {
        searchBtn.isEnabled = false
        thread {
            try {
                val tessDir = File(filesDir, "tessdata")
                if (!tessDir.exists()) tessDir.mkdirs()

                for (lang in listOf("rus.traineddata", "eng.traineddata")) {
                    val outFile = File(tessDir, lang)
                    if (!outFile.exists()) {
                        assets.open("tessdata/$lang").use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bitmap = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }

                if (bitmap != null) {
                    val tess = TessBaseAPI()
                    tess.init(filesDir.absolutePath, "rus+eng")
                    tess.setImage(bitmap)
                    val recognized = tess.utF8Text ?: ""
                    tess.recycle()

                    val cleanText = recognized
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    runOnUiThread {
                        ocrEditText.setText(cleanText)
                        statusText.text = if (cleanText.isNotEmpty()) "Текст распознан (можно подправить):" else "Текст не найден, впишите вручную:"
                        searchBtn.isEnabled = true
                        suffixEditText.requestFocus()
                        suffixEditText.setSelection(suffixEditText.text.length)
                    }
                } else {
                    runOnUiThread {
                        statusText.text = "Не удалось прочитать картинку"
                        searchBtn.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Ошибка OCR: ${e.localizedMessage}"
                    searchBtn.isEnabled = true
                }
            }
        }
    }

    private fun openDuckDuckGo(query: String) {
        val url = "https://duckduckgo.com/?q=" + Uri.encode(query)
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(browserIntent)
        finish()
    }
}
