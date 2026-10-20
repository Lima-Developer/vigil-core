package com.vigil.adapters.postgres.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the DDL contract of the laboratory schema without Docker: the foreign key, the single secondary index and the
 * absence of indexes on {@code status} and {@code placed_at}. {@link PostgresLaboratoryIT} checks the same facts in the
 * database catalog; this test fails fast when {@code schema.sql} is edited.
 */
class PostgresLaboratorySchemaMutationTest {

    private static final String SCHEMA_RESOURCE = "/lab/postgres/schema.sql";

    private static final Pattern FOREIGN_KEY = Pattern.compile(
            "customer_id\\s+integer\\s+NOT\\s+NULL\\s+REFERENCES\\s+customers\\s*\\(\\s*id\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUSTOMER_INDEX = Pattern.compile(
            "CREATE\\s+INDEX\\s+orders_customer_id_idx\\s+ON\\s+orders\\s*\\(\\s*customer_id\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_INDEX = Pattern.compile("CREATE\\s+(UNIQUE\\s+)?INDEX", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIMARY_KEY = Pattern.compile("PRIMARY\\s+KEY", Pattern.CASE_INSENSITIVE);

    @Test
    void ordersReferenceCustomersThroughTheForeignKey() {
        assertTrue(matches(FOREIGN_KEY), "orders.customer_id must reference customers(id)");
    }

    @Test
    void theOnlySecondaryIndexIsOnOrdersCustomerId() {
        assertEquals(1, count(ANY_INDEX), "exactly one CREATE INDEX is allowed");
        assertTrue(matches(CUSTOMER_INDEX), "the single index must be orders_customer_id_idx on orders(customer_id)");
    }

    @Test
    void thereIsNoIndexOnStatusOrPlacedAt() {
        String ddl = schema();

        assertEquals(0, Pattern.compile("INDEX[^;]*\\b(status|placed_at)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(ddl).results().count());
    }

    @Test
    void bothTablesDeclareAnIdentityPrimaryKey() {
        assertEquals(2, count(PRIMARY_KEY));
        assertTrue(schema().contains("CREATE TABLE customers"));
        assertTrue(schema().contains("CREATE TABLE orders"));
    }

    private static boolean matches(Pattern pattern) {
        return pattern.matcher(schema()).find();
    }

    private static long count(Pattern pattern) {
        Matcher matcher = pattern.matcher(schema());
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String schema() {
        try (InputStream stream = PostgresLaboratorySchemaMutationTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing classpath resource " + SCHEMA_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SCHEMA_RESOURCE, e);
        }
    }
}
