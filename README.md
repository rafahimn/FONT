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
