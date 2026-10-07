package com.gmoqa.shusher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val ACTION_START = "com.gmoqa.shusher.START"
        private const val MIN_DB = -60 // incluye el ruido ambiente: en silencio la barra igual se mueve un poco
        private const val STEPS = 10 // pasos del slider por dB: la barra avanza continua, no a saltos
        private const val PICK_PNG = 2
        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

        // Paleta de dibujo animado, como las ilustraciones: colores vivos pero suaves y contornos oscuros.
        private const val BG = 0xFFFFF4E0.toInt()
        private const val CARD = 0xFFFFFFFF.toInt()
        private const val INK = 0xFF33303F.toInt() // texto y contornos
        private const val MUTED = 0xFF6E6A7C.toInt()
        private const val CALM = 0xFF2E9BDA.toInt() // acción principal / nivel normal (celeste del ícono)
        private const val WARM = 0xFFE8684A.toInt() // pausar / nivel sobre el umbral
        private const val MINT = 0xFF5CC992.toInt() // barras del historial
        private const val LEVEL = 0xFF6FD66F.toInt() // nivel del micrófono: el verde típico de un medidor de volumen
        private const val TRACK = 0xFFF6EAD3.toInt()
        private const val QUIET_ZONE = 0xFFD3ECFA.toInt() // slider: bajo el umbral
        private const val ALERT_ZONE = 0xFFFFD3C4.toInt() // slider: sobre el umbral, aparece el aviso
        // Fondos pastel que se alternan en las miniaturas de personajes.
        private val PASTELS = intArrayOf(0xFFFFD6E0.toInt(), 0xFFFFE9A8.toInt(), 0xFFCDEFD9.toInt(), 0xFFD3ECFA.toInt())
    }

    private val main = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }
    private val images by lazy { getExternalFilesDir("images")!! } // la crea si no existe
    private val counts by lazy { getSharedPreferences(ShushService.COUNTS, MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var meter: SeekBar
    private lateinit var today: TextView
    private lateinit var history: LinearLayout
    private lateinit var thumbs: LinearLayout
    private lateinit var imagesInfo: TextView
    private var waitingOverlay = false
    private var ready = false // false mientras se muestra el onboarding
    private var shownOn: Boolean? = null
    private var shownLevel = 0f
    @Volatile private var previewLevel = -90.0
    private var preview: Thread? = null
    private var shownOver: Boolean? = null

    // Solo corre con la app visible: estado y medidor en vivo para calibrar.
    private val tick = object : Runnable {
        override fun run() {
            val on = ShushService.running
            if (on != shownOn) {
                shownOn = on
                status.text = if (on) "👂  ${t("listening")}" else "😴  ${t("paused")}"
                toggle.text = t(if (on) "pause" else "activate")
                setChunky(toggle, if (on) WARM else CALM)
            }
            // En pausa el medidor usa su propio micrófono; escuchando, el del servicio (nunca los dos).
            if (on) previewOff() else previewOn()
            main.postDelayed(this, 150)
        }
    }

    // Medidor en cada cuadro de pantalla, solo con esta vista visible. Sube rápido y baja suave,
    // como un vúmetro: el servicio mide cada 50 ms y esto lo vuelve un movimiento continuo.
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val db = if (ShushService.running) ShushService.level else previewLevel
            val target = ((db - MIN_DB) * STEPS).toFloat().coerceAtLeast(0f)
            shownLevel += (target - shownLevel) * if (target > shownLevel) 0.5f else 0.1f
            meter.secondaryProgress = shownLevel.toInt()
            val over = meter.secondaryProgress > meter.progress
            if (over != shownOver) {
                shownOver = over
                meter.secondaryProgressTintList = ColorStateList.valueOf(if (over) WARM else LEVEL)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // El servicio suma un aviso: se redibuja el historial sin sondear.
    private val countsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> showHistory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        copyDefaults()
        if (I18n.chosen(this) == null) {
            showOnboarding(I18n.deviceDefault(this))
            return
        }
        ready = true
        val prefs = getSharedPreferences(ShushService.PREFS, MODE_PRIVATE)

        status = text("", 26f, INK, bold = true)
        toggle = button("", CALM) {
            if (ShushService.running) stopService(Intent(this@MainActivity, ShushService::class.java)) else start()
        }.apply { textSize = 22f; minHeight = (68 * dp).toInt() }
        // El nivel del micrófono es la barra (secondaryProgress) y el umbral es el punto:
        // quien cuida ve en vivo cuándo un sonido cruzaría el umbral, sin hablar de decibeles.
        meter = SeekBar(this).apply {
            max = -MIN_DB * STEPS
            progress = (prefs.getInt(ShushService.KEY_THRESHOLD, ShushService.DEFAULT_THRESHOLD) - MIN_DB) * STEPS
            // Pista gruesa: fondo, nivel (secondaryProgress) y progress invisible (solo el punto marca el umbral).
            // Dos zonas siempre visibles: a la izquierda del punto la zona tranquila, a la derecha la del aviso.
            // Encima, una barra más delgada con el sonido en vivo. El orden del arreglo es el orden de dibujo.
            fun fill(color: Int) = ClipDrawable(rounded(color, 999), Gravity.START, ClipDrawable.HORIZONTAL)
            val stroke = (3 * dp).toInt()
            progressDrawable = LayerDrawable(arrayOf(
                outlined(ALERT_ZONE, 999).apply { setSize(0, (30 * dp).toInt()) },
                fill(QUIET_ZONE),
                fill(Color.WHITE),
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                setId(2, android.R.id.secondaryProgress)
                setLayerInset(1, stroke, stroke, stroke, stroke)
                val needle = (10 * dp).toInt()
                setLayerInset(2, stroke + needle / 2, needle, stroke + needle / 2, needle)
            }
            // Perilla: círculo blanco con contorno y centro oscuro, para que se note que se arrastra.
            thumb = LayerDrawable(arrayOf(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(stroke, INK)
                    setSize((40 * dp).toInt(), (40 * dp).toInt())
                },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(INK) },
            )).apply { val c = (14 * dp).toInt(); setLayerInset(1, c, c, c, c) }
            // Sin esto Android corta la pista con bordes rectos alrededor del punto y se ve un recuadro.
            splitTrack = false
            background = null // sin la onda rectangular al tocar
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    ShushService.threshold = p / STEPS + MIN_DB
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) =
                    prefs.edit().putInt(ShushService.KEY_THRESHOLD, s.progress / STEPS + MIN_DB).apply()
            })
        }
        today = text("", 40f, INK, bold = true)
        history = column()
        thumbs = LinearLayout(this)
        imagesInfo = text("", 16f, MUTED)

        val content = column().apply {
            // Ícono a la izquierda; título y subtítulo apilados a su derecha, alineados y centrados con él.
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(appIcon(), LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply {
                    marginEnd = (14 * dp).toInt()
                })
                addView(column().apply {
                    addView(text("Shusher", 32f, INK, bold = true))
                    addView(text(t("subtitle"), 16f, MUTED))
                })
            }, gap(0, 24))

            addView(card(
                status,
                text(t("mic_rests"), 16f, MUTED),
                toggle.withGap(16),
            ), gap(0, 16))

            addView(card(
                text(t("sensitivity"), 20f, INK, bold = true),
                text(t("sensitivity_help"), 16f, MUTED),
                meter.withGap(16),
                LinearLayout(this@MainActivity).apply {
                    addView(text(t("more_sensitive"), 15f, MUTED), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(text(t("less_sensitive"), 15f, MUTED).apply { gravity = Gravity.END },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                },
            ), gap(0, 16))

            addView(card(
                text(t("today_title"), 20f, INK, bold = true),
                today,
                history.withGap(8),
            ), gap(0, 16))

            addView(card(
                text(t("characters"), 20f, INK, bold = true),
                imagesInfo,
                HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(thumbs)
                }.withGap(12),
                LinearLayout(this@MainActivity).apply {
                    addView(button(t("add_png"), CALM) {
                        startActivityForResult(
                            Intent(Intent.ACTION_GET_CONTENT)
                                .setType("image/png")
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                            PICK_PNG,
                        )
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = (12 * dp).toInt() })
                    addView(button(t("remove_all"), WARM) {
                        images.listFiles()?.forEach { it.delete() }
                        showImages()
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }.withGap(16),
            ), gap(0, 16))

            addView(text(t("tip"), 15f, MUTED), gap(0, 16))
            val json = I18n.json(this@MainActivity, I18n.current(this@MainActivity))
            // Abajo: idioma a la izquierda y, discreto a la derecha, el enlace al repositorio.
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(button("${json.getString("flag")}  ${json.getString("name")} · ${t("language")}", CARD) {
                    ready = false
                    shownOn = null
                    main.removeCallbacks(tick)
                    Choreographer.getInstance().removeFrameCallback(frame)
                    previewOff()
                    showOnboarding(I18n.current(this@MainActivity))
                }.apply { setTextColor(INK); textSize = 16f }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                // Abre el navegador: la app sigue sin permiso de internet.
                addView(text(t("github"), 14f, MUTED).apply {
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    val p = (8 * dp).toInt()
                    setPadding(p, p, 0, p) // área táctil más cómoda
                    setOnClickListener {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gmoqa/shusher"))) }
                    }
                })
            })
        }
        setScreen(content)
        // El tile abre la activity para arrancar el servicio con la app visible (requisito del micrófono).
        if (intent.action == ACTION_START) start()
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        main.post(tick)
        Choreographer.getInstance().postFrameCallback(frame)
        counts.registerOnSharedPreferenceChangeListener(countsChanged)
        showHistory()
        showImages()
        if (waitingOverlay && Settings.canDrawOverlays(this)) {
            waitingOverlay = false
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!ready) return
        main.removeCallbacks(tick)
        Choreographer.getInstance().removeFrameCallback(frame)
        previewOff()
        counts.unregisterOnSharedPreferenceChangeListener(countsChanged)
    }

    /**
     * Medidor en pausa: escucha solo mientras esta pantalla está visible, para calibrar antes de activar.
     * No muestra avisos. Bloques de 20 ms (el servicio usa 50): la barra se ve más fluida.
     */
    @SuppressLint("MissingPermission") // se revisa RECORD_AUDIO justo antes
    private fun previewOn() {
        // isFinishing: abierta desde el tile solo para activar; el servicio va a tomar el micrófono.
        if (preview != null || isFinishing ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        preview = Thread({
            val rate = 8000
            val buf = ShortArray(rate / 50)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), buf.size * 2),
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                rec.startRecording()
                while (!Thread.interrupted()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    previewLevel = ShushService.dbfs(buf, n)
                }
                rec.stop()
            }
            rec.release()
            previewLevel = -90.0
        }, "shusher-meter").apply { start() }
    }

    private fun previewOff() {
        preview?.run { interrupt(); join() } // como mucho un bloque (20 ms)
        preview = null
    }

    private fun showHistory() {
        val now = LocalDate.now()
        val days = (0L..6L).map { now.minusDays(it) }
        val values = days.map { counts.getInt(it.toString(), 0) }
        val max = maxOf(1, values.max())
        today.text = when (values[0]) {
            0 -> t("count_none")
            1 -> t("count_one")
            else -> t("count_many", values[0])
        }
        val locale = Locale.forLanguageTag(I18n.current(this))
        history.removeAllViews()
        // Semana sin avisos: un estado vacío amable en vez de siete filas en cero.
        today.visibility = if (values.sum() == 0) View.GONE else View.VISIBLE
        if (values.sum() == 0) {
            history.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@MainActivity).apply { setImageBitmap(emptyArt) },
                    LinearLayout.LayoutParams((72 * dp).toInt(), (100 * dp).toInt()).apply { marginEnd = (16 * dp).toInt() })
                addView(column().apply {
                    addView(text(t("history_empty_title"), 20f, INK, bold = true))
                    addView(text(t("history_empty_text"), 15f, MUTED))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            return
        }
        days.zip(values).forEach { (day, n) ->
            val label = when (day) {
                now -> t("today")
                now.minusDays(1) -> t("yesterday")
                else -> "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${day.dayOfMonth}"
            }
            history.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(label, 15f, MUTED), LinearLayout.LayoutParams((100 * dp).toInt(), WRAP_CONTENT)) // cabe "Aujourd'hui"
                // Pista de fondo con la barra encima: un día en cero se ve como pista vacía, no como hueco.
                addView(LinearLayout(this@MainActivity).apply {
                    background = rounded(TRACK, 999)
                    addView(View(this@MainActivity).apply {
                        background = rounded(MINT, 999).apply { setStroke((2 * dp).toInt(), INK) }
                    }, LinearLayout.LayoutParams(0, MATCH_PARENT, n.toFloat()))
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, MATCH_PARENT, (max - n).toFloat()))
                }, LinearLayout.LayoutParams(0, (18 * dp).toInt(), 1f))
                addView(text("$n", 15f, INK, bold = true).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams((40 * dp).toInt(), WRAP_CONTENT))
            }, gap(0, 8))
        }
    }

    // La niña de los personajes por defecto, reducida: ilustra el historial vacío.
    private val emptyArt by lazy {
        assets.open("defaults/girl.png").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }
    }

    /**
     * Instalación nueva: copia los personajes incluidos (assets/defaults) a la carpeta de imágenes.
     * Se decide por la carpeta: si existe vacía, el usuario los quitó.
     */
    private fun copyDefaults() {
        val dir = File(getExternalFilesDir(null) ?: return, "images")
        if (dir.exists()) return
        dir.mkdirs()
        assets.list("defaults").orEmpty().forEach { name ->
            assets.open("defaults/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
    }

    private fun showImages() {
        // Del más antiguo al más nuevo: los personajes por defecto (copiados al instalar) quedan al inicio.
        val files = images.listFiles()?.sortedWith(compareBy({ it.lastModified() }, { it.name })).orEmpty()
        imagesInfo.text = t(if (files.isEmpty()) "characters_none" else "characters_some")
        thumbs.removeAllViews()
        val size = (96 * dp).toInt()
        files.forEachIndexed { i, f ->
            // Miniatura reducida al decodificar, no la imagen completa.
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            o.inSampleSize = maxOf(1, o.outHeight / size)
            o.inJustDecodeBounds = false
            thumbs.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(f.path, o))
                background = outlined(PASTELS[i % PASTELS.size], 18)
                val p = (8 * dp).toInt()
                setPadding(p, p, p, p)
            }, LinearLayout.LayoutParams(size, size).apply { marginEnd = (10 * dp).toInt() })
        }
    }

    // Copia los PNG elegidos tal cual (conserva la transparencia). Las galerías a veces ignoran
    // el filtro de tipo, así que se valida la firma PNG del archivo.
    // ponytail: copia en el hilo principal; con PNG de pocos MB es instantáneo.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != PICK_PNG || resultCode != RESULT_OK || data == null) return
        val uris = data.clipData?.let { c -> List(c.itemCount) { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        var skipped = 0
        uris.forEachIndexed { i, uri ->
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null && bytes.size > PNG.size && bytes.copyOf(PNG.size).contentEquals(PNG)) {
                File(images, "${System.currentTimeMillis()}-$i.png").writeBytes(bytes)
            } else {
                skipped++
            }
        }
        if (skipped > 0) Toast.makeText(this, t("not_png", skipped), Toast.LENGTH_LONG).show()
        showImages()
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                else arrayOf(Manifest.permission.RECORD_AUDIO),
                1,
            )
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlay = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        previewOff() // el servicio toma el micrófono
        startForegroundService(Intent(this, ShushService::class.java))
        if (intent.action == ACTION_START) finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
    }

    // Primer inicio (o botón de idioma): título, subtítulo y una lista grande de idiomas con banderas.
    // Al tocar un idioma la pantalla se redibuja ya traducida; "Continuar" lo guarda.
    private fun showOnboarding(selected: String) {
        val content = column().apply {
            addView(appIcon(), LinearLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt()).apply {
                bottomMargin = (16 * dp).toInt()
            })
            addView(text(t("onboarding_title", lang = selected), 34f, INK, bold = true))
            addView(text(t("onboarding_subtitle", lang = selected), 18f, MUTED), gap(8, 32))
            // Select desplegable: ocupa una sola fila, así "Continuar" se ve también en horizontal.
            val langs = I18n.languages(this@MainActivity)
            addView(Spinner(this@MainActivity, Spinner.MODE_DROPDOWN).apply {
                background = outlined(CARD, 22)
                setPopupBackgroundDrawable(rounded(CARD, 22).apply { setStroke((3 * dp).toInt(), INK) })
                setPadding(0, 0, 0, 0) // sin el relleno del estilo (reservaba la flecha del sistema)
                dropDownWidth = MATCH_PARENT // el desplegable mide lo mismo que el select
                adapter = object : ArrayAdapter<String>(this@MainActivity, 0, langs) {
                    override fun getView(pos: Int, convertView: View?, parent: ViewGroup) = langRow(langs[pos], arrow = true)
                    override fun getDropDownView(pos: Int, convertView: View?, parent: ViewGroup) =
                        langRow(langs[pos], arrow = false, checked = langs[pos] == selected)
                }
                setSelection(langs.indexOf(selected))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    // Se llama también al montar el select: solo se redibuja si cambió el idioma.
                    override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                        if (langs[pos] != selected) showOnboarding(langs[pos])
                    }
                    override fun onNothingSelected(p: AdapterView<*>) {}
                }
            }, gap(0, 0))
            addView(button(t("continue", lang = selected), CALM) {
                I18n.choose(this@MainActivity, selected)
                recreate()
            }.apply { textSize = 22f; minHeight = (68 * dp).toInt() }, gap(24, 0))
        }
        setScreen(content)
    }

    /** Fila de idioma: bandera + nombre; con flecha si es el select cerrado, con ✓ si es el elegido. */
    private fun langRow(lang: String, arrow: Boolean, checked: Boolean = false) = LinearLayout(this).apply {
        val json = I18n.json(this@MainActivity, lang)
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (80 * dp).toInt()
        val p = (20 * dp).toInt()
        setPadding(p, 0, p, 0)
        addView(text(json.getString("flag"), 36f, INK), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginEnd = (16 * dp).toInt()
        })
        addView(text(json.getString("name"), 22f, INK, bold = arrow || checked), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (arrow) addView(text("▾", 26f, CALM))
        if (checked) addView(text("✓", 24f, CALM, bold = true)) // marca el elegido sin depender del color
    }

    private fun setScreen(content: LinearLayout) {
        val side = (24 * dp).toInt()
        content.setPadding(side, (24 * dp).toInt(), side, (32 * dp).toInt())
        // Android 15+ dibuja de borde a borde: fitsSystemWindows deja el contenido fuera de las barras.
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(BG)
            addView(content)
        })
    }

    // --- piezas de la interfaz ---

    /** El ícono de la app (el mismo del launcher). */
    private fun appIcon() = ImageView(this).apply { setImageResource(R.drawable.app_icon) }

    private fun t(key: String, n: Int? = null, lang: String = I18n.current(this)) = I18n.t(this, key, n, lang)

    // Nunito (OFL, assets/fonts): redondeada y gruesa, el tono de un libro infantil.
    private val heavy by lazy { nunito(900) }
    private val regular by lazy { nunito(600) }
    private fun nunito(weight: Int) =
        Typeface.Builder(assets, "fonts/Nunito.ttf").setFontVariationSettings("'wght' $weight").build()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = if (bold) heavy else regular
        setLineSpacing(0f, 1.1f)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * dp
    }

    /** Forma de dibujo animado: redondeada y con contorno oscuro parejo. */
    private fun outlined(fill: Int, radiusDp: Int) = rounded(fill, radiusDp).apply { setStroke((3 * dp).toInt(), INK) }

    private fun setChunky(button: Button, fill: Int) {
        button.background = RippleDrawable(ColorStateList.valueOf(0x33000000), outlined(fill, 999), null)
        // El fondo nuevo borra el relleno: se vuelve a poner.
        val p = (20 * dp).toInt()
        button.setPadding(p, 0, p, 0)
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        typeface = heavy
        setTextColor(Color.WHITE)
        setChunky(this, color)
        stateListAnimator = null
        minHeight = (60 * dp).toInt()
        setOnClickListener { onClick() }
    }

    private fun card(vararg views: View) = column().apply {
        background = outlined(CARD, 26)
        val p = (24 * dp).toInt()
        setPadding(p, p, p, p)
        views.forEach { addView(it) }
    }

    private fun gap(topDp: Int, bottomDp: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = (topDp * dp).toInt()
        bottomMargin = (bottomDp * dp).toInt()
    }

    private fun <V : View> V.withGap(topDp: Int) = apply { layoutParams = gap(topDp, 0) }
}
