package com.rssolplan.edu.domain.auth.passkey;

import static com.rssolplan.edu.domain.auth.passkey.PasskeyFlows.Action.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rssolplan.edu.domain.auth.UserRefreshToken;
import com.rssolplan.edu.domain.auth.UserRefreshTokenRepository;
import com.rssolplan.edu.domain.auth.passkey.PasskeyFlows.Action;
import com.rssolplan.edu.domain.auth.passkey.PasskeyFlows.State;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.config.JwtTokenProvider;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class PasskeyService {
    private final PasskeyStore store;
    private final PasskeyRealm realm;
    private final PasskeyFlows flows;
    private final RelyingParty rp;
    private final ObjectMapper json;
    private final UserRepository users;
    private final UserRefreshTokenRepository refreshTokens;
    private final JwtTokenProvider jwt;

    public record EmailStep(String nextStep, String flowToken) {}

    public record Options(String ceremonyId, JsonNode publicKey) {}

    public sealed interface AssertionFinish permits Session, Approval, Verification {}

    public record PasskeyUser(String rpId, String userId, String name, String displayName) {}

    public record Verification(boolean verified, PasskeyUser passkeyUser)
            implements AssertionFinish {}

    public record Session(
            long userId,
            String accessToken,
            String refreshToken,
            String recoveryCode,
            PasskeyUser passkeyUser)
            implements AssertionFinish {}

    public record Approval(String approvalToken) implements AssertionFinish {}

    public record Recovery(String recoveryCode) {}

    public boolean requiresPasskey(long user) {
        store.lock(user);
        return !store.devices(user).isEmpty();
    }

    public EmailStep afterVerifiedEmail(long user) {
        var account = store.lock(user);
        boolean registered = !store.devices(user).isEmpty();
        var state = new State(registered ? RECOVER : FIRST, user, account.revision(), null, null);
        return new EmailStep(registered ? "AUTHENTICATE" : "REGISTER", flows.put("email", state));
    }

    public Options initialRegistration(String token) {
        State state = flows.take("email", token);
        if (state.action() != FIRST) throw PasskeyProblem.required();
        return registration(state);
    }

    public Options recover(String emailToken, String recoveryCode) {
        State email = flows.take("email", emailToken);
        if (email.action() != RECOVER) throw PasskeyProblem.invalid();
        byte[] hash = codeHash(recoveryCode);
        if (!store.validRecovery(email.user(), hash)) throw PasskeyProblem.invalid();
        // Recheck the hash, expiry and account revision at commit, before replacing any
        // credentials.
        return registration(new State(RECOVER, email.user(), email.revision(), base64(hash), null));
    }

    public Options add(long user, String approval) {
        return registration(approved(user, approval, ADD, null));
    }

    private Options registration(State state) {
        var account = current(state);
        String email = store.verifiedEmail(state.user()).orElseThrow(PasskeyProblem::stale);
        var identity =
                UserIdentity.builder().name(email).displayName(email).id(account.handle()).build();
        var creation =
                rp.startRegistration(
                        StartRegistrationOptions.builder()
                                .user(identity)
                                .authenticatorSelection(
                                        AuthenticatorSelectionCriteria.builder()
                                                .residentKey(ResidentKeyRequirement.REQUIRED)
                                                .userVerification(
                                                        UserVerificationRequirement.REQUIRED)
                                                .build())
                                .timeout(PasskeyFlows.TTL.toMillis())
                                .build());
        try {
            return options(
                    "register", state, creation.toJson(), creation.toCredentialsCreateJson());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode registration options", e);
        }
    }

    public Session finishRegistration(
            String ceremonyId, JsonNode response, Long signedInUser, boolean includeRecoveryCode) {
        State state = flows.take("register", ceremonyId);
        if (state.action() == ADD && !Objects.equals(signedInUser, state.user()))
            throw PasskeyProblem.invalid();
        current(state);
        long user = state.user();
        if (state.action() == FIRST && !store.devices(user).isEmpty()) throw PasskeyProblem.stale();
        if (state.action() == RECOVER
                && !store.validRecovery(user, decode(state.target()).getBytes()))
            throw PasskeyProblem.stale();
        RegistrationResult result;
        try {
            var request = PublicKeyCredentialCreationOptions.fromJson(state.options());
            var credential = PublicKeyCredential.parseRegistrationResponseJson(response.toString());
            requireTopLevel(credential.getResponse());
            result =
                    rp.finishRegistration(
                            FinishRegistrationOptions.builder()
                                    .request(request)
                                    .response(credential)
                                    .build());
        } catch (IOException | RegistrationFailedException | IllegalArgumentException e) {
            throw PasskeyProblem.invalid();
        }
        if (!result.isUserVerified()) throw PasskeyProblem.invalid();
        if (state.action() == RECOVER) store.removeAll(user);
        ByteArray id = result.getKeyId().getId();
        try {
            store.insert(
                    user,
                    id,
                    result.getPublicKeyCose(),
                    result.getSignatureCount(),
                    result.isBackupEligible(),
                    result.isBackedUp());
        } catch (DuplicateKeyException e) {
            throw PasskeyProblem.stale();
        }
        String recovery = null;
        if (state.action() != ADD && includeRecoveryCode) recovery = rotateCode(user);
        else if (state.action() == RECOVER) store.clearRecovery(user);
        store.advance(user);
        return session(user, id, recovery, null);
    }

    public Options login() {
        return assertion(new State(LOGIN, null, 0, null, null), null);
    }

    public Options reauthenticate(long user, String operation, String target) {
        Action action =
                switch (operation) {
                    case "VERIFY" -> VERIFY;
                    case "ADD" -> ADD;
                    case "DELETE" -> DELETE;
                    case "ROTATE" -> ROTATE;
                    default -> throw PasskeyProblem.invalid();
                };
        var account = store.lock(user);
        if (store.devices(user).isEmpty()) throw PasskeyProblem.required();
        if (action == DELETE) decode(target);
        else if (target != null) throw PasskeyProblem.invalid();
        return assertion(new State(action, user, account.revision(), target, null), account.handle());
    }

    private Options assertion(State state, ByteArray handle) {
        var builder =
                StartAssertionOptions.builder()
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .timeout(PasskeyFlows.TTL.toMillis());
        if (handle != null) builder.userHandle(handle);
        var request = rp.startAssertion(builder.build());
        try {
            return options("assert", state, request.toJson(), request.toCredentialsGetJson());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode assertion options", e);
        }
    }

    private void requireTopLevel(AuthenticatorResponse response) throws IOException {
        var data = json.readTree(response.getClientDataJSON().getBytes());
        if (data.path("crossOrigin").asBoolean(false) || data.hasNonNull("topOrigin"))
            throw PasskeyProblem.invalid();
    }

    private Options options(String stage, State state, String stored, String client)
            throws IOException {
        return new Options(
                flows.put(stage, state.withOptions(stored)),
                json.readTree(client).get("publicKey"));
    }

    public AssertionFinish finishAssertion(
            String ceremonyId, JsonNode response, Long signedInUser) {
        State state = flows.take("assert", ceremonyId);
        if (state.action() != LOGIN && !Objects.equals(state.user(), signedInUser))
            throw PasskeyProblem.invalid();
        try {
            var credential = PublicKeyCredential.parseAssertionResponseJson(response.toString());
            requireTopLevel(credential.getResponse());
            // Lock before signature-counter verification, including for discoverable (usernameless)
            // credentials.
            var found =
                    store.lookupAll(credential.getId()).stream()
                            .findFirst()
                            .orElseThrow(PasskeyProblem::invalid);
            long user =
                    store.userIdForHandle(found.getUserHandle())
                            .orElseThrow(PasskeyProblem::invalid);
            var account = store.lock(user);
            if (state.user() != null
                    && (state.user() != user || state.revision() != account.revision()))
                throw PasskeyProblem.stale();
            var result =
                    rp.finishAssertion(
                            FinishAssertionOptions.builder()
                                    .request(AssertionRequest.fromJson(state.options()))
                                    .response(credential)
                                    .build());
            if (!result.isSuccess()
                    || !result.isUserVerified()
                    || !account.handle().equals(result.getCredential().getUserHandle()))
                throw PasskeyProblem.invalid();
            store.recordAssertion(
                    user, credential.getId(), result.getSignatureCount(), result.isBackedUp());
            if (state.action() == LOGIN || state.action() == VERIFY) {
                var details =
                        store.verifiedEmail(user)
                                .map(
                                        email ->
                                                new PasskeyUser(
                                                        realm.id(),
                                                        account.handle().getBase64Url(),
                                                        email,
                                                        email))
                                .orElse(null);
                if (state.action() == VERIFY) return new Verification(true, details);
                return session(user, credential.getId(), null, details);
            }
            return new Approval(flows.put("approval", state.withOptions(null)));
        } catch (IOException | AssertionFailedException | IllegalArgumentException e) {
            throw PasskeyProblem.invalid();
        }
    }

    public List<PasskeyStore.Device> devices(long user) {
        return store.devices(user);
    }

    public void remove(long user, String target, String token) {
        approved(user, token, DELETE, target);
        if (store.devices(user).size() <= 1)
            throw new PasskeyProblem(
                    HttpStatus.CONFLICT, "LAST_PASSKEY", "마지막 패스키는 새 기기를 등록한 뒤 삭제할 수 있습니다.");
        if (store.remove(user, decode(target)) != 1) throw PasskeyProblem.invalid();
        store.advance(user);
    }

    public Recovery replaceRecoveryCode(long user, String token) {
        approved(user, token, ROTATE, null);
        store.advance(user);
        return new Recovery(rotateCode(user));
    }

    private State approved(long user, String token, Action action, String target) {
        State state = flows.take("approval", token);
        if (state.action() != action
                || state.user() != user
                || !Objects.equals(state.target(), target)) throw PasskeyProblem.invalid();
        current(state);
        return state;
    }

    private PasskeyStore.Account current(State state) {
        var account = store.lock(state.user());
        if (account.revision() != state.revision()) throw PasskeyProblem.stale();
        return account;
    }

    private String rotateCode(long user) {
        String code = base64(PasskeyStore.randomBytes());
        store.saveRecovery(user, codeHash(code));
        return code;
    }

    private static byte[] codeHash(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{43}")) throw PasskeyProblem.invalid();
        return hash(decode(code).getBytes());
    }

    private static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64(byte[] bytes) {
        return new ByteArray(bytes).getBase64Url();
    }

    private static ByteArray decode(String value) {
        if (value == null || value.isBlank() || value.length() > 1364)
            throw PasskeyProblem.invalid();
        try {
            return ByteArray.fromBase64Url(value);
        } catch (Base64UrlException e) {
            throw PasskeyProblem.invalid();
        }
    }

    private Session session(long user, ByteArray id, String recovery, PasskeyUser details) {
        String fingerprint = HexFormat.of().formatHex(hash(id.getBytes()));
        String access = jwt.issue(user, "access", "passkey", fingerprint);
        String refresh = jwt.issue(user, "refresh", "passkey", fingerprint);
        refreshTokens.save(
                UserRefreshToken.builder()
                        .user(users.getReferenceById(user))
                        .refreshToken(refresh)
                        .expiresAt(LocalDateTime.now().plusDays(14))
                        .build());
        return new Session(user, access, refresh, recovery, details);
    }
}
