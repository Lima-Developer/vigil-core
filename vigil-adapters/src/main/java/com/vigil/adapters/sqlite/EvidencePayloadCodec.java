package com.vigil.adapters.sqlite;

import com.vigil.core.domain.MetricEvidencePayload;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.RelationMetadataEvidencePayload;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.enums.MeasurementUnit;
import com.vigil.core.domain.enums.MetricKind;
import com.vigil.core.domain.enums.PlanNodeType;
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
            case PlanSummaryEvidencePayload plan -> join(
                    "nodes", String.join(",", plan.nodeTypes().stream().map(Enum::name).toList()),
                    "estimatedRows", Long.toString(plan.estimatedRows()));
            case RelationMetadataEvidencePayload relation -> join(
                    "relation", relation.relation().value(),
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
                    var f = fields(text, "nodes", "estimatedRows");
                    var nodes = new ArrayList<PlanNodeType>();
                    for (var node : f.get("nodes").split(",", -1)) {
                        nodes.add(PersistedValues.code(PlanNodeType.class, node, "payload"));
                    }
                    yield new PlanSummaryEvidencePayload(nodes, Long.parseLong(f.get("estimatedRows")));
                }
                case RELATION_METADATA -> {
                    var f = fields(text, "relation", "estimatedRows", "indexCount", "compatibleIndexExists");
                    yield new RelationMetadataEvidencePayload(
                            new SafeRelationReference(f.get("relation")),
                            Long.parseLong(f.get("estimatedRows")),
                            Integer.parseInt(f.get("indexCount")),
                            bool(f.get("compatibleIndexExists")));
                }
            };
        } catch (CorruptAnalysisCaseDataException corrupt) {
            throw corrupt;
        } catch (RuntimeException invalid) {
            throw new CorruptAnalysisCaseDataException("Evidence payload is not valid for type " + type + ".", invalid);
        }
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
