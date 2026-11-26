package com.vigil.adapters.sqlite;

import com.vigil.core.domain.MetricEvidencePayload;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.RelationMetadataEvidencePayload;
import com.vigil.core.domain.RelationBlueprint;
import com.vigil.core.domain.IndexBlueprint;
import com.vigil.core.domain.IndexKey;
import com.vigil.core.domain.SafeCatalogReference;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.enums.MeasurementUnit;
import com.vigil.core.domain.enums.MetricKind;
import com.vigil.core.domain.enums.PlanNodeType;
import com.vigil.core.domain.enums.RelationType;
import com.vigil.core.domain.interfaces.EvidencePayload;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit text codecs for the three evidence payloads: {@code name=value} fields joined by {@code ;} in a fixed
 * order. Values are enum codes, canonical instants, plain decimals/integers, booleans and sanitized relation
 * identifiers, so none can contain the separators. Decimals are written with {@link BigDecimal#toString()}
 * (scale preserved, never through {@code double}).
 */
final class EvidencePayloadCodec {
    private static final String SEPARATOR = ";";

    private EvidencePayloadCodec() {
    }

    static String encode(EvidencePayload payload) {
        return switch (payload) {
            case MetricEvidencePayload metric -> join(
                    "metric", metric.metric().name(),
                    "value", metric.value().toString(),
                    "unit", metric.unit().name(),
                    "windowStart", SqliteInstantCodec.encode(metric.observationWindow().startedAtExclusive()),
                    "windowEnd", SqliteInstantCodec.encode(metric.observationWindow().endedAtInclusive()));
            case PlanSummaryEvidencePayload plan -> encodePlan(plan);
            case RelationMetadataEvidencePayload relation -> relation.blueprint().isPresent()
                    ? encodeBlueprint(relation.blueprint().orElseThrow())
                    : join("relation", relation.relation().value(),
                            "estimatedRows", Long.toString(relation.estimatedRows()),
                            "indexCount", Integer.toString(relation.indexCount()),
                            "compatibleIndexExists", Boolean.toString(relation.compatibleIndexExists()));
            default -> throw new IllegalArgumentException("Unsupported evidence payload type.");
        };
    }

    static EvidencePayload decode(EvidenceType type, String text) {
        try {
            return switch (type) {
                case METRIC -> {
                    var f = fields(text, "metric", "value", "unit", "windowStart", "windowEnd");
                    yield new MetricEvidencePayload(
                            PersistedValues.code(MetricKind.class, f.get("metric"), "payload"),
                            new BigDecimal(f.get("value")),
                            PersistedValues.code(MeasurementUnit.class, f.get("unit"), "payload"),
                            new ObservationWindow(SqliteInstantCodec.decode(f.get("windowStart")),
                                    SqliteInstantCodec.decode(f.get("windowEnd"))));
                }
                case PLAN_SUMMARY -> {
                    int count = text.split(SEPARATOR, -1).length;
                    var f = switch (count) {
                        case 2 -> fields(text, "nodes", "estimatedRows");
                        case 3 -> fields(text, "nodes", "estimatedRows", "estimatedTotalCost");
                        case 4 -> fields(text, "nodes", "estimatedRows", "estimatedTotalCost", "planNodes");
                        default -> throw new IllegalArgumentException("Unexpected plan payload fields.");
                    };
                    var nodes = new ArrayList<PlanNodeType>();
                    for (var node : f.get("nodes").split(",", -1)) {
                        nodes.add(PersistedValues.code(PlanNodeType.class, node, "payload"));
                    }
                    yield new PlanSummaryEvidencePayload(nodes, Long.parseLong(f.get("estimatedRows")),
                            f.containsKey("estimatedTotalCost") && !"-".equals(f.get("estimatedTotalCost"))
                                    ? java.util.Optional.of(new BigDecimal(f.get("estimatedTotalCost")))
                                    : java.util.Optional.empty(),
                            f.containsKey("planNodes") ? decodePlanNodes(f.get("planNodes")) : List.of());
                }
                case RELATION_METADATA -> {
                    if (text.split(SEPARATOR, -1).length == 6) {
                        yield RelationMetadataEvidencePayload.fromBlueprint(decodeBlueprint(text));
                    }
                    var f = fields(text, "relation", "estimatedRows", "indexCount", "compatibleIndexExists");
                    yield new RelationMetadataEvidencePayload(new SafeRelationReference(f.get("relation")),
                            Long.parseLong(f.get("estimatedRows")), Integer.parseInt(f.get("indexCount")),
                            bool(f.get("compatibleIndexExists")));
                }
            };
        } catch (CorruptAnalysisCaseDataException corrupt) {
            throw corrupt;
        } catch (RuntimeException invalid) {
            throw new CorruptAnalysisCaseDataException("Evidence payload is not valid for type " + type + ".", invalid);
        }
    }

    private static String encodePlan(PlanSummaryEvidencePayload plan) {
        var values = new ArrayList<String>();
        values.add("nodes");
        values.add(String.join(",", plan.nodeTypes().stream().map(Enum::name).toList()));
        values.add("estimatedRows");
        values.add(Long.toString(plan.estimatedRows()));
        if (plan.estimatedTotalCost().isPresent() || !plan.nodes().isEmpty()) {
            values.add("estimatedTotalCost");
            values.add(plan.estimatedTotalCost().map(BigDecimal::toPlainString).orElse("-"));
        }
        if (!plan.nodes().isEmpty()) {
            values.add("planNodes");
            values.add(String.join("|", plan.nodes().stream().map(node -> String.join("~",
                    node.type().name(), node.relation().map(SafeRelationReference::value).orElse("-"),
                    Boolean.toString(node.filterPresent()), Boolean.toString(node.filterColumnsKnown()),
                    Long.toString(node.estimatedRows()),
                    String.join(",", node.filterColumns().stream().map(SafeCatalogReference::value).toList()))).toList()));
        }
        return join(values.toArray(String[]::new));
    }

    private static List<com.vigil.core.domain.PlanNodeEvidence> decodePlanNodes(String text) {
        var result = new ArrayList<com.vigil.core.domain.PlanNodeEvidence>();
        if (text.isEmpty()) return result;
        for (var encoded : text.split("\\|", -1)) {
            var p = encoded.split("~", -1);
            if (p.length != 6) throw new IllegalArgumentException("Invalid plan node.");
            var columns = new ArrayList<SafeCatalogReference>();
            if (!p[5].isEmpty()) for (var column : p[5].split(",", -1)) columns.add(new SafeCatalogReference(column));
            result.add(new com.vigil.core.domain.PlanNodeEvidence(
                    PersistedValues.code(PlanNodeType.class, p[0], "payload"),
                    "-".equals(p[1]) ? java.util.Optional.empty()
                            : java.util.Optional.of(new SafeRelationReference(p[1])),
                    bool(p[2]), columns, bool(p[3]), Long.parseLong(p[4])));
        }
        return result;
    }

    private static String encodeBlueprint(RelationBlueprint relation) {
        String indexes = String.join("|", relation.indexes().stream().map(index -> String.join("~",
                index.index().value(), index.accessMethod(),
                String.join(",", index.keys().stream().map(key -> key.column().map(SafeCatalogReference::value).orElse("-")).toList()),
                Boolean.toString(index.unique()), Boolean.toString(index.primary()), Boolean.toString(index.partial()),
                Boolean.toString(index.valid()))).toList());
        return join("relation", relation.relation().value(), "schema", relation.schema().value(),
                "relationType", relation.type().name(), "estimatedRows",
                relation.estimatedRows().isPresent() ? Long.toString(relation.estimatedRows().getAsLong()) : "-",
                "pages", Long.toString(relation.pages()), "indexes", indexes);
    }

    private static RelationBlueprint decodeBlueprint(String text) {
        var f = fields(text, "relation", "schema", "relationType", "estimatedRows", "pages", "indexes");
        var indexes = new ArrayList<IndexBlueprint>();
        if (!f.get("indexes").isEmpty()) for (var encoded : f.get("indexes").split("\\|", -1)) {
            var p = encoded.split("~", -1);
            if (p.length != 7) throw new IllegalArgumentException("Invalid index blueprint.");
            var keys = new ArrayList<IndexKey>();
            for (var key : p[2].split(",", -1)) keys.add("-".equals(key)
                    ? IndexKey.ofExpression() : IndexKey.ofColumn(new SafeCatalogReference(key)));
            indexes.add(new IndexBlueprint(new SafeCatalogReference(p[0]), p[1], keys,
                    bool(p[3]), bool(p[4]), bool(p[5]), bool(p[6])));
        }
        return new RelationBlueprint(new SafeRelationReference(f.get("relation")),
                new SafeCatalogReference(f.get("schema")),
                PersistedValues.code(RelationType.class, f.get("relationType"), "payload"),
                "-".equals(f.get("estimatedRows")) ? java.util.OptionalLong.empty()
                        : java.util.OptionalLong.of(Long.parseLong(f.get("estimatedRows"))),
                Long.parseLong(f.get("pages")), List.of(), List.of(), indexes);
    }

    private static boolean bool(String text) {
        return switch (text) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Not a boolean.");
        };
    }

    private static String join(String... namesAndValues) {
        var parts = new ArrayList<String>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            parts.add(namesAndValues[index] + "=" + namesAndValues[index + 1]);
        }
        return String.join(SEPARATOR, parts);
    }

    private static Map<String, String> fields(String text, String... expectedNames) {
        List<String> parts = List.of(text.split(SEPARATOR, -1));
        if (parts.size() != expectedNames.length) {
            throw new IllegalArgumentException("Unexpected number of payload fields.");
        }
        var fields = new LinkedHashMap<String, String>();
        for (int index = 0; index < parts.size(); index++) {
            var prefix = expectedNames[index] + "=";
            if (!parts.get(index).startsWith(prefix)) {
                throw new IllegalArgumentException("Unexpected payload field order.");
            }
            fields.put(expectedNames[index], parts.get(index).substring(prefix.length()));
        }
        return fields;
    }
}
