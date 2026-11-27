package com.rssolplan.edu.domain.roster.bulk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rssolplan.edu.domain.roster.TeacherRosterService;
import com.rssolplan.edu.domain.roster.TeacherRosterService.*;
import com.rssolplan.edu.domain.schedule.loadimport.LoadProblem.Issue;
import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.rssolplan.edu.domain.roster.bulk.RosterImportPlan.*;

@Service
@RequiredArgsConstructor
public class RosterImportService {
    public record Preview(UUID previewId, String fileSha256, Counts counts, List<Change> changes, List<Issue> errors) {}
    public record Receipt(UUID previewId, String fileSha256, Counts counts) {}
    private record Saved(String fileHash, String stateHash, String rows, String counts, boolean confirmed, boolean expired) {}
    private record State(int year, int semester, List<Change> changes) {}

    private final JdbcTemplate db;
    private final TeacherRosterService roster;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public byte[] template(long user, long school, long term) throws IOException {
        roster.requireAdmin(user, school);
        Catalog catalog = catalog(school, term, false);
        var codes = new ArrayList<RosterWorkbook.Code>();
        catalog.subjects().values().forEach(s -> codes.add(new RosterWorkbook.Code("과목", s.subjectId(), s.subjectName())));
        catalog.classes().values().forEach(c -> codes.add(new RosterWorkbook.Code("담임 학급", c.classId(),
                c.grade() + "학년 " + c.classNumber() + "반")));
        return RosterWorkbook.template(codes);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preview preview(long user, long school, long term, byte[] bytes) {
        roster.requireAdmin(user, school);
        var input = RosterWorkbook.parse(bytes);
        lockScope(user, school);
        var catalog = catalog(school, term, true);
        var plan = RosterImportPlan.build(input, catalog, roster.list(user, school, term));
        String fileHash = digest(bytes);
        if (!plan.errors().isEmpty())
            return new Preview(null, fileHash, plan.counts(), plan.changes(), plan.errors());

        String baseHash = stateHash(catalog, plan);
        // A replay before confirmation returns the same preview. A later fresh upload compares
        // against today's roster, even if an earlier import was subsequently edited or undone.
        db.update("""
                DELETE FROM roster_import_preview WHERE school_id=? AND confirmed_at IS NULL
                AND created_at <= CURRENT_TIMESTAMP - INTERVAL 1 DAY
                """, school);
        var pending = db.queryForList("""
                SELECT id FROM roster_import_preview WHERE school_id=? AND term_id=? AND admin_id=?
                AND file_sha256=? AND state_sha256=? AND confirmed_at IS NULL
                """, String.class, school, term, user, fileHash, baseHash);
        UUID id;
        if (pending.isEmpty()) {
            id = UUID.randomUUID();
            db.update("""
                    INSERT INTO roster_import_preview
                    (id,school_id,term_id,admin_id,file_sha256,state_sha256,rows_json,counts_json)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, id.toString(), school, term, user, fileHash, baseHash,
                    encode(input.rows()), encode(plan.counts()));
        } else {
            id = UUID.fromString(pending.get(0));
        }
        return new Preview(id, fileHash, plan.counts(), plan.changes(), plan.errors());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt confirm(long user, long school, long term, UUID id, String fileHash) {
        lockScope(user, school);
        Saved saved = db.query("""
                SELECT file_sha256,state_sha256,rows_json,counts_json,confirmed_at IS NOT NULL,
                       created_at <= CURRENT_TIMESTAMP - INTERVAL 1 DAY
                FROM roster_import_preview WHERE id=? AND school_id=? AND term_id=? AND admin_id=? FOR UPDATE
                """, (rs, n) -> new Saved(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBoolean(5), rs.getBoolean(6)), id.toString(), school, term, user).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("이 학교·학기·관리자의 미리보기를 찾을 수 없습니다."));
        if (!saved.fileHash().equals(fileHash))
            throw new ImportConflict("FILE_CHANGED", "선택 파일이 바뀌었습니다. 현재 파일을 다시 검증하세요.");
        Receipt receipt = new Receipt(id, fileHash, decode(saved.counts(), new TypeReference<Counts>() {}));
        if (saved.confirmed()) return receipt;
        if (saved.expired()) throw new ImportConflict("PREVIEW_EXPIRED", "미리보기가 만료되었습니다. 다시 검증하세요.");

        Catalog catalog = catalog(school, term, true);
        List<RosterWorkbook.RowInput> rows = decode(saved.rows(), new TypeReference<>() {});
        var plan = RosterImportPlan.build(new RosterWorkbook.Input(rows, List.of()), catalog, roster.list(user, school, term));
        if (!plan.errors().isEmpty() || !stateHash(catalog, plan).equals(saved.stateHash()))
            throw new ImportConflict("STALE_ROSTER", "명부 또는 코드표가 변경되었습니다. 다시 검증하세요.");
        apply(user, school, term, plan.changes());
        db.update("UPDATE roster_import_preview SET confirmed_at=CURRENT_TIMESTAMP, rows_json=NULL WHERE id=?", id.toString());
        return receipt;
    }

    private void apply(long user, long school, long term, List<Change> changes) {
        // Release only homerooms explicitly reassigned in this import. This allows swaps within
        // a batch without violating R1's unique (term, homeroom) key during intermediate writes.
        for (var change : changes) {
            Entry old = change.before();
            if (old != null && old.term().homeroomClass() != null
                    && !old.term().homeroomClass().equals(change.after().homeroomClass()))
                db.update("UPDATE roster_term SET homeroom_class_id=NULL WHERE roster_id=? AND term_id=?", old.rosterId(), term);
        }
        for (var change : changes) {
            if (change.action() == Action.UNCHANGED) continue;
            var target = change.after();
            var identity = new IdentityInput(change.authEmail(), target.displayName());
            Entry entry = change.before();
            if (entry == null) entry = roster.register(user, school, identity);
            else if (!entry.displayName().equals(target.displayName()))
                roster.updateIdentity(user, school, entry.rosterId(), identity);
            var ids = target.subjects().stream().map(SubjectItem::subjectId).toList();
            var room = target.homeroomClass();
            roster.replaceAssignment(user, school, entry.rosterId(), term,
                    new AssignmentInput(ids, room == null ? null : room.classId()));
        }
    }

    private void lockScope(long user, long school) {
        // Same first lock as R1 single-entry writes, signup and approval.
        db.queryForList("SELECT id FROM school WHERE id=? FOR UPDATE", Long.class, school);
        if (db.queryForList("""
                SELECT m.id FROM school_user m JOIN users u ON u.id=m.user_id
                WHERE u.id=? AND u.active_school_id=? AND m.school_id=?
                  AND m.position='ADMIN' AND m.employment_status='HIRED' LOCK IN SHARE MODE
                """, Long.class, user, school, school).isEmpty())
            throw new ForbiddenException("활성 학교의 재직 관리자만 명부를 가져올 수 있습니다.");
    }

    private Catalog catalog(long school, long term, boolean lock) {
        String suffix = lock ? " LOCK IN SHARE MODE" : "";
        int[] period = db.query("SELECT academic_year,semester FROM timetable_set WHERE school_id=? AND id=?" + suffix,
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, school, term).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("해당 학교의 학기를 찾을 수 없습니다."));
        Map<Long, SubjectItem> subjects = new LinkedHashMap<>();
        db.query("SELECT id,name FROM subject WHERE school_id=? ORDER BY id" + suffix,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    long id = rs.getLong(1);
                    subjects.put(id, new SubjectItem(id, rs.getString(2)));
                }, school);
        Map<Long, Homeroom> classes = new LinkedHashMap<>();
        db.query("SELECT id,grade,class_number FROM school_class WHERE school_id=? AND academic_year=? ORDER BY grade,class_number" + suffix,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    long id = rs.getLong(1);
                    classes.put(id, new Homeroom(id, period[0], rs.getInt(2), rs.getInt(3)));
                }, school, period[0]);
        return new Catalog(period[0], period[1], subjects, classes);
    }

    private String stateHash(Catalog catalog, Result plan) {
        return digest(encode(new State(catalog.year(), catalog.semester(), plan.changes())).getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("미리보기 직렬화 실패", e); }
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("저장된 미리보기를 읽을 수 없습니다.", e); }
    }

    static final class ImportConflict extends RuntimeException {
        final String code;
        ImportConflict(String code, String message) { super(message); this.code = code; }
    }
}
