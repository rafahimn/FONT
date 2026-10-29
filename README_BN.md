# Natuu — Personal AI Assistant (Android) · v1.1.0

Natuu is a Bangla-first personal Android assistant with hybrid AI: OpenRouter free models online plus an optional local Qwen3-0.6B model for offline text chat. It also includes a local API-key setup screen, basic app-launch commands, dialer handoff, alarm handoff, and scheduled reminders.

## Included in this build

- First-run OpenRouter API key setup popup and free-model selection.
- API key stored encrypted using Android Keystore (device-local at-rest protection).
- OpenRouter OpenAI-compatible chat-completions API using `openrouter/free`, which routes requests to an available free model. Up to seven recent exchanges are included as context.
- On-device offline text chat using LiteRT-LM with the public `litert-community/Qwen3-0.6B` model. The model is downloaded once inside Settings (about 586 MB) and saved to private app storage; it is not bundled inside the APK.
- `Auto`, `Online`, and `Offline` chat modes. Auto mode uses online AI when configured and falls back to the downloaded local model if the online request fails.
- Tap-to-talk Android SpeechRecognizer for voice input and TextToSpeech for spoken responses. Always-on wake-word listening (e.g., “Hey Natuu”) is not enabled in this MVP. If a custom voice has been configured, Natuu uses ElevenLabs text-to-speech; otherwise it uses the Pixel system TTS voice.
- Optional custom voice cloning using five bundled audio samples (`natuu_voice_sample_01.mp3` through `natuu_voice_sample_04.mp3`, plus `natuu_voice_reference.mp3`). They are sent to ElevenLabs only when the user enters a separate ElevenLabs API key, checks the rights/consent box, and taps the clone button. OpenRouter handles the assistant’s AI replies.
- Commands for opening a set of installed apps, handing calls to the system dialer, opening the Clock alarm UI, Bluetooth settings, and camera.
- One-shot and daily local notification reminders; reminders are rescheduled after reboot where the Android system permits.
- Basic chat UI with quick actions and a settings sheet.
- GitHub Actions workflow that builds and uploads a debug APK artifact.

## Important limits

This is an MVP source project intended to build into an installable APK, not a prebuilt APK attached to this archive, and not a limitless phone-control agent. The first offline-model download needs internet and about 586 MB of data/storage. On-device inference can use several GB of RAM, so a phone with 6 GB RAM or more is recommended; speed and stability depend on the device. Offline mode is for text AI. Voice transcription depends on Android speech-recognition services and may still need the internet or a downloaded language pack. Offline answers from the small 0.6B model are generally less capable than online models. Android permissions and default-app roles control what it can do. Calls are opened in the system dialer so you can check the number and tap Call; this build does **not** silently answer carrier calls or speak to callers. It also listens only when you tap the microphone; it is not an always-listening wake-word assistant yet. A full replacement dialer needs a compliant dial pad UI and Android `InCallService` implementation, and the user has to explicitly select it as the default phone app.

A custom voice profile is not pre-generated. It is created from all five bundled samples (about 75 seconds total) only after you provide an ElevenLabs API key and explicitly confirm that you own or are authorized to use the voice. For the most consistent result, all samples should feature the same speaker and similar recording conditions; combining different speakers can reduce voice similarity or consistency. ElevenLabs recommends about 1–2 minutes of clean, consistent audio for best quality, so results may vary. The samples are uploaded to ElevenLabs when you press “Create voice”; they are not uploaded to OpenRouter. The app is designed for personal sideloading rather than publishing.

## Get an OpenRouter API key

1. Open https://openrouter.ai/settings/keys and sign in.
2. Create an API key in your OpenRouter account.
3. Launch Natuu and paste the key into the first-run setup dialog. The default model is `openrouter/free`, which routes to an available free model.
4. Free model requests have rate limits and may be slower or temporarily unavailable. Check https://openrouter.ai/collections/free-models for current limits. This app defaults only to the free router; changing it to a paid model is not exposed in this build.

## Set up free hybrid AI

1. Open Natuu Settings.
2. For online AI, add an OpenRouter API key from https://openrouter.ai/settings/keys. Free-model requests have rate limits and providers can be unavailable.
3. Tap **Offline AI model ডাউনলোড করো** while connected to Wi-Fi. The `Qwen3-0.6B.litertlm` file is about 586 MB and is saved privately on the device. Keep Natuu open until the download finishes.
4. Choose **Auto** to use OpenRouter online first and fall back to the local model if the online request fails, **Online** to require OpenRouter, or **Offline** to use only the on-device model.
5. After the local model is downloaded, switch the phone to Airplane mode and test text chat. Speech recognition may depend on Android's installed speech service/language pack.

No subscription is required by this source project, but OpenRouter free limits and model availability can change. The local model requires device resources and is not unlimited in quality or speed.

## Optional: Create your personal reference voice

1. Create an ElevenLabs account and an API key at https://elevenlabs.io/app/settings/api-keys. This is a separate provider/key from OpenRouter. Plan access, pricing, and quota are determined by ElevenLabs.
2. Open Natuu Settings, paste the key into “ElevenLabs API key”, tick the declaration that you own or are authorized to use the voice and consent to cloning, then tap “আমার দেওয়া voice reference দিয়ে কণ্ঠ তৈরি করো”.
3. Natuu uploads all five MP3 samples to ElevenLabs in one voice-creation request, saves the returned voice ID encrypted on the phone, and uses ElevenLabs text-to-speech for subsequent spoken responses. If setup is skipped, Natuu keeps using the Pixel TTS voice.
4. The app itself does not publish anything. This optional feature does transmit the voice sample to ElevenLabs for processing. Keep the APK private because it embeds the personal voice reference audio.

**Security note:** the app encrypts the API key at rest using Android Keystore, but any key used directly by a mobile client can potentially be extracted by a determined attacker. This APK is intended for personal sideloading only. For a public release, route OpenRouter requests through a backend and do not distribute a personal API key inside the app.

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
- Default model: `openrouter/free`
- Voice reference assets: `app/src/main/assets/natuu_voice_sample_01.mp3` through `natuu_voice_sample_04.mp3`, plus `natuu_voice_reference.mp3` (five provided samples)
- Source language: Java + HTML/CSS/JavaScript; no third-party app libraries are required.


## এই আপডেটে কী ঠিক করা হয়েছে

- ছোট ফোন, landscape orientation, tablet এবং desktop WebView-এর জন্য responsive layout ও safe-area spacing উন্নত করা হয়েছে।
- Voice command recognition এখন Bangla (bn-BD) language-কে অগ্রাধিকার দেয়।
- Background wake-word service English “Hey Natu” এবং বাংলা “নাতু/হেই নাতু” শনাক্ত করার জন্য English ও Bangla recognition পালাক্রমে চেষ্টা করে।
- Microphone permission বা speech recognition error হলে আরও স্পষ্ট বাংলা error message দেখায়।
- AI reply পেতে Settings-এ valid OpenRouter API key এবং Auto/Online mode ব্যবহার করতে হবে; free model provider-এর availability অনুযায়ী response time বদলাতে পারে।
