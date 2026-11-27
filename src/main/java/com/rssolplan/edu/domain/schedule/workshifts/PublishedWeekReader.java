package com.rssolplan.edu.domain.schedule.workshifts;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.workshifts.dto.TimetableDto;
import com.rssolplan.edu.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Repository
@RequiredArgsConstructor
class PublishedWeekReader {
    private final NamedParameterJdbcTemplate db;

    // Select the revision separately for each teaching day, as DatedLessons does for origin_date.
    // Choosing the version before filtering the teacher also preserves genuinely empty days.
    private static final String WEEK = """
        with week_dates as (
            select day_no, date_add(:monday, interval (day_no - 1) day) as lesson_date
            from (select 1 day_no union all select 2 union all select 3 union all select 4
                  union all select 5 union all select 6 union all select 7) days
        ), effective as (
            select t.id term_id, t.academic_year, t.semester, d.day_no, d.lesson_date,
                   not exists(select 1 from term_excluded_date x
                              where x.term_id=t.id and x.excluded_date=d.lesson_date) as teaching_day,
                   d.lesson_date between t.start_date and t.end_date as in_term,
                   (select v.id from schedule_version v
                    where v.term_id=t.id and v.effective_from<=d.lesson_date
                    order by v.version_no desc limit 1) as version_id
            from timetable_set t cross join week_dates d
            where t.school_id=:school and (:year is null or t.academic_year=:year)
              and (:semester is null or t.semester=:semester) and (:term is null or t.id=:term)
        ), occurrences as (
            select w.id weekly_id,e.academic_year,e.semester,e.day_no,w.class_id,w.subject_id,
                   cast(json_unquote(json_extract(o.actual,'$.teacherId')) as unsigned) teacher_id,
                   cast(json_unquote(json_extract(o.actual,'$.periodId')) as unsigned) period_id
            from effective e join dated_lesson o on o.teaching_date=e.lesson_date
            join weekly_lesson w on w.id=o.origin_id
            where e.in_term and e.teaching_day and w.version_id=(
                select v.id from schedule_version v where v.term_id=e.term_id and v.effective_from<=o.origin_date
                order by v.version_no desc limit 1)
              and coalesce(json_unquote(json_extract(o.actual,'$.cancelled')),'false') not in ('true','1')
              and not exists(select 1 from term_excluded_date x where x.term_id=e.term_id and x.excluded_date=o.origin_date)
        ), slots as (
            select null as legacy_id, o.weekly_id,o.academic_year,o.semester,o.day_no,o.class_id,o.period_id,o.subject_id,o.teacher_id
            from occurrences o where (:teacher is null or o.teacher_id=:teacher)
            union all
            select null, w.id, e.academic_year,e.semester,e.day_no,w.class_id,w.period_id,w.subject_id,w.teacher_id
            from effective e join weekly_lesson w on w.version_id=e.version_id and w.weekday=e.day_no
            where e.in_term and e.teaching_day and (:teacher is null or w.teacher_id=:teacher)
              and not exists(select 1 from dated_lesson o where o.origin_id=w.id and o.origin_date=e.lesson_date)
              and not exists(select 1 from publication_receipt p where p.version_id=w.version_id)
            union all
            select l.id, null, l.academic_year, l.semester, d.day_no,
                   l.school_class_id, l.period_setting_id, l.subject_id, l.teacher_id
            from timetable l join week_dates d
                on l.day_of_week=elt(d.day_no,'MON','TUE','WED','THU','FRI','SAT','SUN')
            left join effective e on e.academic_year=l.academic_year
                and e.semester=l.semester and e.day_no=d.day_no
            where l.school_id=:school and (:year is null or l.academic_year=:year)
              and (:semester is null or l.semester=:semester) and (:term is null or e.term_id=:term)
              and (:teacher is null or l.teacher_id=:teacher) and e.version_id is null
              and (e.term_id is null or (e.in_term and e.teaching_day))
        )
        select s.*, c.grade, c.class_number, p.period_number, p.start_time, p.end_time,
               sub.name as subject_name, coalesce(r.display_name,u.username) as teacher_name
        from slots s join school_class c on c.id=s.class_id join period_setting p on p.id=s.period_id
        join subject sub on sub.id=s.subject_id join school_user su on su.id=s.teacher_id
        join users u on u.id=su.user_id
        left join school_teacher_roster r on r.school_id=:school and r.school_user_id=su.id and r.base_name is not null
        order by s.day_no, p.period_number, c.grade, c.class_number, s.teacher_id
        """;

    List<TimetableDto> read(long school, Long teacher, Integer year, Integer semester,
                            Long termId, LocalDate weekOf) {
        LocalDate anchor = weekOf == null ? LocalDate.now(ZoneId.of("Asia/Seoul")) : weekOf;
        var params = new MapSqlParameterSource()
                .addValue("school", school).addValue("teacher", teacher)
                .addValue("term", termId).addValue("year", year).addValue("semester", semester)
                .addValue("monday", anchor.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)));
        if (termId != null && db.queryForList("""
                select id from timetable_set where id=:term and school_id=:school
                  and (:year is null or academic_year=:year) and (:semester is null or semester=:semester)
                """, params, Long.class).isEmpty()) {
            throw new NotFoundException("학기를 찾을 수 없습니다.");
        }
        return db.query(WEEK, params, (r, i) -> new TimetableDto(
                r.getObject("legacy_id", Long.class), r.getObject("weekly_id", Long.class), school,
                r.getInt("academic_year"), r.getInt("semester"), r.getLong("class_id"), r.getInt("grade"),
                r.getInt("class_number"), r.getLong("period_id"), r.getInt("period_number"),
                r.getObject("start_time", LocalTime.class), r.getObject("end_time", LocalTime.class),
                DayOfWeek.values()[r.getInt("day_no") - 1], r.getLong("subject_id"), r.getString("subject_name"),
                r.getLong("teacher_id"), r.getString("teacher_name")));
    }
}
