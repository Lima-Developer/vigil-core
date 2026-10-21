package com.vigil.adapters.postgres.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.adapters.postgres.schema.CatalogSnapshot.ColumnRow;
import com.vigil.adapters.postgres.schema.CatalogSnapshot.ConstraintRow;
import com.vigil.adapters.postgres.schema.CatalogSnapshot.IndexRow;
import com.vigil.adapters.postgres.schema.CatalogSnapshot.RelationRow;
import com.vigil.core.domain.IndexKey;
import com.vigil.core.domain.enums.ConstraintKind;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.RelationType;
import com.vigil.core.domain.enums.SchemaIncompleteness;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SchemaBlueprintAssemblerTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private static final Pseudonymizer PSEUDONYMS = new Pseudonymizer(new byte[32]);
    private static final String SCHEMA = "payroll_secret";
    private static final String TABLE = "salaries_secret";

    @ParameterizedTest
    @CsvSource({"r,TABLE", "m,MATERIALIZED_VIEW", "p,PARTITIONED_TABLE", "f,FOREIGN_TABLE"})
    void relkindMapsToRelationType(String relkind, RelationType expected) {
        assertEquals(expected, SchemaBlueprintAssembler.relationType(relkind));
    }

    @ParameterizedTest
    @ValueSource(strings = {"v", "i", "S", "c", "t", "", "R"})
    void unsupportedRelkindFailsWithoutEchoingInput(String relkind) {
        var failure = assertThrows(IllegalStateException.class, () -> SchemaBlueprintAssembler.relationType(relkind));
        assertFalse(failure.getMessage().contains("'" + relkind + "'"));
    }

    @ParameterizedTest
    @CsvSource({"p,PRIMARY_KEY", "u,UNIQUE", "f,FOREIGN_KEY", "c,CHECK", "x,EXCLUSION"})
    void contypeMapsToConstraintKind(String contype, ConstraintKind expected) {
        assertEquals(expected, SchemaBlueprintAssembler.constraintKind(contype));
    }

    @Test
    void unsupportedContypeIsRejected() {
        assertThrows(IllegalStateException.class, () -> SchemaBlueprintAssembler.constraintKind("t"));
    }

    @ParameterizedTest
    @CsvSource({"-1.0,", "-0.5,", "0.0,0", "0.4,0", "0.5,1", "1000.0,1000", "123456789012.0,123456789012"})
    void reltuplesBelowZeroIsUnknownNotZero(double reltuples, Long expected) {
        OptionalLong rows = SchemaBlueprintAssembler.estimatedRows(reltuples);
        assertEquals(expected == null ? OptionalLong.empty() : OptionalLong.of(expected), rows);
    }

    @Test
    void nanEstimateIsUnknown() {
        assertTrue(SchemaBlueprintAssembler.estimatedRows(Double.NaN).isEmpty());
    }

    @Test
    void assemblesRelationColumnsConstraintsAndIndexesWithPseudonymsOnly() {
        var snapshot = new CatalogSnapshot(
                List.of(new RelationRow(10, SCHEMA, TABLE, "r", 42.0, 7)),
                List.of(new ColumnRow(10, 1, "cpf_secret", "text", false),
                        new ColumnRow(10, 2, "bonus_secret", "numeric(12,2)", true),
                        new ColumnRow(11, 1, "other", "integer", true)),
                List.of(new ConstraintRow(10, "p", List.of(1)), new ConstraintRow(10, "c", List.of(2)),
                        new ConstraintRow(11, "u", List.of(1))),
                List.of(new IndexRow(10, 100, "salaries_idx_secret", "btree", true, false, true, true, 2,
                        List.of(1, 0)),
                        new IndexRow(11, 101, "other_idx", "hash", false, false, true, false, 1, List.of(1))));

        var blueprint = SchemaBlueprintAssembler.assemble(snapshot, PSEUDONYMS, NOW, 1);

        assertEquals(NOW, blueprint.collectedAt());
        assertEquals(EvidenceSource.POSTGRESQL_CATALOG, blueprint.source());
        assertTrue(blueprint.complete());
        assertEquals(1, blueprint.relations().size());
        var relation = blueprint.relations().getFirst();
        assertEquals(PSEUDONYMS.relation(SCHEMA, TABLE), relation.relation());
        assertEquals(PSEUDONYMS.schema(SCHEMA), relation.schema());
        assertEquals(RelationType.TABLE, relation.type());
        assertEquals(OptionalLong.of(42), relation.estimatedRows());
        assertEquals(7, relation.pages());

        assertEquals(2, relation.columns().size());
        assertEquals(PSEUDONYMS.column(SCHEMA, TABLE, "cpf_secret"), relation.columns().get(0).column());
        assertEquals("text", relation.columns().get(0).sqlType());
        assertFalse(relation.columns().get(0).nullable());
        assertEquals("numeric(12,2)", relation.columns().get(1).sqlType());
        assertTrue(relation.columns().get(1).nullable());

        assertEquals(2, relation.constraints().size());
        assertEquals(ConstraintKind.PRIMARY_KEY, relation.constraints().get(0).kind());
        assertEquals(List.of(PSEUDONYMS.column(SCHEMA, TABLE, "cpf_secret")), relation.constraints().get(0).columns());
        assertEquals(ConstraintKind.CHECK, relation.constraints().get(1).kind());

        assertEquals(1, relation.indexes().size());
        var index = relation.indexes().getFirst();
        assertEquals(PSEUDONYMS.index(SCHEMA, "salaries_idx_secret"), index.index());
        assertEquals("btree", index.accessMethod());
        assertTrue(index.unique());
        assertFalse(index.primary());
        assertTrue(index.partial());
        assertTrue(index.valid());
        assertEquals(List.of(IndexKey.ofColumn(PSEUDONYMS.column(SCHEMA, TABLE, "cpf_secret")),
                IndexKey.ofExpression()), index.keys());
    }

    @Test
    void includedColumnsAreNotKeys() {
        var snapshot = new CatalogSnapshot(
                List.of(new RelationRow(10, "s", "t", "r", 1, 1)),
                List.of(new ColumnRow(10, 1, "a", "integer", true), new ColumnRow(10, 2, "b", "integer", true)),
                List.of(),
                List.of(new IndexRow(10, 100, "i", "btree", false, false, true, false, 1, List.of(1, 2))));

        var keys = SchemaBlueprintAssembler.assemble(snapshot, PSEUDONYMS, NOW, 1)
                .relations().getFirst().indexes().getFirst().keys();

        assertEquals(List.of(IndexKey.ofColumn(PSEUDONYMS.column("s", "t", "a"))), keys);
    }

    @Test
    void sameIndexCatalogIdentityAppearsOnce() {
        var row = new IndexRow(10, 100, "i", "btree", false, false, true, false, 1, List.of(1));
        var snapshot = new CatalogSnapshot(
                List.of(new RelationRow(10, "s", "t", "r", 1, 1)),
                List.of(new ColumnRow(10, 1, "a", "integer", true)), List.of(), List.of(row, row));

        assertEquals(1, SchemaBlueprintAssembler.assemble(snapshot, PSEUDONYMS, NOW, 1)
                .relations().getFirst().indexes().size());
    }

    @Test
    void partitionedParentAndPartitionKeepTheirOwnInheritedIndexOnce() {
        var snapshot = new CatalogSnapshot(
                List.of(new RelationRow(10, "s", "parent", "p", -1, 0), new RelationRow(11, "s", "child", "r", 5, 1)),
                List.of(new ColumnRow(10, 1, "a", "integer", true), new ColumnRow(11, 1, "a", "integer", true)),
                List.of(),
                List.of(new IndexRow(10, 100, "parent_a_idx", "btree", false, false, true, false, 1, List.of(1)),
                        new IndexRow(11, 101, "child_a_idx", "btree", false, false, true, false, 1, List.of(1))));

        var blueprint = SchemaBlueprintAssembler.assemble(snapshot, PSEUDONYMS, NOW, 2);

        assertEquals(1, blueprint.relations().get(0).indexes().size());
        assertEquals(1, blueprint.relations().get(1).indexes().size());
        assertEquals(RelationType.PARTITIONED_TABLE, blueprint.relations().get(0).type());
        assertTrue(blueprint.relations().get(0).estimatedRows().isEmpty());
    }

    @Test
    void fewerRelationsThanRequestedIsExplicitIncompleteness() {
        var snapshot = new CatalogSnapshot(List.of(new RelationRow(10, "s", "t", "r", 1, 1)),
                List.of(), List.of(), List.of());

        var blueprint = SchemaBlueprintAssembler.assemble(snapshot, PSEUDONYMS, NOW, 2);

        assertFalse(blueprint.complete());
        assertEquals(Set.of(SchemaIncompleteness.RELATION_NOT_VISIBLE), blueprint.incompleteness());
        assertEquals(1, blueprint.relations().size());
    }

    @Test
    void privilegeDeniedIsAnIncompleteBlueprintWithSourceAndInstant() {
        var blueprint = SchemaBlueprintAssembler.privilegeDenied(NOW);

        assertEquals(Set.of(SchemaIncompleteness.CATALOG_PRIVILEGE_DENIED), blueprint.incompleteness());
        assertEquals(NOW, blueprint.collectedAt());
        assertEquals(EvidenceSource.POSTGRESQL_CATALOG, blueprint.source());
        assertTrue(blueprint.relations().isEmpty());
    }

    @Test
    void outputNeverContainsOriginalNames() {
        var snapshot = new CatalogSnapshot(
                List.of(new RelationRow(10, SCHEMA, TABLE, "r", 1, 1)),
                List.of(new ColumnRow(10, 1, "cpf_secret", "text", true)),
                List.of(new ConstraintRow(10, "u", List.of(1))),
                List.of(new IndexRow(10, 100, "idx_secret", "btree", false, false, true, true, 1, List.of(1))));

        String rendered = SchemaBlueprintAssembler.assemble(snapshot, PSEUDONYMS, NOW, 1).toString();

        for (String original : List.of("payroll", "salaries", "cpf_secret", "idx_secret", "secret")) {
            assertFalse(rendered.contains(original), original);
        }
        assertEquals("RelationRow[redacted]", snapshot.relations().getFirst().toString());
        assertEquals("ColumnRow[redacted]", snapshot.columns().getFirst().toString());
        assertEquals("IndexRow[redacted]", snapshot.indexes().getFirst().toString());
    }

    @Test
    void inconsistentSnapshotFailsWithoutNames() {
        var snapshot = new CatalogSnapshot(
                List.of(new RelationRow(10, SCHEMA, TABLE, "r", 1, 1)), List.of(),
                List.of(new ConstraintRow(10, "p", List.of(3))), List.of());

        var failure = assertThrows(IllegalStateException.class,
                () -> SchemaBlueprintAssembler.assemble(snapshot, PSEUDONYMS, NOW, 1));

        assertFalse(Arrays.asList(SCHEMA, TABLE).stream().anyMatch(failure.getMessage()::contains));
    }
}
