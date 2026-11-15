-- Security fix 8.1 (Sprint C3.6): tool results are protected at rest.
--
-- ADDITIVE and backward compatible: `result` (plaintext, legacy) is NOT dropped or altered, so rows written before this migration stay
-- readable and a rolling deployment of an older instance keeps working for them. New results are written ONLY to `result_envelope`:
-- a versioned AES-256-GCM envelope (see ToolResultProtector: format byte, key id, 96-bit nonce, ciphertext+tag) whose additional
-- authenticated data binds organization, execution and invocation, with a key DERIVED (HKDF-SHA256) for this purpose from the existing
-- master key. Legacy plaintext rows are converted by the application's batch backfill (ToolResultBackfill), never by this script, so the
-- conversion can verify every row and never needs the key inside the database.
--
-- A row never holds both forms: the CHECK below makes "plaintext AND ciphertext" impossible, so a converted row cannot keep a second
-- readable copy. (Older WAL segments, backups and dead tuples may still hold the former plaintext: see docs/SECURITY_AUDIT.md.)

ALTER TABLE tool_invocations ADD COLUMN result_envelope bytea;

ALTER TABLE tool_invocations
    ADD CONSTRAINT ck_tool_invocations_one_result_form CHECK (result IS NULL OR result_envelope IS NULL);

-- The minimum envelope is 2 header bytes + 12-byte nonce + 16-byte GCM tag.
ALTER TABLE tool_invocations
    ADD CONSTRAINT ck_tool_invocations_result_envelope_size CHECK (result_envelope IS NULL OR octet_length(result_envelope) >= 30);

COMMENT ON COLUMN tool_invocations.result IS 'LEGACY plaintext tool result (rows written before V11). New results are never stored here; the batch backfill moves them to result_envelope and clears this column.';
COMMENT ON COLUMN tool_invocations.result_envelope IS 'Versioned AES-256-GCM envelope of the tool result. AAD binds organization, execution and invocation. Written only by the Core API.';
