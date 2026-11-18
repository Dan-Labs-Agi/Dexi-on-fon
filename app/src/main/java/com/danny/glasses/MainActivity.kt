package com.danny.glasses

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Voice-first home: one monochrome orb. Tap the orb to talk to Dani; with the glasses
 * connected the glasses' button does the same hands-free. Keys, cloud apps, tests and
 * the log live behind the gear (SettingsActivity).
 */
class MainActivity : Activity(), DaniService.Ui {

    private var service: DaniService? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("danny", MODE_PRIVATE) }

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var glassesRow: TextView
    private lateinit var agentInput: android.widget.EditText
    private lateinit var agentReply: TextView
    private val backendTools by lazy { BackendTools(this, { }) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as DaniService.LocalBinder).service
            service = s
            s.ui = this@MainActivity
            onStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) { service = null; onStateChanged() }
    }

    // The orb's speaking/listening states change without a service callback, so poll lightly.
    private val stateTicker = object : Runnable {
        override fun run() {
            updateOrbState()
            main.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        askPermissions()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DaniService::class.java), connection, BIND_AUTO_CREATE)
        main.post(stateTicker)
        onStateChanged()   // pick up anything changed in Settings while we were away
    }

    override fun onStop() {
        main.removeCallbacks(stateTicker)
        service?.ui = null
        unbindService(connection)
        service = null
        super.onStop()
    }

    // ---------------- UI ----------------
    // Strict monochrome: black page, white/gray orb and text. No color accents anywhere.

    private val WHITE = Color.parseColor("#FAFAFA")
    private val GRAY = Color.parseColor("#A3A3A3")
    private val DIM = Color.parseColor("#737373")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Top bar: wordmark left, settings gear right
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(14), dp(6))
        }
        top.addView(TextView(this).apply {
            text = "DANI"
            textSize = 13f
            setTextColor(GRAY)
            letterSpacing = 0.3f
        }, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(top, FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP))

        // Center column: orb, status, hint
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        orb = OrbView(this)
        orb.setOnClickListener { toggleLive() }
        center.addView(orb, LinearLayout.LayoutParams(dp(280), dp(280)))
        status = TextView(this).apply {
            textSize = 17f
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(10), dp(24), 0)
        }
        center.addView(status)
        hint = TextView(this).apply {
            textSize = 12.5f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(8), dp(40), 0)
            setLineSpacing(0f, 1.2f)
        }
        center.addView(hint)
        root.addView(center, FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))

        // Bottom column: cloud-agent chat, then glasses connection
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        agentReply = TextView(this).apply {
            textSize = 13.5f
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(6))
            visibility = View.GONE
        }
        bottom.addView(agentReply)
        val chatRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        agentInput = android.widget.EditText(this).apply {
            this.hint = "Text Dani (cloud agent)"
            textSize = 14f
            setHintTextColor(DIM)
            setTextColor(WHITE)
            setSingleLine(true)
        }
        chatRow.addView(agentInput, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chatRow.addView(TextView(this).apply {
            text = "Send"
            textSize = 15f
            setTextColor(WHITE)
            setPadding(dp(16), dp(10), dp(8), dp(10))
            setOnClickListener { sendAgent() }
        })
        bottom.addView(chatRow)
        glassesRow = TextView(this).apply {
            textSize = 13f
            setTextColor(GRAY)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(22))
            setOnClickListener { toggleConnect() }
        }
        bottom.addView(glassesRow)
        root.addView(bottom, FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        setContentView(root)
        onStateChanged()
    }

    private fun sendAgent() {
        val text = agentInput.text.toString().trim()
        if (text.isEmpty()) return
        agentInput.text.clear()
        agentReply.visibility = View.VISIBLE
        agentReply.text = "…"
        Thread {
            val r = backendTools.agentChat(text)
            val out = r.optString("result").ifBlank { r.optString("error").ifBlank { "(no reply)" } }
            main.post { agentReply.text = out }
        }.start()
    }

    // ---------------- state ----------------

    private val hasKey: Boolean
        get() = KeyStoreSecrets.decrypt(prefs.getString("apiKey", "").orEmpty()).isNotBlank()

    private fun updateOrbState() {
        val s = service
        orb.state = when {
            s == null -> OrbView.State.IDLE
            s.liveState == DaniService.LiveState.READY && s.speaking -> OrbView.State.SPEAKING
            s.liveState == DaniService.LiveState.READY -> OrbView.State.LISTENING
            s.liveState == DaniService.LiveState.CONNECTING -> OrbView.State.CONNECTING
            s.running && !s.glassesConnected -> OrbView.State.CONNECTING
            else -> OrbView.State.IDLE
        }
    }

    override fun onStateChanged() {
        updateOrbState()
        val s = service
        status.text = when {
            s == null -> "Starting..."
            s.liveState == DaniService.LiveState.READY && s.speaking -> "Dani is speaking"
            s.liveState == DaniService.LiveState.READY -> "Listening"
            s.liveState == DaniService.LiveState.CONNECTING -> "Connecting to Gemini..."
            !hasKey -> "Finish setup to start"
            s.glassesConnected -> "Connected to ${s.glassesName ?: "glasses"}"
            s.running -> "Looking for your glasses..."
            else -> "Tap the orb to talk"
        }
        hint.text = when {
            s?.liveState == DaniService.LiveState.READY ->
                "Tap the orb to stop. On the glasses: press to interrupt, hold 2 s to end."
            !hasKey ->
                "Tap the gear, paste your Gemini API key, then come back and tap the orb. " +
                "For Gmail and Calendar, also paste the device token there and tap Save."
            else ->
                "Tap the orb to start or stop. With the glasses on, press the button to talk, hold 2 s to end."
        }
        glassesRow.text = when {
            s?.glassesConnected == true -> "Glasses connected  ·  tap to disconnect"
            s?.running == true -> "Looking for your glasses...  ·  tap to stop"
            else -> "Connect glasses"
        }
    }

    override fun onLog(line: String) { /* the log lives in Settings */ }

    override fun onLevel(level: Int) {
        orb.level = if (level <= 100) 0 else ((Math.log10(level / 100.0) / 2.3) * 100).toInt().coerceIn(0, 100)
    }

    // ---------------- actions ----------------

    private fun toggleLive() {
        val s = service ?: return
        if (s.liveState == DaniService.LiveState.OFF) {
            if (!hasPermissions()) { askPermissions(); return }
            if (KeyStoreSecrets.decrypt(prefs.getString("apiKey", "").orEmpty()).isBlank()) {
                Toast.makeText(this, "Add your Gemini API key in settings first", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return
            }
            s.startLive(userStarted = true)
        } else s.stopLive()
        onStateChanged()
    }

    private fun toggleConnect() {
        val s = service
        if (s != null && s.running) {
            startService(Intent(this, DaniService::class.java).setAction(DaniService.ACTION_STOP))
            return
        }
        if (!hasPermissions()) { askPermissions(); return }
        startForegroundService(Intent(this, DaniService::class.java).setAction(DaniService.ACTION_CONNECT))
    }

    // ---------------- permissions ----------------

    private val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO)

    private fun hasPermissions() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun askPermissions() {
        val want = perms.toList() +
            if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
