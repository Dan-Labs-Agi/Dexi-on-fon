package com.danny.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Google sign-in for Gmail and Calendar, with Google's Android Authorization API.
 * No password or client secret lives in the app: Google recognises the app by its
 * package name + signing fingerprint (the Android OAuth client in Google Cloud).
 * Access tokens last about an hour; after the first sign-in new ones come silently.
 */
class GoogleAuth(private val context: Context) {

    companion object {
        const val REQUEST_CODE = 4242
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/gmail.readonly",    // read email (no sending, no deleting)
            "https://www.googleapis.com/auth/calendar.events",   // see and create calendar events
        )
    }

    private val prefs = context.getSharedPreferences("danny", Context.MODE_PRIVATE)
    @Volatile private var token: String? = null
    @Volatile private var expiresAt = 0L

    val connected: Boolean get() = prefs.getBoolean("googleConnected", false)

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES.map { Scope(it) })
        .build()

    /** Shows Google's account and permission screens if needed. */
    fun signIn(activity: Activity, onResult: (Boolean, String) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request())
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) {
                    activity.startIntentSenderForResult(pending.intentSender, REQUEST_CODE, null, 0, 0, 0)
                } else {
                    save(r)
                    onResult(true, "Google account connected")
                }
            }
            .addOnFailureListener { onResult(false, it.message ?: "sign-in failed") }
    }

    /** Call from onActivityResult for REQUEST_CODE. */
    fun handleResult(activity: Activity, data: Intent?): Pair<Boolean, String> = try {
        val r = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        save(r)
        if (r.accessToken != null) true to "Google account connected" else false to "No access granted"
    } catch (e: Exception) {
        false to (e.message ?: "sign-in cancelled")
    }

    private fun save(r: AuthorizationResult) {
        token = r.accessToken
        expiresAt = System.currentTimeMillis() + 50 * 60_000L
        val edit = prefs.edit().putBoolean("googleConnected", r.accessToken != null)
        // Google often includes the account here; otherwise fetchProfile() fills it in.
        @Suppress("DEPRECATION")
        runCatching { r.toGoogleSignInAccount() }.getOrNull()?.let { acct ->
            acct.email?.let { edit.putString("googleEmail", it) }
            acct.displayName?.let { edit.putString("googleName", it) }
        }
        edit.apply()
    }

    /** Signed-in account, for showing in the app. */
    val accountEmail: String? get() = prefs.getString("googleEmail", null)
    val accountName: String? get() = prefs.getString("googleName", null)

    private val http = okhttp3.OkHttpClient()

    /** Background thread: look up the account's email address via Gmail (no extra permission needed). */
    fun fetchProfile(): Boolean {
        if (accountEmail != null) return true
        val t = accessToken() ?: return false
        return runCatching {
            val req = okhttp3.Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/profile")
                .header("Authorization", "Bearer $t").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val email = org.json.JSONObject(resp.body?.string() ?: "{}").optString("emailAddress")
                if (email.isNotEmpty()) prefs.edit().putString("googleEmail", email).apply()
                email.isNotEmpty()
            }
        }.getOrDefault(false)
    }

    /**
     * Background thread: log out. Asks Google to revoke this app's access (so it's really cut off,
     * not just forgotten on the phone), then clears the saved account.
     */
    fun logout(): Boolean {
        val t = token ?: runCatching { accessToken() }.getOrNull()
        val revoked = t?.let {
            runCatching {
                val req = okhttp3.Request.Builder().url("https://oauth2.googleapis.com/revoke")
                    .post(okhttp3.FormBody.Builder().add("token", it).build()).build()
                http.newCall(req).execute().use { resp -> resp.isSuccessful }
            }.getOrDefault(false)
        } ?: false
        token = null
        expiresAt = 0
        prefs.edit().putBoolean("googleConnected", false).remove("googleEmail").remove("googleName").apply()
        return revoked
    }

    /** Background threads only. Returns null if the user must sign in again in the app. */
    fun accessToken(): String? {
        token?.let { if (System.currentTimeMillis() < expiresAt) return it }
        return try {
            val r = Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 15, TimeUnit.SECONDS)
            if (r.hasResolution()) null else { save(r); r.accessToken }
        } catch (e: Exception) {
            null
        }
    }

    /** Forget the cached token (after Google says it's no longer valid). */
    fun invalidate() { token = null }

}
