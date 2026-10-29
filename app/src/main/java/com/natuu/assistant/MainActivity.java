package com.natuu.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.ModelDownloadListener;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.content.res.Configuration;
import android.media.MediaPlayer;
import android.media.AudioManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 101;
    private static final int REQ_CONTACTS = 102;
    private static final int REQ_NOTIFICATIONS = 103;
    private static final int REQ_EXACT_ALARM = 104;
    private static final int REQ_WAKE_MIC = 105;
    private static final String PREFS = "natuu_prefs";
    private static final String MODEL_DEFAULT = "openrouter/free";
    private static final String PREF_AUTO_LISTEN = "auto_listen";
    private static final int MAX_REPLY_TOKENS = 320;
    private static final String SYSTEM_PROMPT = "You are Natuu, a warm, kind personal friend and assistant. " +
            "The app speaks your replies aloud in a cloned human voice, so keep every answer SHORT and conversational: " +
            "usually one to three short sentences, never long lists or bullet points. Reply naturally in the language " +
            "the user used — Bangla (as spoken in Bangladesh) for Bangla messages, English for English messages. " +
            "Be helpful, clear and friendly. Never claim you performed a phone action unless the app confirms it. " +
            "You cannot directly access private phone data or control apps unless the Natuu app tells you that action " +
            "was completed. For risky actions such as sending messages or placing calls, require the user's explicit " +
            "confirmation.";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private SpeechRecognizer speechRecognizer;
    private SpeechRecognizer speechPackRecognizer;
    private boolean speechAutoMode = true;
    private boolean speechFallbackAttempted = false;
    private boolean speechOnlineFallbackAttempted = false;
    private String currentSpeechLanguage = "bn-BD";
    private boolean webPageReady = false;
    private boolean activityInForeground = false;
    private boolean pendingWakeDispatch = false;
    private String pendingWakeCommand = "";
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private String pendingCallName = null;
    private MediaPlayer customVoicePlayer = null;
    private OfflineModelManager offlineModelManager;
    private static final String CHAT_HISTORY_KEY = "natuu_chat_history_v1";

    // ===== Fast streamed reply + voice pipeline state (v1.5) =====
    // Synthesizes each finished sentence of a streamed reply and plays it back
    // while the rest of the reply is still being generated, then re-opens the
    // microphone for hands-free conversation when everything is spoken.
    private boolean replyVoiceActive = false;              // a streamed reply is being spoken right now
    private volatile boolean voiceSourceFinished = true;   // no more sentences will arrive for the current reply
    private boolean customPlaying = false;                 // a streamed sentence is playing right now
    private boolean systemFlushPending = false;            // first system-TTS sentence of the current reply
    private boolean legacyReplySoundPending = false;       // a single (non-streamed) reply sound is playing
    private volatile boolean streamStopped = false;        // user pressed the stop button
    private boolean micAutoGuard = false;                  // avoids double auto-listen triggers
    private final java.util.concurrent.ConcurrentLinkedDeque<File> playbackQueue = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private MediaPlayer streamingPlayer = null;
    private final java.util.concurrent.atomic.AtomicInteger ttsReplyPending = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicBoolean customVoiceBroken = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ExecutorService voiceSynthExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(16, 13, 29));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(16, 13, 29));
        createNotificationChannel();
        captureWakeIntent(getIntent());
        offlineModelManager = new OfflineModelManager(this);
        initTts();
        setupWebView();
        maybeRequestNotificationPermission();
    }

    private void setupWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.rgb(16, 13, 29));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null && uri.getPath().startsWith("/android_asset/")) return false;
                openExternalUrl(uri);
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null && uri.getPath().startsWith("/android_asset/")) return false;
                openExternalUrl(uri);
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                webPageReady = true;
                view.evaluateJavascript("window.onNatuuState && window.onNatuuState(" + getStateJson() + ");", null);
                dispatchPendingWake();
            }
        });
        webView.addJavascriptInterface(new NatuuBridge(), "Natuu");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureWakeIntent(intent);
        dispatchPendingWake();
    }

    @Override protected void onStart() {
        super.onStart();
        activityInForeground = true;
        // Keep the background listener from competing with the visible conversation UI.
        WakeWordService.pause(this);
    }

    @Override protected void onStop() {
        activityInForeground = false;
        super.onStop();
        // Resume the explicitly enabled wake listener after Natuu leaves the foreground.
        WakeWordService.resume(this);
    }

    private void openExternalUrl(Uri uri) {
        if (uri == null) return;
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException ignored) { sendNativeEvent("No browser is available to open that link."); }
    }

    private void captureWakeIntent(Intent intent) {
        if (intent == null || !WakeWordService.ACTION_WAKE_TRIGGER.equals(intent.getAction())) return;
        pendingWakeCommand = intent.getStringExtra(WakeWordService.EXTRA_COMMAND);
        if (pendingWakeCommand == null) pendingWakeCommand = "";
        pendingWakeDispatch = true;
        // Do not replay the wake event if Android later recreates this Activity.
        setIntent(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN));
    }

    private void dispatchPendingWake() {
        if (!webPageReady || !pendingWakeDispatch || webView == null) return;
        pendingWakeDispatch = false;
        String command = pendingWakeCommand;
        pendingWakeCommand = "";
        webView.evaluateJavascript("window.handleWakeLaunch && window.handleWakeLaunch(" + JSONObject.quote(command) + ");", null);
    }

    private void startWakeWordService() {
        try {
            WakeWordService.start(this);
            sendNativeEvent("Hey Natu is enabled. A persistent notification will remain visible while wake-word listening is active.");
            mainHandler.postDelayed(() -> {
                if (activityInForeground) WakeWordService.pause(MainActivity.this);
                if (webView != null) webView.evaluateJavascript("window.onNatuuState && window.onNatuuState(" + getStateJson() + ");", null);
            }, 600);
        } catch (Exception e) {
            sendNativeEvent("Could not start Hey Natu. Open the app, allow microphone access, and try again.");
        }
    }

    private void initTts() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            ttsReady = true;
            textToSpeech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) { handleTtsEnd(utteranceId); }
                @Override public void onStop(String utteranceId, boolean interrupted) { handleTtsEnd(utteranceId); }
                @Override public void onError(String utteranceId) { handleTtsEnd(utteranceId); }
                @Override public void onError(String utteranceId, int errorCode) { handleTtsEnd(utteranceId); }
            });
            configureFemaleLeaningVoice();
        });
    }

    private void configureFemaleLeaningVoice() {
        if (textToSpeech == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            int result = textToSpeech.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech.setLanguage(Locale.ENGLISH);
            }
            Voice best = null;
            for (Voice voice : textToSpeech.getVoices()) {
                String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.ROOT);
                Locale locale = voice.getLocale();
                boolean languageMatch = locale != null && "en".equalsIgnoreCase(locale.getLanguage());
                boolean femaleHint = name.contains("female") || name.contains("woman") || name.contains("girl");
                if (languageMatch && femaleHint) { best = voice; break; }
                if (best == null && languageMatch && !voice.isNetworkConnectionRequired()) best = voice;
            }
            if (best != null) textToSpeech.setVoice(best);
            textToSpeech.setSpeechRate(0.96f);
            textToSpeech.setPitch(1.08f);
        } catch (Exception ignored) { }
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("natuu_reminders", "Natuu reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Reminders scheduled with Natuu");
            channel.enableVibration(true);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startSpeechRecognition();
            else sendNativeEvent("Microphone permission is needed for voice commands.");
        } else if (requestCode == REQ_WAKE_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startWakeWordService();
            else sendNativeEvent("Microphone access is required to enable Hey Natu.");
        } else if (requestCode == REQ_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingCallName != null) {
                String name = pendingCallName; pendingCallName = null; callContactByName(name);
            } else {
                pendingCallName = null;
                sendNativeEvent("Contacts permission was not granted. You can say a phone number instead.");
            }
        }
    }

    private void startSpeechRecognition() {
        String languageSetting = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("speech_language", "auto");
        speechAutoMode = "auto".equals(languageSetting);
        speechFallbackAttempted = false;
        speechOnlineFallbackAttempted = false;
        String firstLanguage = "en-US".equals(languageSetting) ? "en-US" : "bn-BD";
        startSpeechRecognitionAttempt(firstLanguage);
    }

    private void startSpeechRecognitionAttempt(String language) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendStatus("idle");
            sendNativeEvent("এই ফোনে speech recognition service চালু নেই। Google Speech Services/voice input চালু করুন।");
            return;
        }
        // Try local recognition first, but do not get stuck there if the selected language
        // model is missing. The normal provider can use online recognition when needed.
        final boolean useOnDevice = !speechOnlineFallbackAttempted
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        currentSpeechLanguage = language;
        try {
            if (speechRecognizer != null) {
                try { speechRecognizer.cancel(); } catch (Exception ignored) { }
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
            speechRecognizer = useOnDevice
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    : SpeechRecognizer.createSpeechRecognizer(this);
            final SpeechRecognizer thisRecognizer = speechRecognizer;
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { sendStatus("listening"); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { sendStatus("thinking"); }
                @Override public void onError(int error) {
                    if (speechRecognizer != thisRecognizer) return;
                    if (shouldTryOnlineSpeechFallback(useOnDevice, error)) {
                        speechOnlineFallbackAttempted = true;
                        sendNativeEvent("এই ভাষার offline speech pack পাওয়া যায়নি। Android speech provider দিয়ে আবার চেষ্টা করছি; এতে internet ব্যবহার হতে পারে।");
                        mainHandler.postDelayed(() -> startSpeechRecognitionAttempt(language), 250);
                        return;
                    }
                    if (shouldTryEnglishFallback(error)) {
                        speechFallbackAttempted = true;
                        sendNativeEvent("বাংলা recognition হয়নি, English দিয়ে আরেকবার চেষ্টা করছি। Settings থেকে Bangla ও English speech pack download করুন।");
                        mainHandler.postDelayed(() -> startSpeechRecognitionAttempt("en-US"), 250);
                        return;
                    }
                    String message;
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        message = "কথাটি বুঝতে পারিনি। আবার বলুন। Settings → Speech language packs থেকে Bangla ও English download করুন; pack না থাকলে online recognition দরকার হতে পারে।";
                    } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        message = "ভয়েস ব্যবহারের জন্য Microphone permission দিন: Android Settings → Apps → Natuu → Permissions।";
                    } else if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        message = "এই ভাষার speech model পাওয়া যায়নি। Natuu Settings থেকে pack download করুন অথবা ফোনের speech provider settings দেখুন।";
                    } else {
                        message = "ভয়েস ইনপুট শুরু হয়নি। Microphone permission, speech service ও language pack check করুন। (Error " + error + ")";
                    }
                    sendStatus("idle");
                    sendNativeEvent(message);
                }
                @Override public void onResults(Bundle results) {
                    if (speechRecognizer != thisRecognizer) return;
                    sendStatus("thinking");
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty() && matches.get(0) != null && !matches.get(0).trim().isEmpty()) {
                        processCommand(matches.get(0));
                    } else if (shouldTryOnlineSpeechFallback(useOnDevice, SpeechRecognizer.ERROR_NO_MATCH)) {
                        speechOnlineFallbackAttempted = true;
                        sendNativeEvent("Offline speech modelটি এই কথাটি ধরতে পারেনি। Android speech provider দিয়ে আরেকবার চেষ্টা করছি; internet ব্যবহার হতে পারে।");
                        mainHandler.postDelayed(() -> startSpeechRecognitionAttempt(language), 250);
                    } else if (shouldTryEnglishFallback(SpeechRecognizer.ERROR_NO_MATCH)) {
                        speechFallbackAttempted = true;
                        startSpeechRecognitionAttempt("en-US");
                    } else {
                        sendStatus("idle");
                        sendNativeEvent("কথাটি বুঝতে পারিনি। আবার চেষ্টা করুন।");
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent intent = makeSpeechRecognitionIntent(language, true);
            // Only force offline on the on-device recognizer. The normal recognizer is allowed
            // to use its provider's network path if the offline language pack is absent.
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOnDevice);
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            sendStatus("idle");
            sendNativeEvent("Voice input could not start. Check microphone access and that the selected speech pack is installed.");
        }
    }

    private boolean shouldTryOnlineSpeechFallback(boolean useOnDevice, int error) {
        if (!useOnDevice || speechOnlineFallbackAttempted) return false;
        return error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE;
    }

    private boolean shouldTryEnglishFallback(int error) {
        if (!speechAutoMode || speechFallbackAttempted || "en-US".equals(currentSpeechLanguage)) return false;
        return error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                || error == SpeechRecognizer.ERROR_NETWORK
                || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER;
    }

    private Intent makeSpeechRecognitionIntent(String language, boolean allowAutoSwitch) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        if (allowAutoSwitch && speechAutoMode && Build.VERSION.SDK_INT >= 34) {
            ArrayList<String> languages = new ArrayList<>(Arrays.asList("bn-BD", "en-US"));
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, languages);
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, languages);
        }
        return intent;
    }

    private void requestSpeechLanguagePack(String language) {
        if (!"bn-BD".equals(language) && !"en-US".equals(language)) {
            sendSpeechPackStatus("Select Bangla or English first.");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendSpeechPackStatus("এই ফোনে speech recognition service নেই। Google Speech Services install/enable করুন।");
            return;
        }
        try {
            if (speechPackRecognizer != null) {
                try { speechPackRecognizer.destroy(); } catch (Exception ignored) { }
                speechPackRecognizer = null;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                speechPackRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else {
                speechPackRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
            Intent request = makeSpeechRecognitionIntent(language, false);
            request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            String label = "bn-BD".equals(language) ? "Bangla (Bangladesh)" : "English (US)";
            final SpeechRecognizer downloadRecognizer = speechPackRecognizer;
            if (Build.VERSION.SDK_INT >= 34) {
                downloadRecognizer.triggerModelDownload(request, getMainExecutor(), new ModelDownloadListener() {
                    @Override public void onProgress(int completedPercent) {
                        sendSpeechPackStatus(label + " speech pack downloading: " + completedPercent + "%");
                    }
                    @Override public void onScheduled() {
                        sendSpeechPackStatus(label + " pack download scheduled by Android. Keep the phone online; the provider will finish it in the background.");
                        releaseSpeechPackRecognizer(downloadRecognizer);
                    }
                    @Override public void onSuccess() {
                        sendSpeechPackStatus("✅ " + label + " speech pack is ready, if supported by your phone's speech provider.");
                        releaseSpeechPackRecognizer(downloadRecognizer);
                    }
                    @Override public void onError(int error) {
                        sendSpeechPackStatus(label + " pack request failed (error " + error + "). Your speech provider may not support in-app model downloads; open Speech settings below.");
                        releaseSpeechPackRecognizer(downloadRecognizer);
                    }
                });
                sendSpeechPackStatus("Requesting " + label + " speech pack from Android…");
            } else if (Build.VERSION.SDK_INT >= 33) {
                downloadRecognizer.triggerModelDownload(request);
                sendSpeechPackStatus("Requested " + label + " speech pack. Android/provider may ask for confirmation or schedule it. Check again after downloading.");
                // API 33 has no download listener. Keep the recognizer alive until the next
                // pack request or Activity destruction instead of risking canceling a slow download.
            } else {
                openSpeechLanguageSettings();
                sendSpeechPackStatus("This Android version cannot request language models directly. In Google app → Settings → Voice → Offline speech recognition, download Bangla and English if listed.");
                releaseSpeechPackRecognizer();
            }
        } catch (Exception e) {
            sendSpeechPackStatus("Could not request the pack. Open your phone's Speech/Voice input settings and download Bangla/English there.");
            releaseSpeechPackRecognizer();
        }
    }

    private void releaseSpeechPackRecognizer() {
        releaseSpeechPackRecognizer(speechPackRecognizer);
    }

    private void releaseSpeechPackRecognizer(SpeechRecognizer target) {
        if (target == null) return;
        mainHandler.post(() -> {
            if (speechPackRecognizer == target) speechPackRecognizer = null;
            try { target.destroy(); } catch (Exception ignored) { }
        });
    }

    private void openSpeechLanguageSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
            if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
            else startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            sendNativeEvent("Android Settings → System → Languages & input → Voice input খুলে Bangla/English pack check করুন।");
        }
    }

    private void openTextToSpeechSettings() {
        // ACTION_TEXT_TO_SPEECH_SETTINGS is not a public Settings constant on all SDKs.
        // First ask the installed TTS engine to open its voice-data installer, then fall back
        // to the common Android TTS settings action and finally the main Settings page.
        try {
            Intent installData = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
            if (installData.resolveActivity(getPackageManager()) != null) {
                startActivity(installData);
                return;
            }
        } catch (Exception ignored) { }
        try {
            Intent ttsSettings = new Intent("com.android.settings.TTS_SETTINGS");
            if (ttsSettings.resolveActivity(getPackageManager()) != null) {
                startActivity(ttsSettings);
                return;
            }
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            sendNativeEvent("Android Settings → Text-to-speech output খুলে Bangla voice data download করুন, যদি পাওয়া যায়।");
        }
    }

    private void sendSpeechPackStatus(String message) {
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript("window.onSpeechPackStatus && window.onSpeechPackStatus(" + JSONObject.quote(message) + ");", null);
            }
        });
    }

    private void processCommand(String input) {
        if (input == null || input.trim().isEmpty()) return;
        sendStatus("thinking");
        // Handle native phone actions on Android's main thread; keep AI work off it.
        mainHandler.post(() -> {
            ActionResult action = tryNativeAction(input);
            if (action.handled) {
                if (action.reply != null) postReply(input, action.reply, "action", true);
                return;
            }

            String mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("chat_mode", "auto");
            boolean offlineReady = offlineModelManager != null && offlineModelManager.isModelReady();
            if ("offline".equals(mode)) {
                sendOfflineReply(input);
                return;
            }

            executor.execute(() -> {
                String key = SecureStore.getApiKey(this);
                if (key.isEmpty()) {
                    if ("auto".equals(mode) && offlineReady) {
                        sendOfflineReply(input);
                        return;
                    }
                    postReply(input, "No OpenRouter API key is configured. Add a key in Settings or download the free offline AI model.", "error", false);
                    mainHandler.post(() -> { if (webView != null) webView.evaluateJavascript("window.showSettings && window.showSettings(true);", null); });
                    return;
                }
                try {
                    streamChatReply(key, input, mode, offlineReady);
                } catch (Exception e) {
                    if ("auto".equals(mode) && offlineReady) {
                        sendOfflineReply(input);
                        return;
                    }
                    String msg = e.getMessage() == null ? "Could not connect to OpenRouter." : e.getMessage();
                    postReply(input, "Sorry, " + msg, "error", false);
                }
            });
        });
    }

    private void sendOfflineReply(String input) {
        if (offlineModelManager == null || !offlineModelManager.isModelReady()) {
            postReply(input, "The offline AI model is not installed. Open Settings to download the approximately 586 MB model, or add an OpenRouter API key and use Auto mode.", "error", false);
            mainHandler.post(() -> { if (webView != null) webView.evaluateJavascript("window.showSettings && window.showSettings(true);", null); });
            return;
        }
        offlineModelManager.generateReply(input, new OfflineModelManager.ReplyCallback() {
            @Override public void onComplete(boolean success, String reply) {
                if (success) postReply(input, reply, "offline", true);
                else postReply(input, "Offline AI: " + reply, "error", false);
            }
        });
    }

    private JSONArray getRecentChatHistory() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(CHAT_HISTORY_KEY, "[]");
        try { return new JSONArray(saved); } catch (JSONException ignored) { return new JSONArray(); }
    }

    private void rememberChatTurn(String userText, String assistantText) {
        JSONArray previous = getRecentChatHistory();
        JSONArray updated = new JSONArray();
        try {
            for (int i = Math.max(0, previous.length() - 14); i < previous.length(); i++) updated.put(previous.getJSONObject(i));
            updated.put(new JSONObject().put("role", "user").put("content", userText));
            updated.put(new JSONObject().put("role", "assistant").put("content", assistantText));
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CHAT_HISTORY_KEY, updated.toString()).apply();
        } catch (JSONException ignored) { }
    }

    private static class ActionResult {
        final boolean handled; final String reply;
        ActionResult(boolean handled, String reply) { this.handled = handled; this.reply = reply; }
        static ActionResult no() { return new ActionResult(false, null); }
        static ActionResult yes(String reply) { return new ActionResult(true, reply); }
    }

    private ActionResult tryNativeAction(String raw) {
        String text = latinDigits(raw).toLowerCase(Locale.ROOT).trim();
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (containsAny(text, "unmute", "turn sound back on", "restore sound")) {
            if (audio != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), Math.max(1, audio.getStreamVolume(AudioManager.STREAM_MUSIC))), 0);
            return ActionResult.yes("Media sound is on.");
        }
        if (containsAny(text, "mute", "mute sound", "silence media")) {
            if (audio != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            return ActionResult.yes("Media sound is muted.");
        }
        if (containsAny(text, "volume up", "increase volume", "turn it up", "louder")) {
            if (audio != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC) + 1), 0);
            return ActionResult.yes("Media volume increased.");
        }
        if (containsAny(text, "volume down", "decrease volume", "turn it down", "quieter")) {
            if (audio != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC) - 1), 0);
            return ActionResult.yes("Media volume decreased.");
        }
        if (containsAny(text, "wifi settings", "wi-fi settings", "open wifi", "open wi-fi", "turn on wifi", "turn off wifi", "turn on wi-fi", "turn off wi-fi", "enable wifi", "disable wifi", "connect to wifi")) {
            try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); return ActionResult.yes("Wi-Fi settings are open. Confirm any network changes there."); }
            catch (Exception e) { return ActionResult.yes("Could not open Wi-Fi settings."); }
        }
        if (containsAny(text, "bluetooth settings", "open bluetooth", "turn on bluetooth", "turn off bluetooth", "enable bluetooth", "disable bluetooth")) {
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return ActionResult.yes("Bluetooth settings are open. Confirm any changes there."); }
            catch (Exception e) { return ActionResult.yes("Could not open Bluetooth settings."); }
        }
        if (containsAny(text, "airplane mode", "flight mode", "airplane settings")) {
            try { startActivity(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); return ActionResult.yes("Airplane mode settings are open. Confirm the change there."); }
            catch (Exception e) { return ActionResult.yes("Could not open airplane mode settings."); }
        }
        if (containsAny(text, "sound settings", "ringer settings", "ringtone settings", "open sound settings")) {
            try { startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS)); return ActionResult.yes("Sound settings are open."); }
            catch (Exception e) { return ActionResult.yes("Could not open sound settings."); }
        }
        if (containsAny(text, "brightness settings", "change brightness", "display settings", "screen brightness")) {
            try { startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); return ActionResult.yes("Display settings are open. Adjust brightness there."); }
            catch (Exception e) { return ActionResult.yes("Could not open display settings."); }
        }
        if (containsAny(text, "location settings", "open location", "gps settings")) {
            try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return ActionResult.yes("Location settings are open."); }
            catch (Exception e) { return ActionResult.yes("Could not open location settings."); }
        }

        boolean openVerb = containsAny(text, "খোলো", "খুলে দাও", "চালু কর", "ওপেন", "open ", "open it", "launch ", "start ", "চালাও", "খুলুন");
        if (openVerb) {
            String[][] apps = {
                    {"youtube", "ইউটিউব", "com.google.android.youtube"},
                    {"chrome", "ক্রোম", "browser", "ব্রাউজার", "com.android.chrome"},
                    {"whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ", "com.whatsapp"},
                    {"gmail", "জিমেইল", "com.google.android.gm"},
                    {"maps", "গুগল ম্যাপ", "ম্যাপস", "com.google.android.apps.maps"},
                    {"instagram", "ইনস্টাগ্রাম", "com.instagram.android"},
                    {"facebook", "ফেসবুক", "com.facebook.katana"},
                    {"messages", "মেসেজ", "মেসেজেস", "com.google.android.apps.messaging"},
                    {"play store", "প্লে স্টোর", "com.android.vending"},
                    {"settings", "সেটিংস", "সেটিং", "@settings"},
                    {"camera", "ক্যামেরা", "@camera"},
                    {"contacts", "কন্টাক্ট", "কন্টাক্টস", "@contacts"},
                    {"phone", "ফোন", "ডায়ালার", "@phone"}
            };
            for (String[] app : apps) {
                for (int i = 0; i < app.length - 1; i++) {
                    if (text.contains(app[i])) {
                        String id = app[app.length - 1];
                        boolean ok = id.startsWith("@") ? openSystemApp(id.substring(1)) : openPackage(id);
                        return ActionResult.yes(ok ? app[0] + " opening." : app[0] + " could not be found on this phone.");
                    }
                }
            }
        }

        if (containsAny(text, "অ্যালার্ম", "alarm", "wake me", "ঘুম থেকে", "অ্যালার্ম দাও")) {
            Matcher time = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|টা|টায়|টায়|তে)?").matcher(text);
            int hour = -1, minute = 0;
            if (time.find()) {
                hour = Integer.parseInt(time.group(1));
                if (time.group(2) != null) minute = Integer.parseInt(time.group(2));
                String suffix = time.group(3) == null ? "" : time.group(3);
                if ("pm".equals(suffix) && hour < 12) hour += 12;
                if ("am".equals(suffix) && hour == 12) hour = 0;
                if ((text.contains("বিকাল") || text.contains("সন্ধ্যা") || text.contains("রাত")) && hour < 12) hour += 12;
                if (hour >= 0 && hour <= 23 && minute <= 59) {
                    try {
                        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                                .putExtra(AlarmClock.EXTRA_MESSAGE, "Natuu alarm")
                                .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
                        startActivity(intent);
                        return ActionResult.yes(String.format(Locale.getDefault(), "%02d:%02d alarm setup is open.", hour, minute));
                    } catch (Exception ignored) { }
                }
            }
            try { startActivity(new Intent(AlarmClock.ACTION_SET_ALARM)); return ActionResult.yes("The Clock app alarm screen is open."); }
            catch (Exception e) { return ActionResult.yes("The Clock app was not found on this phone."); }
        }

        boolean reminderIntent = containsAny(text, "remind", "reminder", "মনে কর", "মনে করিয়ে", "মনে করিও", "রিমাইন্ডার", "timer", "টাইমার", "প্রতিদিন");
        if (reminderIntent) {
            Matcher duration = Pattern.compile("(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|সেকেন্ড|মিনিট|ঘণ্টা|ঘন্টা|দিন)").matcher(text);
            if (duration.find()) {
                long amount = Long.parseLong(duration.group(1));
                String unit = duration.group(2);
                long multiplier;
                if (unit.startsWith("s") || unit.equals("সেকেন্ড")) multiplier = 1000L;
                else if (unit.startsWith("m") || unit.equals("মিনিট")) multiplier = 60_000L;
                else if (unit.startsWith("h") || unit.equals("ঘণ্টা") || unit.equals("ঘন্টা")) multiplier = 3_600_000L;
                else multiplier = 86_400_000L;
                if (amount > 0 && amount <= 365 && amount * multiplier > 0) {
                    String title = makeReminderTitle(raw);
                    long at = System.currentTimeMillis() + amount * multiplier;
                    addReminder(title, at, false);
                    return ActionResult.yes(String.format(Locale.getDefault(), "%s — %d %s reminder has been set.", title, amount, unit));
                }
            }
            if (text.contains("প্রতিদিন") || text.contains("every day") || text.contains("daily")) {
                Matcher tm = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|টা|টায়|তে)?").matcher(text);
                if (tm.find()) {
                    int hour = Integer.parseInt(tm.group(1));
                    int min = tm.group(2) == null ? 0 : Integer.parseInt(tm.group(2));
                    String suffix = tm.group(3) == null ? "" : tm.group(3);
                    if ("pm".equals(suffix) && hour < 12) hour += 12;
                    if ((text.contains("বিকাল") || text.contains("সন্ধ্যা") || text.contains("রাত")) && hour < 12) hour += 12;
                    if (hour >= 0 && hour <= 23 && min >= 0 && min < 60) {
                        java.util.Calendar cal = java.util.Calendar.getInstance();
                        cal.set(java.util.Calendar.HOUR_OF_DAY, hour); cal.set(java.util.Calendar.MINUTE, min); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0);
                        if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
                        String title = makeReminderTitle(raw);
                        addReminder(title, cal.getTimeInMillis(), true);
                        return ActionResult.yes(String.format(Locale.getDefault(), "Daily reminder set for %02d:%02d.", hour, min));
                    }
                }
            }
            return ActionResult.yes("Try a reminder such as “Remind me to drink water in 10 minutes.”");
        }

        boolean callIntent = containsAny(text, "call ", "কল কর", "কল করো", "ফোন কর", "ফোন করো", "ফোন দাও", "ডায়াল", "dial ", "কল দাও", "call my");
        if (callIntent) {
            Matcher number = Pattern.compile("(\\+?\\d[\\d\\s().-]{5,}\\d)").matcher(latinDigits(raw));
            if (number.find()) {
                String phone = number.group(1).replaceAll("[^+\\d]", "");
                try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)))); return ActionResult.yes("The dialer is open. Review the number and tap Call when ready."); }
                catch (Exception e) { return ActionResult.yes("Could not open the dialer."); }
            }
            String name = raw.replaceAll("(?i)call|কল করো?|ফোন করো?|ফোন দাও|ডায়াল|কল দাও|আমাকে|দাও|please", " ").trim();
            name = name.replaceAll("^(আমার|to)\\s*", "").trim();
            if (name.length() > 1) {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    pendingCallName = name;
                    mainHandler.post(() -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS));
                    return ActionResult.yes("Contacts permission is needed to search for this person. If you allow it, I will look up the contact and open the dialer.");
                }
                String matched = callContactByName(name);
                if (matched != null) return ActionResult.yes("" + matched + " is ready in the dialer. Verify the number and tap Call.");
                return ActionResult.yes("Could not find “" + name + "” in your contacts. You can say the phone number instead.");
            }
            return ActionResult.yes("Who should I call? Say a contact name or phone number.");
        }
        if (containsAny(text, "message ", "send a text", "send text", "sms ", "text ", "মেসেজ পাঠাও", "এসএমএস")) {
            Matcher number = Pattern.compile("(\\+?\\d[\\d\\s().-]{5,}\\d)").matcher(latinDigits(raw));
            if (number.find()) {
                String phone = number.group(1).replaceAll("[^+\\d]", "");
                try { startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)))); return ActionResult.yes("Messages is open. Write the message and tap Send yourself."); }
                catch (Exception e) { return ActionResult.yes("Could not open Messages."); }
            }
        }
        if (containsAny(text, "turn on bluetooth", "ব্লুটুথ চালু", "bluetooth settings", "ব্লুটুথ সেটিং")) {
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return ActionResult.yes("Bluetooth settings are open. Turn Bluetooth on there."); }
            catch (Exception e) { return ActionResult.yes("Could not open Bluetooth settings."); }
        }
        if (containsAny(text, "take a photo", "ছবি তোলো", "ক্যামেরা খোলো")) {
            try { startActivity(new Intent(MediaStore.ACTION_IMAGE_CAPTURE)); return ActionResult.yes("The camera is open."); }
            catch (Exception e) { return ActionResult.yes("Could not open the camera."); }
        }
        return ActionResult.no();
    }

    private boolean openPackage(String packageName) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean openSystemApp(String which) {
        try {
            Intent intent;
            switch (which) {
                case "settings": intent = new Intent(Settings.ACTION_SETTINGS); break;
                case "camera": intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE); break;
                case "contacts": intent = new Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI); break;
                case "phone": intent = new Intent(Intent.ACTION_DIAL); break;
                default: return false;
            }
            startActivity(intent); return true;
        } catch (Exception e) { return false; }
    }

    private String callContactByName(String targetName) {
        ContentResolver resolver = getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            if (cursor == null) return null;
            String normalizedTarget = targetName.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
            while (cursor.moveToNext()) {
                String name = cursor.getString(0); String number = cursor.getString(1);
                if (name == null || number == null) continue;
                String normalizedName = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
                if (normalizedName.contains(normalizedTarget) || normalizedTarget.contains(normalizedName)) {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
                    return name;
                }
            }
        } catch (Exception ignored) {
        } finally { if (cursor != null) cursor.close(); }
        return null;
    }

    private String makeReminderTitle(String raw) {
        String cleaned = raw.trim().replaceAll("(?i)remind me|reminder|please|natuu", "").trim();
        if (cleaned.isEmpty()) cleaned = "Your reminder";
        if (cleaned.length() > 90) cleaned = cleaned.substring(0, 90);
        return cleaned;
    }

    private void addReminder(String title, long triggerAt, boolean daily) {
        SharedPreferences prefs = getSharedPreferences("natuu_reminders", MODE_PRIVATE);
        String json = prefs.getString("items", "[]");
        JSONArray items;
        try { items = new JSONArray(json); } catch (JSONException e) { items = new JSONArray(); }
        long id = prefs.getLong("next_id", 1L);
        prefs.edit().putLong("next_id", id + 1L).apply();
        JSONObject item = new JSONObject();
        try {
            item.put("id", id); item.put("title", title); item.put("at", triggerAt); item.put("daily", daily);
            items.put(item); prefs.edit().putString("items", items.toString()).apply();
        } catch (JSONException ignored) { }
        scheduleAlarm(this, id, title, triggerAt, daily);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            mainHandler.post(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS));
        }
    }

    static void scheduleAlarm(Context context, long id, String title, long triggerAt, boolean daily) {
        AlarmManager manager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (manager == null) return;
        Intent intent = new Intent(context, ReminderReceiver.class).putExtra("id", id).putExtra("title", title).putExtra("daily", daily);
        PendingIntent pending = PendingIntent.getBroadcast(context, (int) id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (daily) {
            manager.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAt, AlarmManager.INTERVAL_DAY, pending);
        } else if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        } else {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        }
    }

    private String askOpenRouter(String apiKey, String userText) throws Exception {
        HttpURLConnection connection = null;
        try {
            String model = getSharedPreferences(PREFS, MODE_PRIVATE).getString("model", MODEL_DEFAULT);
            // Use the OpenRouter free-model router. The user can change this safely in a future UI update.
            if (!"openrouter/free".equals(model)) model = MODEL_DEFAULT;
            URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(18000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("X-Title", "Natuu Android Assistant");

            String systemPrompt = SYSTEM_PROMPT;
            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", systemPrompt));
            JSONArray history = getRecentChatHistory();
            int firstHistoryIndex = Math.max(0, history.length() - 14);
            for (int i = firstHistoryIndex; i < history.length(); i++) {
                JSONObject turn = history.optJSONObject(i);
                if (turn != null && ("user".equals(turn.optString("role")) || "assistant".equals(turn.optString("role")))) {
                    messages.put(turn);
                }
            }
            messages.put(new JSONObject().put("role", "user").put("content", userText));
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("temperature", 0.8)
                    .put("max_tokens", MAX_REPLY_TOKENS);

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) { os.write(bytes); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder response = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }
            }
            JSONObject json = new JSONObject(response.toString());
            if (status < 200 || status >= 300) {
                String error = "OpenRouter request failed";
                JSONObject errorObj = json.optJSONObject("error");
                if (errorObj != null) error = errorObj.optString("message", error);
                if (status == 401 || status == 403) throw new Exception("Check your OpenRouter API key or access (" + status + "): " + error);
                if (status == 402) throw new Exception("Check your OpenRouter account or credits (" + status + "): " + error);
                if (status == 429) throw new Exception("The free-model request limit may be reached or the provider may be busy (429). Try again later: " + error);
                throw new Exception("OpenRouter API error (" + status + "): " + error);
            }
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new Exception("The free AI model returned no answer. Please try again.");
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) throw new Exception("The AI response format was not recognized. Please try again.");
            Object content = message.opt("content");
            StringBuilder answer = new StringBuilder();
            if (content instanceof String) {
                answer.append((String) content);
            } else if (content instanceof JSONArray) {
                JSONArray parts = (JSONArray) content;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null) {
                        String text = part.optString("text", "");
                        if (!text.isEmpty()) {
                            if (answer.length() > 0) answer.append("\n");
                            answer.append(text);
                        }
                    }
                }
            }
            if (answer.toString().trim().isEmpty()) throw new Exception("The AI returned an empty response. Please try again.");
            return answer.toString().trim();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void postReply(String userText, String reply, String kind, boolean speak) {
        mainHandler.post(() -> {
            if (webView != null) {
                String js = "window.onAssistantReply && window.onAssistantReply(" + JSONObject.quote(userText) + "," + JSONObject.quote(reply) + "," + JSONObject.quote(kind) + ");";
                webView.evaluateJavascript(js, null);
            }
            if (speak && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("speak", true)) {
                // The cloned voice pack is used for every spoken reply when it is set up,
                // including offline-AI replies (it needs internet; it falls back to the
                // phone's TTS voice if synthesis fails). Without a clone, offline answers
                // use the phone's installed TTS voice directly.
                boolean customReady = !SecureStore.getVoiceApiKey(this).isEmpty() && !SecureStore.getVoiceId(this).isEmpty();
                boolean forceSystemTts = "offline".equals(kind) && !customReady;
                speakForReply(reply, forceSystemTts);
            }
            sendStatus("idle");
        });
    }

    private void speak(String text) {
        speak(text, false);
    }

    private void speak(String text, boolean forceSystemTts) {
        if (text == null || text.trim().isEmpty()) return;
        if (forceSystemTts) {
            speakWithSystemTts(text);
            return;
        }
        String voiceKey = SecureStore.getVoiceApiKey(this);
        String voiceId = SecureStore.getVoiceId(this);
        if (!voiceKey.isEmpty() && !voiceId.isEmpty()) {
            executor.execute(() -> {
                try {
                    byte[] audio = synthesizeCustomVoice(voiceKey, voiceId, text);
                    playCustomVoiceAudio(audio);
                } catch (Exception e) {
                    speakWithSystemTts(text);
                    String detail = e.getMessage() == null ? "unknown error" : e.getMessage();
                    if (detail.length() > 220) detail = detail.substring(0, 220) + "…";
                    sendNativeEvent("Custom voice failed: " + detail + ". The phone's TTS is being used if it supports this language. Check the ElevenLabs key, voice-clone setup, plan and quota.");
                }
            });
            return;
        }
        speakWithSystemTts(text);
    }

    private void speakWithSystemTts(String text) {
        if (!ttsReady || textToSpeech == null || text == null || text.trim().isEmpty()) return;
        mainHandler.post(() -> {
            try {
                boolean bangla = containsBanglaScript(text);
                Locale targetLocale = bangla ? new Locale("bn", "BD") : Locale.US;
                int languageResult = textToSpeech.setLanguage(targetLocale);
                if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Voice targetVoice = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        for (Voice voice : textToSpeech.getVoices()) {
                            Locale voiceLocale = voice.getLocale();
                            if (voiceLocale != null && (bangla ? "bn" : "en").equalsIgnoreCase(voiceLocale.getLanguage())) {
                                if (targetVoice == null || (!voice.isNetworkConnectionRequired() && targetVoice.isNetworkConnectionRequired())) targetVoice = voice;
                            }
                        }
                    }
                    if (targetVoice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        textToSpeech.setVoice(targetVoice);
                    } else if (bangla) {
                        // Avoid speaking Bengali phonetics through an English voice; it sounds
                        // distorted. Keep the text visible and clearly explain how to add Bangla TTS.
                        sendNativeEvent("এই ফোনে Bangla text-to-speech voice data পাওয়া যায়নি, তাই বিকৃত voice চালানো হয়নি। Settings → TTS voice data থেকে Bangla voice install করুন, অথবা internet ও quota-সহ Natuu custom voice ব্যবহার করুন।");
                        return;
                    }
                }
                if (Build.VERSION.SDK_INT >= 21) textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "natuu-solo-" + System.currentTimeMillis());
                else textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            } catch (Exception ignored) { }
        });
    }

    private static boolean containsBanglaScript(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u0980' && c <= '\u09FF') return true;
        }
        return false;
    }

    // =========================================================================
    // Fast streamed replies (v1.5)
    //
    // The model's answer streams back while each finished sentence is
    // synthesized with the cloned voice pack (or the phone's TTS) and spoken
    // immediately. After the last sentence finishes playing, hands-free mode
    // re-opens the microphone so the conversation keeps going.
    // =========================================================================

    /** Index of the next sentence boundary ('।', '.', '?', '!', newline) at or after `from`. */
    private static int findNextSentenceBoundary(CharSequence text, int from) {
        for (int i = Math.max(0, from); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0964' || c == '\u0965' || c == '\n' || c == '?' || c == '!') return i;
            if (c == '.' && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)))) return i;
        }
        return -1;
    }

    private static boolean hasLetter(CharSequence text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) return true;
        return false;
    }

    private String chatModelOrDefault() {
        String model = getSharedPreferences(PREFS, MODE_PRIVATE).getString("model", MODEL_DEFAULT);
        return "openrouter/free".equals(model) ? model : MODEL_DEFAULT;
    }

    private JSONArray buildChatMessages(String userText) throws JSONException {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
        JSONArray history = getRecentChatHistory();
        int firstHistoryIndex = Math.max(0, history.length() - 14);
        for (int i = firstHistoryIndex; i < history.length(); i++) {
            JSONObject turn = history.optJSONObject(i);
            if (turn != null && ("user".equals(turn.optString("role")) || "assistant".equals(turn.optString("role")))) messages.put(turn);
        }
        messages.put(new JSONObject().put("role", "user").put("content", userText));
        return messages;
    }

    private Exception explainOpenRouterFailure(int status, String raw) {
        String error = "OpenRouter request failed";
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONObject json = new JSONObject(raw);
                JSONObject errorObj = json.optJSONObject("error");
                error = errorObj != null ? errorObj.optString("message", error) : raw.trim();
            } catch (JSONException ignored) { error = raw.trim(); }
        }
        if (error.length() > 320) error = error.substring(0, 320) + "…";
        if (status == 401 || status == 403) return new Exception("Check your OpenRouter API key or access (" + status + "): " + error);
        if (status == 402) return new Exception("Check your OpenRouter account or credits (" + status + "): " + error);
        if (status == 429) return new Exception("The free-model request limit may be reached or the provider is busy (429). Try again shortly: " + error);
        return new Exception("OpenRouter API error (" + status + "): " + error);
    }

    /** Streams the reply and speaks sentences while they arrive. Falls back to the older
     *  non-streamed request and then to the offline model when nothing usable arrives. */
    private void streamChatReply(String apiKey, String userText, String mode, boolean offlineReady) {
        startReplyVoice();
        StringBuilder full = new StringBuilder();
        int spokenFrom = 0;
        int lastUiLen = 0;
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("X-Title", "Natuu Android Assistant");
            JSONObject body = new JSONObject()
                    .put("model", chatModelOrDefault())
                    .put("messages", buildChatMessages(userText))
                    .put("temperature", 0.8)
                    .put("max_tokens", MAX_REPLY_TOKENS)
                    .put("stream", true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) { os.write(bytes); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream errorStream = connection.getErrorStream();
                throw explainOpenRouterFailure(status, errorStream == null ? "" : readStream(errorStream));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || !line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    if ("[DONE]".equals(payload)) break;
                    JSONObject event;
                    try { event = new JSONObject(payload); } catch (JSONException ignored) { continue; }
                    JSONObject error = event.optJSONObject("error");
                    if (error != null) throw new Exception(error.optString("message", "The AI stream failed."));
                    JSONArray choices = event.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject message = choices.getJSONObject(0).optJSONObject("delta");
                    if (message == null) continue;
                    String piece = message.optString("content", "");
                    if (piece == null || piece.isEmpty()) continue;
                    full.append(piece);
                    int end = findNextSentenceBoundary(full, spokenFrom);
                    while (end >= 0) {
                        String sentence = full.substring(spokenFrom, end + 1).trim();
                        spokenFrom = end + 1;
                        if (!sentence.isEmpty() && hasLetter(sentence)) deliverReplySentence(sentence);
                        end = findNextSentenceBoundary(full, spokenFrom);
                    }
                    if (full.length() - lastUiLen >= 12) {
                        postPartialReply(userText, full.toString());
                        lastUiLen = full.length();
                    }
                }
            }
            if (spokenFrom < full.length()) {
                String tail = full.substring(spokenFrom).trim();
                spokenFrom = full.length();
                if (!tail.isEmpty() && hasLetter(tail)) deliverReplySentence(tail);
            }
            if (full.toString().trim().isEmpty()) throw new Exception("The AI returned an empty response. Please try again.");
            postPartialReply(userText, full.toString());
            rememberChatTurn(userText, full.toString().trim());
            postReply(userText, full.toString().trim(), "ai", false);
            finishVoiceSource();
        } catch (Exception streamError) {
            if (full.toString().trim().length() > 0) {
                // A usable partial answer arrived before the connection dropped.
                String shown = full.toString().trim();
                rememberChatTurn(userText, shown);
                postReply(userText, shown + "\n(পুরোটা আসেনি — internet connection কেটে গিয়েছিল।)", "ai", false);
                finishVoiceSource();
                sendNativeEvent("Reply চলাকালে connection খোলা যায়নি; এতক্ষণের অংশটুকু দেখানো হলো। (" + abbreviate(streamError.getMessage(), 160) + ")");
                return;
            }
            // Fall back to the older non-streamed request, then offline AI.
            try {
                String response = askOpenRouter(apiKey, userText);
                rememberChatTurn(userText, response);
                postReply(userText, response, "ai", true);
                return;
            } catch (Exception ignored) { }
            if ("auto".equals(mode) && offlineReady) {
                sendOfflineReply(userText);
                return;
            }
            String msg = streamError.getMessage() == null ? "Could not connect to OpenRouter." : streamError.getMessage();
            postReply(userText, "Sorry, " + msg, "error", false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String abbreviate(String value, int limit) {
        if (value == null) return "unknown";
        return value.length() > limit ? value.substring(0, limit) + "…" : value;
    }

    private void postPartialReply(String userText, String partial) {
        mainHandler.post(() -> {
            if (webView == null) return;
            String js = "window.onAssistantPartial && window.onAssistantPartial(" + JSONObject.quote(userText) + "," + JSONObject.quote(partial) + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    // ---------- streamed voice queue (main-thread state machine) ----------

    /** Called from the streaming thread before the first sentence; arms the voice pipeline. */
    private void startReplyVoice() {
        mainHandler.post(() -> {
            streamStopped = false;
            customVoiceBroken.set(false);
            if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("speak", true)) {
                replyVoiceActive = false;
                voiceSourceFinished = true;
                return;
            }
            clearVoiceQueueState();
            systemFlushPending = false;
            replyVoiceActive = true;
            voiceSourceFinished = false;
        });
    }

    /** Called when the reply text stream ends; the queue drains and the mic re-opens. */
    private void finishVoiceSource() {
        mainHandler.post(() -> {
            voiceSourceFinished = true;
            if (replyVoiceActive) releaseVoiceDone();
            else onReplyVoiceDone();
        });
    }

    private void clearVoiceQueueState() {
        ttsReplyPending.set(0);
        File leftover;
        while ((leftover = playbackQueue.pollFirst()) != null) {
            try { leftover.delete(); } catch (Exception ignored) { }
        }
        if (streamingPlayer != null) {
            try { streamingPlayer.stop(); } catch (Exception ignored) { }
            try { streamingPlayer.release(); } catch (Exception ignored) { }
            streamingPlayer = null;
        }
        customPlaying = false;
    }

    private void stopAllSpokenAudio() {
        streamStopped = true;
        voiceSourceFinished = true;
        replyVoiceActive = false;
        legacyReplySoundPending = false;
        if (textToSpeech != null) { try { textToSpeech.stop(); } catch (Exception ignored) { } }
        ttsReplyPending.set(0);
        File leftover;
        while ((leftover = playbackQueue.pollFirst()) != null) {
            try { leftover.delete(); } catch (Exception ignored) { }
        }
        if (streamingPlayer != null) {
            try { streamingPlayer.stop(); } catch (Exception ignored) { }
            try { streamingPlayer.release(); } catch (Exception ignored) { }
            streamingPlayer = null;
        }
        customPlaying = false;
        if (customVoicePlayer != null) {
            try { customVoicePlayer.stop(); } catch (Exception ignored) { }
            try { customVoicePlayer.release(); } catch (Exception ignored) { }
            customVoicePlayer = null;
        }
    }

    /** Called on the main thread whenever something finished; ends the reply loop when all is quiet. */
    private void releaseVoiceDone() {
        if (customPlaying) return;
        if (!playbackQueue.isEmpty()) return;
        if (ttsReplyPending.get() > 0) return;
        if (!voiceSourceFinished) return;
        if (!replyVoiceActive) return;
        replyVoiceActive = false;
        onReplyVoiceDone();
    }

    /** The streamed reply finished being spoken — reset the mic for the next question. */
    private void onReplyVoiceDone() {
        // Runs on the main thread.
        sendStatus("idle");
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_AUTO_LISTEN, true)) return;
        if (!activityInForeground) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (micAutoGuard) return;
        micAutoGuard = true;
        mainHandler.postDelayed(() -> {
            micAutoGuard = false;
            if (!activityInForeground) return;
            if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_AUTO_LISTEN, true)) return;
            WakeWordService.pause(MainActivity.this);
            startSpeechRecognition();
        }, 650);
    }

    /** Speaks the next streamed sentence. Prefers the cloned voice pack; falls back to TTS. */
    private void deliverReplySentence(final String sentence) {
        if (sentence == null || sentence.trim().isEmpty() || streamStopped) return;
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("speak", true)) return;
        boolean customReady = !SecureStore.getVoiceApiKey(this).isEmpty() && !SecureStore.getVoiceId(this).isEmpty();
        if (!customReady) {
            speakSentenceSystem(sentence);
            return;
        }
        voiceSynthExecutor.execute(() -> {
            if (streamStopped) return;
            try {
                String voiceKey = SecureStore.getVoiceApiKey(MainActivity.this);
                String voiceId = SecureStore.getVoiceId(MainActivity.this);
                byte[] audio = synthesizeCustomVoice(voiceKey, voiceId, sentence);
                if (streamStopped) return;
                final File file = new File(getCacheDir(), "natuu-stream-" + System.nanoTime() + ".mp3");
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(audio); }
                mainHandler.post(() -> {
                    if (streamStopped) {
                        try { file.delete(); } catch (Exception ignored) { }
                        return;
                    }
                    playbackQueue.add(file);
                    kickStreamPlayback();
                });
            } catch (Exception e) {
                if (customVoiceBroken.compareAndSet(false, true)) {
                    sendNativeEvent("Voice pack voice synthesis এরপর ও চালানো যাচ্ছে না (" + abbreviate(e.getMessage(), 150) + ") — বাকিটা ফোনের TTS voice-এ বলছি।");
                }
                speakSentenceSystem(sentence);
            }
        });
    }

    private void speakSentenceSystem(final String sentence) {
        ttsReplyPending.incrementAndGet();
        mainHandler.post(() -> {
            if (streamStopped) {
                ttsReplyPending.updateAndGet(v -> v > 0 ? v - 1 : 0);
                return;
            }
            if (!ttsReady || textToSpeech == null) {
                ttsReplyPending.updateAndGet(v -> v > 0 ? v - 1 : 0);
                releaseVoiceDone();
                return;
            }
            boolean first = !systemFlushPending;
            systemFlushPending = true;
            try {
                textToSpeech.speak(sentence, first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "natuu-reply-" + System.currentTimeMillis());
            } catch (Exception e) {
                ttsReplyPending.updateAndGet(v -> v > 0 ? v - 1 : 0);
                releaseVoiceDone();
            }
        });
    }

    /** Routes TTS engine callbacks used by the streaming pipeline. */
    private void handleTtsEnd(String utteranceId) {
        if (utteranceId == null) return;
        if (utteranceId.startsWith("natuu-reply-")) {
            ttsReplyPending.updateAndGet(v -> v > 0 ? v - 1 : 0);
            mainHandler.post(this::releaseVoiceDone);
        } else if (utteranceId.startsWith("natuu-solo-")) {
            legacyVoiceSoundFinished();
        }
    }

    /** Starts playing the next synthesized sentence file, if any is ready. */
    private void kickStreamPlayback() {
        // main thread only
        if (customPlaying) return;
        File next = playbackQueue.pollFirst();
        if (next == null) {
            if (replyVoiceActive) releaseVoiceDone();
            return;
        }
        if (streamStopped) {
            try { next.delete(); } catch (Exception ignored) { }
            kickStreamPlayback();
            return;
        }
        if (!replyVoiceActive) {
            try { next.delete(); } catch (Exception ignored) { }
            kickStreamPlayback();
            return;
        }
        customPlaying = true;
        try {
            MediaPlayer player = new MediaPlayer();
            streamingPlayer = player;
            player.setDataSource(next.getAbsolutePath());
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                if (streamingPlayer == mp) streamingPlayer = null;
                try { mp.release(); } catch (Exception ignored) { }
                customPlaying = false;
                try { next.delete(); } catch (Exception ignored) { }
                releaseVoiceDone();
                kickStreamPlayback();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (streamingPlayer == mp) streamingPlayer = null;
                try { mp.release(); } catch (Exception ignored) { }
                customPlaying = false;
                try { next.delete(); } catch (Exception ignored) { }
                releaseVoiceDone();
                kickStreamPlayback();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            customPlaying = false;
            if (streamingPlayer != null) {
                try { streamingPlayer.release(); } catch (Exception ignored) { }
                streamingPlayer = null;
            }
            try { next.delete(); } catch (Exception ignored) { }
            releaseVoiceDone();
            kickStreamPlayback();
        }
    }

    // ===================== end of streamed reply engine =====================

    private byte[] synthesizeCustomVoice(String apiKey, String voiceId, String text) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://api.elevenlabs.io/v1/text-to-speech/" + Uri.encode(voiceId) + "?output_format=mp3_44100_128");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setDoOutput(true);
            connection.setRequestProperty("xi-api-key", apiKey);
            connection.setRequestProperty("Accept", "audio/mpeg");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject body = new JSONObject();
            body.put("text", text.length() > 4500 ? text.substring(0, 4500) : text);
            boolean bangla = containsBanglaScript(text);
            // Multilingual v2 does not list Bengali as a supported language. Eleven v3 supports
            // Bengali and English; using one model for both keeps the custom voice more consistent.
            body.put("model_id", "eleven_v3");
            body.put("language_code", bangla ? "bn" : "en");
            // Eleven v3 supports stability, but not Similarity or Speaker Boost settings.
            // Send only the supported field so a validation error cannot force system-TTS fallback.
            body.put("voice_settings", new JSONObject().put("stability", 0.5));
            byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) { os.write(requestBytes); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream errorStream = connection.getErrorStream();
                String error = errorStream == null ? "Voice synthesis failed" : readStream(errorStream);
                throw new Exception("Custom voice API error (" + status + "): " + error);
            }
            try (InputStream in = connection.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                byte[] bytes = out.toByteArray();
                if (bytes.length < 1000) throw new Exception("Voice audio response was too small");
                return bytes;
            }
        } finally { if (connection != null) connection.disconnect(); }
    }

    private String readStream(InputStream stream) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) text.append(line);
            return text.toString();
        }
    }

    private void playCustomVoiceAudio(byte[] audio) throws Exception {
        File file = new File(getCacheDir(), "natuu-voice-" + System.currentTimeMillis() + ".mp3");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(audio); }
        mainHandler.post(() -> {
            try {
                if (customVoicePlayer != null) {
                    try { customVoicePlayer.stop(); } catch (Exception ignored) { }
                    customVoicePlayer.release(); customVoicePlayer = null;
                }
                MediaPlayer player = new MediaPlayer();
                customVoicePlayer = player;
                player.setDataSource(file.getAbsolutePath());
                player.setOnPreparedListener(MediaPlayer::start);
                player.setOnCompletionListener(mp -> {
                    try { mp.release(); } catch (Exception ignored) { }
                    if (customVoicePlayer == mp) customVoicePlayer = null;
                    try { file.delete(); } catch (Exception ignored) { }
                    legacyVoiceSoundFinished();
                });
                player.setOnErrorListener((mp, what, extra) -> {
                    try { mp.release(); } catch (Exception ignored) { }
                    if (customVoicePlayer == mp) customVoicePlayer = null;
                    try { file.delete(); } catch (Exception ignored) { }
                    legacyVoiceSoundFinished();
                    sendNativeEvent("Could not play the custom voice audio. Please try again.");
                    return true;
                });
                player.prepareAsync();
            } catch (Exception e) {
                try { if (customVoicePlayer != null) customVoicePlayer.release(); } catch (Exception ignored) { }
                customVoicePlayer = null;
                try { file.delete(); } catch (Exception ignored) { }
                legacyVoiceSoundFinished();
                sendNativeEvent("Could not start the custom voice.");
            }
        });
    }

    /** Called when a non-streamed (single-shot) reply sound finishes playing. */
    private void legacyVoiceSoundFinished() {
        mainHandler.post(() -> {
            if (legacyReplySoundPending) {
                legacyReplySoundPending = false;
                onReplyVoiceDone();
            }
        });
    }

    /** Marks the next spoken reply (non-streamed) so its playback end can re-open the mic. */
    private void speakForReply(String text, boolean forceSystemTts) {
        legacyReplySoundPending = true;
        streamStopped = false;
        speak(text, forceSystemTts);
    }

    private void createCustomVoiceFromReference() {
        String apiKey = SecureStore.getVoiceApiKey(this);
        if (apiKey.isEmpty()) {
            sendVoiceSetupResult(false, "Save an ElevenLabs API key first. It is separate from OpenRouter and is required for custom voice features.");
            return;
        }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String boundary = "----NatuuBoundary" + UUID.randomUUID().toString().replace("-", "");
                URL url = new URL("https://api.elevenlabs.io/v1/voices/add");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(90000);
                connection.setDoOutput(true);
                connection.setRequestProperty("xi-api-key", apiKey);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try (OutputStream out = connection.getOutputStream()) {
                    writeFormField(out, boundary, "name", "Natuu Personal Voice");
                    writeFormField(out, boundary, "description", "Personal assistant voice created from five user-provided voice reference samples.");
                    String[] sampleAssets = new String[] {
                        "natuu_voice_sample_01.mp3",
                        "natuu_voice_sample_02.mp3",
                        "natuu_voice_sample_03.mp3",
                        "natuu_voice_sample_04.mp3",
                        "natuu_voice_reference.mp3"
                    };
                    byte[] buffer = new byte[8192];
                    for (String sampleName : sampleAssets) {
                        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[]\"; filename=\"" + sampleName + "\"\r\nContent-Type: audio/mpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        try (InputStream voice = getAssets().open(sampleName)) {
                            int count;
                            while ((count = voice.read(buffer)) != -1) out.write(buffer, 0, count);
                        }
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                int status = connection.getResponseCode();
                InputStream responseStream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                String response = responseStream == null ? "" : readStream(responseStream);
                JSONObject json;
                try { json = new JSONObject(response); } catch (JSONException e) { json = new JSONObject(); }
                if (status < 200 || status >= 300) {
                    String message = json.optString("detail", json.optString("message", "Check the API key, quota, or voice-cloning access."));
                    if (json.optJSONObject("detail") != null) message = json.optJSONObject("detail").optString("message", message);
                    throw new Exception("Voice clone API (" + status + "): " + message);
                }
                String voiceId = json.optString("voice_id", "");
                if (voiceId.isEmpty()) throw new Exception("The voice API did not return a voice ID.");
                SecureStore.saveVoiceId(this, voiceId);
                sendVoiceSetupResult(true, "Your custom voice has been created and connected to Natuu. Spoken replies will try to use this voice.");
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Could not create the custom voice." : e.getMessage();
                sendVoiceSetupResult(false, message);
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void writeFormField(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void sendVoiceSetupResult(boolean success, String message) {
        mainHandler.post(() -> {
            if (webView != null) {
                String js = "window.onVoiceSetupResult && window.onVoiceSetupResult(" + success + "," + JSONObject.quote(message) + ");";
                webView.evaluateJavascript(js, null);
                webView.evaluateJavascript("window.onNatuuState && window.onNatuuState(" + getStateJson() + ");", null);
            }
        });
    }

    private void sendOfflineModelResult(boolean success, String message) {
        mainHandler.post(() -> {
            if (webView != null) {
                String js = "window.onOfflineModelResult && window.onOfflineModelResult(" + success + "," + JSONObject.quote(message) + ");";
                webView.evaluateJavascript(js, null);
                webView.evaluateJavascript("window.onNatuuState && window.onNatuuState(" + getStateJson() + ");", null);
            }
        });
    }

    private void sendStatus(String status) {
        mainHandler.post(() -> { if (webView != null) webView.evaluateJavascript("window.onNatuuStatus && window.onNatuuStatus(" + JSONObject.quote(status) + ");", null); });
    }

    private void sendNativeEvent(String message) {
        mainHandler.post(() -> { if (webView != null) webView.evaluateJavascript("window.onNatuuNotice && window.onNatuuNotice(" + JSONObject.quote(message) + ");", null); });
    }

    private JSONObject getStateJson() {
        JSONObject state = new JSONObject();
        try {
            boolean onlineConfigured = !SecureStore.getApiKey(this).isEmpty();
            boolean offlineReady = offlineModelManager != null && offlineModelManager.isModelReady();
            state.put("onlineConfigured", onlineConfigured);
            state.put("offlineReady", offlineReady);
            state.put("configured", onlineConfigured || offlineReady);
            state.put("wakeWordEnabled", WakeWordService.isEnabled(this));
            state.put("chatMode", getSharedPreferences(PREFS, MODE_PRIVATE).getString("chat_mode", "auto"));
            String selectedModel = getSharedPreferences(PREFS, MODE_PRIVATE).getString("model", MODEL_DEFAULT);
            if (!"openrouter/free".equals(selectedModel)) selectedModel = MODEL_DEFAULT;
            state.put("model", selectedModel);
            state.put("speak", getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("speak", true));
            state.put("autoListen", getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_AUTO_LISTEN, true));
            state.put("customVoiceReady", !SecureStore.getVoiceId(this).isEmpty() && !SecureStore.getVoiceApiKey(this).isEmpty());
            state.put("voiceConfigured", !SecureStore.getVoiceApiKey(this).isEmpty());
            state.put("speechLanguage", getSharedPreferences(PREFS, MODE_PRIVATE).getString("speech_language", "auto"));
        } catch (JSONException ignored) { }
        return state;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String latinDigits(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '০' && c <= '৯') result.append((char) ('0' + c - '০'));
            else if (c >= '٠' && c <= '٩') result.append((char) ('0' + c - '٠'));
            else result.append(c);
        }
        return result.toString();
    }

    public class NatuuBridge {
        @JavascriptInterface public String getState() { return getStateJson().toString(); }
        @JavascriptInterface public void startWakeWord() {
            mainHandler.post(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_WAKE_MIC);
                } else {
                    startWakeWordService();
                }
            });
        }
        @JavascriptInterface public void stopWakeWord() {
            mainHandler.post(() -> {
                WakeWordService.stop(MainActivity.this);
                sendNativeEvent("Hey Natu has been disabled.");
                mainHandler.postDelayed(() -> {
                    if (webView != null) webView.evaluateJavascript("window.onNatuuState && window.onNatuuState(" + getStateJson() + ");", null);
                }, 250);
            });
        }
        @JavascriptInterface public boolean saveApiKey(String apiKey) {
            if (apiKey == null || apiKey.trim().length() < 12) return false;
            try { SecureStore.saveApiKey(MainActivity.this, apiKey); return true; }
            catch (Exception e) { return false; }
        }
        @JavascriptInterface public void clearApiKey() { SecureStore.clearApiKey(MainActivity.this); }
        @JavascriptInterface public boolean saveVoiceApiKey(String apiKey) {
            if (apiKey == null || apiKey.trim().length() < 12) return false;
            try { SecureStore.saveVoiceApiKey(MainActivity.this, apiKey); return true; }
            catch (Exception e) { return false; }
        }
        @JavascriptInterface public void clearVoiceApiKey() { SecureStore.clearVoiceApiKey(MainActivity.this); SecureStore.clearVoiceId(MainActivity.this); }
        @JavascriptInterface public void createCustomVoice() { createCustomVoiceFromReference(); }
        @JavascriptInterface public void setModel(String model) {
            if (!"openrouter/free".equals(model)) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("model", model).apply();
        }
        @JavascriptInterface public void setChatMode(String mode) {
            if (!"auto".equals(mode) && !"online".equals(mode) && !"offline".equals(mode)) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("chat_mode", mode).apply();
            mainHandler.post(() -> { if (webView != null) webView.evaluateJavascript("window.onNatuuState && window.onNatuuState(" + getStateJson() + ");", null); });
        }
        @JavascriptInterface public void downloadOfflineModel() {
            if (offlineModelManager == null) {
                sendOfflineModelResult(false, "The offline AI runtime could not start. Reopen the app and try again.");
                return;
            }
            offlineModelManager.downloadModel(new OfflineModelManager.DownloadCallback() {
                @Override public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                    mainHandler.post(() -> {
                        if (webView != null) {
                            String js = "window.onOfflineModelProgress && window.onOfflineModelProgress(" + percent + "," + downloadedBytes + "," + totalBytes + ");";
                            webView.evaluateJavascript(js, null);
                        }
                    });
                }
                @Override public void onComplete(boolean success, String message) {
                    sendOfflineModelResult(success, message);
                }
            });
        }
        @JavascriptInterface public void setSpeakEnabled(boolean enabled) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("speak", enabled).apply();
        }
        @JavascriptInterface public void setSpeechLanguage(String language) {
            if (!"auto".equals(language) && !"bn-BD".equals(language) && !"en-US".equals(language)) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("speech_language", language).apply();
            sendNativeEvent("Voice input language saved: " + ("auto".equals(language) ? "Auto (Bangla + English)" : ("bn-BD".equals(language) ? "Bangla" : "English")) + ".");
        }
        @JavascriptInterface public void downloadSpeechLanguagePack(String language) {
            mainHandler.post(() -> requestSpeechLanguagePack(language));
        }
        @JavascriptInterface public void openSpeechLanguageSettings() {
            mainHandler.post(() -> MainActivity.this.openSpeechLanguageSettings());
        }
        @JavascriptInterface public void openTextToSpeechSettings() {
            mainHandler.post(() -> MainActivity.this.openTextToSpeechSettings());
        }
        @JavascriptInterface public void sendCommand(String text) { processCommand(text); }
        @JavascriptInterface public void requestSpeech() {
            mainHandler.post(() -> {
                WakeWordService.pause(MainActivity.this);
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                else startSpeechRecognition();
            });
        }
        @JavascriptInterface public void stopSpeaking() { mainHandler.post(() -> { stopAllSpokenAudio(); sendStatus("idle"); }); }
        @JavascriptInterface public void testVoice(String text) { speak(text == null ? "Hello, I am Natuu, your personal AI assistant." : text); }
        @JavascriptInterface public void openDeviceSettings() { mainHandler.post(() -> startActivity(new Intent(Settings.ACTION_SETTINGS))); }
        @JavascriptInterface public void setAutoListenEnabled(boolean enabled) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_AUTO_LISTEN, enabled).apply();
            sendNativeEvent(enabled ? "Hands-free mode চালু: প্রতিবার reply শেষ হলে আবার শোনা শুরু হবে।" : "Hands-free mode বন্ধ: কথা বলতে মাইক বাটনে চাপ দিন।");
        }
    }

    @Override public void onConfigurationChanged(Configuration newConfig) { super.onConfigurationChanged(newConfig); }

    @Override protected void onDestroy() {
        if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
        if (speechPackRecognizer != null) { try { speechPackRecognizer.destroy(); } catch (Exception ignored) { } speechPackRecognizer = null; }
        voiceSynthExecutor.shutdownNow();
        if (textToSpeech != null) { textToSpeech.stop(); textToSpeech.shutdown(); textToSpeech = null; }
        if (customVoicePlayer != null) { try { customVoicePlayer.stop(); } catch (Exception ignored) { } try { customVoicePlayer.release(); } catch (Exception ignored) { } customVoicePlayer = null; }
        executor.shutdownNow();
        if (offlineModelManager != null) { offlineModelManager.close(); offlineModelManager = null; }
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
