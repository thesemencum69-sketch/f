package com.semen.skupka;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Плавающий кружок и панель управления скупкой поверх игры.
 * В игру не вмешивается: считает, проверяет, готовит объявления, ведёт журнал.
 */
public class FloatService extends Service {

    static final String ACTION_STOP = "com.semen.skupka.STOP";
    private static final String CH_RUN = "float";
    private static final String CH_ADS = "ads";
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private static final int TAB_TRADE = 0, TAB_PRICES = 1, TAB_CHAT = 2, TAB_LOG = 3;

    static volatile boolean running = false;

    private WindowManager wm;
    private Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // кружок
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private ObjectAnimator pulse;

    // панель
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView statusPill;
    private final TextView[] tabs = new TextView[4];
    private int tab = TAB_TRADE;

    // вкладка «Трейд»
    private Logic.Check lastCheck;
    private String lastFp;
    private boolean confirmArmed;
    private TextView resultView, totalLive, confirmBtn, rejectBtn;

    // вкладка «Чат»
    private TextView adPreview, nextAdView;
    private long nextAdAt = 0;

    private final Runnable adTick = this::onAdTick;

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
        scheduleAds();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (pulse != null) pulse.cancel();
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

    // ================= уведомления =================

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel run = new NotificationChannel(CH_RUN, "Плавающее окно", NotificationManager.IMPORTANCE_LOW);
        run.setShowBadge(false);
        nm.createNotificationChannel(run);
        NotificationChannel ads = new NotificationChannel(CH_ADS, "Напоминания об объявлении", NotificationManager.IMPORTANCE_DEFAULT);
        ads.setShowBadge(false);
        nm.createNotificationChannel(ads);

        int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), f);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FloatService.class).setAction(ACTION_STOP), f);

        Notification n = new Notification.Builder(this, CH_RUN)
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

    // ================= напоминания об объявлении =================

    /** Перезапускает таймер напоминаний по текущим настройкам. */
    private void scheduleAds() {
        handler.removeCallbacks(adTick);
        if (store.running && store.autoAds) {
            long ms = Math.max(1, store.adIntervalMin) * 60_000L;
            nextAdAt = System.currentTimeMillis() + ms;
            handler.postDelayed(adTick, ms);
        } else {
            nextAdAt = 0;
        }
        updateNextAd();
    }

    private void onAdTick() {
        if (!store.running || !store.autoAds) return;
        String text = store.adText();
        copy(text);
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification n = new Notification.Builder(this, CH_ADS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Пора отправить объявление")
                .setContentText("Текст уже скопирован, вставьте его в чат игры")
                .setAutoCancel(true)
                .setTimeoutAfter(90_000)
                .build();
        try {
            nm.notify(2, n);
        } catch (SecurityException ignored) {
        }
        Toast.makeText(this, "📢 Пора отправить объявление: текст скопирован", Toast.LENGTH_LONG).show();
        setBubbleAlert(true);
        scheduleAds();
    }

    private void updateNextAd() {
        if (nextAdView == null) return;
        if (!store.autoAds) {
            nextAdView.setText("Напоминания выключены");
        } else if (!store.running) {
            nextAdView.setText("Скупка на паузе, напоминаний нет");
        } else {
            String t = new SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")).format(new Date(nextAdAt));
            nextAdView.setText("Следующее напоминание в " + t);
        }
    }

    // ================= кружок =================

    private void showBubble() {
        if (bubble == null) {
            bubble = new TextView(this);
            bubble.setText("💎");
            bubble.setTextSize(24);
            bubble.setGravity(Gravity.CENTER);
            styleBubble(false);
            bubble.setAlpha(0.93f);
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

    private void styleBubble(boolean alert) {
        int[] colors = alert ? new int[]{0xFF34D6C0, 0xFF0F766E} : new int[]{0xFF8B5CF6, 0xFF4C1D95};
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(Ui.dp(this, 2), store != null && !store.running ? 0xAAF87171 : 0x66FFFFFF);
        bubble.setBackground(g);
    }

    private void setBubbleAlert(boolean alert) {
        if (bubble == null) return;
        bubble.setText(alert ? "📢" : "💎");
        styleBubble(alert);
        if (alert) {
            if (pulse == null) {
                pulse = ObjectAnimator.ofPropertyValuesHolder(bubble,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f));
                pulse.setDuration(550);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
            }
            if (!pulse.isRunning()) pulse.start();
        } else {
            if (pulse != null) pulse.cancel();
            bubble.setScaleX(1f);
            bubble.setScaleY(1f);
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

    // ================= панель =================

    private void expand() {
        setBubbleAlert(false);
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
        styleBubble(false);
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

        // Шапка: заголовок, статус, свернуть, закрыть. За шапку панель перетаскивается.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 10));
        header.addView(Ui.text(this, "💎 Скупка", 17, Ui.TEXT, true), Ui.lp(0, WRAP, 1));
        statusPill = Ui.pill(this);
        statusPill.setOnClickListener(v -> toggleRunning());
        LinearLayout.LayoutParams sl = Ui.lp(WRAP, WRAP, 0);
        sl.rightMargin = Ui.dp(this, 8);
        header.addView(statusPill, sl);
        TextView min = Ui.button(this, "—", Ui.CARD, Ui.TEXT);
        min.setOnClickListener(v -> collapse());
        header.addView(min, sq(38, 6));
        TextView close = Ui.button(this, "✕", Ui.CARD, Ui.RED);
        close.setOnClickListener(v -> stopSelf());
        header.addView(close, sq(38, 0));
        header.setOnTouchListener(new Drag(panelLp, false));
        panel.addView(header, Ui.lp(MATCH, WRAP, 0));

        // Вкладки.
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Трейд", "Цены", "Чат", "Журнал"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = Ui.button(this, names[i], Ui.CARD, Ui.MUTED);
            t.setTextSize(13);
            t.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
            t.setOnClickListener(v -> switchTab(idx));
            LinearLayout.LayoutParams l = Ui.lp(0, WRAP, 1);
            if (i < 3) l.rightMargin = Ui.dp(this, 5);
            tabRow.addView(t, l);
            tabs[i] = t;
        }
        LinearLayout.LayoutParams tl = Ui.lp(MATCH, WRAP, 0);
        tl.bottomMargin = Ui.dp(this, 10);
        panel.addView(tabRow, tl);

        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(MATCH, WRAP));
        panel.addView(scroll, Ui.lp(MATCH, 0, 1));
    }

    private void sizePanel() {
        int w = screenW(), h = screenH();
        panelLp.width = Math.min(w - Ui.dp(this, 16), Ui.dp(this, 350));
        panelLp.height = Math.min(h - Ui.dp(this, 32), Ui.dp(this, 520));
        boolean left = bubbleLp == null || bubbleLp.x + bubbleLp.width / 2 < w / 2;
        panelLp.x = left ? Ui.dp(this, 8) : w - panelLp.width - Ui.dp(this, 8);
        panelLp.y = Math.max(0, (h - panelLp.height) / 2);
    }

    private void toggleRunning() {
        store.running = !store.running;
        store.save();
        scheduleAds();
        updateStatusPill();
        Toast.makeText(this, store.running ? "Скупка запущена" : "Скупка на паузе", Toast.LENGTH_SHORT).show();
        if (tab == TAB_TRADE) rebuildContent();
    }

    private void updateStatusPill() {
        Ui.stylePill(this, statusPill, store.running, "● Работает", "⏸ Пауза");
        if (!store.running) statusPill.setTextColor(Ui.RED);
    }

    private void switchTab(int t) {
        if (tab == t) return;
        tab = t;
        setPanelFocusable(false);
        invalidateCheck(false);
        rebuildContent();
        scroll.scrollTo(0, 0);
    }

    private void rebuildContent() {
        content.removeAllViews();
        resultView = totalLive = confirmBtn = rejectBtn = null;
        adPreview = nextAdView = null;
        updateStatusPill();
        for (int i = 0; i < 4; i++) {
            tabs[i].setBackground(Ui.round(this, i == tab ? Ui.ACCENT : Ui.CARD, 10));
            tabs[i].setTextColor(i == tab ? Ui.WHITE : Ui.MUTED);
        }
        switch (tab) {
            case TAB_PRICES: buildPrices(); break;
            case TAB_CHAT: buildChat(); break;
            case TAB_LOG: buildLog(); break;
            default: buildTrade();
        }
    }

    // ================= вкладка «Трейд» =================

    private void buildTrade() {
        String budgetLine = store.budget > 0
                ? "Бюджет: осталось " + Ui.fmt(Math.max(0, store.budget - store.spent)) + "$ из " + Ui.fmt(store.budget) + "$"
                : "Бюджет не ограничен";
        if (store.maxDeal > 0) budgetLine += " · сделка до " + Ui.fmt(store.maxDeal) + "$";
        content.addView(hint(budgetLine));
        if (!store.running) {
            TextView paused = Ui.text(this, "Скупка на паузе. Нажмите «⏸ Пауза» вверху, чтобы продолжить.", 13, Ui.RED, false);
            paused.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 8));
            content.addView(paused);
        }
        content.addView(hint("Впишите, сколько вам дают в трейде:"));

        for (Logic.Res r : store.items) {
            LinearLayout row = card();

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(Ui.text(this, r.name, 15, r.enabled ? Ui.TEXT : Ui.MUTED, true));
            String sub;
            int subColor = Ui.MUTED;
            if (!r.enabled) {
                sub = "не скупаю";
                subColor = Ui.RED;
            } else if (r.price <= 0) {
                sub = "цена не задана";
                subColor = Ui.RED;
            } else {
                sub = "по " + Ui.fmt(r.price) + "$" + (r.maxQty > 0 ? " · до " + Ui.fmt(r.maxQty) + " шт." : "");
            }
            left.addView(Ui.text(this, sub, 12, subColor, false));
            row.addView(left, Ui.lp(0, WRAP, 1));

            EditText qty = Ui.field(this, "0", true, 7);
            if (r.offer > 0) qty.setText(String.valueOf(r.offer));
            LinearLayout.LayoutParams ql = Ui.lp(Ui.dp(this, 66), WRAP, 0);
            ql.leftMargin = Ui.dp(this, 6);
            row.addView(qty, ql);

            TextView sum = Ui.text(this, lineText(r), 14, Ui.GREEN, true);
            sum.setGravity(Gravity.END);
            sum.setSingleLine(true);
            row.addView(sum, Ui.lp(Ui.dp(this, 92), WRAP, 0));

            qty.addTextChangedListener(new Ui.Watcher(s -> {
                r.offer = Ui.parse(s);
                sum.setText(lineText(r));
                store.save();
                invalidateCheck(true);
                updateLiveTotal();
            }));
            attachEdit(qty);
            content.addView(row, rowLp());
        }

        totalLive = Ui.text(this, "", 16, Ui.TEXT, true);
        totalLive.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), 0, Ui.dp(this, 8));
        content.addView(totalLive);
        updateLiveTotal();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView checkBtn = Ui.button(this, "Проверить", Ui.ACCENT, Ui.WHITE);
        checkBtn.setOnClickListener(v -> runCheck());
        LinearLayout.LayoutParams cl = Ui.lp(0, WRAP, 1.6f);
        cl.rightMargin = Ui.dp(this, 6);
        actions.addView(checkBtn, cl);
        TextView clear = Ui.button(this, "Очистить", Ui.CARD, Ui.TEXT);
        clear.setOnClickListener(v -> {
            for (Logic.Res r : store.items) r.offer = 0;
            store.save();
            lastCheck = null;
            setPanelFocusable(false);
            rebuildContent();
        });
        actions.addView(clear, Ui.lp(0, WRAP, 1));
        content.addView(actions, rowLp());

        resultView = Ui.text(this, "Нажмите «Проверить», чтобы посчитать сумму и проверить сделку.", 14, Ui.MUTED, false);
        resultView.setBackground(Ui.round(this, Ui.CARD, 12));
        int p = Ui.dp(this, 12);
        resultView.setPadding(p, p, p, p);
        resultView.setLineSpacing(0, 1.1f);
        content.addView(resultView, rowLp());

        confirmBtn = Ui.button(this, "", 0xFF0F766E, Ui.WHITE);
        confirmBtn.setOnClickListener(v -> onConfirm());
        content.addView(confirmBtn, rowLp());

        rejectBtn = Ui.button(this, "✗ Записать отказ в журнал", Ui.CARD, Ui.RED);
        rejectBtn.setOnClickListener(v -> onReject());
        content.addView(rejectBtn, rowLp());

        showCheckResult();
    }

    private String lineText(Logic.Res r) {
        if (r.offer <= 0) return "";
        if (!r.enabled || r.price <= 0) return "✗";
        try {
            return "= " + Ui.fmt(Math.multiplyExact(r.price, r.offer)) + "$";
        } catch (ArithmeticException e) {
            return "✗";
        }
    }

    private void updateLiveTotal() {
        if (totalLive == null) return;
        Logic.Check c = Logic.check(store.items, 0, 0, 0);
        totalLive.setText(c.total > 0 ? "Сумма по ценам: " + Ui.fmt(c.total) + "$" : "");
    }

    private String fp() {
        return Logic.fingerprint(store.items, store.maxDeal, store.budget, store.spent);
    }

    private void runCheck() {
        setPanelFocusable(false);
        lastCheck = Logic.check(store.items, store.maxDeal, store.budget, store.spent);
        lastFp = fp();
        confirmArmed = false;
        showCheckResult();
    }

    /** Любое изменение после расчёта сбрасывает его: оплатить можно только после новой проверки. */
    private void invalidateCheck(boolean showHint) {
        if (lastCheck == null) return;
        lastCheck = null;
        lastFp = null;
        confirmArmed = false;
        if (showHint && resultView != null) {
            showCheckResult();
            resultView.setText("Данные изменились. Нажмите «Проверить» ещё раз.");
        }
    }

    private void showCheckResult() {
        if (resultView == null) return;
        confirmBtn.setVisibility(View.GONE);
        rejectBtn.setVisibility(View.GONE);
        if (lastCheck == null) {
            resultView.setTextColor(Ui.MUTED);
            resultView.setText("Нажмите «Проверить», чтобы посчитать сумму и проверить сделку.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (lastCheck.ok) {
            sb.append("✅ Можно платить: ").append(Ui.fmt(lastCheck.total)).append("$\n\n");
            for (String l : lastCheck.lines) sb.append(l).append('\n');
            sb.append("\nСверьте количество в окне трейда игры, передайте ровно ")
                    .append(Ui.fmt(lastCheck.total)).append("$ и нажмите кнопку ниже.");
            resultView.setTextColor(Ui.TEXT);
            confirmBtn.setVisibility(View.VISIBLE);
            confirmBtn.setText(store.running ? "✓ Сделка проведена, записать" : "Скупка на паузе");
            confirmBtn.setAlpha(store.running ? 1f : 0.5f);
            rejectBtn.setText("✗ Отказаться и записать в журнал");
            rejectBtn.setVisibility(View.VISIBLE);
        } else {
            sb.append("⛔ Не платить\n\n");
            for (String pr : lastCheck.problems) sb.append("• ").append(pr).append('\n');
            resultView.setTextColor(Ui.RED);
            rejectBtn.setText("✗ Записать отказ в журнал");
            rejectBtn.setVisibility(View.VISIBLE);
        }
        resultView.setText(sb.toString().trim());
    }

    private void onConfirm() {
        if (!store.running) {
            Toast.makeText(this, "Скупка на паузе. Включите её кнопкой вверху.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (lastCheck == null || !lastCheck.ok || !fp().equals(lastFp)) {
            invalidateCheck(true);
            Toast.makeText(this, "Данные изменились, проверьте заново", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!confirmArmed) {
            confirmArmed = true;
            confirmBtn.setText("Нажмите ещё раз: отдано " + Ui.fmt(lastCheck.total) + "$");
            handler.postDelayed(() -> {
                if (confirmArmed && confirmBtn != null && lastCheck != null) {
                    confirmArmed = false;
                    confirmBtn.setText("✓ Сделка проведена, записать");
                }
            }, 4000);
            return;
        }
        Logic.Entry e = Logic.commit(store.items, lastCheck, lastFp,
                store.maxDeal, store.budget, store.spent, System.currentTimeMillis());
        lastCheck = null;
        lastFp = null;
        confirmArmed = false;
        if (e == null) {
            Toast.makeText(this, "Сделка не записана: данные не совпали. Проверьте заново.", Toast.LENGTH_LONG).show();
            rebuildContent();
            return;
        }
        store.spent += e.sum;
        store.addEntry(e);
        store.save();
        Toast.makeText(this, "Записано: −" + Ui.fmt(e.sum) + "$", Toast.LENGTH_SHORT).show();
        rebuildContent();
    }

    private void onReject() {
        Logic.Check c = lastCheck;
        boolean any = false;
        for (Logic.Res r : store.items) if (r.offer > 0) any = true;
        if (!any) {
            Toast.makeText(this, "Трейд пустой, записывать нечего", Toast.LENGTH_SHORT).show();
            return;
        }
        Logic.Entry e = Logic.reject(store.items, c != null && !c.ok ? c : null, System.currentTimeMillis());
        store.addEntry(e);
        store.save();
        lastCheck = null;
        lastFp = null;
        Toast.makeText(this, "Отказ записан в журнал", Toast.LENGTH_SHORT).show();
        rebuildContent();
    }

    // ================= вкладка «Цены» =================

    private void buildPrices() {
        content.addView(hint("Цена за 1 шт. и лимит штук за одну сделку (0 = без лимита). Меняется на ходу."));

        for (Logic.Res r : store.items) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(Ui.round(this, Ui.CARD, 12));
            int p = Ui.dp(this, 10);
            box.setPadding(p, p, p, p);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(Ui.text(this, r.name, 15, Ui.TEXT, true), Ui.lp(0, WRAP, 1));
            TextView pill = Ui.pill(this);
            Ui.stylePill(this, pill, r.enabled, "Скупаю", "Выкл");
            pill.setOnClickListener(v -> {
                r.enabled = !r.enabled;
                store.save();
                Ui.stylePill(this, pill, r.enabled, "Скупаю", "Выкл");
            });
            top.addView(pill);
            box.addView(top);

            LinearLayout fields = new LinearLayout(this);
            fields.setOrientation(LinearLayout.HORIZONTAL);
            fields.setPadding(0, Ui.dp(this, 8), 0, 0);

            EditText price = Ui.field(this, "цена $", true, 10);
            if (r.price > 0) price.setText(String.valueOf(r.price));
            price.addTextChangedListener(new Ui.Watcher(s -> {
                r.price = Ui.parse(s);
                store.save();
            }));
            attachEdit(price);
            fields.addView(labeled("$ за 1 шт.", price), withRight(Ui.lp(0, WRAP, 1), 8));

            EditText max = Ui.field(this, "0", true, 7);
            if (r.maxQty > 0) max.setText(String.valueOf(r.maxQty));
            max.addTextChangedListener(new Ui.Watcher(s -> {
                r.maxQty = Ui.parse(s);
                store.save();
            }));
            attachEdit(max);
            fields.addView(labeled("макс. шт. за сделку", max), Ui.lp(0, WRAP, 1));
            box.addView(fields);

            content.addView(box, rowLp());
        }

        TextView sec = Ui.text(this, "Сделка и бюджет", 15, Ui.TEXT, true);
        sec.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, Ui.dp(this, 4));
        content.addView(sec);

        EditText maxDeal = Ui.field(this, "0 = без лимита", true, 12);
        if (store.maxDeal > 0) maxDeal.setText(String.valueOf(store.maxDeal));
        maxDeal.addTextChangedListener(new Ui.Watcher(s -> {
            store.maxDeal = Ui.parse(s);
            store.save();
        }));
        attachEdit(maxDeal);
        content.addView(labeled("Макс. сумма одной сделки, $", maxDeal), rowLp());

        EditText budget = Ui.field(this, "0 = без лимита", true, 12);
        if (store.budget > 0) budget.setText(String.valueOf(store.budget));
        budget.addTextChangedListener(new Ui.Watcher(s -> {
            store.budget = Ui.parse(s);
            store.save();
        }));
        attachEdit(budget);
        content.addView(labeled("Бюджет на скупку, $", budget), rowLp());

        content.addView(hint("Приложение не видит ваш баланс в игре, поэтому следит за бюджетом: "
                + "сделку больше остатка оно не пропустит. Потрачено сейчас: " + Ui.fmt(store.spent) + "$."));
    }

    // ================= вкладка «Чат» =================

    private void buildChat() {
        adPreview = Ui.text(this, "", 14, Ui.TEXT, false);
        adPreview.setBackground(Ui.round(this, Ui.CARD, 12));
        int p = Ui.dp(this, 12);
        adPreview.setPadding(p, p, p, p);
        content.addView(adPreview, rowLp());
        updateAdPreview();

        TextView send = Ui.button(this, "📋 Отправить объявление", Ui.ACCENT, Ui.WHITE);
        send.setOnClickListener(v -> {
            copy(store.adText());
            Toast.makeText(this, "Скопировано. Откройте чат игры и вставьте.", Toast.LENGTH_SHORT).show();
            collapse();
        });
        content.addView(send, rowLp());
        content.addView(hint("Кнопка копирует текст и сворачивает панель. Вставьте его в чат игры. "
                + "Само приложение сообщения не отправляет: так нельзя по правилам игры."));

        // Автообъявления
        LinearLayout auto = card();
        auto.addView(Ui.text(this, "Автообъявления", 15, Ui.TEXT, true), Ui.lp(0, WRAP, 1));
        TextView autoPill = Ui.pill(this);
        Ui.stylePill(this, autoPill, store.autoAds, "ВКЛ", "ВЫКЛ");
        autoPill.setOnClickListener(v -> {
            store.autoAds = !store.autoAds;
            store.save();
            Ui.stylePill(this, autoPill, store.autoAds, "ВКЛ", "ВЫКЛ");
            scheduleAds();
        });
        auto.addView(autoPill);
        content.addView(auto, rowLp());

        EditText interval = Ui.field(this, "мин.", true, 3);
        interval.setText(String.valueOf(store.adIntervalMin));
        interval.addTextChangedListener(new Ui.Watcher(s -> {
            long v = Ui.parse(s);
            if (v < 1) return; // пустое поле или 0 — ждём нормального значения
            store.adIntervalMin = v;
            store.save();
            scheduleAds();
        }));
        attachEdit(interval);
        content.addView(labeled("Интервал, минут (не меньше 1)", interval), rowLp());

        nextAdView = Ui.text(this, "", 13, Ui.GREEN, true);
        nextAdView.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 6));
        content.addView(nextAdView);
        updateNextAd();
        content.addView(hint("В нужный момент кружок замигает 📢 и придёт уведомление, а текст уже будет скопирован."));

        // Ресурсы в объявлении
        TextView sec = Ui.text(this, "Что писать в объявлении", 15, Ui.TEXT, true);
        sec.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, Ui.dp(this, 4));
        content.addView(sec);
        for (Logic.Res r : store.items) {
            LinearLayout row = card();
            String note = !r.enabled ? " (не скупаю)" : r.price <= 0 ? " (нет цены)" : "";
            row.addView(Ui.text(this, r.name + note, 14, r.enabled && r.price > 0 ? Ui.TEXT : Ui.MUTED, false), Ui.lp(0, WRAP, 1));
            TextView pill = Ui.pill(this);
            Ui.stylePill(this, pill, r.inAd, "В тексте", "Скрыт");
            pill.setOnClickListener(v -> {
                r.inAd = !r.inAd;
                store.save();
                Ui.stylePill(this, pill, r.inAd, "В тексте", "Скрыт");
                updateAdPreview();
            });
            row.addView(pill);
            content.addView(row, rowLp());
        }

        EditText start = Ui.field(this, "например: КУПЛЮ", false, 60);
        start.setText(store.adStart);
        start.addTextChangedListener(new Ui.Watcher(s -> {
            store.adStart = s;
            store.save();
            updateAdPreview();
        }));
        attachEdit(start);
        content.addView(labeled("Начало текста", start), rowLp());

        EditText end = Ui.field(this, "например: пишите в ЛС", false, 60);
        end.setText(store.adEnd);
        end.addTextChangedListener(new Ui.Watcher(s -> {
            store.adEnd = s;
            store.save();
            updateAdPreview();
        }));
        attachEdit(end);
        content.addView(labeled("Конец текста", end), rowLp());
    }

    private void updateAdPreview() {
        if (adPreview == null) return;
        String t = store.adText();
        if (t.trim().isEmpty()) {
            adPreview.setText("Объявление пустое: задайте цены во вкладке «Цены».");
            adPreview.setTextColor(Ui.MUTED);
        } else {
            adPreview.setText(t + "\n\n" + t.length() + " симв.");
            adPreview.setTextColor(Ui.TEXT);
        }
    }

    // ================= вкладка «Журнал» =================

    private void buildLog() {
        int okCount = 0, noCount = 0;
        for (Logic.Entry e : store.journal) {
            if (e.ok) okCount++;
            else noCount++;
        }
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);
        stats.setBackground(Ui.round(this, Ui.CARD, 12));
        int p = Ui.dp(this, 12);
        stats.setPadding(p, p, p, p);
        stats.addView(Ui.text(this, "Потрачено: " + Ui.fmt(store.spent) + "$", 18, Ui.GREEN, true));
        if (store.budget > 0) {
            stats.addView(Ui.text(this, "Бюджет: осталось " + Ui.fmt(Math.max(0, store.budget - store.spent))
                    + "$ из " + Ui.fmt(store.budget) + "$", 13, Ui.MUTED, false));
        }
        stats.addView(Ui.text(this, "Сделок: " + okCount + " · отказов: " + noCount, 13, Ui.MUTED, false));
        StringBuilder bought = new StringBuilder();
        for (Logic.Res r : store.items) {
            if (r.bought <= 0) continue;
            bought.append(r.name).append(": ").append(Ui.fmt(r.bought)).append(" шт.\n");
        }
        TextView b = Ui.text(this, bought.length() == 0 ? "Куплено: пока ничего" : "Куплено:\n" + bought.toString().trim(),
                14, Ui.TEXT, false);
        b.setPadding(0, Ui.dp(this, 8), 0, 0);
        stats.addView(b);
        content.addView(stats, rowLp());

        if (store.journal.isEmpty()) {
            content.addView(hint("Журнал пуст. Здесь появятся проведённые сделки и отказы."));
        } else {
            SimpleDateFormat df = new SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("ru"));
            int shown = 0;
            for (Logic.Entry e : store.journal) {
                if (shown++ >= 60) break;
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setBackground(Ui.round(this, Ui.CARD, 10));
                int bp = Ui.dp(this, 10);
                box.setPadding(bp, Ui.dp(this, 8), bp, Ui.dp(this, 8));
                String head = (e.ok ? "✓ " : "✗ ") + df.format(new Date(e.time))
                        + (e.ok ? " · −" + Ui.fmt(e.sum) + "$" : " · отказ");
                box.addView(Ui.text(this, head, 14, e.ok ? Ui.GREEN : Ui.RED, true));
                box.addView(Ui.text(this, e.details, 13, Ui.TEXT, false));
                if (!e.ok && !e.reason.isEmpty()) box.addView(Ui.text(this, e.reason, 12, Ui.MUTED, false));
                content.addView(box, rowLp());
            }
        }

        TextView reset = Ui.button(this, "Сбросить журнал и статистику", Ui.CARD, Ui.RED);
        final boolean[] armed = {false};
        reset.setOnClickListener(v -> {
            if (!armed[0]) {
                armed[0] = true;
                reset.setText("Точно? Нажмите ещё раз");
                handler.postDelayed(() -> {
                    armed[0] = false;
                    reset.setText("Сбросить журнал и статистику");
                }, 4000);
                return;
            }
            store.resetStats();
            store.save();
            Toast.makeText(this, "Журнал и статистика сброшены", Toast.LENGTH_SHORT).show();
            rebuildContent();
        });
        LinearLayout.LayoutParams rl = rowLp();
        rl.topMargin = Ui.dp(this, 8);
        content.addView(reset, rl);
    }

    // ================= клавиатура и фокус =================

    private boolean isPanelFocusable() {
        return panelLp != null && (panelLp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
    }

    /** Панель забирает ввод только пока вы печатаете; тап мимо панели отдаёт управление игре. */
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

    // ================= перетаскивание =================

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

    // ================= утилиты =================

    private void copy(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Объявление", text));
    }

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

    private TextView hint(String s) {
        TextView t = Ui.text(this, s, 12, Ui.MUTED, false);
        t.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 8));
        return t;
    }

    private LinearLayout labeled(String label, View field) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, label, 12, Ui.MUTED, false);
        t.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 3));
        l.addView(t);
        l.addView(field, Ui.lp(MATCH, WRAP, 0));
        return l;
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

    private LinearLayout.LayoutParams withRight(LinearLayout.LayoutParams l, int rightDp) {
        l.rightMargin = Ui.dp(this, rightDp);
        return l;
    }

    private LinearLayout.LayoutParams sq(int sizeDp, int rightDp) {
        LinearLayout.LayoutParams l = Ui.lp(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp), 0);
        l.rightMargin = Ui.dp(this, rightDp);
        return l;
    }
}
