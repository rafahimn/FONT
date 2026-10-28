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
    private static final String PREFS = "natuu_prefs";
    private static final String MODEL_DEFAULT = "gemini-3.8-flash";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private String pendingCallName = null;
    private MediaPlayer customVoicePlayer = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(16, 13, 29));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(16, 13, 29));
        createNotificationChannel();
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
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("window.onNatuuState && window.onNatuuState(" + getStateJson() + ");", null);
            }
        });
        webView.addJavascriptInterface(new NatuuBridge(), "Natuu");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void initTts() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            ttsReady = true;
            configureFemaleLeaningVoice();
        });
    }

    private void configureFemaleLeaningVoice() {
        if (textToSpeech == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            int result = textToSpeech.setLanguage(new Locale("bn", "BD"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech.setLanguage(new Locale("en", "IN"));
            }
            Voice best = null;
            for (Voice voice : textToSpeech.getVoices()) {
                String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.ROOT);
                Locale locale = voice.getLocale();
                boolean languageMatch = locale != null && ("bn".equalsIgnoreCase(locale.getLanguage()) || "en".equalsIgnoreCase(locale.getLanguage()));
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendNativeEvent("This phone has no speech recognition service available. Install or enable Google speech services.");
            return;
        }
        try {
            if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { sendStatus("listening"); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { sendStatus("thinking"); }
                @Override public void onError(int error) {
                    String message = error == SpeechRecognizer.ERROR_NO_MATCH ? "কথাটি বুঝতে পারিনি, আবার বলো।" : "ভয়েস শোনা যায়নি। আবার চেষ্টা করো।";
                    sendStatus("idle"); sendNativeEvent(message);
                }
                @Override public void onResults(Bundle results) {
                    sendStatus("thinking");
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) processCommand(matches.get(0));
                    else sendNativeEvent("কথাটি বুঝতে পারিনি, আবার বলো।");
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            sendStatus("idle"); sendNativeEvent("Voice input could not start. Check microphone access.");
        }
    }

    private void processCommand(String input) {
        if (input == null || input.trim().isEmpty()) return;
        sendStatus("thinking");
        // Handle native phone actions on Android's main thread; keep network calls off it.
        mainHandler.post(() -> {
            ActionResult action = tryNativeAction(input);
            if (action.handled) {
                if (action.reply != null) postReply(input, action.reply, "action", true);
                return;
            }
            executor.execute(() -> {
                String key = SecureStore.getApiKey(this);
                if (key.isEmpty()) {
                    postReply(input, "Gemini API key সেট করা নেই। Settings খুলে তোমার API key যোগ করো।", "error", false);
                    mainHandler.post(() -> webView.evaluateJavascript("window.showSettings && window.showSettings(true);", null));
                    return;
                }
                try {
                    String response = askGemini(key, input);
                    postReply(input, response, "ai", true);
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "Gemini API-তে সংযোগ করা যায়নি।" : e.getMessage();
                    postReply(input, "দুঃখিত, " + msg, "error", false);
                }
            });
        });
    }

    private static class ActionResult {
        final boolean handled; final String reply;
        ActionResult(boolean handled, String reply) { this.handled = handled; this.reply = reply; }
        static ActionResult no() { return new ActionResult(false, null); }
        static ActionResult yes(String reply) { return new ActionResult(true, reply); }
    }

    private ActionResult tryNativeAction(String raw) {
        String text = latinDigits(raw).toLowerCase(Locale.ROOT).trim();
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
                        return ActionResult.yes(ok ? app[0] + " খুলছি।" : app[0] + " অ্যাপটি ফোনে খুঁজে পেলাম না।");
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
                        return ActionResult.yes(String.format(Locale.getDefault(), "%02d:%02d সময়ের অ্যালার্ম সেট করার স্ক্রিন খুলেছি।", hour, minute));
                    } catch (Exception ignored) { }
                }
            }
            try { startActivity(new Intent(AlarmClock.ACTION_SET_ALARM)); return ActionResult.yes("Clock অ্যাপের alarm screen খুলেছি।"); }
            catch (Exception e) { return ActionResult.yes("এই ফোনে Clock অ্যাপটি পাওয়া যায়নি।"); }
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
                    return ActionResult.yes(String.format(Locale.getDefault(), "%s — %d %s পরে রিমাইন্ডার সেট করেছি।", title, amount, unit));
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
                        return ActionResult.yes(String.format(Locale.getDefault(), "প্রতিদিন %02d:%02d-এ রিমাইন্ডার সেট করেছি।", hour, min));
                    }
                }
            }
            return ActionResult.yes("রিমাইন্ডার সেট করতে বলো, যেমন: ‘১০ মিনিট পরে আমাকে পানি খেতে মনে করিও’।");
        }

        boolean callIntent = containsAny(text, "call ", "কল কর", "কল করো", "ফোন কর", "ফোন করো", "ফোন দাও", "ডায়াল", "dial ", "কল দাও", "call my");
        if (callIntent) {
            Matcher number = Pattern.compile("(\\+?\\d[\\d\\s().-]{5,}\\d)").matcher(latinDigits(raw));
            if (number.find()) {
                String phone = number.group(1).replaceAll("[^+\\d]", "");
                try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)))); return ActionResult.yes("ডায়ালার খুলেছি। কল করার আগে নম্বরটি দেখে Call চাপো।"); }
                catch (Exception e) { return ActionResult.yes("ডায়ালার খোলা যায়নি।"); }
            }
            String name = raw.replaceAll("(?i)call|কল করো?|ফোন করো?|ফোন দাও|ডায়াল|কল দাও|আমাকে|দাও|please", " ").trim();
            name = name.replaceAll("^(আমার|to)\\s*", "").trim();
            if (name.length() > 1) {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    pendingCallName = name;
                    mainHandler.post(() -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS));
                    return ActionResult.yes("কন্টাক্ট থেকে নাম খুঁজতে Contacts permission দরকার। অনুমতি দিলে নামটি খুঁজে ডায়ালার খুলব।");
                }
                String matched = callContactByName(name);
                if (matched != null) return ActionResult.yes("" + matched + "-এর নম্বর ডায়ালারে খুলেছি। যাচাই করে Call চাপো।");
                return ActionResult.yes("কন্টাক্টে ‘" + name + "’ নামটি পাইনি। চাইলে ফোন নম্বর বলো।");
            }
            return ActionResult.yes("কাকে কল করব? নাম অথবা ফোন নম্বর বলো।");
        }
        if (containsAny(text, "message ", "sms ", "মেসেজ পাঠাও", "এসএমএস")) {
            Matcher number = Pattern.compile("(\\+?\\d[\\d\\s().-]{5,}\\d)").matcher(latinDigits(raw));
            if (number.find()) {
                String phone = number.group(1).replaceAll("[^+\\d]", "");
                try { startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)))); return ActionResult.yes("Messages খুলেছি। বার্তা লিখে নিজে Send চাপো।"); }
                catch (Exception e) { return ActionResult.yes("Messages অ্যাপ খোলা যায়নি।"); }
            }
        }
        if (containsAny(text, "turn on bluetooth", "ব্লুটুথ চালু", "bluetooth settings", "ব্লুটুথ সেটিং")) {
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return ActionResult.yes("Bluetooth settings খুলেছি। সেখান থেকে Bluetooth চালু করো।"); }
            catch (Exception e) { return ActionResult.yes("Bluetooth settings খোলা যায়নি।"); }
        }
        if (containsAny(text, "take a photo", "ছবি তোলো", "ক্যামেরা খোলো")) {
            try { startActivity(new Intent(MediaStore.ACTION_IMAGE_CAPTURE)); return ActionResult.yes("ক্যামেরা খুলেছি।"); }
            catch (Exception e) { return ActionResult.yes("ক্যামেরা খুলতে পারিনি।"); }
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
        if (cleaned.isEmpty()) cleaned = "তোমার রিমাইন্ডার";
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
        scheduleAlarm(id, title, triggerAt, daily);
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

    private String askGemini(String apiKey, String userText) throws Exception {
        HttpURLConnection connection = null;
        try {
            String model = getSharedPreferences(PREFS, MODE_PRIVATE).getString("model", MODEL_DEFAULT);
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(18000);
            connection.setReadTimeout(45000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", apiKey);
            JSONObject body = new JSONObject();
            JSONObject system = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text",
                    "You are Natuu, a warm, kind, feminine-sounding personal assistant for one user. Reply naturally in the language the user used, especially Bangla/Bengali (Bangladesh). Be concise and friendly. Never claim you performed a phone action unless the app confirms it. You cannot directly access private phone data or control apps unless the Natuu app tells you that action was completed. For risky actions such as sending messages or placing calls, require the user's explicit confirmation.")));
            body.put("system_instruction", system);
            body.put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", userText)))));
            body.put("generationConfig", new JSONObject().put("temperature", 0.7).put("maxOutputTokens", 1200));
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) { os.write(bytes); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) response.append(line);
            }
            JSONObject json = new JSONObject(response.toString());
            if (status < 200 || status >= 300) {
                String error = json.optJSONObject("error") != null ? json.getJSONObject("error").optString("message", "Gemini API request failed") : "Gemini API request failed";
                if (status == 400 || status == 401 || status == 403) throw new Exception("API key বা API access যাচাই করো (" + status + "): " + error);
                throw new Exception("Gemini API error (" + status + "): " + error);
            }
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) throw new Exception("Gemini কোনো উত্তর দেয়নি। আবার চেষ্টা করো।");
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            StringBuilder answer = new StringBuilder();
            if (parts != null) for (int i = 0; i < parts.length(); i++) {
                String piece = parts.getJSONObject(i).optString("text", "");
                if (!piece.isEmpty()) { if (answer.length() > 0) answer.append("\n"); answer.append(piece); }
            }
            if (answer.length() == 0) throw new Exception("Gemini থেকে পড়ার মতো উত্তর আসেনি।");
            return answer.toString().trim();
        } finally { if (connection != null) connection.disconnect(); }
    }

    private void postReply(String userText, String reply, String kind, boolean speak) {
        mainHandler.post(() -> {
            if (webView != null) {
                String js = "window.onAssistantReply && window.onAssistantReply(" + JSONObject.quote(userText) + "," + JSONObject.quote(reply) + "," + JSONObject.quote(kind) + ");";
                webView.evaluateJavascript(js, null);
            }
            if (speak && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("speak", true)) speak(reply);
            sendStatus("idle");
        });
    }

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String voiceKey = SecureStore.getVoiceApiKey(this);
        String voiceId = SecureStore.getVoiceId(this);
        if (!voiceKey.isEmpty() && !voiceId.isEmpty()) {
            executor.execute(() -> {
                try {
                    byte[] audio = synthesizeCustomVoice(voiceKey, voiceId, text);
                    playCustomVoiceAudio(audio);
                } catch (Exception e) {
                    speakWithSystemTts(text);
                    sendNativeEvent("কাস্টম ভয়েসে সংযোগ হয়নি, তাই ফোনের বিকল্প ভয়েস ব্যবহার করছি।");
                }
            });
            return;
        }
        speakWithSystemTts(text);
    }

    private void speakWithSystemTts(String text) {
        if (!ttsReady || textToSpeech == null || text == null || text.trim().isEmpty()) return;
        mainHandler.post(() -> {
            try { if (Build.VERSION.SDK_INT >= 21) textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "natuu-" + System.currentTimeMillis()); else textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null); }
            catch (Exception ignored) { }
        });
    }

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
            body.put("model_id", "eleven_multilingual_v2");
            body.put("voice_settings", new JSONObject().put("stability", 0.55).put("similarity_boost", 0.78).put("style", 0.12).put("use_speaker_boost", true));
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
                });
                player.setOnErrorListener((mp, what, extra) -> {
                    try { mp.release(); } catch (Exception ignored) { }
                    if (customVoicePlayer == mp) customVoicePlayer = null;
                    try { file.delete(); } catch (Exception ignored) { }
                    sendNativeEvent("কাস্টম ভয়েসের অডিও চালানো যায়নি। আবার চেষ্টা করো।");
                    return true;
                });
                player.prepareAsync();
            } catch (Exception e) {
                try { if (customVoicePlayer != null) customVoicePlayer.release(); } catch (Exception ignored) { }
                customVoicePlayer = null;
                try { file.delete(); } catch (Exception ignored) { }
                sendNativeEvent("কাস্টম ভয়েস চালু করা যায়নি।");
            }
        });
    }

    private void createCustomVoiceFromReference() {
        String apiKey = SecureStore.getVoiceApiKey(this);
        if (apiKey.isEmpty()) {
            sendVoiceSetupResult(false, "আগে ElevenLabs API key সেভ করো। Gemini key আলাদা—এটি শুধু কণ্ঠ তৈরির জন্য।");
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
                try (OutputStream out = connection.getOutputStream(); InputStream voice = getAssets().open("natuu_voice_reference.mp3")) {
                    writeFormField(out, boundary, "name", "Natuu Personal Voice");
                    writeFormField(out, boundary, "description", "Private personal assistant voice created from the user's provided reference audio.");
                    out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"natuu_voice_reference.mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = voice.read(buffer)) != -1) out.write(buffer, 0, count);
                    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                int status = connection.getResponseCode();
                InputStream responseStream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                String response = responseStream == null ? "" : readStream(responseStream);
                JSONObject json;
                try { json = new JSONObject(response); } catch (JSONException e) { json = new JSONObject(); }
                if (status < 200 || status >= 300) {
                    String message = json.optString("detail", json.optString("message", "API key, quota অথবা voice-cloning access পরীক্ষা করো."));
                    if (json.optJSONObject("detail") != null) message = json.optJSONObject("detail").optString("message", message);
                    throw new Exception("Voice clone API (" + status + "): " + message);
                }
                String voiceId = json.optString("voice_id", "");
                if (voiceId.isEmpty()) throw new Exception("Voice API থেকে voice ID আসেনি।");
                SecureStore.saveVoiceId(this, voiceId);
                sendVoiceSetupResult(true, "কাস্টম ভয়েস তৈরি হয়েছে এবং Natuu-তে যুক্ত হয়েছে। এখন থেকে উত্তর এই ভয়েসে শোনানোর চেষ্টা করবে।");
            } catch (Exception e) {
                String message = e.getMessage() == null ? "কাস্টম ভয়েস তৈরি করা যায়নি।" : e.getMessage();
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

    private void sendStatus(String status) {
        mainHandler.post(() -> { if (webView != null) webView.evaluateJavascript("window.onNatuuStatus && window.onNatuuStatus(" + JSONObject.quote(status) + ");", null); });
    }

    private void sendNativeEvent(String message) {
        mainHandler.post(() -> { if (webView != null) webView.evaluateJavascript("window.onNatuuNotice && window.onNatuuNotice(" + JSONObject.quote(message) + ");", null); });
    }

    private JSONObject getStateJson() {
        JSONObject state = new JSONObject();
        try {
            state.put("configured", !SecureStore.getApiKey(this).isEmpty());
            state.put("model", getSharedPreferences(PREFS, MODE_PRIVATE).getString("model", MODEL_DEFAULT));
            state.put("speak", getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("speak", true));
            state.put("customVoiceReady", !SecureStore.getVoiceId(this).isEmpty() && !SecureStore.getVoiceApiKey(this).isEmpty());
            state.put("voiceConfigured", !SecureStore.getVoiceApiKey(this).isEmpty());
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
            if (model == null || !model.matches("[a-zA-Z0-9._-]{3,80}")) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("model", model).apply();
        }
        @JavascriptInterface public void setSpeakEnabled(boolean enabled) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("speak", enabled).apply();
        }
        @JavascriptInterface public void sendCommand(String text) { processCommand(text); }
        @JavascriptInterface public void requestSpeech() {
            mainHandler.post(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                else startSpeechRecognition();
            });
        }
        @JavascriptInterface public void stopSpeaking() { mainHandler.post(() -> {
            if (textToSpeech != null) textToSpeech.stop();
            if (customVoicePlayer != null) { try { customVoicePlayer.stop(); } catch (Exception ignored) { } try { customVoicePlayer.release(); } catch (Exception ignored) { } customVoicePlayer = null; }
        }); }
        @JavascriptInterface public void testVoice(String text) { speak(text == null ? "হ্যালো, আমি Natuu। তোমার ব্যক্তিগত AI সহকারী।" : text); }
        @JavascriptInterface public void openDeviceSettings() { mainHandler.post(() -> startActivity(new Intent(Settings.ACTION_SETTINGS))); }
    }

    @Override public void onConfigurationChanged(Configuration newConfig) { super.onConfigurationChanged(newConfig); }

    @Override protected void onDestroy() {
        if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
        if (textToSpeech != null) { textToSpeech.stop(); textToSpeech.shutdown(); textToSpeech = null; }
        if (customVoicePlayer != null) { try { customVoicePlayer.stop(); } catch (Exception ignored) { } try { customVoicePlayer.release(); } catch (Exception ignored) { } customVoicePlayer = null; }
        executor.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
