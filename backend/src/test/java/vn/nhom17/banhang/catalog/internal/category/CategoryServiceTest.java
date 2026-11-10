package vn.nhom17.banhang.catalog.internal.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Danh mục bị xóa ở yêu cầu khác giữa lúc đọc và lúc ghi: Hibernate báo cập nhật/xóa 0 dòng (optimistic locking).
 * Khó dựng xen kẽ hai transaction trong test tích hợp, nên giả lập lỗi đó ở repository.
 */
class CategoryServiceTest {

    static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

    final CategoryRepository categories = mock(CategoryRepository.class);
    final CategoryService service = new CategoryService(categories, mock(JdbcTemplate.class),
            mock(CurrentContext.class), Clock.fixed(NOW, ZoneOffset.UTC));
    final UUID id = UUID.randomUUID();

    @Test
    void renamingACategoryRemovedMeanwhileIsNotFound() {
        when(categories.findById(id)).thenReturn(Optional.of(new CategoryEntity("Bia", NOW)));
        doThrow(new ObjectOptimisticLockingFailureException(CategoryEntity.class, id)).when(categories).flush();

        assertThatThrownBy(() -> service.rename(id, "Rượu")).isInstanceOfSatisfying(ProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.type()).isEqualTo("/errors/common/not-found");
        });
    }

    @Test
    void deletingACategoryRemovedMeanwhileIsNotFound() {
        when(categories.findById(id)).thenReturn(Optional.of(new CategoryEntity("Bia", NOW)));
        doThrow(new ObjectOptimisticLockingFailureException(CategoryEntity.class, id)).when(categories).flush();

        assertThatThrownBy(() -> service.delete(id)).isInstanceOfSatisfying(ProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.type()).isEqualTo("/errors/common/not-found");
        });
    }
}
