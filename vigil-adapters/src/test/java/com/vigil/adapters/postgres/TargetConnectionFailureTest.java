package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.application.exception.TargetConnectionFailure;
import com.vigil.application.exception.TargetFailureReason;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class TargetConnectionFailureTest {

    @Test
    void shouldDescribeTheReasonTargetAndSqlStateOnly() {
        var cause = new SQLException("jdbc:postgresql://db:5432/x password=" + PostgresTestSupport.canary());

        var failure = new TargetConnectionFailure(TargetFailureReason.TIMEOUT, "lab", "57014", cause);

        assertEquals("Falha ao acessar o target 'lab': TIMEOUT (SQLSTATE 57014)", failure.getMessage());
        assertEquals(TargetFailureReason.TIMEOUT, failure.reason());
        assertEquals("lab", failure.targetName());
        assertEquals("57014", failure.sqlState().orElseThrow());
        assertSame(cause, failure.getCause());
        assertFalse(failure.toString().contains("jdbc:"));
    }

    @Test
    void shouldOmitTheSqlStateWhenAbsent() {
        var failure = new TargetConnectionFailure(TargetFailureReason.UNKNOWN, "lab", null, null);

        assertEquals("Falha ao acessar o target 'lab': UNKNOWN", failure.getMessage());
        assertTrue(failure.sqlState().isEmpty());
    }

    @Test
    void shouldRejectMissingReasonOrTargetName() {
        assertThrows(NullPointerException.class, () -> new TargetConnectionFailure(null, "lab", null, null));
        assertThrows(NullPointerException.class,
                () -> new TargetConnectionFailure(TargetFailureReason.UNKNOWN, null, null, null));
    }
}
