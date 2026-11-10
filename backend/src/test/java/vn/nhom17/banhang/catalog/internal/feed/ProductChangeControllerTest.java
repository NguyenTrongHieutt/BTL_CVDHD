package vn.nhom17.banhang.catalog.internal.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.nhom17.banhang.platform.ProblemException;

/** Tham số và {@code nextCursor} của change-feed, với repository giả ghi lại lời gọi (không có database). */
class ProductChangeControllerTest {

    /** Ghi lại {@code (cursor, limit)} của mỗi lần đọc, trả trang dựng sẵn. */
    static final class RecordingRepository extends ProductChangeRepository {

        record Call(FeedCursor cursor, int limit) {
        }

        final List<Call> calls = new ArrayList<>();
        List<ProductChange> page = List.of();

        RecordingRepository() {
            super(null, null);
        }

        @Override
        List<ProductChange> after(FeedCursor cursor, int limit) {
            calls.add(new Call(cursor, limit));
            return page;
        }
    }

    final RecordingRepository repository = new RecordingRepository();
    final ProductChangeController controller = new ProductChangeController(repository);

    @Test
    void aMissingOrEmptyLimitReadsFiveHundred() {
        controller.changes(null, null);
        controller.changes(null, "");
        controller.changes("", "7");
        assertThat(repository.calls).containsExactly(
                new RecordingRepository.Call(null, 500),
                new RecordingRepository.Call(null, 500),
                new RecordingRepository.Call(null, 7));
    }

    @Test
    void anEmptyPageWithoutAfterHasANullNextCursor() {
        assertThat(controller.changes(null, null).nextCursor()).isNull();
        assertThat(controller.changes("", null).nextCursor()).isNull();
    }

    @Test
    void anEmptyPageHandsBackAfterAndAFullPagePointsAtItsLastItem() {
        FeedCursor at = new FeedCursor(Instant.parse("2026-11-10T03:04:05.000120Z"), new UUID(0L, 1L));
        assertThat(controller.changes(at.encode(), null).nextCursor()).isEqualTo(at.encode());
        assertThat(repository.calls.getLast()).isEqualTo(new RecordingRepository.Call(at, 500));

        ProductChange last = new ProductChange(new UUID(0L, 9L), new UUID(0L, 2L), "Bia Tiger", "ACTIVE",
                Instant.parse("2026-11-10T03:04:06Z"));
        repository.page = List.of(last);
        assertThat(controller.changes(at.encode(), "1").nextCursor()).isEqualTo(FeedCursor.of(last).encode());
    }

    @Test
    void brokenParametersNeverReachTheRepository() {
        assertThatThrownBy(() -> controller.changes("abc", "0"))
                .isInstanceOfSatisfying(ProblemException.class, e -> {
                    assertThat(e.type()).isEqualTo("/errors/common/validation");
                    assertThat(e.properties().get("errors")).asString().contains("after").contains("limit");
                });
        assertThat(repository.calls).isEmpty();
    }
}
