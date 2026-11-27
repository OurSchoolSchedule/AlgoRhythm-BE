package com.rssolplan.edu.domain.schedule.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v2/terms/{term}")
@RequiredArgsConstructor
public class OperationsController {
    private final ChangeService changes;
    private final DatedLessons lessons;

    public record Decision(
            @NotNull Boolean accept, @NotNull @PositiveOrZero Long expectedRequestRev) {}

    @GetMapping("/lessons")
    public List<DatedLessons.Lesson> lessons(
            @AuthenticationPrincipal Long user,
            @PathVariable long term,
            @RequestParam LocalDate date) {
        return lessons.date(user, term, date);
    }

    @GetMapping("/lesson-changes")
    public List<ChangeService.Change> list(
            @AuthenticationPrincipal Long user, @PathVariable long term) {
        return changes.list(user, term);
    }

    @GetMapping("/lesson-changes/{id}/lessons")
    public List<DatedLessons.Lesson> details(@AuthenticationPrincipal Long user,
            @PathVariable long term, @PathVariable long id) {
        return changes.details(user, term, id);
    }

    @GetMapping("/lesson-changes/{firstId}/candidates")
    public List<Long> candidates(@AuthenticationPrincipal Long user, @PathVariable long term,
            @PathVariable long firstId, @RequestParam long firstRev, @RequestParam ChangeService.Kind kind,
            @RequestParam(required=false) LocalDate date) {
        return changes.candidates(user, term, firstId, firstRev, kind, date);
    }

    @PostMapping("/lesson-changes/substitutes")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ChangeService.Change> batch(@AuthenticationPrincipal Long user, @PathVariable long term,
            @Valid @RequestBody ChangeService.CoverBatch request) {
        return changes.createBatch(user, term, request);
    }

    @PostMapping("/lesson-changes")
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeService.Change create(
            @AuthenticationPrincipal Long user,
            @PathVariable long term,
            @Valid @RequestBody ChangeService.NewChange request) {
        return changes.create(user, term, request);
    }

    @PostMapping("/lesson-changes/{id}/response")
    public ChangeService.Change respond(
            @AuthenticationPrincipal Long user,
            @PathVariable long term,
            @PathVariable long id,
            @Valid @RequestBody Decision decision) {
        return changes.respond(user, term, id, decision.accept, decision.expectedRequestRev);
    }

    @PostMapping("/lesson-changes/{id}/approval")
    public ChangeService.Outcome approve(
            @AuthenticationPrincipal Long user,
            @PathVariable long term,
            @PathVariable long id,
            @Valid @RequestBody ChangeService.Approval request,
            @RequestHeader("Idempotency-Key") UUID key) {
        return changes.approve(user, term, id, request, key);
    }
}
