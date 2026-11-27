package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.voicecommand.dto.ParsedIntent;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Common read-only commands also work when the external parser is unavailable. */
final class LessonPhrases {
    private static final Pattern PERIOD = Pattern.compile("(\\d{1,2})\\s*교시");
    private static final Pattern TEACHER = Pattern.compile("(?:^|\\s)([가-힣]{2,5})\\s*(?:선생님|교사)(?:과|와|에게)");
    private static final Pattern ADMIN_COVER = Pattern.compile("모집|공고|보결.*생성|시간표\\s*(?:ID|아이디|#|번호|[0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern HELP = Pattern.compile("도움말|사용법|사용 방법|이용 방법|하는 법|하는법|방법|가이드|일반 안내|어떤 (?:일|기능)|무엇을 할 수|어떻게 (?:신청|요청|등록|사용|이용|교환|보결)|(?:신청|요청|등록|교환|보결).*어떻게");

    private LessonPhrases() {}

    static ParsedIntent tryRead(String text, LocalDate today) {
        String intent;
        if (HELP.matcher(text).find()) intent = "GUIDE";
        else if ((text.contains("보결") || text.contains("교환")) && (text.contains("상태") || text.contains("처리 현황")))
            intent = "CHANGE_STATUS";
        else if (text.contains("승인") || text.contains("수락") || text.contains("거절")) return null;
        else if (text.contains("교환"))
            intent = "SWAP_OPTIONS";
        else if ((text.contains("공강") || text.contains("보결")) && !ADMIN_COVER.matcher(text).find())
            intent = "COVER_OPTIONS";
        else if (text.replace(" ", "").contains("내시간표") || text.replace(" ", "").contains("내수업")) intent = "MY_WEEK";
        else return null;

        var period = PERIOD.matcher(text);
        var teacher = TEACHER.matcher(text);
        var match = new ParsedIntent(intent, "HIGH", null, null, null, null, null, null, null,
                period.find() ? Integer.parseInt(period.group(1)) : null, teacher.find() ? teacher.group(1) : null);
        return LessonDates.resolve(text, today, match);
    }
}
