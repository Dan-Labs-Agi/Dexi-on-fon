package com.danny.glasses

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * The Dani Glasses screen. Everything that runs (glasses link, Gemini, tools, alerts) lives in
 * DaniService so it keeps working in the background; this screen binds to it, shows its state
 * and log, and sends it commands.
 */
class SettingsActivity : Activity(), DaniService.Ui {

    private var service: DaniService? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("danny", MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var meter: ProgressBar
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var liveButton: Button
    private lateinit var listenButton: Button
    private lateinit var batteryButton: Button
    private lateinit var keyField: EditText
    private lateinit var backendUrlField: EditText
    private lateinit var backendTokenField: EditText
    private lateinit var cloudAppsView: android.widget.TextView
    private lateinit var modelField: EditText
    private lateinit var voiceField: EditText
    private lateinit var searchSwitch: android.widget.Switch
    private lateinit var sayText: EditText
    private val testButtons = mutableListOf<View>()

    // Echo test recording and live listening (fed by the service's mic tap)
    @Volatile private var recording = false
    private var recorded = ShortArray(0)
    private var recordedCount = 0
    @Volatile private var monitor: AudioTrack? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as DaniService.LocalBinder).service
            service = s
            s.ui = this@SettingsActivity
            s.micTap = { pcm, n -> onMicAudio(pcm, n) }
            logView.text = s.logText()
            onStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) { service = null; onStateChanged() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        askPermissions()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DaniService::class.java), connection, BIND_AUTO_CREATE)
        refreshBattery()
        searchSwitch.isChecked = prefs.getBoolean("search", false)
    }

    override fun onPause() {
        super.onPause()
        // Never lose typed settings: leaving this screen saves both forms.
        if (::keyField.isInitialized) saveGeminiSettings()
        if (::backendUrlField.isInitialized) saveCloudSettings()
    }

    override fun onStop() {
        stopListening()
        service?.let { it.ui = null; it.micTap = null }
        unbindService(connection)
        service = null
        super.onStop()
    }

    // ---------------- UI ----------------
    // Minimal neutral theme in the spirit of shadcn/ui (zinc): near-white background,
    // white cards with hairline borders, near-black primary actions, muted gray text.

    private val ICE = Color.parseColor("#FAFAFA")          // page background
    private val NAVY = Color.parseColor("#09090B")         // primary text (zinc-950)
    private val TANGERINE = Color.parseColor("#18181B")    // primary action (zinc-900)
    private val NAVY_MUTED = Color.parseColor("#71717A")   // muted text (zinc-500)
    private val CARD = Color.parseColor("#FFFFFF")
    private val BORDER = Color.parseColor("#E4E4E7")       // hairline (zinc-200)
    private val SUBTLE = Color.parseColor("#F4F4F5")       // pressed / chip fill (zinc-100)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun styleButton(b: Button, primary: Boolean) {
        b.background = android.graphics.drawable.StateListDrawable().apply {
            if (primary) {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(Color.parseColor("#3F3F46"), 10))
                addState(intArrayOf(), rounded(TANGERINE, 10))
            } else {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(SUBTLE, 10, BORDER))
                addState(intArrayOf(), rounded(Color.WHITE, 10, BORDER))
            }
        }
        b.setTextColor(if (primary) Color.parseColor("#FAFAFA") else NAVY)
        b.stateListAnimator = null
    }

    private fun button(parent: LinearLayout, label: String, test: Boolean = true, primary: Boolean = false,
                       onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 15f
        styleButton(b, primary)
        b.setOnClickListener { onClick() }
        parent.addView(b, LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply { topMargin = dp(8) })
        if (test) testButtons += b
        return b
    }

    private fun section(parent: LinearLayout, title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 12, BORDER)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(NAVY)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(head)
        parent.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        return card
    }

    private fun hint(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(NAVY_MUTED)
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun label(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(NAVY)
            setPadding(0, dp(12), 0, dp(2))
        })
    }

    private fun field(hintText: String, value: String?): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        textSize = 14f
        setTextColor(NAVY)
        setHintTextColor(NAVY_MUTED)
    }

    private fun buildUi() {
        window.statusBarColor = CARD
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ICE) }

        // Flat minimal header: title, status, one primary action, hairline divider
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARD)
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        header.addView(TextView(this).apply {
            text = "Dani"
            textSize = 24f
            setTextColor(NAVY)
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            text = "Not connected"
            textSize = 14f
            setTextColor(NAVY_MUTED)
            setPadding(0, dp(4), 0, 0)
        }
        header.addView(status)
        // Always show the build: support asks "which version?" first.
        header.addView(TextView(this).apply {
            text = runCatching {
                val pi = packageManager.getPackageInfo(packageName, 0)
                "v${pi.versionName} (build ${pi.longVersionCode})"
            }.getOrDefault("version unknown")
            textSize = 11f
            setTextColor(NAVY_MUTED)
        })
        connectButton = button(header, "Connect to glasses", test = false, primary = true) { toggleConnect() }
        (connectButton.layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
        page.addView(header)
        page.addView(View(this).apply { setBackgroundColor(BORDER) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(24))
        }
        page.addView(root)

        // ---- Gemini Live ----
        val gemini = section(root, "Talk to Dani")
        keyField = field("Paste your Gemini API key (aistudio.google.com)", KeyStoreSecrets.decrypt(prefs.getString("apiKey", "").orEmpty())).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        gemini.addView(keyField)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modelField = field("model", prefs.getString("model", "gemini-3.8-live"))
        voiceField = field("voice", prefs.getString("voice", "Puck"))
        row.addView(modelField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))
        row.addView(voiceField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        gemini.addView(row)
        searchSwitch = android.widget.Switch(this).apply {
            text = "Web search (weather, news). May need a paid Gemini plan."
            textSize = 14f
            setTextColor(NAVY)
            isChecked = prefs.getBoolean("search", false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("search", on).apply() }
        }
        gemini.addView(searchSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        liveButton = button(gemini, "Start talking to Gemini", primary = true) {
            val s = service ?: return@button
            if (s.liveState == DaniService.LiveState.OFF) {
                saveGeminiSettings()
                s.startLive(userStarted = true)
            } else s.stopLive()
        }
        hint(gemini, "Hands-free: with the glasses connected, press the glasses' button to start talking, " +
            "press again to interrupt a reply, and hold it for 2 seconds to end the session. " +
            "Dani keeps working with the screen off.")

        // ---- DAN cloud ----
        val cloud = section(root, "DAN cloud (Gmail & Calendar)")
        backendUrlField = field("Backend address (https://...)", prefs.getString("backendUrl", BackendTools.DEFAULT_BACKEND_URL))
        cloud.addView(backendUrlField)
        backendTokenField = field("Device token (from your assistant vault)", KeyStoreSecrets.decrypt(prefs.getString("backendToken", "").orEmpty())).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        cloud.addView(backendTokenField)
        cloudAppsView = android.widget.TextView(this).apply {
            text = "Cloud apps: enter the backend address and token, then Save."
            textSize = 14f
            setTextColor(NAVY)
            setPadding(0, dp(6), 0, 0)
        }
        cloud.addView(cloudAppsView)
        button(cloud, "Save cloud settings & refresh apps", primary = true) {
            saveCloudSettings()
            refreshCloudApps()
        }
        button(cloud, "Connect Gmail", test = false) { connectCloudApp("gmail") }
        button(cloud, "Connect Google Calendar", test = false) { connectCloudApp("googlecalendar") }
        hint(cloud, "Gmail and Calendar connect through the DAN backend: tapping a button opens a secure " +
            "sign-in page from Composio. The app never sees your Google password. The device token works " +
            "like a password for this phone only; it is stored encrypted.")

        // ---- Background ----
        val bgSection = section(root, "Background")
        batteryButton = button(bgSection, "Keep Dani running in the background", test = false, primary = true) {
            requestBatteryExemption()
        }
        hint(bgSection,"While connected, Dani runs in the background and shows a notification with a Stop button. " +
            "OnePlus phones may still close background apps unless the battery setting is \"Unrestricted\".")

        // ---- Tests ----
        val tests = section(root, "Glasses tests")
        label(tests, "Glasses mic level")
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(NAVY)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#33172C45"))
        }
        tests.addView(meter, LinearLayout.LayoutParams(MATCH_PARENT, dp(20)))
        button(tests, "Beep on the glasses") { service?.link?.command("tone") }
        button(tests, "Echo test: speak 4 s, hear it back") { echoTest() }
        listenButton = button(tests, "Listen live (glasses mic → phone speaker)") {
            if (monitor == null) startListening() else stopListening()
        }
        sayText = field("Text to say", "Hello! I am Dani, your glasses assistant.").apply { textSize = 15f }
        tests.addView(sayText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        button(tests, "Say it on the glasses") {
            if (service?.speakOnGlasses(sayText.text.toString()) == true) log("Saying it on the glasses")
            else log("Phone voice not ready yet")
        }
        button(tests, "Stop sound") { service?.link?.stop() }
        label(tests, "Glasses volume")
        val volume = SeekBar(this).apply {
            max = 100
            progress = 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { service?.link?.command("volume ${s.progress}") }
            })
        }
        tests.addView(volume)
        testButtons += volume

        // ---- Log ----
        val logCard = section(root, "Log")
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#D4D4D8"))
            setTextIsSelectable(true)
            gravity = Gravity.TOP
            minHeight = dp(140)
            background = rounded(Color.parseColor("#09090B"), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(0f, 1.15f)
        }
        logCard.addView(logView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        button(logCard, "Copy log", test = false) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Dani log",
                service?.logText() ?: logView.text.toString()))
            android.widget.Toast.makeText(this, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ICE)
            isFillViewport = true
            addView(page)
        })
        onStateChanged()
    }

    private fun saveGeminiSettings() {
        prefs.edit()
            .putString("apiKey", KeyStoreSecrets.encrypt(keyField.text.toString().trim()))
            .putString("model", modelField.text.toString().trim().ifEmpty { "gemini-3.8-live" })
            .putString("voice", voiceField.text.toString().trim().ifEmpty { "Puck" })
            .apply()
    }

    private fun saveCloudSettings() {
        prefs.edit()
            .putString("backendUrl", backendUrlField.text.toString().trim())
            .putString("backendToken", KeyStoreSecrets.encrypt(backendTokenField.text.toString().trim()))
            .apply()
    }

    private fun cloudRequest(path: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val base = prefs.getString("backendUrl", BackendTools.DEFAULT_BACKEND_URL).orEmpty().trim().trimEnd('/')
        val token = KeyStoreSecrets.decrypt(prefs.getString("backendToken", "").orEmpty())
        if (!base.startsWith("https://") || token.length < 32) return 0 to "not configured"
        val conn = (java.net.URL(base + path).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("content-type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        return code to text
    }

    private fun refreshCloudApps() {
        cloudAppsView.text = "Cloud apps: checking..."
        Thread {
            val (code, text) = cloudRequest("/v1/apps")
            val label = if (code == 200) try {
                val apps = org.json.JSONObject(text).getJSONArray("apps")
                buildString {
                    append("Cloud apps: ")
                    for (i in 0 until apps.length()) {
                        val a = apps.getJSONObject(i)
                        // Optional apps that are not connected (e.g. github) are not problems - skip them.
                        if (a.optBoolean("optional") && !a.optBoolean("connected")) continue
                        append(a.getString("toolkit")).append(if (a.optBoolean("connected")) " ✓  " else " ✗  ")
                    }
                }.trimEnd()
            } catch (e: Exception) { "Cloud apps: unreadable response" }
            else "Cloud apps: " + when (code) {
                401 -> "device token rejected - paste the current token, then Save"
                0 -> "enter the backend address and token, then Save"
                else -> "unreachable ($code)"
            }
            runOnUiThread { cloudAppsView.text = label }
        }.start()
    }

    private fun connectCloudApp(toolkit: String) {
        saveCloudSettings()
        Thread {
            val (code, text) = cloudRequest("/v1/apps/connect", "POST", "{\"toolkit\":\"$toolkit\"}")
            val url = if (code == 200) runCatching { org.json.JSONObject(text).getString("redirect_url") }.getOrNull() else null
            runOnUiThread {
                if (url != null && url.startsWith("https://")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    cloudAppsView.text = "Cloud apps: finish the sign-in in the browser, then come back and tap Save & refresh."
                } else {
                    cloudAppsView.text = "Cloud apps: " + when (code) {
                        401 -> "device token rejected - paste the current token, then Save."
                        0 -> "enter the backend address and token, then tap Save first."
                        else -> "could not start the $toolkit connection ($code)."
                    }
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (::cloudAppsView.isInitialized && !prefs.getString("backendUrl", BackendTools.DEFAULT_BACKEND_URL).isNullOrBlank()) refreshCloudApps()
    }

    /** Local log line (the service keeps the shared log). */
    private fun log(text: String) {
        service?.log(text) ?: run { logView.text = "$text\n${logView.text}" }
    }

    // ---------------- DaniService.Ui ----------------

    override fun onLog(line: String) {
        logView.text = "$line\n${logView.text}".take(8000)
    }

    override fun onStateChanged() {
        val s = service
        val connected = s?.glassesConnected == true
        val running = s?.running == true
        testButtons.forEach { it.isEnabled = connected; it.alpha = if (connected) 1f else 0.4f }
        connectButton.text = when {
            connected -> "Disconnect glasses"
            running -> "Stop looking for glasses"
            else -> "Connect to glasses"
        }
        styleButton(connectButton, primary = !running)
        status.text = when {
            s == null -> "Starting..."
            connected && s.liveState == DaniService.LiveState.READY -> "Dani is listening. Talk through the glasses."
            connected && s.liveState == DaniService.LiveState.CONNECTING -> "Connecting to Gemini..."
            connected -> "Connected to ${s.glassesName ?: "glasses"}"
            running -> "Looking for your glasses..."
            else -> "Not connected"
        }
        liveButton.text = when (s?.liveState) {
            DaniService.LiveState.READY -> "Stop Gemini"
            DaniService.LiveState.CONNECTING -> "Connecting to Gemini..."
            else -> "Start talking to Gemini"
        }
        if (::searchSwitch.isInitialized) searchSwitch.isChecked = prefs.getBoolean("search", false)
        if (!connected) stopListening()
    }

    override fun onLevel(level: Int) {
        val pct = if (level <= 100) 0 else ((Math.log10(level / 100.0) / 2.3) * 100).toInt().coerceIn(0, 100)
        meter.progress = pct
    }

    // ---------------- connect / background ----------------

    private fun toggleConnect() {
        val s = service
        if (s != null && s.running) {
            startService(Intent(this, DaniService::class.java).setAction(DaniService.ACTION_STOP))
            return
        }
        if (!hasPermissions()) { askPermissions(); return }
        startForegroundService(Intent(this, DaniService::class.java).setAction(DaniService.ACTION_CONNECT))
    }

    private fun ignoringBattery() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun refreshBattery() {
        val ok = ignoringBattery()
        batteryButton.text = if (ok) "Background allowed ✓" else "Keep Dani running in the background"
        styleButton(batteryButton, primary = !ok)
    }

    private fun requestBatteryExemption() {
        if (ignoringBattery()) {
            // Already allowed: open the app's settings in case the user wants to check OnePlus's own options.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        @Suppress("BatteryLife")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
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

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        log(if (hasPermissions()) "Bluetooth permission granted. Tap Connect." else "Bluetooth permission is needed to find the glasses.")
    }

    // ---------------- tests ----------------

    private fun echoTest() {
        val link = service?.link ?: return
        link.command("tone")
        log("Speak after the beep (4 s)...")
        main.postDelayed({
            recorded = ShortArray(GlassesLink.RATE * 4)
            recordedCount = 0
            recording = true
            status.text = "Recording... speak now"
        }, 800)
        main.postDelayed({
            recording = false
            val pcm = recorded.copyOf(recordedCount)
            log("Recorded %.1f s, playing it back on the glasses".format(pcm.size / 16000.0))
            status.text = "Playing back on the glasses"
            link.play(pcm) { onStateChanged(); log("Echo test done") }
        }, 4800)
    }

    private fun onMicAudio(pcm: ShortArray, count: Int) {
        if (recording) {
            val n = minOf(count, recorded.size - recordedCount)
            System.arraycopy(pcm, 0, recorded, recordedCount, n)
            recordedCount += n
        }
        monitor?.write(pcm, 0, count, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun startListening() {
        val size = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        monitor = track
        listenButton.text = "Stop listening"
        log("Listening: the glasses mic plays on this phone (use headphones to avoid feedback)")
    }

    private fun stopListening() {
        val track = monitor ?: return
        monitor = null
        track.stop()
        track.release()
        if (::listenButton.isInitialized) listenButton.text = "Listen live (glasses mic → phone speaker)"
    }

}
