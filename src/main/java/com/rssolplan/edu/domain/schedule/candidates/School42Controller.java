package com.rssolplan.edu.domain.schedule.candidates;

import com.rssolplan.edu.domain.schedule.loadimport.LoadProblem;
import com.rssolplan.edu.domain.schedule.loadimport.LoadValidationController;
import com.rssolplan.edu.domain.schedule.loadimport.LoadWorkbookParser;
import com.rssolplan.edu.domain.schedule.workshifts.TimetableService;
import com.rssolplan.edu.domain.schedule.workshifts.dto.TimetableDto;
import com.rssolplan.edu.global.config.School42Access;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/school42")
@RequiredArgsConstructor
public class School42Controller {
    private static final String TERM_PATH = "/v2/terms/547001";
    private static final LocalDate WEEK = LocalDate.of(2026, 11, 23);
    private final School42Round rounds;
    private final LoadValidationController loads;
    private final GenerationController generation;
    private final TimetableService timetables;

    record Status(String role, long round, long teachers, long classes, long subjects, long jobs, long versions) {}

    @GetMapping("/status")
    Status status(@RequestAttribute(School42Access.ROLE) String role) {
        if ("ADMIN".equals(role)) {
            rounds.prepareTable();
            rounds.open();
        } else {
            rounds.verifyIdentity();
        }
        return new Status(role, rounds.round(false),
                rounds.count("select count(*) from school_user where school_id=540001 and position='TEACHER'"),
                rounds.count("select count(*) from school_class where school_id=540001"),
                rounds.count("select count(*) from subject where school_id=540001"),
                rounds.count("select count(*) from generation_job where school_id=540001 and term_id=547001"),
                rounds.count("select count(*) from schedule_version where term_id=547001"));
    }

    @PostMapping(TERM_PATH + "/load-imports/validate")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LoadWorkbookParser.Result validate(@RequestHeader("X-School42-Round") long round,
                                              @RequestPart MultipartFile file) throws IOException {
        rounds.requireRound(round);
        var result = loads.validateTerm(School42Round.ADMIN, School42Round.TERM, file, null, true, Map.of());
        rounds.retain(result.validatedLoadId());
        return result;
    }

    @ExceptionHandler(LoadProblem.class)
    ResponseEntity<?> loadProblem(LoadProblem e) {
        return ResponseEntity.status(e.status()).body(Map.of(
                "code", e.code(), "message", e.getMessage(), "fieldErrors", e.issues()));
    }

    @PostMapping(TERM_PATH + "/generations")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResponseEntity<?> generate(@RequestHeader("X-School42-Round") long round,
                                      @RequestBody CandidateApi.Request input) {
        rounds.requireRound(round);
        rounds.requireLoad(input.validatedLoadId());
        return generation.start(School42Round.ADMIN, School42Round.TERM, input);
    }

    @GetMapping(TERM_PATH + "/generations/{id}")
    Object job(@PathVariable UUID id) {
        rounds.verifyIdentity();
        return generation.poll(School42Round.ADMIN, School42Round.TERM, id);
    }

    @PostMapping(TERM_PATH + "/publications")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PublicationService.Published publish(@RequestHeader("X-School42-Round") long round,
                                                @Valid @RequestBody PublicationService.Publish input) {
        rounds.requireRound(round);
        return generation.publish(School42Round.ADMIN, School42Round.TERM, input);
    }

    @GetMapping("/week")
    List<TimetableDto> week(@RequestAttribute(School42Access.ROLE) String role) {
        rounds.verifyIdentity();
        boolean teacher = "TEACHER".equals(role);
        return timetables.week(teacher ? School42Round.TEACHER : School42Round.ADMIN,
                teacher, 2026, 2, School42Round.TERM, WEEK);
    }

    @PostMapping("/reset")
    Map<String,Long> reset(@RequestHeader("X-School42-Round") long round) {
        return Map.of("round", rounds.reset(round));
    }
}
