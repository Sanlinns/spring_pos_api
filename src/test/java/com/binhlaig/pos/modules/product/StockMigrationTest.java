package com.binhlaig.pos.modules.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "STOCK_TEST_URL", matches = "jdbc:postgresql://.*")
class StockMigrationTest {
    private Connection database() throws SQLException {
        String url = System.getenv("STOCK_TEST_URL");
        if (!url.endsWith("/stock_test")) throw new IllegalArgumentException("Disposable stock_test DB required");
        Connection c = DriverManager.getConnection(url, "postgres", "");
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
            s.execute("CREATE SCHEMA " + schema);
            s.execute("SET LOCAL search_path TO " + schema);
            s.execute("""
                CREATE TABLE products(id bigint PRIMARY KEY, shop_id bigint, stock integer,
                    product_quantity_amount numeric(12,2));
                CREATE TABLE pos_receipts(id bigint PRIMARY KEY, shop_id bigint, status varchar(30));
                CREATE TABLE pos_receipt_items(receipt_id bigint, product_id varchar(255), qty integer);
                CREATE TABLE restaurant_orders(id bigint PRIMARY KEY, shop_id bigint, status varchar(30));
                CREATE TABLE restaurant_order_items(order_id bigint, product_id bigint, quantity integer);
                CREATE TABLE restaurant_payments(order_id bigint, shop_id bigint, status varchar(30));
                INSERT INTO products VALUES (1,10,999,28), (2,10,777,0);
                INSERT INTO pos_receipts VALUES (1,10,'COMPLETED'),(2,10,'CANCELLED'),(3,10,'REFUNDED'),(4,11,'COMPLETED');
                INSERT INTO pos_receipt_items VALUES (1,'1',52),(2,'1',4),(3,'1',5),(4,'1',7),(1,'999',3);
                INSERT INTO restaurant_orders VALUES (1,10,'PAID'),(2,10,'OPEN'),(3,10,'REFUNDED'),(4,10,'PAID');
                INSERT INTO restaurant_order_items VALUES (1,1,8),(2,1,10),(3,1,2),(4,1,11);
                INSERT INTO restaurant_payments VALUES (1,10,'PAID'),(3,10,'REFUNDED'),(4,10,'CANCELLED');
                """);
        }
        return c;
    }

    private void migrate(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute(Files.readString(Path.of("src/main/resources/db/migration/V26__stock_tracking.sql")));
        }
    }

    @Test void migrationPreservesOpeningAndSeparatesHistoricalSalesWithoutFabricatingTotal() throws Exception {
        try (Connection c = database()) {
            migrate(c);
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM products WHERE id=1")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBigDecimal("product_quantity_amount")).isEqualByComparingTo("28");
                assertThat(r.getInt("stock")).isEqualTo(999);
                assertThat(r.getBigDecimal("opening_balance")).isEqualByComparingTo("28");
                assertThat(r.getBigDecimal("total_stock")).isEqualByComparingTo("28");
                assertThat(r.getBigDecimal("sold_quantity")).isZero();
                assertThat(r.getBigDecimal("historical_sold_quantity")).isEqualByComparingTo("60");
                assertThat(r.getString("stock_tracking_basis")).isEqualTo("OPENING_BALANCE");
                assertThat(r.getTimestamp("stock_tracking_started_at")).isNotNull();
            }
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM stock_movements WHERE operation='OPENING_BALANCE'")) {
                r.next(); assertThat(r.getInt(1)).isEqualTo(2);
            }
            c.rollback(); // Includes the disposable schema and all fixture DDL.
        }
    }

    @Test void migrationRejectsUnknownOpeningInsteadOfInventingZero() throws Exception {
        try (Connection c = database(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE products SET product_quantity_amount=NULL WHERE id=1");
            assertThatThrownBy(() -> migrate(c)).isInstanceOf(SQLException.class).hasMessageContaining("reviewed nonnegative");
            c.rollback();
        }
    }

    @Test void databaseRejectsBalanceDriftAndDuplicateMovements() throws Exception {
        try (Connection c = database(); Statement s = c.createStatement()) {
            migrate(c);
            Savepoint point = c.setSavepoint();
            assertThatThrownBy(() -> s.executeUpdate("UPDATE products SET product_quantity_amount=99 WHERE id=1"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_product_stock_balance");
            c.rollback(point);
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO stock_movements(product_id,operation,quantity_delta,balance_before,balance_after,reference) VALUES (1,'OPENING_BALANCE',28,0,28,'OPENING')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("unique");
            c.rollback();
        }
    }
}
