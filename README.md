# Dexi on fon

DAN Glasses V0 Android companion app (Kotlin, no libraries).

The glasses are a Bluetooth LE microphone and speaker; this app does the AI work:
voice in -> Gemini Live -> spoken reply, with cloud apps (Gmail, Calendar, and
on-request connections like Slack or Notion) through the DAN cloud backend.

If the glasses are not connected, the phone's own mic and speaker take over, so
the app works standalone too.

## Build

Open in Android Studio, or from the repo root:

```
gradle assembleDebug  (or .\gradlew if you add the wrapper)
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.
