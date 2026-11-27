package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.voicecommand.dto.IntentType;
import com.rssolplan.edu.domain.voicecommand.dto.ParsedIntent;
import com.rssolplan.edu.global.exception.IntentParseException;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LessonDates {
    private static final String WEEKDAYS = "월화수목금토일";
    private static final Pattern MENTION = Pattern.compile(
            "(?<![0-9])(?<year>[0-9]{4})[-./](?<month>[0-9]{1,2})[-./](?<day>[0-9]{1,2})(?![0-9])"
            + "|(?<![0-9])(?:(?<kYear>[0-9]{4})\\s*년\\s*)?(?<kMonth>[0-9]{1,2})\\s*월\\s*(?<kDay>[0-9]{1,2})\\s*일"
            + "|(?<![0-9])(?<shortMonth>[0-9]{1,2})/(?<shortDay>[0-9]{1,2})(?![0-9])"
            + "|(?<![0-9])(?<dayOnly>[0-9]{1,2})\\s*일(?!요일)"
            + "|(?<relative>그저께|어제|오늘|내일|모레)"
            + "|(?<week>이번|다음|지난|저번)\\s*주(?:\\s*(?<weekDay>[월화수목금토일])요일)?"
            + "|(?<weekday>[월화수목금토일])요일");
    private static final String ROLE = "(?<role>상대(?:방)?|내|나의|제)\\s*(?:(?:교사|선생님)의?\\s*)?(?:의\\s*)?(?:수업|시간표)";
    private static final Pattern BEFORE = Pattern.compile(ROLE + "(?:\\s*날짜)?(?:은|는|이|가|을|를|으로|로)?\\s*$");
    private static final Pattern AFTER = Pattern.compile("^\\s*(?:의|에\\s*있는)?\\s*" + ROLE);
    private static final Pattern ANNOTATION = Pattern.compile("^\\s*(?:[(（]\\s*)?$");
    private static final Pattern WEEK_VIEW = Pattern.compile("주간|일주일|한\\s*주|그\\s*주|있는\\s*주|포함.*주|주\\s*전체|부터.*까지");

    private LessonDates() {}

    record Mention(LocalDate date, LocalDate week, String role) {}

    static boolean isWeekQuery(String text, ParsedIntent intent) {
        return intent.substituteDate() == null || intent.weekStart() != null || WEEK_VIEW.matcher(text).find();
    }

    static ParsedIntent resolve(String text, LocalDate today, ParsedIntent intent) {
        var type = intent.resolvedType();
        if (type == IntentType.GUIDE || type == IntentType.CHANGE_STATUS)
            return intent.withDates(null, null, null, null);
        if (!type.isLessonAssistant() && type != IntentType.SUBSTITUTE_CREATE &&
                type != IntentType.SUBSTITUTE_RESPOND && type != IntentType.SUBSTITUTE_APPROVE) return intent;
        var mentions = new ArrayList<Mention>();
        Matcher matcher = MENTION.matcher(text);
        int previousEnd = 0;
        LocalDate reference = today;
        boolean mayHaveWeekdayLabel = false;
        while (matcher.find()) {
            String before = text.substring(previousEnd, matcher.start());
            if (matcher.group("weekday") != null && mayHaveWeekdayLabel && ANNOTATION.matcher(before).matches()) {
                if (reference.getDayOfWeek().getValue() != WEEKDAYS.indexOf(matcher.group("weekday")) + 1)
                    throw new IntentParseException("입력한 날짜와 요일이 일치하지 않습니다. 날짜를 확인하세요.");
                previousEnd = matcher.end();
                mayHaveWeekdayLabel = false;
                continue;
            }
            var afterRole = AFTER.matcher(text.substring(matcher.end()));
            var beforeRole = BEFORE.matcher(before);
            String role = afterRole.find() ? afterRole.group("role") : beforeRole.find() ? beforeRole.group("role") : "";
            var mention = decode(matcher, today, reference, role);
            if (mention.date() != null) reference = mention.date();
            mayHaveWeekdayLabel = matcher.group("weekday") == null && matcher.group("week") == null;
            mentions.add(mention);
            previousEnd = matcher.end();
        }
        if (mentions.isEmpty()) return intent.withDates(null, null, null, null);
        Mention first = mentions.get(0);
        Mention second = mentions.size() > 1 ? mentions.get(1) : null;
        if (type == IntentType.MY_WEEK && mentions.stream().allMatch(m -> weekOf(m).equals(weekOf(first))))
            return intent.withDates(iso(first.date() == null ? first.week() : first.date()), null, iso(first.week()), null);
        if (mentions.size() > (type == IntentType.SWAP_OPTIONS ? 2 : 1))
            throw new IntentParseException("요청 날짜를 하나로 지정하세요. 교환은 내 수업 날짜와 상대 수업 날짜를 각각 지정하세요.");
        boolean reverse = type == IntentType.SWAP_OPTIONS
                && (first.role().startsWith("상대") || second != null && !second.role().isEmpty() && !second.role().startsWith("상대"));
        Mention from = reverse ? second : first;
        Mention to = reverse ? first : second;
        return intent.withDates(from == null ? null : iso(from.date()), to == null ? null : iso(to.date()),
                from == null ? null : iso(from.week()), to == null ? null : iso(to.week()));
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static LocalDate weekOf(Mention m) {
        return m.week() != null ? m.week() : m.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static Mention decode(Matcher m, LocalDate today, LocalDate reference, String role) {
        try {
            if (m.group("year") != null)
                return new Mention(LocalDate.of(number(m, "year"), number(m, "month"), number(m, "day")), null, role);
            if (m.group("kMonth") != null)
                return new Mention(LocalDate.of(m.group("kYear") == null ? reference.getYear() : number(m, "kYear"),
                        number(m, "kMonth"), number(m, "kDay")), null, role);
            if (m.group("shortMonth") != null)
                return new Mention(LocalDate.of(reference.getYear(), number(m, "shortMonth"), number(m, "shortDay")), null, role);
            if (m.group("dayOnly") != null)
                return new Mention(reference.withDayOfMonth(number(m, "dayOnly")), null, role);
            if (m.group("relative") != null) {
                int offset = switch (m.group("relative")) {
                    case "그저께" -> -2;
                    case "어제" -> -1;
                    case "내일" -> 1;
                    case "모레" -> 2;
                    default -> 0;
                };
                return new Mention(today.plusDays(offset), null, role);
            }
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if ("다음".equals(m.group("week"))) monday = monday.plusWeeks(1);
            if ("지난".equals(m.group("week")) || "저번".equals(m.group("week"))) monday = monday.minusWeeks(1);
            String day = m.group("weekDay") != null ? m.group("weekDay") : m.group("weekday");
            return day == null ? new Mention(null, monday, role) : new Mention(monday.plusDays(WEEKDAYS.indexOf(day)), null, role);
        } catch (DateTimeException invalid) {
            throw new IntentParseException("달력에 없는 날짜입니다. 연·월·일을 확인하세요.");
        }
    }

    private static int number(Matcher match, String name) {
        return Integer.parseInt(match.group(name));
    }

    static LocalDate parse(String value) {
        if (value == null) return null;
        if (value.length() != 10 || !Character.isDigit(value.charAt(0)))
            throw new IntentParseException("날짜는 유효한 YYYY-MM-DD 값이어야 합니다.");
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException invalid) {
            throw new IntentParseException("날짜는 유효한 YYYY-MM-DD 값이어야 합니다.");
        }
    }
}
