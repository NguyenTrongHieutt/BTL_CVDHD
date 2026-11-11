package vn.nhom17.banhang.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Dòng {@code Detail} của PostgreSQL chứa dữ liệu người dùng nhập, nên không được quyết định constraint nào bị vi phạm. */
class ConstraintsTest {

    /** Câu lỗi PostgreSQL thật: dòng đầu có tên constraint, dòng Detail có giá trị SKU người dùng gõ. */
    static final String MESSAGE = """
            ERROR: duplicate key value violates unique constraint "product_variant_sku_uq"
              Detail: Key (store_id, sku)=(d0000000-0000-4000-8000-00000000000a, "product_variant_barcode_uq") \
            already exists.""";

    @Test
    void theConstraintNameReportedByHibernateWins() {
        var error = new DataIntegrityViolationException("x",
                new ConstraintViolationException(MESSAGE, new SQLException(MESSAGE), "product_variant_sku_uq"));
        assertThat(Constraints.violated(error, "product_variant_sku_uq")).isTrue();
        assertThat(Constraints.violated(error, "product_variant_barcode_uq")).isFalse();
    }

    @Test
    void withoutAConstraintNameOnlyTheFirstLineOfTheMessageCounts() {
        var error = new DataIntegrityViolationException("x",
                new ConstraintViolationException(MESSAGE, new SQLException(MESSAGE), null));
        assertThat(Constraints.violated(error, "product_variant_sku_uq")).isTrue();
        assertThat(Constraints.violated(error, "product_variant_barcode_uq")).isFalse();
        assertThat(Constraints.violated(new RuntimeException("khác"), "product_variant_sku_uq")).isFalse();
    }
}
