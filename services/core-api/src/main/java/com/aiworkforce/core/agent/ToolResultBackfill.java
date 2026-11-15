package com.aiworkforce.core.agent;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Controlled conversion of tool results stored in PLAINTEXT before V11 (security fix 8.1).
 *
 * <ul>
 *   <li><b>Batched and short</b>: each batch is its own small transaction ({@code FOR UPDATE SKIP LOCKED}, ordered by id), so no long
 *       lock, no table lock, and several instances can run it at once without converting a row twice.</li>
 *   <li><b>Verified</b>: a row is switched only after its envelope has been opened again and equals the original; the same UPDATE clears the
 *       plaintext column, so a converted row never keeps a second readable copy (the V11 CHECK forbids it).</li>
 *   <li><b>Content-free</b>: only counts are logged, never a result, a key or an identifier of a result.</li>
 *   <li><b>Fail-safe</b>: the key is the one the application already requires to start; a row that fails verification is left untouched
 *       (still readable as legacy) and counted, the run continues; nothing is ever deleted.</li>
 *   <li><b>Idempotent / resumable</b>: it selects only rows that still hold plaintext. Disable the startup run with
 *       {@code app.tool-results.backfill-on-startup=false}; it can be invoked any time.</li>
 * </ul>
 */
@Component
class ToolResultBackfill implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ToolResultBackfill.class);
    static final int BATCH = 200;

    record Counts(int converted, int failed) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ToolResultProtector protector;
    private final boolean runAtStartup;

    ToolResultBackfill(JdbcTemplate jdbc, TransactionTemplate transactions, ToolResultProtector protector,
            @Value("${app.tool-results.backfill-on-startup:true}") boolean runAtStartup) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.protector = protector;
        this.runAtStartup = runAtStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (runAtStartup) {
            convertAll();
        }
    }

    /** Converts every remaining plaintext result, batch by batch. Returns the totals. */
    Counts convertAll() {
        int converted = 0;
        int failed = 0;
        int previousFailed;
        while (true) {
            previousFailed = failed;
            Counts batch = transactions.execute(status -> convertBatch(BATCH));
            converted += batch.converted();
            failed += batch.failed();
            // Stop when nothing was converted in a batch (empty, or only rows that fail verification: they must not loop for ever).
            if (batch.converted() == 0 || failed < previousFailed) {
                break;
            }
        }
        if (converted > 0 || failed > 0) {
            LOG.info("tool result backfill finished: converted={} failed_verification={}", converted, failed);
        }
        return new Counts(converted, failed);
    }

    private Counts convertBatch(int size) {
        record Row(UUID id, UUID organizationId, UUID executionId, String result) {
        }
        List<Row> rows = jdbc.query("""
                SELECT id, organization_id, execution_id, result FROM tool_invocations
                WHERE result IS NOT NULL AND result_envelope IS NULL
                ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4)), size);
        int converted = 0;
        int failed = 0;
        for (Row row : rows) {
            try {
                byte[] envelope = protector.seal(row.organizationId(), row.executionId(), row.id(), row.result());
                if (!row.result().equals(protector.open(row.organizationId(), row.executionId(), row.id(), envelope))) {
                    throw new IllegalStateException("verification failed");
                }
                int updated = jdbc.update("""
                        UPDATE tool_invocations SET result_envelope = ?, result = NULL, version = version + 1
                        WHERE id = ? AND result IS NOT NULL AND result_envelope IS NULL
                        """, envelope, row.id());
                converted += updated;
            } catch (RuntimeException e) {
                failed++; // left untouched and still readable as legacy; never logged with content
            }
        }
        return new Counts(converted, failed);
    }
}
