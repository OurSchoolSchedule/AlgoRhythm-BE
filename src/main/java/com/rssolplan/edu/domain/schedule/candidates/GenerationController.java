package com.rssolplan.edu.domain.schedule.candidates;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v2/terms")
@RequiredArgsConstructor
class GenerationController {
    private final GenerationJobs jobs;
    private final GenerationStore store;
    private final PublicationService publications;

    record Accepted(UUID jobId, String state, String statusUrl, long expectedPublishRev) {}

    @GetMapping
    List<PublicationService.TermSummary> terms(@AuthenticationPrincipal Long user) {
        return publications.terms(user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> term(
            @AuthenticationPrincipal Long user,
            @Valid @RequestBody PublicationService.NewTerm request) {
        return Map.of("termId", publications.term(user, request));
    }

    @PostMapping("/{term}/generations")
    ResponseEntity<Accepted> start(
            @AuthenticationPrincipal Long user,
            @PathVariable long term,
            @RequestBody CandidateApi.Request request) {
        var t = jobs.submit(user, term, request);
        String url = "/api/v2/terms/" + term + "/generations/" + t.jobId();
        return ResponseEntity.accepted()
                .location(URI.create(url))
                .header("Retry-After", "2")
                .body(new Accepted(t.jobId(), "QUEUED", url, t.expectedPublishRev()));
    }

    @GetMapping("/{term}/generations/{id}")
    GenerationStore.Poll poll(
            @AuthenticationPrincipal Long user, @PathVariable long term, @PathVariable UUID id) {
        return store.poll(user, term, id);
    }

    @PostMapping("/{term}/publications")
    @ResponseStatus(HttpStatus.CREATED)
    PublicationService.Published publish(
            @AuthenticationPrincipal Long user,
            @PathVariable long term,
            @Valid @RequestBody PublicationService.Publish request) {
        return publications.publish(user, term, request);
    }
}
