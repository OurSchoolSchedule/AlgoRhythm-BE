package com.rssolplan.edu.domain.schedule.workshifts;

import com.rssolplan.edu.domain.schedule.workshifts.dto.TimetableCreateDto;
import com.rssolplan.edu.domain.schedule.workshifts.dto.TimetableDto;
import com.rssolplan.edu.global.security.annotation.OwnerOnly;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/timetable")
@RequiredArgsConstructor
public class TimetableController {

    private final TimetableService timetableService;

    @GetMapping("")
    public ResponseEntity<List<TimetableDto>> getTimetables(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) LocalDate weekOf,
            @RequestParam(required = false) Long termId) {
        return ResponseEntity.ok(timetableService.week(userId, false, null, null, termId, weekOf));
    }

    @GetMapping("/year/{year}/semester/{semester}")
    public ResponseEntity<List<TimetableDto>> getTimetablesByYearSemester(
            @AuthenticationPrincipal Long userId,
            @PathVariable int year,
            @PathVariable int semester,
            @RequestParam(required = false) LocalDate weekOf,
            @RequestParam(required = false) Long termId) {
        return ResponseEntity.ok(timetableService.week(userId, false, year, semester, termId, weekOf));
    }

    @GetMapping("/me")
    public ResponseEntity<List<TimetableDto>> getMyTimetable(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) LocalDate weekOf,
            @RequestParam(required = false) Long termId) {
        return ResponseEntity.ok(timetableService.week(userId, true, null, null, termId, weekOf));
    }

    @GetMapping("/me/year/{year}/semester/{semester}")
    public ResponseEntity<List<TimetableDto>> getMyTimetableByYearSemester(
            @AuthenticationPrincipal Long userId,
            @PathVariable int year,
            @PathVariable int semester,
            @RequestParam(required = false) LocalDate weekOf,
            @RequestParam(required = false) Long termId) {
        return ResponseEntity.ok(timetableService.week(userId, true, year, semester, termId, weekOf));
    }

    @OwnerOnly
    @PostMapping("")
    public ResponseEntity<TimetableDto> createTimetable(
            @AuthenticationPrincipal Long userId,
            @RequestBody TimetableCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(timetableService.createTimetable(userId, dto));
    }

    @OwnerOnly
    @PatchMapping("/{timetableId}")
    public ResponseEntity<TimetableDto> updateTimetable(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long timetableId,
            @RequestBody TimetableCreateDto dto) {
        return ResponseEntity.ok(timetableService.updateTimetable(userId, timetableId, dto));
    }

    @OwnerOnly
    @DeleteMapping("/{timetableId}")
    public ResponseEntity<Void> deleteTimetable(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long timetableId) {
        timetableService.deleteTimetable(userId, timetableId);
        return ResponseEntity.noContent().build();
    }
}
