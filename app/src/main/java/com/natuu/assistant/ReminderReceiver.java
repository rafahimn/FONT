package com.natuu.assistant;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra("id", System.currentTimeMillis());
        String title = intent.getStringExtra("title");
        if (title == null || title.trim().isEmpty()) title = "তোমার রিমাইন্ডার";
        boolean daily = intent.getBooleanExtra("daily", false);

        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(context, (int) (id + 900000), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, "natuu_reminders")
                : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Natuu reminder")
                .setContentText(title)
                .setStyle(new Notification.BigTextStyle().bigText(title))
                .setAutoCancel(true)
                .setContentIntent(openPending)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify((int) id, builder.build());

        if (!daily) removeFromSaved(context, id);
    }

    private void removeFromSaved(Context context, long id) {
        SharedPreferences prefs = context.getSharedPreferences("natuu_reminders", Context.MODE_PRIVATE);
        JSONArray old;
        try { old = new JSONArray(prefs.getString("items", "[]")); }
        catch (JSONException e) { old = new JSONArray(); }
        JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null && item.optLong("id") != id) next.put(item);
        }
        prefs.edit().putString("items", next.toString()).apply();
    }
}
