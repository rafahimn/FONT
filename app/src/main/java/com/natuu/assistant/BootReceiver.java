package com.natuu.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        SharedPreferences prefs = context.getSharedPreferences("natuu_reminders", Context.MODE_PRIVATE);
        JSONArray items;
        try { items = new JSONArray(prefs.getString("items", "[]")); }
        catch (JSONException e) { return; }
        JSONArray keep = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            long id = item.optLong("id");
            String title = item.optString("title", "Natuu reminder");
            long at = item.optLong("at");
            boolean daily = item.optBoolean("daily", false);
            if (!daily && at <= now) continue;
            if (daily && at <= now) {
                long day = 86_400_000L;
                at = at + ((now - at) / day + 1L) * day;
                try { item.put("at", at); } catch (JSONException ignored) { }
            }
            keep.put(item);
            MainActivity.scheduleAlarm(context, id, title, at, daily);
        }
        prefs.edit().putString("items", keep.toString()).apply();
    }
}
