package com.natrasta.inzipsolver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val reqCapture = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            setBackgroundColor(Color.parseColor("#F3F1EE"))
        }
        fun text(s: String, size: Float, bold: Boolean = false, color: String = "#1D1A24") = TextView(this).apply {
            text = s; textSize = size; setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (14 * dp).toInt())
        }
        fun button(label: String, bg: String, fg: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 17f; setTextColor(Color.parseColor(fg))
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.parseColor(bg)) }
            setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (12 * dp).toInt() }
        }

        root.addView(text("InZip Solver", 30f, true))
        root.addView(text(
            "Une bulle flottante s'affiche par-dessus LinkedIn. Ouvre le Zip, touche la bulle : " +
            "le chemin à suivre apparaît en transparence sur la grille. C'est toi qui le traces au doigt.", 16f, color = "#4A4552"))
        root.addView(text(
            "• Touche la bulle : afficher ou masquer le chemin\n• Glisse la bulle : la déplacer\n• Appui long sur la bulle : la fermer",
            15f, color = "#4A4552"))
        status = text("", 14f, color = "#6F6A78")
        root.addView(status)
        root.addView(button("Lancer la bulle et ouvrir LinkedIn", "#F26A21", "#FFFFFF") { start() })
        root.addView(button("Arrêter la bulle", "#E6E1EA", "#1D1A24") {
            stopService(Intent(this, OverlayService::class.java)); Toast.makeText(this, "Bulle arrêtée", Toast.LENGTH_SHORT).show()
        })
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.parseColor("#F3F1EE")) })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this))
            "Autorisation d'affichage par-dessus les apps : accordée."
        else
            "Étape 1 : autorise l'affichage par-dessus les autres apps (le bouton ci-dessous t'y emmène)."
        status.gravity = Gravity.START
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Active « InZip Solver » dans la liste, puis reviens ici.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Sans la capture d'écran, la bulle ne peut pas lire la grille.", Toast.LENGTH_LONG).show()
            return
        }
        val svc = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_DATA, data)
        startForegroundService(svc)
        val linkedin = packageManager.getLaunchIntentForPackage("com.linkedin.android")
        if (linkedin != null) startActivity(linkedin) else moveTaskToBack(true)
    }
}
