package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Service;

/** Builds a short, cosmetic greeting for eligible first-party web-chat turns. */
@Service
public class WebChatGreetingService {
    private final BridgeProperties properties;

    public WebChatGreetingService(BridgeProperties properties) { this.properties = properties; }

    public String greetingHtml(String kind, String language, String browserTimezone) {
        return greetingHtml(kind, language, browserTimezone, Instant.now());
    }

    String greetingHtml(String kind, String language, String browserTimezone, Instant now) {
        if (!"first".equals(kind) && !"returning".equals(kind)) { return null; }
        ZonedDateTime local = now.atZone(resolveTimezone(browserTimezone));
        int hour = local.getHour();
        // Do not insert a forced greeting during the visitor's overnight hours.
        if (hour >= 1 && hour <= 5) { return null; }

        boolean chinese = "zh".equals(language);
        String salutation;
        if (isUsFederalHolidayOrEve(local.toLocalDate())) {
            salutation = chinese ? "节日快乐！" : "Happy holiday!";
        } else if (hour >= 23 || hour == 0) {
            salutation = chinese ? "夜深了！" : "It's getting late!";
        } else if (hour < 12) {
            salutation = chinese ? "早上好！" : "Good morning!";
        } else if (hour < 18) {
            salutation = chinese ? "下午好！" : "Good afternoon!";
        } else {
            salutation = chinese ? "晚上好！" : "Good evening!";
        }

        boolean weekend = local.getDayOfWeek() == DayOfWeek.FRIDAY
                || local.getDayOfWeek() == DayOfWeek.SATURDAY
                || local.getDayOfWeek() == DayOfWeek.SUNDAY;
        String message;
        if ("returning".equals(kind)) {
            message = chinese ? salutation + "又见面啦！很高兴为您服务。"
                    : salutation + " Welcome back! It's a pleasure to assist you.";
        } else if (weekend) {
            message = chinese ? salutation + "周末愉快！我是 Love Hibachi 的 AI 助手。很高兴为您服务。"
                    : salutation + " Hope you're having a great weekend. I'm Love Hibachi's AI assistant. It's a pleasure to assist you.";
        } else {
            message = chinese ? salutation + "我是 Love Hibachi 的 AI 助手。很高兴为您服务。"
                    : salutation + " I'm Love Hibachi's AI assistant. It's a pleasure to assist you.";
        }
        return "<p>" + message + "</p>";
    }

    private ZoneId resolveTimezone(String candidate) {
        String fallback = properties.getWebChat().getGreetingFallbackTimezone();
        try { return ZoneId.of(candidate == null || candidate.trim().isEmpty() ? fallback : candidate); }
        catch (Exception ignored) { return ZoneId.of(fallback); }
    }

    private boolean isUsFederalHolidayOrEve(LocalDate date) {
        return isUsFederalHoliday(date) || isUsFederalHoliday(date.plusDays(1));
    }

    private boolean isUsFederalHoliday(LocalDate date) {
        int year = date.getYear();
        return date.equals(observed(LocalDate.of(year, 1, 1)))
                || date.equals(nthWeekday(year, 1, DayOfWeek.MONDAY, 3))
                || date.equals(nthWeekday(year, 2, DayOfWeek.MONDAY, 3))
                || date.equals(lastWeekday(year, 5, DayOfWeek.MONDAY))
                || date.equals(observed(LocalDate.of(year, 6, 19)))
                || date.equals(observed(LocalDate.of(year, 7, 4)))
                || date.equals(nthWeekday(year, 9, DayOfWeek.MONDAY, 1))
                || date.equals(nthWeekday(year, 10, DayOfWeek.MONDAY, 2))
                || date.equals(observed(LocalDate.of(year, 11, 11)))
                || date.equals(nthWeekday(year, 11, DayOfWeek.THURSDAY, 4))
                || date.equals(observed(LocalDate.of(year, 12, 25)));
    }

    private LocalDate observed(LocalDate holiday) {
        if (holiday.getDayOfWeek() == DayOfWeek.SATURDAY) { return holiday.minusDays(1); }
        if (holiday.getDayOfWeek() == DayOfWeek.SUNDAY) { return holiday.plusDays(1); }
        return holiday;
    }

    private LocalDate nthWeekday(int year, int month, DayOfWeek weekday, int occurrence) {
        LocalDate date = LocalDate.of(year, month, 1);
        while (date.getDayOfWeek() != weekday) { date = date.plusDays(1); }
        return date.plusWeeks(occurrence - 1L);
    }

    private LocalDate lastWeekday(int year, int month, DayOfWeek weekday) {
        LocalDate date = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        while (date.getDayOfWeek() != weekday) { date = date.minusDays(1); }
        return date;
    }
}
