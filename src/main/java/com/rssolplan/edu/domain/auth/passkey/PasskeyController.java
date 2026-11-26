package com.rssolplan.edu.domain.auth.passkey;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth/passkeys")
@RequiredArgsConstructor
public class PasskeyController {
    private final PasskeyService passkeys;

    public record Flow(@NotBlank String flowToken) {}

    public record Finish(@NotBlank String ceremonyId, @NotNull JsonNode credential) {}

    public record RegistrationFinish(
            @NotBlank String ceremonyId,
            @NotNull JsonNode credential,
            Boolean includeRecoveryCode) {}

    public record Reauth(@NotBlank String operation, String credentialId) {}

    public record Approval(@NotBlank String approvalToken) {}

    public record Recover(@NotBlank String flowToken, @NotBlank String recoveryCode) {}

    @ExceptionHandler({
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public PasskeyProblem.Handler.Error invalidBody() {
        return new PasskeyProblem.Handler.Error("PASSKEY_INVALID", "요청 값을 확인해 주세요.");
    }

    @PostMapping("/registration/options")
    public PasskeyService.Options register(@Valid @RequestBody Flow request) {
        return passkeys.initialRegistration(request.flowToken());
    }

    @PostMapping("/registration/finish")
    public PasskeyService.Session finishRegistration(
            @AuthenticationPrincipal Long user, @Valid @RequestBody RegistrationFinish request) {
        return passkeys.finishRegistration(
                request.ceremonyId(),
                request.credential(),
                user,
                !Boolean.FALSE.equals(request.includeRecoveryCode()));
    }

    @PostMapping("/authentication/options")
    public PasskeyService.Options authenticate() {
        return passkeys.login();
    }

    @PostMapping("/authentication/finish")
    public PasskeyService.AssertionFinish finishAuthentication(
            @AuthenticationPrincipal Long user, @Valid @RequestBody Finish request) {
        return passkeys.finishAssertion(request.ceremonyId(), request.credential(), user);
    }

    @GetMapping("/credentials")
    public List<PasskeyStore.Device> list(@AuthenticationPrincipal Long user) {
        return passkeys.devices(user);
    }

    @PostMapping("/reauthentication/options")
    public PasskeyService.Options reauthenticate(
            @AuthenticationPrincipal Long user, @Valid @RequestBody Reauth request) {
        return passkeys.reauthenticate(user, request.operation(), request.credentialId());
    }

    @PostMapping("/credentials/options")
    public PasskeyService.Options add(
            @AuthenticationPrincipal Long user, @Valid @RequestBody Approval request) {
        return passkeys.add(user, request.approvalToken());
    }

    @DeleteMapping("/credentials/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal Long user,
            @PathVariable String id,
            @Valid @RequestBody Approval request) {
        passkeys.remove(user, id, request.approvalToken());
    }

    @PostMapping("/recovery/options")
    public PasskeyService.Options recover(@Valid @RequestBody Recover request) {
        return passkeys.recover(request.flowToken(), request.recoveryCode());
    }

    @PostMapping("/recovery-code")
    public PasskeyService.Recovery replaceRecoveryCode(
            @AuthenticationPrincipal Long user, @Valid @RequestBody Approval request) {
        return passkeys.replaceRecoveryCode(user, request.approvalToken());
    }
}
