package vn.nhom17.banhang.catalog.internal.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.nhom17.banhang.catalog.CustomerLookup;
import vn.nhom17.banhang.catalog.CustomerLookup.CustomerSummary;
import vn.nhom17.banhang.catalog.internal.customer.CustomerController.CustomerRequest;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Trùng số điện thoại khi đọc lại {@code existingCustomerId} lỗi: khó dựng trên database thật, nên giả lập ở đây. Các
 * trường hợp còn lại nằm ở {@code CustomerIntegrationTest}.
 */
class CustomerServiceTest {

    static final UUID EXISTING = UUID.fromString("00000000-0000-4000-8000-000000000001");

    final CustomerRepository customers = mock(CustomerRepository.class);
    final CustomerLookup lookup = mock(CustomerLookup.class);
    final CustomerService service = new CustomerService(customers, lookup, mock(JdbcTemplate.class),
            mock(CurrentContext.class), Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC),
            mock(PlatformTransactionManager.class));

    CustomerServiceTest() {
        String message = "ERROR: duplicate key value violates unique constraint \"customer_phone_uq\"";
        when(customers.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("x",
                new ConstraintViolationException(message, new SQLException(message), "customer_phone_uq")));
    }

    @Test
    void aFailingLookupStillAnswersTheConflictWithoutTheExistingId() {
        when(lookup.findByPhone("0912345678")).thenThrow(new QueryTimeoutException("hết giờ"));

        ProblemException problem = create("0912 345 678");
        assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problem.type()).isEqualTo(CustomerService.PHONE_TAKEN);
        assertThat(problem.properties()).doesNotContainKey(CustomerService.EXISTING_CUSTOMER_ID);
    }

    @Test
    void theConflictCarriesTheExistingIdWhenTheLookupFindsIt() {
        when(lookup.findByPhone("0912345678"))
                .thenReturn(Optional.of(new CustomerSummary(EXISTING, "Chị Hoa", "0912345678")));

        assertThat(create("0912345678").properties()).containsEntry(CustomerService.EXISTING_CUSTOMER_ID, EXISTING);
    }

    private ProblemException create(String phone) {
        Throwable thrown = catchThrowable(() -> service.create(new CustomerRequest(phone, null, null)));
        assertThat(thrown).isInstanceOf(ProblemException.class);
        return (ProblemException) thrown;
    }
}
