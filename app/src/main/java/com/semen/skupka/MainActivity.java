package com.semen.skupka;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Стартовый экран: выдать разрешение и запустить плавающий кружок. */
public class MainActivity extends Activity {

    private TextView status;
    private TextView permBtn;
    private TextView startBtn;
    private TextView stopBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(0xFF0F1115);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 22);
        root.setPadding(p, Ui.dp(this, 40), p, p);
        scroll.addView(root);

        TextView gem = Ui.text(this, "💎", 52, Ui.WHITE, false);
        gem.setGravity(Gravity.CENTER);
        root.addView(gem);

        TextView title = Ui.text(this, "Скупка ресурсов", 26, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = Ui.text(this,
                "Плавающий калькулятор для скупки: цены на ресурсы, подсчёт трейда и готовое объявление для чата.",
                15, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 20));
        root.addView(sub);

        status = Ui.text(this, "", 15, Ui.TEXT, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, Ui.dp(this, 14));
        root.addView(status);

        permBtn = Ui.button(this, "Разрешить показ поверх других приложений", Ui.CARD, Ui.TEXT);
        permBtn.setOnClickListener(v -> openOverlaySettings());
        root.addView(permBtn, full(10));

        startBtn = Ui.button(this, "Запустить кружок", Ui.ACCENT, Ui.WHITE);
        startBtn.setTextSize(17);
        startBtn.setOnClickListener(v -> startBubble());
        root.addView(startBtn, full(10));

        stopBtn = Ui.button(this, "Убрать кружок", Ui.CARD, Ui.RED);
        stopBtn.setOnClickListener(v -> {
            stopService(new Intent(this, FloatService.class));
            Toast.makeText(this, "Кружок убран", Toast.LENGTH_SHORT).show();
        });
        root.addView(stopBtn, full(22));

        TextView how = Ui.text(this,
                "Как пользоваться\n\n"
                        + "• Нажмите на кружок, откроется панель. «—» сворачивает её, кружок и панель перетаскиваются пальцем.\n"
                        + "• «Цены»: для железа, жемчуга, рубинов, сапфиров, изумрудов и алмазов включите скупку, впишите цену за 1 шт. и лимит штук за сделку. Там же лимит суммы сделки и общий бюджет.\n"
                        + "• «Трейд»: впишите, что вам дают, и нажмите «Проверить». Приложение посчитает сумму (10 × 500 = 5 000$) и проверит лимиты. Передайте деньги в игре и нажмите «Сделка проведена».\n"
                        + "• «Чат»: готовое объявление «КУПЛЮ ЖЕЛЕЗО — 500$/шт | …». «Отправить объявление» копирует его, вставьте в чат. «Автообъявления» напоминают отправить его через заданный интервал.\n"
                        + "• «Журнал»: все сделки и отказы, сколько потрачено и куплено.\n"
                        + "• Кнопка «● Работает / ⏸ Пауза» вверху панели запускает и останавливает скупку.\n\n"
                        + "Приложение не вмешивается в игру: оно не пишет в чат и не принимает трейды само, это запрещено правилами игры.",
                14, Ui.TEXT, false);
        how.setLineSpacing(0, 1.15f);
        how.setBackground(Ui.round(this, Ui.CARD, 14));
        int cp = Ui.dp(this, 16);
        how.setPadding(cp, cp, cp, cp);
        root.addView(how, full(14));

        TextView note = Ui.text(this,
                "Xiaomi / Redmi / POCO: в настройках приложения «Скупка» → «Другие разрешения» включите также «Отображать всплывающие окна, когда запущено в фоновом режиме».",
                13, Ui.MUTED, false);
        root.addView(note);

        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private LinearLayout.LayoutParams full(int bottomDp) {
        LinearLayout.LayoutParams l = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        l.bottomMargin = Ui.dp(this, bottomDp);
        return l;
    }

    private void refresh() {
        boolean ok = Settings.canDrawOverlays(this);
        status.setText(ok ? "✅ Разрешение выдано" : "⚠️ Сначала разрешите показ поверх других приложений");
        status.setTextColor(ok ? Ui.GREEN : 0xFFFBBF24);
        permBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
        startBtn.setAlpha(ok ? 1f : 0.5f);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Включите «Поверх других приложений» для «Скупка»", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        if (!FloatService.running) {
            startForegroundService(new Intent(this, FloatService.class));
        }
        Toast.makeText(this, "Кружок на экране, можно открывать игру", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }
}
