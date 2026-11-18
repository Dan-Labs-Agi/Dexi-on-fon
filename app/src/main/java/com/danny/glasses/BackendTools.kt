package com.danny.glasses

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * App actions executed through the DAN backend on ComputeSphere (dan-glasses-api-v0).
 * The APK holds only a revocable device token, entered once in Settings.
 * The Composio key and all app OAuth live behind the backend - never in the APK.
 * Anything that sends/creates/changes needs the user's spoken "yes" first
 * (user_confirmed=true), enforced again by the backend.
 */
class BackendTools(context: Context, private val log: (String) -> Unit, private val openUrl: (String) -> Unit = {}) {

    private val prefs = context.getSharedPreferences("danny", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_BACKEND_URL = "https://dani-sandbox-4.tailc62d47.ts.net:10000"
    }
    private val baseUrl: String get() = prefs.getString("backendUrl", DEFAULT_BACKEND_URL).orEmpty().trim().trimEnd('/')
    private val deviceToken: String get() = KeyStoreSecrets.decrypt(prefs.getString("backendToken", "").orEmpty()).trim()
    val connected: Boolean get() = baseUrl.startsWith("https://") && deviceToken.length >= 32

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    // The cloud agent can think for up to a minute on a hard question.
    private val httpAgent = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .build()

    /**
     * Text chat with the shared cloud agent - the same conversation the Telegram bot uses,
     * so context carries over between the phone, the bot and (next) the glasses themselves.
     */
    fun agentChat(message: String): JSONObject = try {
        if (!connected) throw NotConfigured()
        val body = JSONObject().put("message", message).put("conversation_id", "som")
        val request = Request.Builder()
            .url("$baseUrl/v1/agent")
            .header("authorization", "Bearer $deviceToken")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpAgent.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            when {
                response.code == 401 -> JSONObject().put("error", "The device token was rejected. Check it in the app's Settings.")
                !response.isSuccessful -> JSONObject().put("error",
                    json?.optString("error")?.ifBlank { null } ?: "The cloud agent could not answer right now.")
                else -> JSONObject().put("result", json?.optString("reply")?.ifBlank { null } ?: "(no reply)")
            }
        }
    } catch (e: NotConfigured) {
        JSONObject().put("error", e.message)
    } catch (e: Exception) {
        log("agentChat failed: ${e.javaClass.simpleName}")
        JSONObject().put("error", "The DAN cloud is unreachable right now. Check the phone's internet.")
    }

    class NotConfigured : Exception(
        "The DAN cloud isn't set up. Ask the user to enter the backend address and device token in the app's Settings."
    )

    val names = setOf("list_emails", "search_emails", "read_email", "list_events", "create_event", "send_email",
        "search_tools", "connect_app", "execute_tool")

    // ---------------- declarations sent to Gemini ----------------

    private fun prop(type: String, description: String) =
        JSONObject().put("type", type).put("description", description)

    private fun fn(name: String, description: String, props: JSONObject? = null, required: List<String> = emptyList()): JSONObject {
        val f = JSONObject().put("name", name).put("description", description)
        if (props != null && props.length() > 0) {
            val params = JSONObject().put("type", "OBJECT").put("properties", props)
            if (required.isNotEmpty()) params.put("required", JSONArray(required))
            f.put("parameters", params)
        }
        return f
    }

    fun declarations(): JSONArray = JSONArray()
        .put(fn("list_emails",
            "List the user's emails, newest first: sender, subject, date, a short snippet.",
            JSONObject().put("max_results", prop("INTEGER", "How many emails (1-10). Default 5."))))
        .put(fn("search_emails",
            "Search the user's Gmail, e.g. from a person or about a subject.",
            JSONObject()
                .put("query", prop("STRING", "Gmail search, e.g. 'from:rahul', 'subject:invoice newer_than:7d'."))
                .put("max_results", prop("INTEGER", "How many emails (1-10). Default 5.")),
            listOf("query")))
        .put(fn("read_email",
            "Read the full text of one email by its id from list_emails or search_emails.",
            JSONObject().put("id", prop("STRING", "Email id.")), listOf("id")))
        .put(fn("list_events",
            "List events on the user's Google Calendar, soonest first.",
            JSONObject().put("days", prop("INTEGER", "How many days ahead to look (1-14). Default 7."))))
        .put(fn("create_event",
            "Create a Google Calendar event. FIRST read the title, date, time and any guests back " +
            "to the user and ask them to confirm. Only call this after they clearly say yes, with " +
            "user_confirmed=true. Guests receive an email invitation.",
            JSONObject()
                .put("title", prop("STRING", "Event title."))
                .put("start", prop("STRING", "Local start time, ISO like 2026-11-17T15:00, in the user's local time, no timezone suffix."))
                .put("end", prop("STRING", "Local end time, ISO, same rule. Default: one hour after start."))
                .put("guests", prop("STRING", "Optional comma-separated guest emails."))
                .put("user_confirmed", prop("BOOLEAN", "True only if the user said yes after hearing the details.")),
            listOf("title", "start", "user_confirmed")))
        .put(fn("send_email",
            "Send an email from the user's Gmail. FIRST read the recipient, subject and body back " +
            "to the user and ask them to confirm. Only call this after they clearly say yes, with " +
            "user_confirmed=true.",
            JSONObject()
                .put("to", prop("STRING", "Recipient email address."))
                .put("subject", prop("STRING", "Subject line."))
                .put("body", prop("STRING", "Plain-text body."))
                .put("user_confirmed", prop("BOOLEAN", "True only if the user said yes after hearing the message.")),
            listOf("to", "subject", "body", "user_confirmed")))
        .put(fn("search_tools",
            "Find what actions any app in the DAN cloud can do (Composio catalog), e.g. for Notion, Slack, " +
            "Drive, GitHub. Returns tool slugs to use with execute_tool, and whether the app is connected.",
            JSONObject()
                .put("query", prop("STRING", "What to find, e.g. 'notion create page', 'slack send message'."))
                .put("toolkit", prop("STRING", "Optional app name to restrict the search, e.g. 'notion'.")),
            listOf("query")))
        .put(fn("connect_app",
            "Connect a new app in the DAN cloud. Opens the phone's browser so the user signs in. " +
            "Tell the user their browser is opening and to come back when done.",
            JSONObject().put("app_name", prop("STRING", "App name as the user said it, e.g. 'Notion'.")),
            listOf("app_name")))
        .put(fn("execute_tool",
            "Run any tool found with search_tools, on an app that is already connected. For anything that " +
            "changes, sends or deletes, FIRST describe exactly what you will do and only call after the user " +
            "clearly says yes, with user_confirmed=true.",
            JSONObject()
                .put("tool_slug", prop("STRING", "The slug from search_tools, e.g. NOTION_CREATE_PAGE."))
                .put("arguments_json", prop("STRING", "JSON object of the tool's arguments from its input_schema, e.g. {\"title\":\"Note\"}. Empty object if none."))
                .put("user_confirmed", prop("BOOLEAN", "True only if the user said yes after hearing what you will do.")),
            listOf("tool_slug", "arguments_json")))

    // ---------------- execution through the backend ----------------

    // Calendar events go to Composio as naive local datetime + IANA timezone; the device supplies the zone.
    private fun withZone(args: JSONObject): JSONObject {
        if (!args.has("timezone")) args.put("timezone", java.util.TimeZone.getDefault().id)
        return args
    }

    fun run(name: String, args: JSONObject): JSONObject { return try {
        if (name !in names) throw Exception("Unknown app action: $name")
        if (!connected) throw NotConfigured()
        if (name == "search_tools") return searchTools(args)
        if (name == "connect_app") return connectApp(args)
        if (name == "execute_tool") return executeGeneric(args)
        if (name == "create_event") withZone(args)
        val body = JSONObject().put("name", name).put("arguments", args)
        val request = Request.Builder()
            .url("$baseUrl/v1/tools/execute")
            .header("authorization", "Bearer $deviceToken")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            when {
                response.code == 401 -> JSONObject().put("error", "The device token was rejected. Check it in the app's Settings.")
                !response.isSuccessful -> JSONObject().put("error",
                    json?.optString("error")?.ifBlank { null } ?: "The cloud could not do that right now.")
                json?.has("error") == true -> JSONObject().put("error", json.optString("error"))
                else -> JSONObject().put("result", (json?.optString("result") ?: "Done.").take(3000))
            }
        }
    } catch (e: NotConfigured) {
        JSONObject().put("error", e.message)
    } catch (e: Exception) {
        log("Backend $name failed: ${e.javaClass.simpleName}")
        JSONObject().put("error", "The DAN cloud is unreachable right now. Check the phone's internet and the backend address in Settings.")
    } }

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject?> {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("authorization", "Bearer $deviceToken")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return response.code to runCatching { JSONObject(text) }.getOrNull()
        }
    }

    private fun errorFor(code: Int, json: JSONObject?): JSONObject = JSONObject().put("error", when {
        code == 401 -> "The device token was rejected. Check it in the app's Settings."
        json?.optString("error")?.isNotBlank() == true -> json.optString("error")
        else -> "The cloud could not do that right now ($code)."
    })

    private fun searchTools(args: JSONObject): JSONObject = try {
        val body = JSONObject().put("query", args.getString("query"))
        if (args.optString("toolkit").isNotBlank()) body.put("toolkit", args.getString("toolkit"))
        val (code, json) = post("/v1/tools/search", body)
        if (code != 200 || json == null) errorFor(code, json) else {
            val tools = json.optJSONArray("tools")
            if (tools == null || tools.length() == 0) {
                JSONObject().put("result", "No tools found. Try a different wording or connect the app first.")
            } else {
                val sb = StringBuilder("Found tools:\n")
                for (i in 0 until tools.length()) {
                    val t = tools.getJSONObject(i)
                    sb.append(t.optString("slug")).append(" (").append(t.optString("toolkit"))
                        .append(if (t.optBoolean("connected")) ", connected" else ", NOT connected")
                        .append("): ").append(t.optString("description").take(120))
                        .append(". Schema: ").append(t.opt("input_schema")?.toString()?.take(400) ?: "{}")
                        .append("\n")
                }
                JSONObject().put("result", sb.toString().take(3000))
            }
        }
    } catch (e: Exception) {
        log("Backend search_tools failed: ${e.javaClass.simpleName}")
        JSONObject().put("error", "The DAN cloud is unreachable right now.")
    }

    private fun connectApp(args: JSONObject): JSONObject = try {
        val appName = args.getString("app_name")
        val (code, json) = post("/v1/apps/connect", JSONObject().put("toolkit", appName))
        if (code != 200 || json == null) errorFor(code, json) else {
            val url = json.optString("redirect_url")
            if (url.startsWith("https://")) {
                openUrl(url)
                JSONObject().put("result", "The phone's browser is opening the $appName sign-in now. Tell the user to finish it there and come back. It may take a minute.")
            } else JSONObject().put("error", "The cloud did not return a sign-in link for $appName.")
        }
    } catch (e: Exception) {
        log("Backend connect_app failed: ${e.javaClass.simpleName}")
        JSONObject().put("error", "The DAN cloud is unreachable right now.")
    }

    private fun executeGeneric(args: JSONObject): JSONObject = try {
        val slug = args.getString("tool_slug")
        val arguments = runCatching { JSONObject(args.optString("arguments_json").ifBlank { "{}" }) }.getOrNull()
            ?: return JSONObject().put("error", "arguments_json was not valid JSON; build it from the tool's input_schema.")
        val body = JSONObject().put("slug", slug).put("arguments", arguments)
        if (args.optBoolean("user_confirmed")) body.put("user_confirmed", true)
        val (code, json) = post("/v1/tools/execute", body)
        if (code != 200 || json == null) errorFor(code, json) else
            JSONObject().put("result", (json.optString("result").ifBlank { "Done." }).take(3000))
    } catch (e: Exception) {
        log("Backend execute_tool failed: ${e.javaClass.simpleName}")
        JSONObject().put("error", "The DAN cloud is unreachable right now.")
    }
}
