package com.semen.skupka;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Вся логика скупки без привязки к Android: расчёт трейда, проверки,
 * объявление, журнал. Проверяется обычными тестами (см. LogicTest в README).
 */
final class Logic {

    private Logic() {}

    static final String[] NAMES = {"Железо", "Жемчуг", "Рубины", "Сапфиры", "Изумруды", "Алмазы"};

    /** Ресурс и его настройки. */
    static final class Res {
        final String name;
        boolean enabled = true; // скупаю или нет
        boolean inAd = true;    // попадает ли в объявление
        long price;             // $ за 1 шт.
        long maxQty;            // макс. штук за одну сделку, 0 = без лимита
        long offer;             // сколько дают в текущем трейде
        long bought;            // всего куплено

        Res(String name) {
            this.name = name;
        }
    }

    /** Строка журнала. */
    static final class Entry {
        final long time;
        final boolean ok;
        final String details;
        final long sum;
        final String reason;

        Entry(long time, boolean ok, String details, long sum, String reason) {
            this.time = time;
            this.ok = ok;
            this.details = details;
            this.sum = sum;
            this.reason = reason;
        }
    }

    /** Результат проверки трейда. */
    static final class Check {
        boolean ok;
        long total;
        final List<String> lines = new ArrayList<>();    // «Железо: 10 × 500 = 5 000$»
        final List<String> problems = new ArrayList<>(); // причины отказа
    }

    static List<Res> defaults() {
        List<Res> l = new ArrayList<>();
        for (String n : NAMES) l.add(new Res(n));
        return l;
    }

    /**
     * Проверяет трейд по всем правилам ТЗ.
     *
     * @param maxDeal макс. сумма одной сделки, 0 = без лимита
     * @param budget  общий бюджет, 0 = без лимита
     * @param spent   сколько уже потрачено из бюджета
     */
    static Check check(List<Res> items, long maxDeal, long budget, long spent) {
        Check c = new Check();
        long total = 0;
        int offered = 0;
        boolean overflow = false;

        for (Res r : items) {
            if (r.offer < 0) {
                c.problems.add(r.name + ": неверное количество");
                continue;
            }
            if (r.offer == 0) continue;
            offered++;
            if (!r.enabled) {
                c.problems.add(r.name + ": этот ресурс сейчас не скупаю");
                continue;
            }
            if (r.price <= 0) {
                c.problems.add(r.name + ": не задана цена");
                continue;
            }
            if (r.maxQty > 0 && r.offer > r.maxQty) {
                c.problems.add(r.name + ": " + fmt(r.offer) + " шт. больше лимита " + fmt(r.maxQty) + " шт.");
            }
            try {
                long line = Math.multiplyExact(r.price, r.offer);
                total = Math.addExact(total, line);
                c.lines.add(r.name + ": " + fmt(r.offer) + " × " + fmt(r.price) + " = " + fmt(line) + "$");
            } catch (ArithmeticException e) {
                overflow = true;
            }
        }

        if (overflow) c.problems.add("Слишком большая сумма, проверьте цифры");
        if (offered == 0) c.problems.add("Трейд пустой: впишите, сколько ресурсов вам дают");

        c.total = overflow ? 0 : total;

        if (c.problems.isEmpty()) {
            if (maxDeal > 0 && total > maxDeal) {
                c.problems.add("Сумма " + fmt(total) + "$ больше лимита сделки " + fmt(maxDeal) + "$");
            }
            if (budget > 0) {
                long left = budget - spent;
                if (total > left) {
                    c.problems.add("Не хватает бюджета: осталось " + fmt(Math.max(left, 0)) + "$, нужно " + fmt(total) + "$");
                }
            }
        }
        c.ok = c.problems.isEmpty();
        return c;
    }

    /** Отпечаток всех данных, от которых зависит расчёт. Если он изменился, оплату блокируем. */
    static String fingerprint(List<Res> items, long maxDeal, long budget, long spent) {
        StringBuilder sb = new StringBuilder();
        for (Res r : items) {
            sb.append(r.name).append(':').append(r.enabled ? 1 : 0).append(':')
                    .append(r.price).append(':').append(r.maxQty).append(':').append(r.offer).append(';');
        }
        sb.append(maxDeal).append(';').append(budget).append(';').append(spent);
        return sb.toString();
    }

    /**
     * Проводит проверенную сделку: прибавляет купленное, обнуляет трейд и возвращает запись журнала.
     * Возвращает null, если расчёт уже не совпадает с текущими данными (защита от подмены и повтора).
     */
    static Entry commit(List<Res> items, Check approved, String approvedFp,
                        long maxDeal, long budget, long spent, long now) {
        if (approved == null || !approved.ok) return null;
        if (!fingerprint(items, maxDeal, budget, spent).equals(approvedFp)) return null;
        Check again = check(items, maxDeal, budget, spent);
        if (!again.ok || again.total != approved.total) return null;

        String details = offerSummary(items);
        for (Res r : items) {
            if (r.offer > 0) r.bought += r.offer;
            r.offer = 0;
        }
        return new Entry(now, true, details, again.total, "");
    }

    /** Записывает отказ и обнуляет трейд. */
    static Entry reject(List<Res> items, Check c, long now) {
        String details = offerSummary(items);
        String reason = c == null || c.problems.isEmpty() ? "отклонено вручную" : String.join("; ", c.problems);
        for (Res r : items) r.offer = 0;
        return new Entry(now, false, details, 0, reason);
    }

    /** Короткое описание состава трейда для журнала: «Железо 10, Алмазы 2». */
    static String offerSummary(List<Res> items) {
        StringBuilder sb = new StringBuilder();
        for (Res r : items) {
            if (r.offer <= 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.name).append(' ').append(fmt(r.offer));
        }
        return sb.length() == 0 ? "пусто" : sb.toString();
    }

    /** «КУПЛЮ ЖЕЛЕЗО — 500$/шт | ЖЕМЧУГ — 2 000$/шт …» — только включённые ресурсы с ценой, отмеченные для объявления. */
    static String adText(List<Res> items, String start, String end) {
        Locale ru = Locale.forLanguageTag("ru");
        List<String> parts = new ArrayList<>();
        for (Res r : items) {
            if (!r.enabled || !r.inAd || r.price <= 0) continue;
            parts.add(r.name.toUpperCase(ru) + " — " + fmt(r.price) + "$/шт");
        }
        StringBuilder sb = new StringBuilder();
        appendWord(sb, start);
        appendWord(sb, String.join(" | ", parts));
        appendWord(sb, end);
        return sb.toString();
    }

    private static void appendWord(StringBuilder sb, String s) {
        if (s == null) return;
        s = s.trim();
        if (s.isEmpty()) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(s);
    }

    /** 1250000 -> "1 250 000" */
    static String fmt(long v) {
        return String.format(Locale.US, "%,d", v).replace(',', ' ');
    }

    static long parse(String s) {
        if (s == null) return 0;
        s = s.replace(" ", "").trim();
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
