package com.vigil.adapters.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.PlanNodeEvidence;
import com.vigil.adapters.postgres.schema.Pseudonymizer;
import com.vigil.core.domain.enums.PlanNodeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/** Only the safe, aggregate fields cross the adapter boundary. */
public final class PostgresExplainPlanParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FILTER_COLUMN = Pattern.compile(
            "(?i)(?:^|[\\s(])(?:\"?[a-z_][a-z0-9_$]*\"?\\.)?\"?([a-z_][a-z0-9_$]*)\"?\\s*"
                    + "(?:=|<>|<=|>=|<|>|IS\\s+(?:NOT\\s+)?NULL|~~\\*?|!~~\\*?)");
    private static final Pattern FILTER_OPERATOR = Pattern.compile(
            "(?i)(?:=|<>|<=|>=|<|>|IS\\s+(?:NOT\\s+)?NULL|~~\\*?|!~~\\*?)");

    private PostgresExplainPlanParser() {
    }

    public static PlanSummaryEvidencePayload parse(String json) {
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        return parse(json, new Pseudonymizer(salt));
    }

    public static PlanSummaryEvidencePayload parse(String json, Pseudonymizer pseudonyms) {
        try {
            JsonNode document = JSON.readTree(json);
            if (!document.isArray() || document.isEmpty()) throw new IllegalArgumentException();
            JsonNode root = document.get(0).path("Plan");
            if (!root.isObject()) throw new IllegalArgumentException();
            JsonNode rows = root.path("Plan Rows");
            if (!rows.isNumber() || !Double.isFinite(rows.doubleValue()) || rows.doubleValue() < 0
                    || rows.decimalValue().compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE)) > 0) throw new IllegalArgumentException();
            List<PlanNodeType> types = new ArrayList<>();
            List<PlanNodeEvidence> nodes = new ArrayList<>();
            collect(root, types, nodes, pseudonyms);
            JsonNode cost = root.path("Total Cost");
            Optional<java.math.BigDecimal> estimatedCost = Optional.empty();
            if (!cost.isMissingNode()) {
                if (!cost.isNumber() || !Double.isFinite(cost.doubleValue()) || cost.decimalValue().signum() < 0)
                    throw new IllegalArgumentException();
                estimatedCost = Optional.of(cost.decimalValue());
            }
            return new PlanSummaryEvidencePayload(types, Math.round(rows.doubleValue()), estimatedCost, nodes);
        } catch (Exception failure) {
            throw new PlanCollectionRejected(PlanRejectionReason.PLAN_UNREADABLE);
        }
    }

    private static void collect(JsonNode node, List<PlanNodeType> types, List<PlanNodeEvidence> nodes,
                                Pseudonymizer pseudonyms) {
        if (!node.isObject() || !node.path("Node Type").isTextual()) throw new IllegalArgumentException();
        var type = switch (node.path("Node Type").textValue()) {
            case "Seq Scan" -> PlanNodeType.SEQUENTIAL_SCAN;
            case "Index Scan", "Index Only Scan", "Bitmap Index Scan" -> PlanNodeType.INDEX_SCAN;
            case "Hash Join" -> PlanNodeType.HASH_JOIN;
            default -> PlanNodeType.UNKNOWN;
        };
        types.add(type);
        String schema = node.path("Schema").isTextual() ? node.path("Schema").textValue() : "public";
        String relation = node.path("Relation Name").isTextual() ? node.path("Relation Name").textValue() : null;
        var safeRelation = relation == null ? Optional.<com.vigil.core.domain.SafeRelationReference>empty()
                : Optional.of(pseudonyms.relation(schema, relation));
        boolean filterPresent = node.path("Filter").isTextual();
        var columns = new ArrayList<com.vigil.core.domain.SafeCatalogReference>();
        boolean columnsKnown = !filterPresent;
        if (filterPresent && relation != null) {
            String filter = node.path("Filter").textValue();
            if (!Pattern.compile("(?i)\\bOR\\b|SubPlan|\\bSELECT\\b").matcher(filter).find()) {
                var matches = FILTER_COLUMN.matcher(filter);
                int count = 0;
                while (matches.find()) {
                    columns.add(pseudonyms.column(schema, relation, matches.group(1)));
                    count++;
                }
                int operators = 0;
                var operatorMatches = FILTER_OPERATOR.matcher(filter);
                while (operatorMatches.find()) operators++;
                columnsKnown = count > 0 && count == operators;
            }
        }
        if (!columnsKnown) columns.clear();
        JsonNode nodeRows = node.path("Plan Rows");
        long estimatedRows = nodeRows.isNumber() && nodeRows.doubleValue() >= 0
                ? Math.round(nodeRows.doubleValue()) : 0;
        nodes.add(new PlanNodeEvidence(type, safeRelation, filterPresent, columns, columnsKnown, estimatedRows));
        JsonNode children = node.path("Plans");
        if (!children.isMissingNode()) {
            if (!children.isArray()) throw new IllegalArgumentException();
            children.forEach(child -> collect(child, types, nodes, pseudonyms));
        }
    }
}
