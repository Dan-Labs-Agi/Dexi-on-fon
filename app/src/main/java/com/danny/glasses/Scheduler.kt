package com.danny.glasses

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import org.json.JSONArray
import org.json.JSONObject

/** A timer or reminder that goes off at a set time. */
data class Alert(val id: Int, val kind: String, val label: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("label", label).put("at", at)

    companion object {
        fun from(j: JSONObject) = Alert(j.getInt("id"), j.getString("kind"), j.getString("label"), j.getLong("at"))
    }
}

/**
 * Timers and reminders, kept in app storage and woken by Android's AlarmManager, so they go
 * off on time even when the phone is idle. When one fires, the phone shows a notification and,
 * if the app is open, the glasses play a chime and speak it.
 */
class Scheduler(private val context: Context) {

    companion object {
        const val CHANNEL = "dani_alerts"

        /** Set by the open app to play alerts on the glasses. */
        @Volatile var onFire: ((Alert) -> Unit)? = null

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Timers and reminders",
                    NotificationManager.IMPORTANCE_HIGH).apply { description = "Dani's timers and reminders" })
            }
        }
    }

    private val prefs = context.getSharedPreferences("dani_alerts", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    @Synchronized fun all(): List<Alert> {
        val arr = JSONArray(prefs.getString("alerts", "[]"))
        return (0 until arr.length()).map { Alert.from(arr.getJSONObject(it)) }.sortedBy { it.at }
    }

    @Synchronized private fun save(list: List<Alert>) {
        prefs.edit().putString("alerts", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    private fun pending(id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id, Intent(context, AlertReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Synchronized fun add(kind: String, label: String, at: Long): Alert {
        val id = prefs.getInt("nextId", 1)
        prefs.edit().putInt("nextId", id + 1).apply()
        val alert = Alert(id, kind, label, at)
        save(all() + alert)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(id))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(id))   // may be a little late
        }
        return alert
    }

    /** Cancels by id, or by label words ("pasta"), or everything of a kind with "all". Returns what was cancelled. */
    @Synchronized fun cancel(what: String, kind: String? = null): List<Alert> {
        val q = what.trim().lowercase()
        val candidates = all().filter { kind == null || it.kind == kind }
        val hit = when {
            q == "all" -> candidates
            q.toIntOrNull() != null -> candidates.filter { it.id == q.toInt() }
            q.isEmpty() -> candidates.take(1)
            else -> candidates.filter { it.label.lowercase().contains(q) }
        }
        hit.forEach { alarms.cancel(pending(it.id)) }
        save(all().filter { a -> hit.none { it.id == a.id } })
        return hit
    }

    /** Called when an alert fires: removes it and returns it. */
    @Synchronized fun take(id: Int): Alert? {
        val a = all().firstOrNull { it.id == id } ?: return null
        save(all().filter { it.id != id })
        return a
    }

    /** Re-arms everything (after a reboot or app update); fires anything already overdue. */
    fun rearm() {
        val now = System.currentTimeMillis()
        for (a in all()) {
            if (a.at <= now) context.sendBroadcast(Intent(context, AlertReceiver::class.java).putExtra("id", a.id))
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.at, pending(a.id))
        }
    }
}

/** After the phone restarts, put waiting timers and reminders back on Android's alarm clock. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Scheduler(context).rearm()
    }
}

/** Wakes up when a timer or reminder is due (only this app's own alarms can trigger it). */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alert = Scheduler(context).take(intent.getIntExtra("id", -1)) ?: return
        val text = spokenText(alert)
        Scheduler.ensureChannel(context)
        val tap = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, Scheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (alert.kind == "timer") "Timer done" else "Reminder")
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(alert.id, notification) }
        Scheduler.onFire?.invoke(alert)
    }

    companion object {
        fun spokenText(a: Alert) =
            if (a.kind == "timer") "Your ${a.label.ifBlank { "" }} timer is done.".replace("  ", " ")
            else "Reminder: ${a.label}"
    }
}
