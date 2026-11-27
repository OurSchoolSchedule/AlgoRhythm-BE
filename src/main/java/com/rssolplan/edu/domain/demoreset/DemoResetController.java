package com.rssolplan.edu.domain.demoreset;

import com.rssolplan.edu.domain.schedule.candidates.School42Round.TimetableScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/demo-reset")
@RequiredArgsConstructor
public class DemoResetController {
  private final DemoResetService service;

  public record Input(
      @Valid @NotNull TimetableScope scope,
      @NotBlank @Size(max = 16) String password,
      @NotBlank @Size(max = 128) String approvalToken) {
    @Override
    public String toString() {
      return "DemoResetInput";
    }
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public java.util.Map<String, String> invalidInput() {
    return java.util.Map.of("message", "초기화 요청을 확인하세요.");
  }

  @GetMapping("/setup")
  public com.rssolplan.edu.domain.schedule.candidates.School42Round.Setup setupStatus(
      @AuthenticationPrincipal Long user) {
    return service.setupStatus(user);
  }

  @PostMapping("/setup")
  public com.rssolplan.edu.domain.schedule.candidates.School42Round.Setup prepare(
      @AuthenticationPrincipal Long user) {
    return service.prepare(user);
  }

  @GetMapping
  public TimetableScope preview(@AuthenticationPrincipal Long user,
      @RequestParam(defaultValue = "TIMETABLE") com.rssolplan.edu.domain.schedule.candidates.School42Round.Mode mode) {
    return service.preview(user, mode);
  }

  @PostMapping
  public TimetableScope reset(@AuthenticationPrincipal Long user, @Valid @RequestBody Input input) {
    return service.reset(user, input.scope(), input.password(), input.approvalToken());
  }
}
