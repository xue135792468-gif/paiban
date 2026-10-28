package com.paiban.ai.imports;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How a timetable sheet writes a weekday and a period number. Shared by the slot merge
 * (which keys rows by them) and the timetable importer (which resolves them to periods),
 * so a slot both sides call "星期一第1节" is the same slot.
 */
public final class SlotText {

    private SlotText() {}

    /** NFKC, no whitespace: {@code 星期一 第 1 节} and {@code 星期一第1节} agree. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "");
    }

    public static String normalizeName(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }

    /** @return 1..7 for 周一/星期一/礼拜一/1, empty when the text is not a weekday. */
    public static Optional<Integer> day(String raw) {
        String value = normalize(raw).replace("星期", "").replace("礼拜", "").replace("周", "");
        return switch (value) {
            case "一", "1" -> Optional.of(1);
            case "二", "2" -> Optional.of(2);
            case "三", "3" -> Optional.of(3);
            case "四", "4" -> Optional.of(4);
            case "五", "5" -> Optional.of(5);
            case "六", "6" -> Optional.of(6);
            case "日", "天", "七", "7" -> Optional.of(7);
            default -> Optional.empty();
        };
    }

    /** Day-part prefixes a real workbook may put before the period label. */
    private static final List<String> DAY_PARTS =
            List.of("上午", "下午", "中午", "晚上", "早晨", "清晨", "傍晚", "晚间");

    /** @return the period number for 第3节/3/三/上午第1节, empty when it is not a period. */
    public static Optional<Integer> periodNo(String raw) {
        String value = normalize(raw);
        for (String part : DAY_PARTS) {
            if (value.startsWith(part)) {
                value = value.substring(part.length());
                break;
            }
        }
        if (value.startsWith("第")) {
            value = value.substring(1);
        }
        if (value.endsWith("节")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return Optional.empty();
        }
        int number;
        if (value.matches("\\d+")) {
            number = Integer.parseInt(value);
        } else {
            number = chineseNumber(value);
        }
        return number > 0 ? Optional.of(number) : Optional.empty();
    }

    /** Parses 一..九, 十, 十一..九十九; anything else yields 0. */
    static int chineseNumber(String value) {
        String digits = "一二三四五六七八九";
        int total = 0;
        int current = 0;
        for (char c : value.toCharArray()) {
            int digit = c == '两' ? 2 : digits.indexOf(c) + 1;
            if (digit > 0) {
                current = digit;
            } else if (c == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else {
                return 0;
            }
        }
        return total + current;
    }
}
