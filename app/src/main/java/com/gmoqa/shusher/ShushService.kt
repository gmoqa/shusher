package com.gmoqa.shusher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.time.LocalDate
import kotlin.math.log10
import kotlin.math.sqrt

class ShushService : Service() {

    companion object {
        const val PREFS = "shusher"
        const val KEY_THRESHOLD = "threshold"
        const val DEFAULT_THRESHOLD = -15 // dBFS
        const val COUNTS = "counts" // prefs: "yyyy-MM-dd" → cantidad de shhh ese día

        private const val RATE = 8000 // el CDD de Android garantiza 8 kHz; un grito cabe de sobra
        private const val BLOCK = RATE / 20 // 50 ms
        // 200 ms seguidos sobre el umbral = grito. Menos deja pasar portazos y aplausos.
        private const val LOUD_BLOCKS = 4
        private const val FADE_MS = 300L

        @Volatile var running = false
        @Volatile var threshold = DEFAULT_THRESHOLD
        @Volatile var level = -90.0 // último nivel medido, para el medidor de la UI

        /** Nivel RMS en dBFS: 0 = máximo, silencio ≈ -90. */
        fun dbfs(buf: ShortArray, n: Int): Double {
            if (n <= 0) return -90.0
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            val rms = sqrt(sum / n) / 32768.0
            return if (rms > 0) maxOf(20 * log10(rms), -90.0) else -90.0
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val shh by lazy {
        TextView(this).apply {
            text = "🤫\n${I18n.t(this@ShushService, "overlay_text")}"
            textSize = 80f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFF3F0EA.toInt())
        }
    }
    private val image by lazy {
        ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = resources.displayMetrics.heightPixels * 8 / 10
            maxWidth = resources.displayMetrics.widthPixels * 8 / 10
        }
    }

    // Android 12+ fuerza opacidad <= 0.8 en overlays que dejan pasar los toques.
    // Por eso son dos ventanas: el fondo semitransparente cubre todo y deja pasar los toques;
    // el personaje es opaco y recibe (descarta) los toques solo dentro de su rectángulo por 2 s.
    // Azul noche en vez de negro: oscurece igual, se siente menos brusco.
    private val back by lazy { View(this).apply { setBackgroundColor(0xFF0F2438.toInt()) } }
    private val front by lazy {
        FrameLayout(this).apply {
            addView(shh)
            addView(image)
        }
    }
    private val backParams = overlayParams(MATCH_PARENT, touchable = false).apply { alpha = 0.65f }
    private val frontParams = overlayParams(WRAP_CONTENT, touchable = true)
    // Fundido suave al entrar y salir: nada aparece ni desaparece de golpe.
    private val hide = Runnable {
        back.animate().alpha(0f).duration = FADE_MS
        front.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            wm.removeView(front)
            wm.removeView(back)
            image.setImageDrawable(null)
            next = randomImage() // se decodifica ahora para que el próximo shhh no espere (~30 ms)
        }
    }
    private var next: Bitmap? = null
    private var nextFile: File? = null

    // SoundPool deja el sonido decodificado en memoria (~160 KB): suena al instante,
    // MediaPlayer tardaba en crearse y en arrancar cada vez.
    private val sounds = SoundPool.Builder().setMaxStreams(1).build()
    private var shhSound = 0
    private val counts by lazy { getSharedPreferences(COUNTS, MODE_PRIVATE) }

    private var mic: Thread? = null

    // Con la pantalla apagada nadie ve el aviso: se apaga el micrófono y el CPU puede dormir.
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) =
            if (i.action == Intent.ACTION_SCREEN_ON) micOn() else micOff()
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification())
        }
        threshold = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
        shhSound = sounds.load(this, R.raw.shh, 1)
        next = randomImage()
        running = true
        refreshTile()
        micOn()
    }

    // Reiniciar desde background no tendría acceso al micrófono, así que no es sticky.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        unregisterReceiver(screen)
        micOff()
        running = false
        main.removeCallbacks(hide)
        sounds.release()
        if (back.parent != null) {
            front.animate().cancel() // cancelar no ejecuta el withEndAction que también quita las vistas
            wm.removeView(front)
            wm.removeView(back)
        }
        refreshTile()
    }

    private fun micOn() {
        if (mic == null) mic = Thread(::listen, "shusher-mic").apply { start() }
    }

    private fun micOff() {
        mic?.run { interrupt(); join() } // como mucho un bloque (100 ms)
        mic = null
    }

    @SuppressLint("MissingPermission") // MainActivity valida RECORD_AUDIO antes de arrancar
    private fun listen() {
        val rec = AudioRecord(
            // VOICE_RECOGNITION viene sin control automático de ganancia (CDD), así el nivel es real.
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), BLOCK * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            main.post(::stopSelf)
            return
        }
        val buf = ShortArray(BLOCK)
        var loud = 0
        rec.startRecording()
        while (!Thread.interrupted()) {
            val n = rec.read(buf, 0, BLOCK)
            if (n < 0) { // micrófono muerto: sin esto el loop gira en vacío y quema batería
                main.post(::stopSelf)
                break
            }
            level = dbfs(buf, n)
            loud = if (level > threshold) loud + 1 else 0
            if (loud == LOUD_BLOCKS) {
                loud = 0
                main.post(::shush)
            }
        }
        rec.stop()
        rec.release()
        level = -90.0
    }

    private fun shush() {
        if (back.parent != null || !running || !Settings.canDrawOverlays(this)) return
        // Dura 1,7 s, menos que el aviso: así el micrófono no se dispara con su propio "shh".
        sounds.play(shhSound, 1f, 1f, 0, 0, 1f)
        if (nextFile?.exists() == false) next = randomImage() // la borraron desde la app
        image.setImageBitmap(next)
        shh.visibility = if (next == null) View.VISIBLE else View.GONE
        back.alpha = 0f
        front.alpha = 0f
        wm.addView(back, backParams)
        wm.addView(front, frontParams)
        back.animate().alpha(1f).duration = FADE_MS
        front.animate().alpha(1f).duration = FADE_MS
        main.postDelayed(hide, 2000)
        val today = LocalDate.now().toString()
        counts.edit().putInt(today, counts.getInt(today, 0) + 1).apply()
    }

    /** Un PNG al azar de Android/data/com.gmoqa.shusher/files/images, o null si no hay. */
    private fun randomImage(): Bitmap? {
        val f = getExternalFilesDir("images")
            ?.listFiles { f -> f.extension.equals("png", ignoreCase = true) }
            ?.randomOrNull()
        nextFile = f
        if (f == null) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        // Reduce fotos grandes al alto de la pantalla: una de 12 MP ocuparía ~48 MB.
        o.inSampleSize = maxOf(1, o.outHeight / resources.displayMetrics.heightPixels)
        o.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(f.path, o) // null si el archivo está corrupto → SHHHH
    }

    private fun overlayParams(size: Int, touchable: Boolean) = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or // los toques fuera de la ventana siguen de largo
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("listening", I18n.t(this, "notif_channel"), NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, "listening")
            .setSmallIcon(R.drawable.ic_shush)
            .setContentTitle(I18n.t(this, "notif_title"))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()
    }

    private fun refreshTile() =
        TileService.requestListeningState(this, ComponentName(this, ShushTile::class.java))
}
