package com.semen.skupka;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ресурсы, цены, количества в текущем трейде и текст объявления. Всё хранится на телефоне. */
final class Store {

    static final class Res {
        String name;
        long price; // цена за 1 шт.
        long qty;   // сколько дают в текущем трейде

        Res(String name, long price, long qty) {
            this.name = name;
            this.price = price;
            this.qty = qty;
        }
    }

    private static final String[] DEFAULTS = {"Жемчуг", "Рубины", "Сапфиры", "Изумруды", "Алмазы"};

    final List<Res> items = new ArrayList<>();
    String adStart = "Куплю ресурсы:";
    String adEnd = "Кидайте трейд!";
    int bubbleX = -1;
    int bubbleY = -1;

    private final SharedPreferences sp;

    Store(Context c) {
        sp = c.getSharedPreferences("skupka", Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        items.clear();
        String json = sp.getString("items", null);
        if (json == null) {
            for (String n : DEFAULTS) items.add(new Res(n, 0, 0));
        } else {
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    items.add(new Res(o.optString("n", "Ресурс"), o.optLong("p", 0), o.optLong("q", 0)));
                }
            } catch (JSONException e) {
                items.clear();
                for (String n : DEFAULTS) items.add(new Res(n, 0, 0));
            }
        }
        adStart = sp.getString("adStart", adStart);
        adEnd = sp.getString("adEnd", adEnd);
        bubbleX = sp.getInt("bx", -1);
        bubbleY = sp.getInt("by", -1);
    }

    void save() {
        JSONArray a = new JSONArray();
        for (Res r : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("n", r.name);
                o.put("p", r.price);
                o.put("q", r.qty);
            } catch (JSONException ignored) {
            }
            a.put(o);
        }
        sp.edit()
                .putString("items", a.toString())
                .putString("adStart", adStart)
                .putString("adEnd", adEnd)
                .putInt("bx", bubbleX)
                .putInt("by", bubbleY)
                .apply();
    }

    long line(Res r) {
        return r.price * r.qty;
    }

    long total() {
        long t = 0;
        for (Res r : items) t += line(r);
        return t;
    }

    void resetQty() {
        for (Res r : items) r.qty = 0;
    }

    /** "Куплю ресурсы: жемчуг — 10, рубины — 15. Кидайте трейд!" (только ресурсы с ценой). */
    String adText() {
        Locale ru = Locale.forLanguageTag("ru");
        StringBuilder sb = new StringBuilder(adStart.trim());
        boolean any = false;
        for (Res r : items) {
            if (r.price <= 0 || r.name.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(any ? ", " : " ");
            sb.append(r.name.trim().toLowerCase(ru)).append(" — ").append(Ui.fmt(r.price));
            any = true;
        }
        String end = adEnd.trim();
        if (!end.isEmpty()) {
            if (sb.length() > 0) sb.append(any ? ". " : " ");
            sb.append(end);
        }
        return sb.toString();
    }
}
