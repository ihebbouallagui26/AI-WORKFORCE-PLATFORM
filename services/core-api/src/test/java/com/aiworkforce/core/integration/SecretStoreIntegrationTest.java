package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiworkforce.core.provider.SecretStore;
import com.aiworkforce.core.provider.SecretsProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The secret store: AES-256-GCM, random nonces, bound to the organization, tamper-evident. */
class SecretStoreIntegrationTest extends AgentFlows {

    @Autowired
    private SecretStore secrets;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID organization() throws Exception {
        return createOrganization(newUser("owner"), "Secret store");
    }

    @Test
    void roundTripsASecretAndStoresOnlyCiphertext() throws Exception {
        UUID org = organization();
        String secret = "sk-very-secret-" + UUID.randomUUID();
        UUID reference = secrets.store(org, secret);

        assertThat(secrets.reveal(org, reference)).contains(secret);
        byte[] ciphertext = jdbc.queryForObject("SELECT ciphertext FROM secret_entries WHERE id = ?", byte[].class, reference);
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(secret).doesNotContain("sk-very");
        // AES-GCM: plaintext length + 16-byte authentication tag; 12-byte nonce.
        assertThat(ciphertext).hasSize(secret.getBytes(StandardCharsets.UTF_8).length + 16);
        assertThat(jdbc.queryForObject("SELECT octet_length(nonce) FROM secret_entries WHERE id = ?", Integer.class, reference))
                .isEqualTo(12);
    }

    @Test
    void everyEncryptionUsesAFreshNonce() throws Exception {
        UUID org = organization();
        UUID first = secrets.store(org, "same-secret-value");
        UUID second = secrets.store(org, "same-secret-value");
        byte[] nonceA = jdbc.queryForObject("SELECT nonce FROM secret_entries WHERE id = ?", byte[].class, first);
        byte[] nonceB = jdbc.queryForObject("SELECT nonce FROM secret_entries WHERE id = ?", byte[].class, second);
        byte[] cipherA = jdbc.queryForObject("SELECT ciphertext FROM secret_entries WHERE id = ?", byte[].class, first);
        byte[] cipherB = jdbc.queryForObject("SELECT ciphertext FROM secret_entries WHERE id = ?", byte[].class, second);
        assertThat(nonceA).isNotEqualTo(nonceB);
        assertThat(cipherA).isNotEqualTo(cipherB);
    }

    @Test
    void aSecretIsInvisibleToOtherOrganizations() throws Exception {
        UUID orgA = organization();
        UUID orgB = organization();
        UUID reference = secrets.store(orgA, "belongs-to-a-only");
        assertThat(secrets.reveal(orgB, reference)).isEmpty();
        assertThat(secrets.reveal(orgA, UUID.randomUUID())).isEmpty();
        secrets.delete(orgB, reference);
        assertThat(secrets.reveal(orgA, reference)).contains("belongs-to-a-only");
    }

    @Test
    void tamperedOrRelocatedCiphertextFailsWithoutLeakingAnything() throws Exception {
        UUID org = organization();
        String secret = "tamper-test-secret-value";
        UUID reference = secrets.store(org, secret);
        UUID other = secrets.store(org, "another-secret-value");

        // A flipped bit is detected (GCM authentication).
        jdbc.update("UPDATE secret_entries SET ciphertext = set_byte(ciphertext, 0, get_byte(ciphertext, 0) # 1) WHERE id = ?", reference);
        assertThatThrownBy(() -> secrets.reveal(org, reference)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(secret).hasMessage("The secret could not be decrypted");

        // A valid ciphertext copied onto another entry fails too: the entry id is authenticated data.
        UUID victim = secrets.store(org, "victim-secret-value");
        jdbc.update("UPDATE secret_entries SET ciphertext = (SELECT ciphertext FROM secret_entries WHERE id = ?), "
                + "nonce = (SELECT nonce FROM secret_entries WHERE id = ?) WHERE id = ?", other, other, victim);
        assertThatThrownBy(() -> secrets.reveal(org, victim)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("another-secret-value");
        assertThat(secrets.reveal(org, other)).contains("another-secret-value");
    }

    @Test
    void deletingIsIdempotentAndFinal() throws Exception {
        UUID org = organization();
        UUID reference = secrets.store(org, "to-be-deleted");
        secrets.delete(org, reference);
        secrets.delete(org, reference);
        assertThat(secrets.reveal(org, reference)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secret_entries WHERE id = ?", Long.class, reference)).isZero();
    }

    @Test
    void theMasterKeyMustBeAValidAes256KeyAndIsNeverPrinted() {
        byte[] good = new byte[32];
        assertThat(new SecretsProperties(Base64.getEncoder().encodeToString(good)).masterKey()).isNotBlank();
        assertThatThrownBy(() -> new SecretsProperties("not base64 !!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretsProperties(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new SecretsProperties(Base64.getEncoder().encodeToString(new byte[33])))
                .isInstanceOf(IllegalArgumentException.class);
        String key = Base64.getEncoder().encodeToString(good);
        assertThat(new SecretsProperties(key).toString()).doesNotContain(key).contains("redacted");
    }
}
