package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.*;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PostgresSelectStatementGuardTest {
    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of("", PlanRejectionReason.EMPTY),
                Arguments.of("   ", PlanRejectionReason.EMPTY),
                Arguments.of("SELECT 1; SELECT 2", PlanRejectionReason.MULTIPLE_STATEMENTS),
                Arguments.of("SELECT 1;", PlanRejectionReason.MULTIPLE_STATEMENTS),
                Arguments.of(";SELECT 1", PlanRejectionReason.MULTIPLE_STATEMENTS),
                Arguments.of("\0SELECT 1", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT 1 /* ; */", PlanRejectionReason.MULTIPLE_STATEMENTS),
                Arguments.of("EXPLAIN ANALYZE SELECT 1", PlanRejectionReason.NOT_SELECT),
                Arguments.of("ANALYZE orders", PlanRejectionReason.NOT_SELECT),
                Arguments.of("DELETE FROM orders", PlanRejectionReason.NOT_SELECT),
                Arguments.of("INSERT INTO orders VALUES (1)", PlanRejectionReason.NOT_SELECT),
                Arguments.of("UPDATE orders SET id = 1", PlanRejectionReason.NOT_SELECT),
                Arguments.of("CREATE TABLE t (id int)", PlanRejectionReason.NOT_SELECT),
                Arguments.of("DROP TABLE orders", PlanRejectionReason.NOT_SELECT),
                Arguments.of("WITH d AS (DELETE FROM orders RETURNING 1) SELECT 1", PlanRejectionReason.NOT_SELECT),
                Arguments.of("SELECT id INTO t FROM orders", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT 1 /* outer /* nested */ tail */ INTO t", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT 1 /* ordinary */ FOR UPDATE", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT 1 -- ordinary\n INTO t", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT E'unfinished\\", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT * FROM orders FOR UPDATE", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT * FROM orders FOR SHARE", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT * FROM orders FOR NO KEY UPDATE", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT * FROM orders FOR KEY SHARE", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("VACUUM", PlanRejectionReason.NOT_SELECT),
                Arguments.of("SET work_mem = '1MB'", PlanRejectionReason.NOT_SELECT),
                Arguments.of("CALL p()", PlanRejectionReason.NOT_SELECT),
                Arguments.of("SELECT 1\0", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT id FROM orders WHERE customer_id = $1", PlanRejectionReason.PLACEHOLDERS_UNSUPPORTED),
                Arguments.of("SELECT 1 /* unterminated", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT /*/", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT /**", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT 'unterminated", PlanRejectionReason.FORBIDDEN_CLAUSE),
                Arguments.of("SELECT " + " ".repeat(8187), PlanRejectionReason.TOO_LARGE));
    }

    @ParameterizedTest @MethodSource("rejected")
    void rejectsUnsafeText(String sql, PlanRejectionReason reason) {
        assertEquals(reason, assertThrows(PlanCollectionRejected.class,
                () -> PostgresSelectStatementGuard.validate(sql)).reason());
    }

    @ParameterizedTest
    @MethodSource("accepted")
    void acceptsSafeSelect(String sql) {
        assertEquals(sql.trim(), PostgresSelectStatementGuard.validate(sql));
    }

    static Stream<String> accepted() {
        return Stream.of(
                "SELECT id FROM orders WHERE customer_id = 1",
                "SELECT id FROM orders WHERE status = 'PENDING'",
                "SELECT id FROM orders ORDER BY placed_at DESC LIMIT 50",
                "SELECT 'into', 'for update', \"analyze\" FROM orders",
                "SELECT E'for\\' update', $$into for update$$, $tag$for update$tag$",
                "SELECT 1 /* outer /* for update */ into */",
                "SELECT 1-- comment",
                "SELECT -- INTO\n 1",
                "SELECT -",
                "SELECT /",
                "SELECT $",
                "SELECT $tag",
                "SELECT FOR",
                "SELECT FOR NO KEY",
                "SELECT FOR KEY",
                "SELECT 1/* comment */",
                "SELECT 1/* /* nested */ */",
                "SELECT $$for update$$",
                "SELECT $tag$into$tag$",
                "SELECT 'it''s into'",
                "SELECT \"for update\"",
                "  -- into\n SELECT 1",
                "SELECT " + "x".repeat(8185));
    }

    @Test void exactBoundary() {
        assertEquals(8192, PostgresSelectStatementGuard.validate("SELECT " + "x".repeat(8185)).length());
        assertEquals(PlanRejectionReason.TOO_LARGE, assertThrows(PlanCollectionRejected.class,
                () -> PostgresSelectStatementGuard.validate("SELECT " + "x".repeat(8186))).reason());
    }
}
