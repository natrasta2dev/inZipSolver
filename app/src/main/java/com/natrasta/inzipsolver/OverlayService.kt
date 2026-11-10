package com.natrasta.inzipsolver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Bulle flottante : lit l'écran à la demande et dessine le chemin à suivre. Ne touche jamais l'écran à la place de l'utilisateur. */
class OverlayService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "bulle"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var bubble: BubbleView? = null
    private var pathView: PathView? = null
    private var busy = false
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startAsForeground()
        if (projection == null) {
            val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
            @Suppress("DEPRECATION")
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                                else intent?.getParcelableExtra(EXTRA_DATA)
            if (data == null) { stopSelf(); return START_NOT_STICKY }
            wm = getSystemService(WindowManager::class.java)
            setupCapture(code, data)
            showBubble()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bulle InZip", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Bulle InZip active")
            .setContentText("Touche la bulle sur la grille du Zip pour voir le chemin.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun setupCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            screenW = metrics.widthPixels; screenH = metrics.heightPixels
        }
        val ir = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        ir.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                latest?.close(); latest = img
            } catch (_: IllegalStateException) { }
        }, main)
        reader = ir
        display = mp.createVirtualDisplay("inzip", screenW, screenH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, main)
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun showBubble() {
        val dp = resources.displayMetrics.density
        val size = (58 * dp).roundToInt()
        val lp = WindowManager.LayoutParams(size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - (8 * dp).roundToInt()
            y = (resources.displayMetrics.heightPixels * 0.62).roundToInt()
        }
        val b = BubbleView(this)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val longPress = Runnable { if (!moved) { Toast.makeText(this, "Bulle fermée", Toast.LENGTH_SHORT).show(); stopSelf() } }
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; main.postDelayed(longPress, 700) }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.rawX - downX, e.rawY - downY) > 12 * dp) { moved = true; main.removeCallbacks(longPress) }
                    if (moved) { lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt(); wm.updateViewLayout(b, lp) }
                }
                MotionEvent.ACTION_UP -> { main.removeCallbacks(longPress); if (!moved) onBubbleTap() }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
        wm.addView(b, lp)
        bubble = b
    }

    private fun onBubbleTap() {
        if (busy) return
        if (pathView != null) { removePath(); return }
        busy = true
        bubble?.visibility = View.INVISIBLE
        // laisse l'écran se redessiner sans la bulle avant de prendre l'image
        main.postDelayed({ capture() }, 250)
    }

    private fun capture() {
        val img = latest
        val shot: Bitmap? = try { img?.let { toBitmap(it) } } catch (_: Exception) { null }
        bubble?.visibility = View.VISIBLE
        if (shot == null) { busy = false; toast("Capture indisponible. Arrête puis relance la bulle."); return }
        bubble?.setBusy(true)
        Thread {
            val tw = min(720, shot.width)
            val th = (shot.height.toLong() * tw / shot.width).toInt()
            val small = Bitmap.createScaledBitmap(shot, tw, th, true)
            val px = IntArray(tw * th); small.getPixels(px, 0, tw, 0, 0, tw, th)
            val gray = FloatArray(px.size) { i -> val c = px[i]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000f }
            val grid = ZipReader.read(gray, tw, th)
            val res = grid?.takeIf { it.unread == 0 }?.let { ZipSolver.solve(it) }
            val k = shot.width.toFloat() / tw
            main.post {
                busy = false; bubble?.setBusy(false)
                when {
                    grid == null -> toast("Grille du Zip introuvable. Affiche toute la grille à l'écran, puis touche la bulle.")
                    grid.unread > 0 -> toast("Un chiffre n'a pas pu être lu. Réessaie quand la grille est bien visible.")
                    res?.path == null -> toast("Grille lue, mais pas de solution : ${res?.error ?: ""}")
                    else -> showPath(res.path, grid, k)
                }
            }
        }.start()
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val rowPx = plane.rowStride / plane.pixelStride
        val buf = plane.buffer.apply { rewind() }
        val padded = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buf)
        return if (rowPx == img.width) padded else Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
    }

    private fun showPath(path: IntArray, grid: ZipReader.Grid, k: Float) {
        removePath()
        val v = PathView(this, path, grid.n, (grid.x0 * k).toFloat(), (grid.y0 * k).toFloat(), (grid.d * k).toFloat())
        val lp = WindowManager.LayoutParams(screenW, screenH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            // au-delà de 0.8, Android bloque les touchers vers l'app en dessous : il faut pouvoir tracer à travers
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        pathView = v
        // garde la bulle au-dessus du chemin
        bubble?.let { b -> val blp = b.layoutParams; wm.removeView(b); wm.addView(b, blp) }
        toast("Chemin affiché. Trace-le en suivant les flèches. Touche la bulle pour le masquer.")
    }

    private fun removePath() { pathView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; pathView = null }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        removePath()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        latest?.close(); latest = null
        display?.release(); reader?.close(); projection?.stop()
        display = null; reader = null; projection = null
        super.onDestroy()
    }

    /* ---------- Vues ---------- */

    private class BubbleView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F26A21") }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private var busy = false
        fun setBusy(b: Boolean) { busy = b; alpha = if (b) 0.6f else 1f; invalidate() }
        override fun onDraw(c: Canvas) {
            val s = width.toFloat(); val r = s / 2
            fill.setShadowLayer(s * 0.08f, 0f, s * 0.03f, Color.argb(90, 0, 0, 0))
            c.drawCircle(r, r, r * 0.86f, fill)
            ring.strokeWidth = s * 0.035f; c.drawCircle(r, r, r * 0.86f, ring)
            icon.strokeWidth = s * 0.075f
            val p = Path().apply {
                moveTo(s * 0.34f, s * 0.34f); lineTo(s * 0.66f, s * 0.34f); lineTo(s * 0.66f, s * 0.5f)
                lineTo(s * 0.34f, s * 0.5f); lineTo(s * 0.34f, s * 0.66f); lineTo(s * 0.66f, s * 0.66f)
            }
            c.drawPath(p, icon)
        }
    }

    private class PathView(ctx: Context, private val path: IntArray, private val n: Int,
                           private val gx: Float, private val gy: Float, private val cs: Float) : View(ctx) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 242, 106, 33); style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = cs * 0.28f
        }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = maxOf(3f, cs * 0.05f)
        }
        private val loc = IntArray(2)
        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            val ox = gx - loc[0]; val oy = gy - loc[1]
            fun pt(i: Int) = floatArrayOf(ox + (i % n + .5f) * cs, oy + (i / n + .5f) * cs)
            val p = Path()
            path.forEachIndexed { k, i -> val q = pt(i); if (k == 0) p.moveTo(q[0], q[1]) else p.lineTo(q[0], q[1]) }
            c.drawPath(p, line)
            val L = cs * 0.09f
            for (k in 1 until path.size) {
                val a = pt(path[k - 1]); val b = pt(path[k])
                val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
                val ang = atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble())
                val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
                val chev = Path().apply {
                    moveTo(mx - L * ca + L * sa, my - L * sa - L * ca)
                    lineTo(mx + L * ca * 0.6f, my + L * sa * 0.6f)
                    lineTo(mx - L * ca - L * sa, my - L * sa + L * ca)
                }
                c.drawPath(chev, arrow)
            }
        }
    }
}
