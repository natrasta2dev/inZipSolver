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
    private var pathView: SolutionView? = null
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
            .setContentText("Touche la bulle sur un jeu LinkedIn pour voir la solution.")
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
            val out = try { Games.solve(Screen(tw, th, px)) } catch (e: Exception) { Outcome(null, "Erreur de lecture : ${e.message}") }
            val k = shot.width.toFloat() / tw
            main.post {
                busy = false; bubble?.setBusy(false)
                val sol = out.solution
                if (sol == null) toast(out.message) else showSolution(sol, k)
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

    private fun showSolution(sol: Solution, k: Float) {
        removePath()
        val v = SolutionView(this, sol, k)
        val lp = WindowManager.LayoutParams(screenW, screenH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            // au-delà de 0.8, Android bloque les touchers vers l'app en dessous : il faut pouvoir jouer à travers
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        pathView = v
        // garde la bulle au-dessus de la solution
        bubble?.let { b -> val blp = b.layoutParams; wm.removeView(b); wm.addView(b, blp) }
        val tip = when (sol) {
            is Solution.Zip -> "Zip : trace le chemin en suivant les flèches."
            is Solution.Queens -> "Queens : place une reine sur chaque case marquée."
            is Solution.Sudoku -> "Mini Sudoku : les chiffres manquants sont en orange."
            is Solution.Tango -> "Tango : les symboles à placer sont affichés en transparence."
            is Solution.Patches -> "Patches : dessine chaque forme encadrée."
        }
        toast("$tip Touche la bulle pour masquer.")
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

    private class SolutionView(ctx: Context, private val sol: Solution, private val k: Float) : View(ctx) {
        private val loc = IntArray(2)
        private val orange = Color.rgb(242, 106, 33)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }

        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            when (sol) {
                is Solution.Zip -> drawZip(c, sol)
                is Solution.Queens -> sol.queens.forEachIndexed { r, col -> val g = sol.geo; drawQueen(c, sx(g.cx(col)), sy(g.cy(r)), (g.cw * k).toFloat()) }
                is Solution.Sudoku -> { val g = sol.geo; val cs = (g.cw * k).toFloat()
                    text.color = orange; text.textSize = cs * 0.55f
                    for (i in sol.values.indices) if (!sol.given[i]) {
                        val x = sx(g.cx(i % g.n)); val y = sy(g.cy(i / g.n))
                        c.drawText(sol.values[i].toString(), x, y - (text.descent() + text.ascent()) / 2, text)
                    } }
                is Solution.Tango -> { val g = sol.geo; val cs = (g.cw * k).toFloat()
                    for (i in sol.values.indices) if (!sol.given[i]) {
                        val x = sx(g.cx(i % g.n)); val y = sy(g.cy(i / g.n))
                        if (sol.values[i] == 1) drawSun(c, x, y, cs) else drawMoon(c, x, y, cs)
                    } }
                is Solution.Patches -> { val g = sol.geo
                    for (rc in sol.rects) {
                        val l = sx(g.x0 + rc.c0 * g.cw); val t = sy(g.y0 + rc.r0 * g.ch)
                        val r = sx(g.x0 + (rc.c0 + rc.w) * g.cw); val b = sy(g.y0 + (rc.r0 + rc.h) * g.ch)
                        val inset = (g.cw * k * 0.08).toFloat(); val rad = (g.cw * k * 0.18).toFloat()
                        val col = rc.color or 0xFF000000.toInt()
                        fill.color = col; fill.alpha = 70
                        c.drawRoundRect(l + inset, t + inset, r - inset, b - inset, rad, rad, fill)
                        stroke.color = col; stroke.strokeWidth = (g.cw * k * 0.07).toFloat()
                        c.drawRoundRect(l + inset, t + inset, r - inset, b - inset, rad, rad, stroke)
                    } }
            }
        }
        private fun sx(x: Double) = (x * k).toFloat()
        private fun sy(y: Double) = (y * k).toFloat()

        private fun drawZip(c: Canvas, z: Solution.Zip) {
            val n = z.n; val cs = (z.d * k).toFloat()
            fun pt(i: Int) = floatArrayOf(sx(z.x0 + (i % n + .5) * z.d), sy(z.y0 + (i / n + .5) * z.d))
            val p = Path()
            z.path.forEachIndexed { j, i -> val q = pt(i); if (j == 0) p.moveTo(q[0], q[1]) else p.lineTo(q[0], q[1]) }
            stroke.color = Color.argb(150, 242, 106, 33); stroke.strokeWidth = cs * 0.28f
            c.drawPath(p, stroke)
            stroke.color = Color.WHITE; stroke.strokeWidth = maxOf(3f, cs * 0.05f)
            val L = cs * 0.09f
            for (j in 1 until z.path.size) {
                val a = pt(z.path[j - 1]); val b = pt(z.path[j])
                val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
                val ang = atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble())
                val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
                c.drawPath(Path().apply {
                    moveTo(mx - L * ca + L * sa, my - L * sa - L * ca)
                    lineTo(mx + L * ca * 0.6f, my + L * sa * 0.6f)
                    lineTo(mx - L * ca - L * sa, my - L * sa + L * ca)
                }, stroke)
            }
        }
        private fun drawQueen(c: Canvas, x: Float, y: Float, cs: Float) {
            fill.color = orange; fill.alpha = 210
            c.drawCircle(x, y, cs * 0.32f, fill)
            fill.color = Color.WHITE; fill.alpha = 255
            val w = cs * 0.34f; val h = cs * 0.26f
            c.drawPath(Path().apply {
                moveTo(x - w / 2, y + h / 2); lineTo(x - w / 2, y - h / 2); lineTo(x - w / 4, y)
                lineTo(x, y - h / 2); lineTo(x + w / 4, y); lineTo(x + w / 2, y - h / 2); lineTo(x + w / 2, y + h / 2); close()
            }, fill)
        }
        private fun drawSun(c: Canvas, x: Float, y: Float, cs: Float) {
            fill.color = Color.rgb(255, 179, 31); fill.alpha = 170; c.drawCircle(x, y, cs * 0.22f, fill)
            stroke.color = Color.rgb(205, 105, 30); stroke.strokeWidth = cs * 0.04f; c.drawCircle(x, y, cs * 0.22f, stroke)
        }
        private fun drawMoon(c: Canvas, x: Float, y: Float, cs: Float) {
            val r = cs * 0.22f
            val outer = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
            val cut = Path().apply { addCircle(x - r * 0.45f, y - r * 0.35f, r * 0.85f, Path.Direction.CW) }
            outer.op(cut, Path.Op.DIFFERENCE)
            fill.color = Color.rgb(74, 134, 232); fill.alpha = 190; c.drawPath(outer, fill)
        }
    }
}
