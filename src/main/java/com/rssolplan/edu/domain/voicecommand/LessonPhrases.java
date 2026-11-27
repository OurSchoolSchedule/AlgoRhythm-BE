package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.voicecommand.dto.ParsedIntent;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Pattern;

/** Recognizable classroom phrases remain usable when the external parser is unavailable. */
final class LessonPhrases {
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern PERIOD = Pattern.compile("(\\d{1,2})\\s*교시");
    private static final Pattern TEACHER = Pattern.compile("(?:^|\\s)([가-힣]{2,5})\\s*(?:선생님|교사)(?:과|와|에게)");
    private static final Pattern DAY = Pattern.compile("([월화수목금토일])요일");

    private LessonPhrases() {}

    static ParsedIntent tryRead(String text, LocalDate today) {
        if (text.contains("승인") || text.contains("수락") || text.contains("거절")) return null;
        String intent;
        if ((text.contains("보결") || text.contains("교환")) && (text.contains("상태") || text.contains("처리 현황")))
            intent = "CHANGE_STATUS";
        else if (text.contains("교환") && (text.contains("가능") || text.contains("찾") || text.contains("요청")))
            intent = "SWAP_OPTIONS";
        else if ((text.contains("공강") || text.contains("보결")) &&
                (text.contains("찾") || text.contains("가능") || text.contains("보내") || text.contains("부탁")))
            intent = "COVER_OPTIONS";
        else if (text.replace(" ", "").contains("내시간표")) intent = "MY_WEEK";
        else return null;

        LocalDate date = null;
        var iso = ISO_DATE.matcher(text);
        var day = DAY.matcher(text);
        if (iso.find()) {
            try { date = LocalDate.parse(iso.group()); }
            catch (java.time.format.DateTimeParseException invalid) { return null; }
        } else if (text.contains("내일")) date = today.plusDays(1);
        else if (text.contains("오늘")) date = today;
        else if (day.find()) {
            int offset = "월화수목금토일".indexOf(day.group(1));
            date = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(offset);
            if (text.contains("다음 주") || text.contains("다음주")) date = date.plusWeeks(1);
            if (text.contains("지난 주") || text.contains("지난주")) date = date.minusWeeks(1);
        }
        var period = PERIOD.matcher(text);
        var teacher = TEACHER.matcher(text);
        return new ParsedIntent(intent, "HIGH", null, date == null ? null : date.toString(), null,
                null, null, null, null, period.find() ? Integer.parseInt(period.group(1)) : null,
                teacher.find() ? teacher.group(1) : null);
    }
}
