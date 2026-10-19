package com.semen.skupka;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Хранение настроек, статистики и журнала на телефоне. Логика расчёта — в {@link Logic}. */
final class Store {

    static final int JOURNAL_MAX = 200;

    final List<Logic.Res> items = Logic.defaults();
    final List<Logic.Entry> journal = new ArrayList<>(); // новые сверху

    long maxDeal = 0;   // макс. сумма одной сделки, 0 = без лимита
    long budget = 0;    // общий бюджет, 0 = без лимита
    long spent = 0;     // потрачено всего
    String adStart = "КУПЛЮ";
    String adEnd = "";
    long adIntervalMin = 3;
    boolean autoAds = false;
    boolean running = true;
    int bubbleX = -1;
    int bubbleY = -1;

    private final SharedPreferences sp;

    Store(Context c) {
        sp = c.getSharedPreferences("skupka_v2", Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        try {
            String raw = sp.getString("items", null);
            if (raw != null) {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Logic.Res r = find(o.optString("n"));
                    if (r == null) continue;
                    r.enabled = o.optBoolean("e", true);
                    r.inAd = o.optBoolean("a", true);
                    r.price = o.optLong("p", 0);
                    r.maxQty = o.optLong("m", 0);
                    r.offer = o.optLong("o", 0);
                    r.bought = o.optLong("b", 0);
                }
            }
            String j = sp.getString("journal", null);
            if (j != null) {
                JSONArray a = new JSONArray(j);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    journal.add(new Logic.Entry(o.optLong("t"), o.optBoolean("ok"),
                            o.optString("d"), o.optLong("s"), o.optString("r")));
                }
            }
        } catch (JSONException ignored) {
        }
        maxDeal = sp.getLong("maxDeal", 0);
        budget = sp.getLong("budget", 0);
        spent = sp.getLong("spent", 0);
        adStart = sp.getString("adStart", adStart);
        adEnd = sp.getString("adEnd", adEnd);
        adIntervalMin = Math.max(1, sp.getLong("adInterval", adIntervalMin));
        autoAds = sp.getBoolean("autoAds", false);
        running = sp.getBoolean("running", true);
        bubbleX = sp.getInt("bx", -1);
        bubbleY = sp.getInt("by", -1);
    }

    private Logic.Res find(String name) {
        for (Logic.Res r : items) if (r.name.equals(name)) return r;
        return null;
    }

    void save() {
        JSONArray a = new JSONArray();
        JSONArray j = new JSONArray();
        try {
            for (Logic.Res r : items) {
                JSONObject o = new JSONObject();
                o.put("n", r.name);
                o.put("e", r.enabled);
                o.put("a", r.inAd);
                o.put("p", r.price);
                o.put("m", r.maxQty);
                o.put("o", r.offer);
                o.put("b", r.bought);
                a.put(o);
            }
            for (Logic.Entry e : journal) {
                JSONObject o = new JSONObject();
                o.put("t", e.time);
                o.put("ok", e.ok);
                o.put("d", e.details);
                o.put("s", e.sum);
                o.put("r", e.reason);
                j.put(o);
            }
        } catch (JSONException ignored) {
        }
        sp.edit()
                .putString("items", a.toString())
                .putString("journal", j.toString())
                .putLong("maxDeal", maxDeal)
                .putLong("budget", budget)
                .putLong("spent", spent)
                .putString("adStart", adStart)
                .putString("adEnd", adEnd)
                .putLong("adInterval", adIntervalMin)
                .putBoolean("autoAds", autoAds)
                .putBoolean("running", running)
                .putInt("bx", bubbleX)
                .putInt("by", bubbleY)
                .apply();
    }

    void addEntry(Logic.Entry e) {
        journal.add(0, e);
        while (journal.size() > JOURNAL_MAX) journal.remove(journal.size() - 1);
    }

    void resetStats() {
        journal.clear();
        spent = 0;
        for (Logic.Res r : items) r.bought = 0;
    }

    String adText() {
        return Logic.adText(items, adStart, adEnd);
    }
}
