package com.vigil.core;

import com.vigil.core.domain.ColumnBlueprint;
import com.vigil.core.domain.ConstraintBlueprint;
import com.vigil.core.domain.IndexBlueprint;
import com.vigil.core.domain.IndexKey;
import com.vigil.core.domain.RelationBlueprint;
import com.vigil.core.domain.SafeCatalogReference;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.SchemaBlueprint;
import com.vigil.core.domain.enums.ConstraintKind;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.RelationType;
import com.vigil.core.domain.enums.SchemaIncompleteness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaBlueprintTest {
    private static final String HEX = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private static final SafeCatalogReference SCHEMA = new SafeCatalogReference("schema_" + HEX);
    private static final SafeCatalogReference COLUMN = new SafeCatalogReference("column_" + HEX);
    private static final SafeCatalogReference INDEX = new SafeCatalogReference("index_" + HEX);

    @Test
    void relationTypesCoverTheSupportedRelkinds() {
        assertEquals(List.of("TABLE", "MATERIALIZED_VIEW", "PARTITIONED_TABLE", "FOREIGN_TABLE"),
                java.util.Arrays.stream(RelationType.values()).map(Enum::name).toList());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "column_ABC", "relation_" + HEX, "column_0123", "column_" + HEX + "0", "users"})
    void catalogReferenceRejectsAnythingButAPseudonym(String value) {
        assertThrows(IllegalArgumentException.class, () -> new SafeCatalogReference(value));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "Customer Name", "text; DROP TABLE x", "user.defined"})
    void columnRejectsTypesThatAreNotCatalogTypeNames(String sqlType) {
        assertThrows(IllegalArgumentException.class, () -> new ColumnBlueprint(COLUMN, sqlType, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"integer", "character varying(120)", "numeric(12,2)", "timestamp with time zone",
            "integer[]", "\"char\"", "user_defined"})
    void columnAcceptsCatalogTypeNames(String sqlType) {
        assertEquals(sqlType, new ColumnBlueprint(COLUMN, sqlType, false).sqlType());
    }

    @Test
    void columnRejectsOverlongTypeAndMissingReference() {
        assertThrows(IllegalArgumentException.class, () -> new ColumnBlueprint(COLUMN, "a".repeat(129), true));
        assertEquals(128, new ColumnBlueprint(COLUMN, "a".repeat(128), true).sqlType().length());
        assertThrows(NullPointerException.class, () -> new ColumnBlueprint(null, "integer", true));
    }

    @Test
    void indexKeyDistinguishesColumnsFromExpressions() {
        assertFalse(IndexKey.ofColumn(COLUMN).isExpression());
        assertTrue(IndexKey.ofExpression().isExpression());
        assertThrows(NullPointerException.class, () -> IndexKey.ofColumn(null));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "BTREE", "bt ree", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void indexRejectsInvalidAccessMethod(String method) {
        assertThrows(IllegalArgumentException.class, () -> index(INDEX, method, List.of(IndexKey.ofExpression())));
    }

    @Test
    void indexNeedsAKeyAndCopiesItsKeys() {
        assertThrows(IllegalArgumentException.class, () -> index(INDEX, "btree", List.of()));
        var keys = new ArrayList<>(List.of(IndexKey.ofColumn(COLUMN)));
        var index = index(INDEX, "btree", keys);
        keys.add(IndexKey.ofExpression());
        assertEquals(1, index.keys().size());
        assertThrows(UnsupportedOperationException.class, () -> index.keys().add(IndexKey.ofExpression()));
    }

    @Test
    void constraintCopiesItsColumns() {
        var columns = new ArrayList<>(List.of(COLUMN));
        var constraint = new ConstraintBlueprint(ConstraintKind.PRIMARY_KEY, columns);
        columns.clear();
        assertEquals(List.of(COLUMN), constraint.columns());
        assertThrows(NullPointerException.class, () -> new ConstraintBlueprint(null, List.of()));
    }

    @Test
    void relationKeepsUnknownEstimateDistinctFromZero() {
        assertTrue(relation(OptionalLong.empty(), 0, List.of()).estimatedRows().isEmpty());
        assertEquals(0, relation(OptionalLong.of(0), 0, List.of()).estimatedRows().getAsLong());
    }

    @Test
    void relationRejectsNegativeEstimatesAndPages() {
        assertThrows(IllegalArgumentException.class, () -> relation(OptionalLong.of(-1), 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> relation(OptionalLong.empty(), -1, List.of()));
    }

    @Test
    void relationRejectsDuplicatedIndexes() {
        var index = index(INDEX, "btree", List.of(IndexKey.ofColumn(COLUMN)));
        assertThrows(IllegalArgumentException.class,
                () -> relation(OptionalLong.empty(), 0, List.of(index, index)));
        var other = index(new SafeCatalogReference("index_" + "f".repeat(32)), "btree", index.keys());
        assertEquals(2, relation(OptionalLong.empty(), 0, List.of(index, other)).indexes().size());
    }

    @Test
    void blueprintIsCompleteOnlyWithoutIncompleteness() {
        var complete = new SchemaBlueprint(NOW, EvidenceSource.POSTGRESQL_CATALOG, List.of(), Set.of());
        var incomplete = new SchemaBlueprint(NOW, EvidenceSource.POSTGRESQL_CATALOG, List.of(),
                Set.of(SchemaIncompleteness.CATALOG_PRIVILEGE_DENIED));
        assertTrue(complete.complete());
        assertFalse(incomplete.complete());
    }

    @Test
    void blueprintRejectsMissingInstantSourceAndDuplicatedRelations() {
        assertThrows(NullPointerException.class,
                () -> new SchemaBlueprint(null, EvidenceSource.POSTGRESQL_CATALOG, List.of(), Set.of()));
        assertThrows(NullPointerException.class, () -> new SchemaBlueprint(NOW, null, List.of(), Set.of()));
        var relation = relation(OptionalLong.empty(), 0, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaBlueprint(NOW, EvidenceSource.POSTGRESQL_CATALOG, List.of(relation, relation), Set.of()));
    }

    private static IndexBlueprint index(SafeCatalogReference reference, String method, List<IndexKey> keys) {
        return new IndexBlueprint(reference, method, keys, false, false, false, true);
    }

    private static RelationBlueprint relation(OptionalLong rows, long pages, List<IndexBlueprint> indexes) {
        return new RelationBlueprint(new SafeRelationReference("relation_" + HEX), SCHEMA, RelationType.TABLE, rows,
                pages, List.of(new ColumnBlueprint(COLUMN, "integer", false)), List.of(), indexes);
    }
}
