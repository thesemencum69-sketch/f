package com.semen.skupka;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Плавающий кружок поверх всех приложений. Нажатие раскрывает панель с ценами,
 * подсчётом трейда и кнопкой копирования объявления. В игру не вмешивается.
 */
public class FloatService extends Service {

    static final String ACTION_STOP = "com.semen.skupka.STOP";
    private static final String CHANNEL = "float";
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    static volatile boolean running = false;

    private WindowManager wm;
    private Store store;

    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView tabTrade;
    private TextView tabPrices;
    private TextView totalView;
    private TextView resetBtn;
    private TextView adPreview;
    private boolean pricesTab = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Нет разрешения «Поверх других приложений»", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        store = new Store(this);
        running = true;
        showBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        removeSafe(bubble);
        removeSafe(panel);
        if (store != null) store.save();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (panel != null && panel.isAttachedToWindow()) {
            sizePanel();
            safeUpdate(panel, panelLp);
        } else if (bubble != null && bubble.isAttachedToWindow()) {
            clamp(bubbleLp, bubble);
            safeUpdate(bubble, bubbleLp);
        }
    }

    // ---------- уведомление (без него Android убьёт сервис) ----------

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Плавающее окно", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FloatService.class).setAction(ACTION_STOP), piFlags);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Скупка работает")
                .setContentText("Кружок на экране. Нажмите, чтобы открыть приложение")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notify), "Закрыть", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    // ---------- кружок ----------

    private void showBubble() {
        if (bubble == null) {
            bubble = new TextView(this);
            bubble.setText("💎");
            bubble.setTextSize(24);
            bubble.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF8B5CF6, 0xFF4C1D95});
            g.setShape(GradientDrawable.OVAL);
            g.setStroke(Ui.dp(this, 2), 0x66FFFFFF);
            bubble.setBackground(g);
            bubble.setAlpha(0.92f);

            int s = Ui.dp(this, 54);
            bubbleLp = new WindowManager.LayoutParams(s, s,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            bubbleLp.gravity = Gravity.TOP | Gravity.START;
            bubbleLp.x = store.bubbleX >= 0 ? store.bubbleX : 0;
            bubbleLp.y = store.bubbleY >= 0 ? store.bubbleY : Ui.dp(this, 120);
            bubble.setOnTouchListener(new Drag(bubbleLp, true));
        }
        clamp(bubbleLp, bubble);
        if (bubble.isAttachedToWindow()) return;
        try {
            wm.addView(bubble, bubbleLp);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось показать кружок: проверьте разрешения", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void snapBubble() {
        int w = screenW();
        int s = bubbleLp.width;
        bubbleLp.x = (bubbleLp.x + s / 2 < w / 2) ? 0 : w - s;
        clamp(bubbleLp, bubble);
        safeUpdate(bubble, bubbleLp);
        store.bubbleX = bubbleLp.x;
        store.bubbleY = bubbleLp.y;
        store.save();
    }

    // ---------- панель ----------

    private void expand() {
        removeSafe(bubble);
        if (panel == null) buildPanel();
        sizePanel();
        rebuildContent();
        try {
            wm.addView(panel, panelLp);
        } catch (Exception e) {
            showBubble();
        }
    }

    private void collapse() {
        setPanelFocusable(false);
        store.save();
        removeSafe(panel);
        showBubble();
    }

    private void buildPanel() {
        panel = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent ev) {
                if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (ev.getAction() == KeyEvent.ACTION_UP) {
                        if (isPanelFocusable()) setPanelFocusable(false);
                        else collapse();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(ev);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(this, Ui.BG, 16));
        int p = Ui.dp(this, 12);
        panel.setPadding(p, p, p, p);
        // Тап мимо панели (по игре) — убираем клавиатуру и отдаём ввод игре.
        panel.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) setPanelFocusable(false);
            return false;
        });

        panelLp = new WindowManager.LayoutParams(WRAP, WRAP,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;

        // Шапка: за неё панель перетаскивается.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 10));
        TextView title = Ui.text(this, "💎 Скупка", 17, Ui.TEXT, true);
        header.addView(title, Ui.lp(0, WRAP, 1));
        TextView min = Ui.button(this, "—", Ui.CARD, Ui.TEXT);
        min.setOnClickListener(v -> collapse());
        header.addView(min, sq(40, 8));
        TextView close = Ui.button(this, "✕", Ui.CARD, Ui.RED);
        close.setOnClickListener(v -> stopSelf());
        header.addView(close, sq(40, 0));
        header.setOnTouchListener(new Drag(panelLp, false));
        panel.addView(header, Ui.lp(MATCH, WRAP, 0));

        // Вкладки.
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabTrade = Ui.button(this, "Трейд", Ui.CARD, Ui.TEXT);
        tabPrices = Ui.button(this, "Цены", Ui.CARD, Ui.TEXT);
        tabTrade.setOnClickListener(v -> switchTab(false));
        tabPrices.setOnClickListener(v -> switchTab(true));
        LinearLayout.LayoutParams tl = Ui.lp(0, WRAP, 1);
        tl.rightMargin = Ui.dp(this, 6);
        tabs.addView(tabTrade, tl);
        tabs.addView(tabPrices, Ui.lp(0, WRAP, 1));
        LinearLayout.LayoutParams tabsLp = Ui.lp(MATCH, WRAP, 0);
        tabsLp.bottomMargin = Ui.dp(this, 10);
        panel.addView(tabs, tabsLp);

        // Прокручиваемое содержимое.
        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(MATCH, WRAP));
        panel.addView(scroll, Ui.lp(MATCH, 0, 1));

        // Низ: итог и кнопки.
        totalView = Ui.text(this, "", 18, Ui.GREEN, true);
        totalView.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, Ui.dp(this, 8));
        panel.addView(totalView, Ui.lp(MATCH, WRAP, 0));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        resetBtn = Ui.button(this, "Сброс", Ui.CARD, Ui.TEXT);
        resetBtn.setOnClickListener(v -> {
            store.resetQty();
            store.save();
            setPanelFocusable(false);
            rebuildContent();
        });
        LinearLayout.LayoutParams rl = Ui.lp(0, WRAP, 1);
        rl.rightMargin = Ui.dp(this, 6);
        actions.addView(resetBtn, rl);
        TextView copy = Ui.button(this, "📋 Объявление", Ui.ACCENT, Ui.WHITE);
        copy.setOnClickListener(v -> copyAd());
        actions.addView(copy, Ui.lp(0, WRAP, 1.6f));
        panel.addView(actions, Ui.lp(MATCH, WRAP, 0));
    }

    private void sizePanel() {
        int w = screenW(), h = screenH();
        panelLp.width = Math.min(w - Ui.dp(this, 16), Ui.dp(this, 340));
        panelLp.height = Math.min(h - Ui.dp(this, 32), Ui.dp(this, 480));
        boolean left = bubbleLp == null || bubbleLp.x + bubbleLp.width / 2 < w / 2;
        panelLp.x = left ? Ui.dp(this, 8) : w - panelLp.width - Ui.dp(this, 8);
        panelLp.y = Math.max(0, (h - panelLp.height) / 2);
    }

    private void switchTab(boolean prices) {
        if (pricesTab == prices) return;
        pricesTab = prices;
        setPanelFocusable(false);
        rebuildContent();
        scroll.scrollTo(0, 0);
    }

    private void rebuildContent() {
        content.removeAllViews();
        adPreview = null;
        styleTab(tabTrade, !pricesTab);
        styleTab(tabPrices, pricesTab);
        if (pricesTab) buildPrices();
        else buildTrade();
        totalView.setVisibility(pricesTab ? View.GONE : View.VISIBLE);
        resetBtn.setVisibility(pricesTab ? View.GONE : View.VISIBLE);
        updateTotal();
    }

    private void styleTab(TextView t, boolean active) {
        t.setBackground(Ui.round(this, active ? Ui.ACCENT : Ui.CARD, 10));
        t.setTextColor(active ? Ui.WHITE : Ui.MUTED);
    }

    // ----- вкладка «Трейд» -----

    private void buildTrade() {
        if (store.items.isEmpty()) {
            content.addView(Ui.text(this, "Добавьте ресурсы во вкладке «Цены».", 14, Ui.MUTED, false));
            return;
        }
        TextView hint = Ui.text(this, "Сколько вам дают:", 13, Ui.MUTED, false);
        hint.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 6));
        content.addView(hint);

        for (Store.Res r : store.items) {
            LinearLayout row = card();

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(Ui.text(this, r.name, 15, Ui.TEXT, true));
            TextView each = r.price > 0
                    ? Ui.text(this, "по " + Ui.fmt(r.price), 12, Ui.MUTED, false)
                    : Ui.text(this, "цена не задана", 12, Ui.RED, false);
            left.addView(each);
            row.addView(left, Ui.lp(0, WRAP, 1));

            EditText qty = Ui.field(this, "0", true, 7);
            if (r.qty > 0) qty.setText(String.valueOf(r.qty));
            LinearLayout.LayoutParams ql = Ui.lp(Ui.dp(this, 70), WRAP, 0);
            ql.leftMargin = Ui.dp(this, 6);
            row.addView(qty, ql);

            TextView sum = Ui.text(this, "= " + Ui.fmt(store.line(r)), 15, Ui.GREEN, true);
            sum.setGravity(Gravity.END);
            sum.setSingleLine(true);
            row.addView(sum, Ui.lp(Ui.dp(this, 96), WRAP, 0));

            qty.addTextChangedListener(new Ui.Watcher(s -> {
                r.qty = Ui.parse(s);
                sum.setText("= " + Ui.fmt(store.line(r)));
                updateTotal();
                store.save();
            }));
            attachEdit(qty);
            content.addView(row, rowLp());
        }
    }

    private void updateTotal() {
        totalView.setText("Итого к выплате: " + Ui.fmt(store.total()));
    }

    // ----- вкладка «Цены» -----

    private void buildPrices() {
        TextView hint = Ui.text(this, "Ресурс и цена за 1 шт.:", 13, Ui.MUTED, false);
        hint.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 6));
        content.addView(hint);

        for (Store.Res r : store.items) {
            LinearLayout row = card();

            EditText name = Ui.field(this, "Название", false, 24);
            name.setText(r.name);
            row.addView(name, Ui.lp(0, WRAP, 1));

            EditText price = Ui.field(this, "цена", true, 10);
            if (r.price > 0) price.setText(String.valueOf(r.price));
            LinearLayout.LayoutParams pl = Ui.lp(Ui.dp(this, 92), WRAP, 0);
            pl.leftMargin = Ui.dp(this, 6);
            row.addView(price, pl);

            TextView del = Ui.button(this, "✕", Ui.FIELD, Ui.RED);
            del.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams dl = Ui.lp(Ui.dp(this, 36), Ui.dp(this, 36), 0);
            dl.leftMargin = Ui.dp(this, 6);
            row.addView(del, dl);

            name.addTextChangedListener(new Ui.Watcher(s -> {
                r.name = s;
                store.save();
                updateAdPreview();
            }));
            price.addTextChangedListener(new Ui.Watcher(s -> {
                r.price = Ui.parse(s);
                store.save();
                updateAdPreview();
            }));
            del.setOnClickListener(v -> {
                store.items.remove(r);
                store.save();
                setPanelFocusable(false);
                rebuildContent();
            });
            attachEdit(name);
            attachEdit(price);
            content.addView(row, rowLp());
        }

        TextView add = Ui.button(this, "+ Добавить ресурс", Ui.CARD, Ui.ACCENT);
        add.setOnClickListener(v -> {
            store.items.add(new Store.Res("Новый ресурс", 0, 0));
            store.save();
            setPanelFocusable(false);
            int y = scroll.getScrollY();
            rebuildContent();
            scroll.post(() -> scroll.scrollTo(0, y + Ui.dp(this, 60)));
        });
        LinearLayout.LayoutParams al = Ui.lp(MATCH, WRAP, 0);
        al.bottomMargin = Ui.dp(this, 16);
        content.addView(add, al);

        TextView adTitle = Ui.text(this, "Объявление в чат:", 13, Ui.MUTED, false);
        adTitle.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 6));
        content.addView(adTitle);

        EditText start = Ui.field(this, "Начало, например «Куплю ресурсы:»", false, 60);
        start.setText(store.adStart);
        start.addTextChangedListener(new Ui.Watcher(s -> {
            store.adStart = s;
            store.save();
            updateAdPreview();
        }));
        attachEdit(start);
        content.addView(start, rowLp());

        EditText end = Ui.field(this, "Конец, например «Кидайте трейд!»", false, 60);
        end.setText(store.adEnd);
        end.addTextChangedListener(new Ui.Watcher(s -> {
            store.adEnd = s;
            store.save();
            updateAdPreview();
        }));
        attachEdit(end);
        content.addView(end, rowLp());

        adPreview = Ui.text(this, "", 13, Ui.TEXT, false);
        adPreview.setBackground(Ui.round(this, Ui.CARD, 10));
        int p = Ui.dp(this, 10);
        adPreview.setPadding(p, p, p, p);
        content.addView(adPreview, Ui.lp(MATCH, WRAP, 0));
        updateAdPreview();
    }

    private void updateAdPreview() {
        if (adPreview == null) return;
        String t = store.adText();
        adPreview.setText(t + "\n\n" + t.length() + " симв.");
    }

    private void copyAd() {
        String t = store.adText();
        boolean anyPrice = false;
        for (Store.Res r : store.items) if (r.price > 0) anyPrice = true;
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Объявление", t));
        Toast.makeText(this,
                anyPrice ? "Скопировано, вставьте в чат игры" : "Скопировано, но цены ещё не заданы (вкладка «Цены»)",
                Toast.LENGTH_SHORT).show();
    }

    // ---------- клавиатура и фокус ----------

    /**
     * Панель по умолчанию не забирает ввод у игры. Фокус включается,
     * только когда нажали на поле ввода, и выключается при тапе мимо панели.
     */
    private boolean isPanelFocusable() {
        return panelLp != null && (panelLp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
    }

    private void setPanelFocusable(boolean focusable) {
        if (panelLp == null || panel == null) return;
        int f = panelLp.flags;
        if (focusable) f &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else f |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (!focusable) {
            hideKeyboard();
            View fc = panel.findFocus();
            if (fc != null) fc.clearFocus();
        }
        if (f != panelLp.flags) {
            panelLp.flags = f;
            safeUpdate(panel, panelLp);
        }
    }

    private void hideKeyboard() {
        if (panel == null || panel.getWindowToken() == null) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(panel.getWindowToken(), 0);
    }

    private void attachEdit(EditText e) {
        e.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && !isPanelFocusable()) {
                setPanelFocusable(true);
                v.postDelayed(() -> {
                    v.requestFocus();
                    InputMethodManager imm = getSystemService(InputMethodManager.class);
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
                }, 150);
            }
            return false;
        });
        e.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                setPanelFocusable(false);
                return true;
            }
            return false;
        });
    }

    // ---------- перетаскивание ----------

    private final class Drag implements View.OnTouchListener {
        private final WindowManager.LayoutParams lp;
        private final boolean isBubble;
        private final int slop = ViewConfiguration.get(FloatService.this).getScaledTouchSlop();
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        Drag(WindowManager.LayoutParams lp, boolean isBubble) {
            this.lp = lp;
            this.isBubble = isBubble;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            View win = isBubble ? bubble : panel;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;
                    startY = lp.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                    if (moved) {
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        clamp(lp, win);
                        safeUpdate(win, lp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (isBubble) {
                        if (moved) snapBubble();
                        else expand();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    // ---------- утилиты окна ----------

    private int screenW() {
        if (Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds().width();
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        if (Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds().height();
        return getResources().getDisplayMetrics().heightPixels;
    }

    private void clamp(WindowManager.LayoutParams lp, View v) {
        int vw = lp.width > 0 ? lp.width : (v != null ? v.getWidth() : 0);
        int vh = lp.height > 0 ? lp.height : (v != null ? v.getHeight() : 0);
        lp.x = Math.max(0, Math.min(lp.x, screenW() - vw));
        lp.y = Math.max(0, Math.min(lp.y, screenH() - vh));
    }

    private void safeUpdate(View v, WindowManager.LayoutParams lp) {
        if (v == null || !v.isAttachedToWindow()) return;
        try {
            wm.updateViewLayout(v, lp);
        } catch (Exception ignored) {
        }
    }

    private void removeSafe(View v) {
        if (wm == null || v == null || !v.isAttachedToWindow()) return;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    private LinearLayout card() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(this, Ui.CARD, 12));
        int p = Ui.dp(this, 10);
        row.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        return row;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams l = Ui.lp(MATCH, WRAP, 0);
        l.bottomMargin = Ui.dp(this, 6);
        return l;
    }

    private LinearLayout.LayoutParams sq(int sizeDp, int rightDp) {
        LinearLayout.LayoutParams l = Ui.lp(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp), 0);
        l.rightMargin = Ui.dp(this, rightDp);
        return l;
    }
}
