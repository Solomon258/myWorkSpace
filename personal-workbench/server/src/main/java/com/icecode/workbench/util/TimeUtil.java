package com.icecode.workbench.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class TimeUtil {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeUtil() {
    }

    public static long epochMillis() {
        return System.currentTimeMillis();
    }

    public static ZoneId zone(String timezone) {
        return ZoneId.of(timezone);
    }

    public static String now() {
        return LocalDateTime.now().format(DATE_TIME);
    }

    public static String now(String timezone) {
        return LocalDateTime.now(zone(timezone)).format(DATE_TIME);
    }

    public static LocalDate localDate(String timezone) {
        return LocalDate.now(zone(timezone));
    }

    public static String today() {
        return LocalDate.now().format(DATE);
    }

    public static String today(String timezone) {
        return localDate(timezone).format(DATE);
    }

    public static String format(LocalDate value) {
        return value == null ? null : value.format(DATE);
    }

    public static String format(LocalTime value) {
        return value == null ? null : value.format(TIME);
    }

    /**
     * N 天前的同一时刻，格式与 {@link #now(String)} 一致。
     * 用于回收站的保留期窗口——库里存的就是 {@code yyyy-MM-dd HH:mm:ss} 文本，
     * 同格式的字符串可以直接按字典序比较，不必来回转换。
     */
    public static String daysAgo(String timezone, int days) {
        return LocalDateTime.now(zone(timezone)).minusDays(days).format(DATE_TIME);
    }

    /** 解析 {@link #DATE_TIME} 格式的文本；无法解析时返回 null，由调用方决定怎么兜底。 */
    public static LocalDateTime parseDateTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), DATE_TIME);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
