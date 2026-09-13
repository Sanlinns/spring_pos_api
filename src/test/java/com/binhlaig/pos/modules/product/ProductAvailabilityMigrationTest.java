package com.binhlaig.pos.modules.product;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ProductAvailabilityMigrationTest {
    @Test
    void migrationDefaultsExistingProductsToAvailable() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V22__add_product_availability.sql"));
        assertThat(sql.toUpperCase()).contains(
                "AVAILABLE_FOR_SALE BOOLEAN NOT NULL DEFAULT TRUE");
    }
}
