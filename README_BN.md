# Natuu — Personal AI Assistant (Android) · v1.1.0

Natuu is a personal Android assistant prototype for a Google Pixel phone. It has a Bangla-first voice interface, Gemini chat, a local API-key setup screen, basic app-launch commands, dialer handoff, alarm handoff, and scheduled reminders.

## Included in this build

- First-run Gemini API key setup popup and model selection.
- API key stored encrypted using Android Keystore (device-local at-rest protection).
- Gemini `generateContent` chat using `gemini-3.8-flash` by default.
- Tap-to-talk Android SpeechRecognizer for voice input and TextToSpeech for spoken responses. Always-on wake-word listening (e.g., “Hey Natuu”) is not enabled in this MVP. If a custom voice has been configured, Natuu uses ElevenLabs text-to-speech; otherwise it uses the Pixel system TTS voice.
- Optional custom voice cloning using the audio clip supplied by the owner (`app/src/main/assets/natuu_voice_reference.mp3`). The clip is only sent to ElevenLabs when the user enters a separate ElevenLabs API key, checks the rights/consent box, and taps the clone button. Gemini still handles the assistant’s AI replies.
- Commands for opening a set of installed apps, handing calls to the system dialer, opening the Clock alarm UI, Bluetooth settings, and camera.
- One-shot and daily local notification reminders; reminders are rescheduled after reboot where the Android system permits.
- Basic chat UI with quick actions and a settings sheet.
- GitHub Actions workflow that builds and uploads a debug APK artifact.

## Important limits

This is an MVP source project intended to build into an installable APK, not a prebuilt APK attached to this archive, and not a limitless phone-control agent. Android permissions and default-app roles control what it can do. Calls are opened in the system dialer so you can check the number and tap Call; this build does **not** silently answer carrier calls or speak to callers. It also listens only when you tap the microphone; it is not an always-listening wake-word assistant yet. A full replacement dialer needs a compliant dial pad UI and Android `InCallService` implementation, and the user has to explicitly select it as the default phone app.

A custom voice profile is not pre-generated. It is created from the bundled reference clip only after you provide an ElevenLabs API key and explicitly confirm that you own or are authorized to use the voice. This path does not use Google’s voice-replication endpoint, which requires a separate recorded consent statement; Gemini remains the AI/chat provider. The supplied reference clip is about 27 seconds. ElevenLabs accepts clips as short as 10 seconds in total, but recommends 1–2 minutes of clean, consistent audio for better quality; results with the included clip may vary. Your reference audio is uploaded to ElevenLabs when you press “Create voice”; it is not uploaded to Gemini, and the app is designed for personal sideloading rather than publishing.

## Get a Gemini API key

1. Open Google AI Studio: https://aistudio.google.com/app/apikey
2. Create or select a Gemini API key/project with API access enabled.
3. Keep the key private. API requests may consume quota or incur charges according to your account and model access.
4. Launch Natuu and paste the key into the first-run setup dialog. This is the only key needed for Gemini chat and the built-in phone TTS fallback.

## Optional: Create your personal reference voice

1. Create an ElevenLabs account and an API key at https://elevenlabs.io/app/settings/api-keys. This is a separate provider/key from Gemini. Plan access, pricing, and quota are determined by ElevenLabs.
2. Open Natuu Settings, paste the key into “ElevenLabs API key”, tick the declaration that you own or are authorized to use the voice and consent to cloning, then tap “আমার দেওয়া voice reference দিয়ে কণ্ঠ তৈরি করো”.
3. Natuu uploads the included 27-second MP3 reference to ElevenLabs, saves the returned voice ID encrypted on the phone, and uses ElevenLabs text-to-speech for subsequent spoken responses. If setup is skipped, Natuu keeps using the Pixel TTS voice.
4. The app itself does not publish anything. This optional feature does transmit the voice sample to ElevenLabs for processing. Keep the APK private because it embeds the personal voice reference audio.

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
- Voice reference asset: `app/src/main/assets/natuu_voice_reference.mp3` (included from the user-provided audio)
- Source language: Java + HTML/CSS/JavaScript; no third-party app libraries are required.
