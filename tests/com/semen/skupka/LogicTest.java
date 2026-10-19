package com.semen.skupka;

import java.util.List;

public class LogicTest {
    static int fails = 0;

    static void eq(String name, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        System.out.println((ok ? "OK   " : "FAIL ") + name + (ok ? "" : "  got=" + got + " want=" + want));
        if (!ok) fails++;
    }

    static Logic.Res res(List<Logic.Res> l, String n) {
        for (Logic.Res r : l) if (r.name.equals(n)) return r;
        throw new RuntimeException(n);
    }

    static List<Logic.Res> priced() {
        List<Logic.Res> l = Logic.defaults();
        long[] p = {500, 2000, 5000, 7000, 10000, 20000};
        for (int i = 0; i < l.size(); i++) l.get(i).price = p[i];
        return l;
    }

    public static void main(String[] a) {
        // Пример из ТЗ: 10 железа по 500 = 5 000
        List<Logic.Res> l = priced();
        res(l, "Железо").offer = 10;
        Logic.Check c = Logic.check(l, 0, 0, 0);
        eq("iron 10x500 ok", c.ok, true);
        eq("iron 10x500 total", c.total, 5000);

        // Пример из первого запроса: 8 по 10 = 80
        l = Logic.defaults();
        res(l, "Железо").price = 10;
        res(l, "Железо").offer = 8;
        eq("8x10", Logic.check(l, 0, 0, 0).total, 80);

        // Несколько ресурсов складываются
        l = priced();
        res(l, "Железо").offer = 10;    // 5 000
        res(l, "Жемчуг").offer = 3;     // 6 000
        res(l, "Алмазы").offer = 2;     // 40 000
        c = Logic.check(l, 0, 0, 0);
        eq("multi total", c.total, 51000);
        eq("multi lines", c.lines.size(), 3);

        // Выключенный ресурс -> отказ
        l = priced();
        res(l, "Рубины").enabled = false;
        res(l, "Рубины").offer = 1;
        c = Logic.check(l, 0, 0, 0);
        eq("disabled rejected", c.ok, false);

        // Нет цены -> отказ
        l = Logic.defaults();
        res(l, "Сапфиры").offer = 5;
        eq("no price rejected", Logic.check(l, 0, 0, 0).ok, false);

        // Пустой трейд -> отказ
        eq("empty rejected", Logic.check(priced(), 0, 0, 0).ok, false);

        // Лимит штук
        l = priced();
        res(l, "Железо").maxQty = 50;
        res(l, "Железо").offer = 51;
        eq("qty limit rejected", Logic.check(l, 0, 0, 0).ok, false);
        res(l, "Железо").offer = 50;
        eq("qty at limit ok", Logic.check(l, 0, 0, 0).ok, true);

        // Лимит суммы сделки
        l = priced();
        res(l, "Алмазы").offer = 3; // 60 000
        eq("deal limit rejected", Logic.check(l, 50000, 0, 0).ok, false);
        eq("deal limit ok", Logic.check(l, 60000, 0, 0).ok, true);

        // Бюджет
        eq("budget rejected", Logic.check(l, 0, 100000, 50000).ok, false);
        eq("budget ok", Logic.check(l, 0, 100000, 40000).ok, true);

        // Переполнение
        l = priced();
        res(l, "Алмазы").price = Long.MAX_VALUE / 2;
        res(l, "Алмазы").offer = 3;
        eq("overflow rejected", Logic.check(l, 0, 0, 0).ok, false);

        // Проведение сделки
        l = priced();
        res(l, "Железо").offer = 10;
        c = Logic.check(l, 0, 0, 0);
        String fp = Logic.fingerprint(l, 0, 0, 0);
        Logic.Entry e = Logic.commit(l, c, fp, 0, 0, 0, 1L);
        eq("commit ok", e != null && e.ok, true);
        eq("commit sum", e.sum, 5000);
        eq("bought iron", res(l, "Железо").bought, 10);
        eq("offer reset", res(l, "Железо").offer, 0);
        // Повторная обработка того же трейда запрещена
        eq("double commit blocked", Logic.commit(l, c, fp, 0, 0, 0, 2L), null);

        // Изменили количество после расчёта -> оплата запрещена
        l = priced();
        res(l, "Железо").offer = 10;
        c = Logic.check(l, 0, 0, 0);
        fp = Logic.fingerprint(l, 0, 0, 0);
        res(l, "Железо").offer = 9;
        eq("changed qty blocked", Logic.commit(l, c, fp, 0, 0, 0, 3L), null);
        eq("bought untouched", res(l, "Железо").bought, 0);

        // Изменили цену после расчёта -> запрещено
        res(l, "Железо").offer = 10;
        res(l, "Железо").price = 600;
        eq("changed price blocked", Logic.commit(l, c, fp, 0, 0, 0, 4L), null);

        // Нельзя провести отклонённый расчёт
        l = priced();
        c = Logic.check(l, 0, 0, 0);
        eq("commit rejected check blocked", Logic.commit(l, c, Logic.fingerprint(l, 0, 0, 0), 0, 0, 0, 5L), null);

        // Отказ пишется в журнал и обнуляет трейд
        l = priced();
        res(l, "Рубины").enabled = false;
        res(l, "Рубины").offer = 4;
        c = Logic.check(l, 0, 0, 0);
        e = Logic.reject(l, c, 6L);
        eq("reject entry", e.ok, false);
        eq("reject details", e.details, "Рубины 4");
        eq("reject resets", res(l, "Рубины").offer, 0);

        // Объявление в формате из ТЗ
        l = priced();
        eq("ad", Logic.adText(l, "КУПЛЮ", ""),
                "КУПЛЮ ЖЕЛЕЗО — 500$/шт | ЖЕМЧУГ — 2 000$/шт | РУБИНЫ — 5 000$/шт | САПФИРЫ — 7 000$/шт | ИЗУМРУДЫ — 10 000$/шт | АЛМАЗЫ — 20 000$/шт");
        res(l, "Жемчуг").inAd = false;
        res(l, "Рубины").enabled = false;
        res(l, "Сапфиры").price = 0;
        eq("ad filtered", Logic.adText(l, "КУПЛЮ", "пишите в ЛС"),
                "КУПЛЮ ЖЕЛЕЗО — 500$/шт | ИЗУМРУДЫ — 10 000$/шт | АЛМАЗЫ — 20 000$/шт пишите в ЛС");

        eq("parse spaces", Logic.parse("2 000"), 2000);
        eq("parse junk", Logic.parse("abc"), 0);

        System.out.println(fails == 0 ? "\nВсе проверки пройдены" : "\nОшибок: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
