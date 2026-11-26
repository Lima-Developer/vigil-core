package com.vigil.adapters.postgres;

import com.vigil.core.domain.enums.QueryShape;

/** Conservative adapter-side classification; raw SQL never leaves this adapter. */
public final class PostgresQueryShapeClassifier {
    public QueryShape classify(String sql) {
        String safe = PostgresSelectStatementGuard.validate(sql).toUpperCase(java.util.Locale.ROOT);
        if (safe.matches("(?s).*(\\bUNION\\b|\\bINTERSECT\\b|\\bEXCEPT\\b).*$")) return QueryShape.SET_OPERATION;
        if (safe.matches("(?s).*\\(\\s*SELECT\\b.*")) return QueryShape.SUBQUERY;
        if (safe.matches("(?s).*(\\bGROUP\\s+BY\\b|\\bHAVING\\b|\\b(COUNT|SUM|AVG|MIN|MAX)\\s*\\().*$"))
            return QueryShape.AGGREGATE;
        if (safe.matches("(?s).*\\bJOIN\\b.*")) return QueryShape.JOIN;
        if (safe.matches("(?s)^\\s*SELECT\\b.*")) return QueryShape.SIMPLE_SELECT;
        return QueryShape.UNSUPPORTED;
    }
}
