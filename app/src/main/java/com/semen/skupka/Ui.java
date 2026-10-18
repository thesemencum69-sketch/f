package com.semen.skupka;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

/** Цвета и мелкие помощники для интерфейса, собранного из кода. */
final class Ui {
    static final int BG = 0xF5161A22;
    static final int CARD = 0xFF222836;
    static final int FIELD = 0xFF2E3547;
    static final int ACCENT = 0xFF7C5CFF;
    static final int GREEN = 0xFF2DD4BF;
    static final int RED = 0xFFF87171;
    static final int TEXT = 0xFFE8EAF0;
    static final int MUTED = 0xFF8A90A2;
    static final int WHITE = 0xFFFFFFFF;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView button(Context c, String s, int bg, int fg) {
        TextView b = text(c, s, 14, fg, true);
        b.setGravity(Gravity.CENTER);
        int ph = dp(c, 12), pv = dp(c, 10);
        b.setPadding(ph, pv, ph, pv);
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x40FFFFFF), round(c, bg, 10), null));
        b.setClickable(true);
        return b;
    }

    static EditText field(Context c, String hint, boolean number, int maxLen) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setBackground(round(c, FIELD, 8));
        e.setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7));
        if (number) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            e.setGravity(Gravity.CENTER);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
        return e;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    /** 1250000 -> "1 250 000" */
    static String fmt(long v) {
        return String.format(Locale.US, "%,d", v).replace(',', ' ');
    }

    static long parse(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** TextWatcher, которому нужен только итоговый текст. */
    static final class Watcher implements TextWatcher {
        private final Consumer<String> onChange;

        Watcher(Consumer<String> onChange) {
            this.onChange = onChange;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) {
            onChange.accept(s.toString());
        }
    }
}
