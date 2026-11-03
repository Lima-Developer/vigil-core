package com.vigil.adapters.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.enums.PlanNodeType;
import java.util.ArrayList;
import java.util.List;

/** Only the safe, aggregate fields cross the adapter boundary. */
final class PostgresExplainPlanParser {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PostgresExplainPlanParser() {
    }

    static PlanSummaryEvidencePayload parse(String json) {
        try {
            JsonNode document = JSON.readTree(json);
            if (!document.isArray() || document.isEmpty()) throw new IllegalArgumentException();
            JsonNode root = document.get(0).path("Plan");
            if (!root.isObject()) throw new IllegalArgumentException();
            JsonNode rows = root.path("Plan Rows");
            if (!rows.isNumber() || !Double.isFinite(rows.doubleValue()) || rows.doubleValue() < 0
                    || rows.decimalValue().compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE)) > 0) throw new IllegalArgumentException();
            List<PlanNodeType> types = new ArrayList<>();
            collect(root, types);
            return new PlanSummaryEvidencePayload(types, Math.round(rows.doubleValue()));
        } catch (Exception failure) {
            throw new PlanCollectionRejected(PlanRejectionReason.PLAN_UNREADABLE);
        }
    }

    private static void collect(JsonNode node, List<PlanNodeType> types) {
        if (!node.isObject() || !node.path("Node Type").isTextual()) throw new IllegalArgumentException();
        types.add(switch (node.path("Node Type").textValue()) {
            case "Seq Scan" -> PlanNodeType.SEQUENTIAL_SCAN;
            case "Index Scan", "Index Only Scan", "Bitmap Index Scan" -> PlanNodeType.INDEX_SCAN;
            case "Hash Join" -> PlanNodeType.HASH_JOIN;
            default -> PlanNodeType.UNKNOWN;
        });
        JsonNode children = node.path("Plans");
        if (!children.isMissingNode()) {
            if (!children.isArray()) throw new IllegalArgumentException();
            children.forEach(child -> collect(child, types));
        }
    }
}
