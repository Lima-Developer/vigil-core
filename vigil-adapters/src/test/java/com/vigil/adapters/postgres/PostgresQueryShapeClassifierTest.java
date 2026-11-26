package com.vigil.adapters.postgres;

import com.vigil.core.domain.enums.QueryShape;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresQueryShapeClassifierTest {
    private final PostgresQueryShapeClassifier classifier = new PostgresQueryShapeClassifier();

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            SELECT id FROM orders WHERE status = 'OPEN' | SIMPLE_SELECT
            SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id | JOIN
            SELECT status, count(*) FROM orders GROUP BY status | AGGREGATE
            SELECT * FROM orders WHERE customer_id IN (SELECT id FROM customers) | SUBQUERY
            SELECT id FROM orders UNION SELECT id FROM archived_orders | SET_OPERATION
            """)
    void classifiesQueryShapesConservatively(String sql, QueryShape expected) {
        assertEquals(expected, classifier.classify(sql));
    }
}
