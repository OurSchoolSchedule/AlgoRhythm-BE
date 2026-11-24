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
