package com.natuu.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort always-listening wake phrase service for personal testing.
 * It uses Android's installed speech-recognition provider with offline preference
 * where available; Android/provider implementations may still require network access.
 * The service is enabled only by an explicit user action and shows a persistent notification.
 */
public final class WakeWordService extends Service {
    public static final String ACTION_START = "com.natuu.assistant.action.WAKE_START";
    public static final String ACTION_STOP = "com.natuu.assistant.action.WAKE_STOP";
    public static final String ACTION_PAUSE = "com.natuu.assistant.action.WAKE_PAUSE";
    public static final String ACTION_RESUME = "com.natuu.assistant.action.WAKE_RESUME";
    public static final String ACTION_WAKE_TRIGGER = "com.natuu.assistant.action.WAKE_TRIGGER";
    public static final String EXTRA_COMMAND = "com.natuu.assistant.extra.WAKE_COMMAND";

    private static final String PREFS = "natuu_prefs";
    private static final String CHANNEL_ID = "natuu_wakeword";
    private static final int NOTIFICATION_ID = 4207;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean enabled;
    private boolean paused = true;
    private boolean listening;
    private boolean foreground;
    private boolean localRecognitionUnavailable;
    private boolean offlineLanguageWarning;
    private boolean disableAutoLanguageSwitch;
    private boolean wakePendingForNotification;
    private String pendingWakeCommand = "";
    private int nextRecognitionLanguage = 0;
    private boolean destroyed;

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("wake_word_enabled", false);
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, WakeWordService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, WakeWordService.class).setAction(ACTION_STOP);
        try { context.startService(intent); } catch (Exception ignored) { }
    }

    public static void pause(Context context) {
        if (!isEnabled(context)) return;
        Intent intent = new Intent(context, WakeWordService.class).setAction(ACTION_PAUSE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) { }
    }

    public static void resume(Context context) {
        if (!isEnabled(context)) return;
        Intent intent = new Intent(context, WakeWordService.class).setAction(ACTION_RESUME);
        try { context.startService(intent); } catch (Exception ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESUME : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wake_word_enabled", false).apply();
            enabled = false;
            paused = true;
            stopRecognizer();
            if (foreground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
                else stopForeground(true);
                foreground = false;
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        enabled = isEnabled(this);
        if (ACTION_START.equals(action)) {
            enabled = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wake_word_enabled", true).apply();
            paused = false;
            wakePendingForNotification = false;
            pendingWakeCommand = "";
        } else if (ACTION_PAUSE.equals(action)) {
            if (!enabled) return START_NOT_STICKY;
            paused = true;
            stopRecognizer();
        } else if (ACTION_RESUME.equals(action)) {
            if (!enabled) return START_NOT_STICKY;
            paused = false;
            wakePendingForNotification = false;
            pendingWakeCommand = "";
        }

        if (!enabled) return START_NOT_STICKY;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wake_word_enabled", false).apply();
            enabled = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        ensureForeground();
        if (!paused) scheduleListening(250);
        updateNotification();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Hey Natu background listening", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Persistent notification shown while Hey Natu wake-word listening is enabled.");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void ensureForeground() {
        if (foreground) return;
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foreground = true;
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (wakePendingForNotification) {
            openIntent.setAction(ACTION_WAKE_TRIGGER);
            openIntent.putExtra(EXTRA_COMMAND, pendingWakeCommand);
        }
        PendingIntent content = PendingIntent.getActivity(this, 4208, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, WakeWordService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 4209, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        String listeningText;
        if (paused) {
            listeningText = "Wake-word listening is paused while Natuu is open.";
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            listeningText = "Local-only background wake needs Android 12+; use the in-app microphone.";
        } else if (localRecognitionUnavailable) {
            listeningText = "On-device speech service unavailable. Wake listening is paused to save battery and data.";
        } else if (offlineLanguageWarning) {
            listeningText = "A local Bangla/English speech pack may be missing. Install offline packs in Settings.";
        } else {
            listeningText = "Battery saver: on-device listening for “Hey Natu”. Tap Stop to disable.";
        }
        builder.setSmallIcon(R.drawable.ic_natuu)
                .setContentTitle("Hey Natu is enabled")
                .setContentText(listeningText)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Stop", stop);
        return builder.build();
    }

    private void updateNotification() {
        if (!foreground) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void scheduleListening(long delayMs) {
        if (destroyed || !enabled || paused) return;
        mainHandler.removeCallbacks(startListeningRunnable);
        mainHandler.postDelayed(startListeningRunnable, delayMs);
    }

    private final Runnable startListeningRunnable = this::startListeningCycle;

    private void startListeningCycle() {
        if (destroyed || !enabled || paused || listening) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            localRecognitionUnavailable = true;
            updateNotification();
            scheduleListening(60_000);
            return;
        }
        // Do not use the default (potentially cloud-backed) recognizer for an
        // always-on background wake loop. Continuous remote recognition drains
        // battery and data; require Android's on-device recognizer instead.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            stopRecognizer();
            localRecognitionUnavailable = true;
            updateNotification();
            scheduleListening(60_000);
            return;
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(android.os.Bundle params) {
                        listening = true;
                        localRecognitionUnavailable = false;
                        offlineLanguageWarning = false;
                        updateNotification();
                    }
                    @Override public void onBeginningOfSpeech() { }
                    @Override public void onRmsChanged(float rmsdB) { }
                    @Override public void onBufferReceived(byte[] buffer) { }
                    @Override public void onEndOfSpeech() { }
                    @Override public void onError(int error) {
                        listening = false;
                        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                            offlineLanguageWarning = true;
                            // Android 14's dual-language mode may fail when one of the
                            // requested offline packs is missing. Fall back to alternating
                            // local language requests, never to cloud recognition.
                            if (Build.VERSION.SDK_INT >= 34 && !disableAutoLanguageSwitch) {
                                disableAutoLanguageSwitch = true;
                                nextRecognitionLanguage = 0;
                                updateNotification();
                                scheduleListening(1800);
                                return;
                            }
                            updateNotification();
                            scheduleListening(12_000);
                            return;
                        }
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) { }
                            scheduleListening(3000);
                        } else if (error == SpeechRecognizer.ERROR_NO_MATCH
                                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            // Give the recognizer time between background attempts.
                            scheduleListening(2200);
                        } else {
                            scheduleListening(5000);
                        }
                    }
                    @Override public void onResults(android.os.Bundle results) {
                        listening = false;
                        offlineLanguageWarning = false;
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null) {
                            for (String match : matches) {
                                String command = extractWakeCommand(match);
                                if (command != null) {
                                    triggerWake(command);
                                    return;
                                }
                            }
                        }
                        scheduleListening(1800);
                    }
                    @Override public void onPartialResults(android.os.Bundle partialResults) { }
                    @Override public void onEvent(int eventType, android.os.Bundle params) { }
                });
            }

            Intent speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            if (Build.VERSION.SDK_INT >= 34 && !disableAutoLanguageSwitch) {
                // Use dual-language local recognition only when both packs are available.
                ArrayList<String> wakeLanguages = new ArrayList<>();
                wakeLanguages.add("bn-BD");
                wakeLanguages.add("en-US");
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD");
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD");
                speechIntent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, wakeLanguages);
                speechIntent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, wakeLanguages);
            } else {
                // One local language per request on older Android versions, or when
                // Android 14 reports that one of the two packs is unavailable.
                String recognitionLanguage = (nextRecognitionLanguage++ % 2 == 0) ? "bn-BD" : "en-US";
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage);
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionLanguage);
            }
            speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L);
            speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
            speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2800L);
            recognizer.startListening(speechIntent);
            listening = true;
        } catch (Exception e) {
            listening = false;
            // Keep the one local recognizer alive where possible. Repeatedly
            // destroying/recreating it made the old background loop needlessly costly.
            scheduleListening(5000);
        }
    }

    /** Returns null when the phrase is absent, otherwise the command after the wake phrase. */
    private String extractWakeCommand(String phrase) {
        if (phrase == null) return null;
        String raw = phrase.toLowerCase(Locale.ROOT).trim();
        // Keep Bengali letters when speech recognition returns native Bangla script.
        String normalized = raw.replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
        normalized = normalized.replace("hey nat you", "hey natu").replace("hey nattu", "hey natu").replace("hey nattoo", "hey natu").replace("hey nat u", "hey natu");
        Matcher english = Pattern.compile("\\bhey\\s+natu{1,2}\\b").matcher(normalized);
        if (english.find()) return normalized.substring(english.end()).trim();
        Matcher bangla = Pattern.compile("(?:হেই|এই|হে)?\\s*(নাতু|নাতূ|নাটু)").matcher(normalized);
        if (bangla.find()) return normalized.substring(bangla.end()).trim();
        // Some recognizers romanize the Bangla wake name and omit “Hey”.
        Matcher romanized = Pattern.compile("(?:^|\\s)(natu|nattu|nattoo)(?:$|\\s)").matcher(normalized);
        if (romanized.find()) return normalized.substring(romanized.end()).trim();
        return null;
    }

    private void triggerWake(String command) {
        if (paused || !enabled) return;
        paused = true;
        listening = false;
        wakePendingForNotification = true;
        pendingWakeCommand = command == null ? "" : command;
        stopRecognizer();
        updateNotification();
        Intent launch = new Intent(this, MainActivity.class)
                .setAction(ACTION_WAKE_TRIGGER)
                .putExtra(EXTRA_COMMAND, command == null ? "" : command)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mainHandler.postDelayed(() -> {
            if (!enabled || destroyed) return;
            try { startActivity(launch); } catch (Exception ignored) { updateNotification(); }
        }, 250);
    }

    private void stopRecognizer() {
        mainHandler.removeCallbacks(startListeningRunnable);
        listening = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Keep the explicitly enabled low-power local wake listener alive when the
        // launcher task is swiped away. It never starts automatically after reboot.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        stopRecognizer();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
