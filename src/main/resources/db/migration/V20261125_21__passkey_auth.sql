-- Apply after users exists. No existing account or school membership is modified.
CREATE TABLE IF NOT EXISTS passkey_account (
    rp_id VARCHAR(253) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    user_handle BINARY(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    recovery_hash BINARY(32),
    recovery_expires_at DATETIME(6),
    PRIMARY KEY (rp_id, user_id),
    UNIQUE KEY uq_passkey_handle (rp_id, user_handle),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS passkey_credential (
    rp_id VARCHAR(253) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credential_id VARBINARY(1023) NOT NULL,
    user_id BIGINT NOT NULL,
    public_key_cose BLOB NOT NULL,
    signature_count BIGINT NOT NULL,
    backup_eligible BOOLEAN NOT NULL,
    backed_up BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (rp_id, credential_id),
    FOREIGN KEY (rp_id, user_id) REFERENCES passkey_account(rp_id, user_id) ON DELETE CASCADE
) ENGINE=InnoDB;
