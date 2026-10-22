package com.vigil.adapters.postgres.schema;

import com.vigil.adapters.postgres.schema.CatalogSnapshot.ColumnRow;
import com.vigil.adapters.postgres.schema.CatalogSnapshot.ConstraintRow;
import com.vigil.adapters.postgres.schema.CatalogSnapshot.IndexRow;
import com.vigil.adapters.postgres.schema.CatalogSnapshot.RelationRow;
import com.vigil.core.domain.ColumnBlueprint;
import com.vigil.core.domain.ConstraintBlueprint;
import com.vigil.core.domain.IndexBlueprint;
import com.vigil.core.domain.IndexKey;
import com.vigil.core.domain.RelationBlueprint;
import com.vigil.core.domain.SafeCatalogReference;
import com.vigil.core.domain.SchemaBlueprint;
import com.vigil.core.domain.enums.ConstraintKind;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.RelationType;
import com.vigil.core.domain.enums.SchemaIncompleteness;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** Pure translation of catalog rows into the domain blueprint; every name leaves here as a pseudonym. */
final class SchemaBlueprintAssembler {
    private static final int EXPRESSION_ATTNUM = 0;

    private SchemaBlueprintAssembler() {
    }

    static SchemaBlueprint assemble(CatalogSnapshot snapshot, Pseudonymizer pseudonyms, Instant collectedAt,
                                    int requestedRelations) {
        var incompleteness = EnumSet.noneOf(SchemaIncompleteness.class);
        if (snapshot.relations().size() < requestedRelations) {
            incompleteness.add(SchemaIncompleteness.RELATION_NOT_VISIBLE);
        }
        var relations = new ArrayList<RelationBlueprint>();
        for (RelationRow row : snapshot.relations()) {
            relations.add(relation(row, snapshot, pseudonyms));
        }
        return new SchemaBlueprint(collectedAt, EvidenceSource.POSTGRESQL_CATALOG, relations, incompleteness);
    }

    /** Nothing could be read: explicit incompleteness, never an empty "complete" blueprint. */
    static SchemaBlueprint privilegeDenied(Instant collectedAt) {
        return new SchemaBlueprint(collectedAt, EvidenceSource.POSTGRESQL_CATALOG, List.of(),
                EnumSet.of(SchemaIncompleteness.CATALOG_PRIVILEGE_DENIED));
    }

    static RelationType relationType(String relkind) {
        return switch (relkind) {
            case "r" -> RelationType.TABLE;
            case "m" -> RelationType.MATERIALIZED_VIEW;
            case "p" -> RelationType.PARTITIONED_TABLE;
            case "f" -> RelationType.FOREIGN_TABLE;
            default -> throw new IllegalStateException("Unsupported relation kind.");
        };
    }

    static ConstraintKind constraintKind(String contype) {
        return switch (contype) {
            case "p" -> ConstraintKind.PRIMARY_KEY;
            case "u" -> ConstraintKind.UNIQUE;
            case "f" -> ConstraintKind.FOREIGN_KEY;
            case "c" -> ConstraintKind.CHECK;
            case "x" -> ConstraintKind.EXCLUSION;
            default -> throw new IllegalStateException("Unsupported constraint kind.");
        };
    }

    /** {@code reltuples} is -1 (or never positive) until the relation is analyzed: unknown, not zero. */
    static OptionalLong estimatedRows(double reltuples) {
        return Double.isNaN(reltuples) || reltuples < 0 ? OptionalLong.empty() : OptionalLong.of(Math.round(reltuples));
    }

    private static RelationBlueprint relation(RelationRow row, CatalogSnapshot snapshot, Pseudonymizer pseudonyms) {
        var references = new HashMap<Integer, SafeCatalogReference>();
        var columns = new ArrayList<ColumnBlueprint>();
        for (ColumnRow column : snapshot.columns()) {
            if (column.relationOid() == row.oid()) {
                var reference = pseudonyms.column(row.schema(), row.name(), column.name());
                references.put(column.attnum(), reference);
                columns.add(new ColumnBlueprint(reference, column.sqlType(), column.nullable()));
            }
        }
        var constraints = new ArrayList<ConstraintBlueprint>();
        for (ConstraintRow constraint : snapshot.constraints()) {
            if (constraint.relationOid() == row.oid()) {
                constraints.add(new ConstraintBlueprint(constraintKind(constraint.contype()),
                        constraint.attnums().stream().map(attnum -> reference(references, attnum)).toList()));
            }
        }
        var indexes = new LinkedHashMap<Long, IndexBlueprint>();
        for (IndexRow index : snapshot.indexes()) {
            if (index.relationOid() == row.oid()) {
                indexes.putIfAbsent(index.indexOid(), index(row, index, references, pseudonyms));
            }
        }
        return new RelationBlueprint(pseudonyms.relation(row.schema(), row.name()), pseudonyms.schema(row.schema()),
                relationType(row.relkind()), estimatedRows(row.reltuples()), row.relpages(), columns, constraints,
                List.copyOf(indexes.values()));
    }

    private static IndexBlueprint index(RelationRow relation, IndexRow index,
                                        Map<Integer, SafeCatalogReference> references, Pseudonymizer pseudonyms) {
        var keys = new ArrayList<IndexKey>();
        for (int position = 0; position < index.keyCount() && position < index.attnums().size(); position++) {
            int attnum = index.attnums().get(position);
            keys.add(attnum == EXPRESSION_ATTNUM
                    ? IndexKey.ofExpression() : IndexKey.ofColumn(reference(references, attnum)));
        }
        return new IndexBlueprint(pseudonyms.index(relation.schema(), index.name()), index.accessMethod(), keys,
                index.unique(), index.primary(), index.partial(), index.valid());
    }

    private static SafeCatalogReference reference(Map<Integer, SafeCatalogReference> references, int attnum) {
        var reference = references.get(attnum);
        if (reference == null) {
            throw new IllegalStateException("Catalog snapshot references an unknown column.");
        }
        return reference;
    }
}
