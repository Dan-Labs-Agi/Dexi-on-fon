package com.danny.glasses

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Things Dani can do on the phone itself: time, timers, reminders, alarms, notes. */
class LocalTools(private val context: Context, private val log: (String) -> Unit) {

    private val scheduler = Scheduler(context)
    private val notes = context.getSharedPreferences("dani_notes", Context.MODE_PRIVATE)
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val main = Handler(Looper.getMainLooper())

    val names = setOf("get_current_time", "set_timer", "set_reminder", "list_timers_and_reminders",
        "cancel_timer_or_reminder", "set_alarm", "save_note", "get_notes", "delete_notes")

    private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

    private fun function(name: String, description: String, props: JSONObject? = null, required: List<String> = emptyList()): JSONObject {
        val f = JSONObject().put("name", name).put("description", description)
        if (props != null) {
            val p = JSONObject().put("type", "OBJECT").put("properties", props)
            if (required.isNotEmpty()) p.put("required", JSONArray(required))
            f.put("parameters", p)
        }
        return f
    }

    fun declarations(): List<JSONObject> = listOf(
        function("get_current_time",
            "Current local date, time, weekday and time zone. Use it to work out times like 'tomorrow at 6'."),
        function("set_timer",
            "Start a countdown timer. When it ends the glasses chime and say it's done.",
            JSONObject()
                .put("seconds", prop("INTEGER", "Timer length in seconds (e.g. 600 for 10 minutes)."))
                .put("label", prop("STRING", "Optional short name, e.g. 'pasta'.")),
            listOf("seconds")),
        function("set_reminder",
            "Remind the user of something at a time: the glasses chime and speak the reminder, and the phone shows a notification. " +
            "Give either minutes_from_now or time.",
            JSONObject()
                .put("text", prop("STRING", "What to remind, e.g. 'call Mom'."))
                .put("minutes_from_now", prop("INTEGER", "Minutes from now, for 'in 20 minutes'."))
                .put("time", prop("STRING", "Local time, ISO like 2026-11-17T18:00, or just 18:00 for the next 6 pm.")),
            listOf("text")),
        function("list_timers_and_reminders", "List the timers and reminders that are still waiting, with time left."),
        function("cancel_timer_or_reminder",
            "Cancel timers or reminders by name or id, or 'all'.",
            JSONObject()
                .put("which", prop("STRING", "Label words like 'pasta', an id number, or 'all'."))
                .put("kind", prop("STRING", "Optional: 'timer' or 'reminder'.")),
            listOf("which")),
        function("set_alarm",
            "Set a wake-up alarm in the phone's Clock app (it rings on the phone even if this app is closed).",
            JSONObject()
                .put("time", prop("STRING", "24-hour local time, e.g. 07:00."))
                .put("label", prop("STRING", "Optional label.")),
            listOf("time")),
        function("save_note",
            "Remember something for the user, e.g. 'parked on level 3' or 'Wi-Fi password is on the fridge'.",
            JSONObject().put("text", prop("STRING", "The note.")), listOf("text")),
        function("get_notes", "Read back the user's saved notes, newest first."),
        function("delete_notes",
            "Delete saved notes containing some words, or 'all'.",
            JSONObject().put("which", prop("STRING", "Words in the note, or 'all'.")), listOf("which")),
    )

    fun run(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "get_current_time" -> currentTime()
            "set_timer" -> setTimer(args.getLong("seconds"), args.optString("label", ""))
            "set_reminder" -> setReminder(args)
            "list_timers_and_reminders" -> listAlerts()
            "cancel_timer_or_reminder" -> cancel(args.getString("which"), args.optString("kind", "").ifBlank { null })
            "set_alarm" -> setAlarm(args.getString("time"), args.optString("label", ""))
            "save_note" -> saveNote(args.getString("text"))
            "get_notes" -> getNotes()
            "delete_notes" -> deleteNotes(args.getString("which"))
            else -> JSONObject().put("error", "unknown function $name")
        }
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: e.javaClass.simpleName)
    }

    private fun currentTime(): JSONObject {
        val now = ZonedDateTime.now(zone)
        return JSONObject()
            .put("now", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
            .put("weekday", now.dayOfWeek.toString().lowercase().replaceFirstChar { it.uppercase() })
            .put("time_zone", zone.id)
    }

    private fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("EEE HH:mm"))

    private fun left(at: Long): String {
        val s = ((at - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return when {
            s >= 3600 -> "%d h %d min".format(s / 3600, s % 3600 / 60)
            s >= 60 -> "%d min %d s".format(s / 60, s % 60)
            else -> "$s s"
        }
    }

    private fun setTimer(seconds: Long, label: String): JSONObject {
        require(seconds in 1..86_400) { "Timer must be between 1 second and 24 hours." }
        val a = scheduler.add("timer", label, System.currentTimeMillis() + seconds * 1000)
        log("Timer set: ${label.ifBlank { "timer" }} for ${left(a.at)}")
        return JSONObject().put("ok", true).put("id", a.id).put("ends_at", clock(a.at)).put("label", label)
    }

    private fun parseWhen(text: String): ZonedDateTime {
        val t = text.trim()
        runCatching { return LocalDateTime.parse(t).atZone(zone) }
        runCatching { return java.time.OffsetDateTime.parse(t).atZoneSameInstant(zone) }
        runCatching {
            val time = LocalTime.parse(t)                    // "18:00": next time it's 6 pm
            var at = LocalDate.now(zone).atTime(time).atZone(zone)
            if (!at.isAfter(ZonedDateTime.now(zone))) at = at.plusDays(1)
            return at
        }
        throw IllegalArgumentException("Couldn't understand the time '$text'. Use 2026-11-17T18:00 or 18:00.")
    }

    private fun setReminder(args: JSONObject): JSONObject {
        val text = args.getString("text")
        val at = when {
            args.has("minutes_from_now") -> System.currentTimeMillis() + args.getLong("minutes_from_now") * 60_000
            args.optString("time").isNotBlank() -> parseWhen(args.getString("time")).toInstant().toEpochMilli()
            else -> throw IllegalArgumentException("Say when: minutes_from_now or time.")
        }
        require(at > System.currentTimeMillis()) { "That time has already passed." }
        val a = scheduler.add("reminder", text, at)
        log("Reminder set: \"$text\" at ${clock(at)}")
        return JSONObject().put("ok", true).put("id", a.id).put("at", clock(at)).put("in", left(at))
    }

    private fun listAlerts(): JSONObject {
        val arr = JSONArray()
        for (a in scheduler.all()) {
            arr.put(JSONObject().put("id", a.id).put("kind", a.kind).put("label", a.label)
                .put("at", clock(a.at)).put("time_left", left(a.at)))
        }
        return JSONObject().put("waiting", arr)
    }

    private fun cancel(which: String, kind: String?): JSONObject {
        val gone = scheduler.cancel(which, kind)
        if (gone.isNotEmpty()) log("Cancelled: " + gone.joinToString { "${it.kind} ${it.label}".trim() })
        return JSONObject().put("cancelled", JSONArray(gone.map { "${it.kind} '${it.label}' at ${clock(it.at)}" }))
    }

    private fun setAlarm(time: String, label: String): JSONObject {
        val t = LocalTime.parse(time.trim())
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, t.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Dani alarm" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post { runCatching { context.startActivity(intent) }.onFailure { log("Alarm: ${it.message}") } }
        log("Alarm set in the Clock app for %02d:%02d".format(t.hour, t.minute))
        return JSONObject().put("ok", true).put("time", "%02d:%02d".format(t.hour, t.minute))
            .put("note", "Set in the phone's Clock app.")
    }

    @Synchronized private fun noteList(): MutableList<JSONObject> {
        val arr = JSONArray(notes.getString("notes", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.toMutableList()
    }

    @Synchronized private fun saveNote(text: String): JSONObject {
        val list = noteList()
        list.add(0, JSONObject().put("text", text).put("saved", clock(System.currentTimeMillis())))
        notes.edit().putString("notes", JSONArray(list.take(100)).toString()).apply()
        log("Note saved: $text")
        return JSONObject().put("ok", true)
    }

    private fun getNotes(): JSONObject = JSONObject().put("notes", JSONArray(noteList().take(20)))

    @Synchronized private fun deleteNotes(which: String): JSONObject {
        val q = which.trim().lowercase()
        val list = noteList()
        val keep = if (q == "all") emptyList() else list.filter { !it.optString("text").lowercase().contains(q) }
        notes.edit().putString("notes", JSONArray(keep).toString()).apply()
        val removed = list.size - keep.size
        log("Notes deleted: $removed")
        return JSONObject().put("deleted", removed)
    }
}
