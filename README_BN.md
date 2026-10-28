# Natuu — Personal AI Assistant (Android)

Natuu is a personal Android assistant prototype for a Google Pixel phone. It has a Bangla-first voice interface, Gemini chat, a local API-key setup screen, basic app-launch commands, dialer handoff, alarm handoff, and scheduled reminders.

## Included in this build

- First-run Gemini API key setup popup and model selection.
- API key stored encrypted using Android Keystore (device-local at-rest protection).
- Gemini `generateContent` chat using `gemini-3.8-flash` by default.
- Tap-to-talk Android SpeechRecognizer for voice input and TextToSpeech for spoken responses. Always-on wake-word listening (e.g., “Hey Natuu”) is not enabled in this MVP. The app prefers a Bengali voice where one is available and leans toward a female-sounding system voice when the installed TTS engine exposes one. Voice gender/quality varies by the TTS engine and installed language packs.
- Commands for opening a set of installed apps, handing calls to the system dialer, opening the Clock alarm UI, Bluetooth settings, and camera.
- One-shot and daily local notification reminders; reminders are rescheduled after reboot where the Android system permits.
- Basic chat UI with quick actions and a settings sheet.
- GitHub Actions workflow that builds and uploads a debug APK artifact.

## Important limits

This is an MVP source project intended to build into an installable APK, not a prebuilt APK attached to this archive, and not a limitless phone-control agent. Android permissions and default-app roles control what it can do. Calls are opened in the system dialer so you can check the number and tap Call; this build does **not** silently answer carrier calls or speak to callers. It also listens only when you tap the microphone; it is not an always-listening wake-word assistant yet. A full replacement dialer needs a compliant dial pad UI and Android `InCallService` implementation, and the user has to explicitly select it as the default phone app.

The voice in this build is Android's installed Text-to-Speech voice. A clone of a voice reference has **not** been generated or integrated yet. To use a replicated voice, the adult voice owner must consent, and Google's voice replication API requires both a clean 10–30 second reference clip and a separate consent recording. The app can be extended after those files are available and the chosen API flow has been tested.

## Get a Gemini API key

1. Open Google AI Studio: https://aistudio.google.com/app/apikey
2. Create or select a Gemini API key/project with API access enabled.
3. Keep the key private. API requests may consume quota or incur charges according to your account and model access.
4. Launch Natuu and paste the key into the first-run setup dialog.

**Security note:** the app encrypts the API key at rest using Android Keystore, but any key used directly by a mobile client can potentially be extracted by a determined attacker. This APK is intended for personal sideloading only. For a public release, route Gemini requests through a backend and do not distribute a personal API key inside the app.

## Build an APK using GitHub Actions (no local Android SDK required)

1. Create a new private GitHub repository.
2. Upload the contents of this folder to the repository root and push them to `main` (or `master`).
3. Open the repository's **Actions** tab and select **Build Natuu Android APK**. If needed, enable Actions for the repository.
4. Open the completed workflow run and download the `Natuu-debug-apk` artifact.
5. Extract it; the APK is `app-debug.apk`. Transfer it to your Pixel and install it. Android may ask you to allow installs from that file manager/browser.

The repository workflow installs Gradle and the Android SDK platform, compiles `assembleDebug`, then uploads the APK. The APK is unsigned for Play Store distribution, but can be installed manually for testing.

## Build locally (Android Studio)

- Android Studio with JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and Gradle 8.9.
- Open this folder as an Android project, sync Gradle, then run **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
- Or from a shell with Gradle 8.9 installed: `gradle assembleDebug`.

## Example voice commands

- `YouTube খোলো`
- `Chrome খোলো`
- `১০ মিনিট পরে আমাকে পানি খেতে মনে করিও`
- `প্রতিদিন সকাল ৭টায় আমাকে মনে করিও`
- `সকাল ৮টায় অ্যালার্ম দাও`
- `কল কর 017XXXXXXXX`
- `ব্লুটুথ চালু করার সেটিংস খোলো`

The actual wording recognised depends on your Pixel's active speech-recognition service and installed languages. Calls/messages require a final user action in the relevant system app.

## Project details

- Application ID: `com.natuu.assistant`
- Minimum Android: 8.0 (API 26)
- Target SDK: 35
- Default model: `gemini-3.8-flash`
- Source language: Java + HTML/CSS/JavaScript; no third-party app libraries are required.
