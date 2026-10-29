# Natuu Android — Hybrid AI + Hey Natu

Natuu is a personal Android assistant prototype with online AI, optional on-device AI, voice input, reminders, and an experimental background wake phrase.

## Build an APK with GitHub Actions

1. Keep the contents of this folder at the repository root, including `.github/workflows/build-apk.yml`.
2. Push the changes to the `main` branch, or run **Build Natuu Android APK** from the repository's **Actions** tab.
3. When the run succeeds, download the `Natuu-debug-apk` artifact and extract `app-debug.apk` from the artifact ZIP.

The workflow has not been verified by a full Android Gradle build in this editing environment. A green GitHub Actions run is required before installing the APK.

## Enable “Hey Natu”

1. Install and open Natuu.
2. Grant microphone permission and notification permission when requested.
3. In Settings, or from the small button below the microphone, tap **Enable “Hey Natu”**.
4. Keep the ongoing **Hey Natu is enabled** notification visible. Say **“Hey Natu”** followed by a command, such as **“Hey Natu, open YouTube”**. You can also say just **“Hey Natu”** and then speak after Natuu opens.
5. To stop background listening, tap **Disable** in Natuu or **Stop** in the notification.

## Important wake-word limitations

- This is a best-effort prototype. It uses Android's on-device `SpeechRecognizer` when available, then falls back to the installed speech provider. The fallback may require network access or process audio externally. This is not a dedicated, low-power hardware wake-word model.
- Continuous listening uses battery and a microphone foreground service with an ongoing notification. Android manufacturers may restrict it through battery management, and force-stopping the app stops it. Re-enable it after a reboot if it is no longer active.
- This is not a system-level DSP wake word like “Hey Google”, and launching an Activity from the background may be restricted by some Android versions. If the system blocks automatic launch, use the ongoing notification to reopen Natuu.
- Natuu can only perform actions allowed to ordinary apps. It can open supported apps, adjust media volume, open selected Settings screens, and launch the dialer/message composer. Calls and SMS require user confirmation. It cannot silently control every system setting or bypass Android's permission model.
- Free online models have provider-defined limits. Offline AI quality and speed depend on the phone's hardware.

## Online and offline AI

- Online mode uses an OpenRouter API key entered by the user.
- Optional voice cloning sends five bundled reference samples to ElevenLabs after the user provides an ElevenLabs API key and confirms they have rights to use the voice. Use consistent samples from one speaker for best results.
- Offline mode can download the configured on-device model from the app Settings. The first download requires an internet connection and storage space.
- Keep API keys private. A client-side app cannot completely hide a key while using it to call a remote API.

## Test checklist

- Confirm the workflow uses `android-actions/setup-android@v4`.
- Confirm Actions finishes with a green check and uploads `Natuu-debug-apk`.
- Install the APK, test a normal typed chat, and test a supported native command.
- Enable Hey Natu, leave the app in the background, and test the wake phrase while watching the persistent notification and microphone indicator.
- Test on the actual phone: speech-provider behavior and battery restrictions vary by Android device.


## Voice and language-pack troubleshooting (v1.3.0)

- The LiteRT cache directory is now created and write-tested before engine initialization. If the folder cannot be written, Natuu reports a storage-related error instead of passing a missing directory to LiteRT.
- Settings has separate **Download Bangla pack** and **Download English pack** controls. On Android 13+ these request a speech model download from the installed Android speech-recognition provider; Android 14+ can report progress. Providers may reject in-app downloads, and available locales depend on the provider/device. If a request is rejected, use **Open speech language settings** to install/manage the provider's offline speech models. Offline models are not bundled inside the APK.
- Speech recognition packs are separate from text-to-speech (TTS) voice data. The **Open spoken voice / TTS data settings** button opens the phone's TTS data installer/settings. Install Bengali and English TTS voices there if the engine offers them. Natuu cannot force the phone to provide a language pack that the installed engine does not support.
- Custom voice uses ElevenLabs `eleven_v3` for both English and Bengali because the official model list includes Bengali. It requires a working ElevenLabs API key, a successfully created voice ID, internet access, and remaining quota/access. The v3 request sends only `stability`; Similarity and Speaker Boost settings are not supported by v3. If synthesis fails, Natuu clearly reports the failure and falls back to the device's installed TTS voice.
- The five supplied recordings are packaged as references, not as a ready-made voice model. Press **Create my custom voice** inside Natuu after supplying the separate ElevenLabs API key and confirming you own or have permission to clone the voice. The app cannot use a custom voice completely offline; offline AI text replies use the phone's installed TTS voice unless a separate offline voice model is integrated.


## Battery-saving background wake mode (v1.4.0)

- Background wake-word listening now uses Android's on-device `SpeechRecognizer` only; the always-on listener does not fall back to a cloud speech provider.
- The recognizer is reused between wake checks instead of destroyed and recreated for every result. Recognition retries are throttled, and missing on-device language packs use a slow retry rather than a rapid loop.
- Download the phone's on-device Bangla (`bn-BD`) and English (`en-US`) speech packs before enabling background wake-word detection. Manual voice input can still use its separate provider/fallback logic.
- Background wake listening still uses the microphone and some battery. For lowest power use, turn off “Hey Natu” when you do not need it. This is not equivalent to a system-integrated low-power hotword DSP / default assistant implementation.
