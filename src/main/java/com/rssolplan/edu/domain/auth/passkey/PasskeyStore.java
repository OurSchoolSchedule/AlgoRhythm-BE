package com.rssolplan.edu.domain.auth.passkey;

import com.rssolplan.edu.domain.roster.TeacherRosterService;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class PasskeyStore implements CredentialRepository {
    private final JdbcTemplate db;
    private final PasskeyRealm realm;
    private static final SecureRandom RANDOM = new SecureRandom();

    static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    record Account(ByteArray handle, long revision) {}

    public record Device(String id, LocalDateTime createdAt) {}

    // Match BE-R1's immutable verification binding first, including pre-roster email accounts.
    public Long verifiedUserId(String email) {
        String address = TeacherRosterService.normalizeEmail(email);
        return db.queryForObject(
                """
                SELECT COALESCE(
                    (SELECT user_id FROM roster_verified_account WHERE auth_email=?),
                    (SELECT id FROM users WHERE provider='email' AND LOWER(provider_id)=?)
                )
                """,
                Long.class,
                address,
                address);
    }

    public Optional<String> verifiedEmail(long user) {
        return db
                .query(
                        """
                        SELECT COALESCE(v.auth_email, CASE WHEN u.provider='email' THEN LOWER(u.provider_id) END)
                        FROM users u LEFT JOIN roster_verified_account v ON v.user_id=u.id WHERE u.id=?
                        """,
                        (rs, n) -> rs.getString(1),
                        user)
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    private Long userIdForUsername(String name) {
        // Older in-flight ceremonies may still contain the numeric username.
        return name.contains("@") ? verifiedUserId(name) : Long.valueOf(name);
    }

    Account lock(long user) {
        db.update(
                """
                INSERT INTO passkey_account(rp_id,user_id,user_handle) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE revision=revision
                """,
                realm.id(),
                user,
                randomBytes());
        return db.queryForObject(
"""
SELECT user_handle,revision FROM passkey_account WHERE rp_id=? AND user_id=? FOR UPDATE
""",
                (rs, n) -> new Account(new ByteArray(rs.getBytes(1)), rs.getLong(2)),
                realm.id(),
                user);
    }

    List<Device> devices(long user) {
        return db.query(
                """
                SELECT credential_id,created_at FROM passkey_credential WHERE rp_id=? AND user_id=?
                ORDER BY created_at,credential_id
                """,
                (rs, n) ->
                        new Device(
                                new ByteArray(rs.getBytes(1)).getBase64Url(),
                                rs.getObject(2, LocalDateTime.class)),
                realm.id(),
                user);
    }

    void insert(
            long user,
            ByteArray id,
            ByteArray key,
            long count,
            boolean backupEligible,
            boolean backedUp) {
        db.update(
"""
INSERT INTO passkey_credential(rp_id,user_id,credential_id,public_key_cose,signature_count,backup_eligible,backed_up)
VALUES (?,?,?,?,?,?,?)
""",
                realm.id(),
                user,
                id.getBytes(),
                key.getBytes(),
                count,
                backupEligible,
                backedUp);
    }

    void recordAssertion(long user, ByteArray id, long count, boolean backedUp) {
        db.update(
                "UPDATE passkey_credential SET signature_count=?, backed_up=? WHERE rp_id=? AND"
                        + " user_id=? AND credential_id=?",
                count,
                backedUp,
                realm.id(),
                user,
                id.getBytes());
    }

    int remove(long user, ByteArray id) {
        return db.update(
                "DELETE FROM passkey_credential WHERE rp_id=? AND user_id=? AND credential_id=?",
                realm.id(),
                user,
                id.getBytes());
    }

    void removeAll(long user) {
        db.update("DELETE FROM passkey_credential WHERE rp_id=? AND user_id=?", realm.id(), user);
    }

    void advance(long user) {
        db.update(
                "UPDATE passkey_account SET revision=revision+1 WHERE rp_id=? AND user_id=?",
                realm.id(),
                user);
    }

    void saveRecovery(long user, byte[] hash) {
        db.update(
"""
UPDATE passkey_account SET recovery_hash=?, recovery_expires_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 365 DAY)
WHERE rp_id=? AND user_id=?
""",
                hash,
                realm.id(),
                user);
    }

    void clearRecovery(long user) {
        db.update(
                "UPDATE passkey_account SET recovery_hash=NULL, recovery_expires_at=NULL WHERE"
                    + " rp_id=? AND user_id=?",
                realm.id(),
                user);
    }

    boolean validRecovery(long user, byte[] hash) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM passkey_account WHERE rp_id=? AND user_id=?
                            AND recovery_hash=? AND recovery_expires_at>UTC_TIMESTAMP(6))
                        """,
                        Boolean.class,
                        realm.id(),
                        user,
                        hash));
    }

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String name) {
        return new HashSet<>(
                db.query(
                        "SELECT credential_id FROM passkey_credential WHERE rp_id=? AND user_id=?",
                        (rs, n) ->
                                PublicKeyCredentialDescriptor.builder()
                                        .id(new ByteArray(rs.getBytes(1)))
                                        .build(),
                        realm.id(),
                        userIdForUsername(name)));
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String name) {
        return db
                .query(
                        "SELECT user_handle FROM passkey_account WHERE rp_id=? AND user_id=?",
                        (rs, n) -> new ByteArray(rs.getBytes(1)),
                        realm.id(),
                        userIdForUsername(name))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray handle) {
        return userIdForHandle(handle).map(id -> verifiedEmail(id).orElseGet(id::toString));
    }

    Optional<Long> userIdForHandle(ByteArray handle) {
        return db
                .query(
                        "SELECT user_id FROM passkey_account WHERE rp_id=? AND user_handle=?",
                        (rs, n) -> rs.getLong(1),
                        realm.id(),
                        handle.getBytes())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray id, ByteArray handle) {
        return lookupAll(id).stream().filter(c -> c.getUserHandle().equals(handle)).findFirst();
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray id) {
        return new HashSet<>(
                db.query(
"""
SELECT a.user_handle,c.public_key_cose,c.signature_count,c.backup_eligible,c.backed_up FROM passkey_credential c
JOIN passkey_account a ON a.rp_id=c.rp_id AND a.user_id=c.user_id
WHERE c.rp_id=? AND c.credential_id=?
""",
                        (rs, n) ->
                                RegisteredCredential.builder()
                                        .credentialId(id)
                                        .userHandle(new ByteArray(rs.getBytes(1)))
                                        .publicKeyCose(new ByteArray(rs.getBytes(2)))
                                        .signatureCount(rs.getLong(3))
                                        .backupEligible(rs.getBoolean(4))
                                        .backupState(rs.getBoolean(5))
                                        .build(),
                        realm.id(),
                        id.getBytes()));
    }
}
