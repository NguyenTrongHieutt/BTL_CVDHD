package vn.nhom17.banhang.platform.internal.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.ProblemException;

/** AD-7. Giữ khóa và tạo chứng từ trong CÙNG một transaction; lỗi unique do gửi trùng đồng thời thì đọc lại. */
@Component
class JdbcIdempotencyService implements IdempotencyService {

    private record Existing(String requestHash, UUID documentId) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CurrentContext context;
    private final JsonMapper canonicalJson;

    JdbcIdempotencyService(JdbcTemplate jdbc, PlatformTransactionManager txManager, CurrentContext context,
                           JsonMapper json) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.context = context;
        this.canonicalJson = json.rebuild()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    @Override
    public <T> IdempotentResult<T> execute(String documentType, UUID key, String requestHash, Supplier<UUID> create,
                                           Function<UUID, T> load) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("IdempotencyService.execute phải được gọi ngoài transaction");
        }
        if (key == null) {
            throw ProblemException.unprocessable("/errors/common/idempotency-key-missing", "Thiếu Idempotency-Key",
                    "Request tạo chứng từ phải có header Idempotency-Key (UUID).");
        }
        try {
            return tx.execute(s -> {
                Existing existing = find(documentType, key);
                if (existing != null) {
                    return replay(existing, requestHash, load);
                }
                jdbc.update("""
                        insert into app.idempotency_record (store_id, document_type, idempotency_key, request_hash)
                        values (?, ?, ?, ?)
                        """, context.storeId(), documentType, key, requestHash);
                UUID documentId = create.get();
                jdbc.update("""
                        update app.idempotency_record set document_id = ?
                        where store_id = ? and document_type = ? and idempotency_key = ?
                        """, documentId, context.storeId(), documentType, key);
                return new IdempotentResult<>(load.apply(documentId), false);
            });
        } catch (DuplicateKeyException concurrent) {
            // Một yêu cầu trùng khác vừa thắng: đọc lại bản ghi của nó trong transaction mới
            return tx.execute(s -> {
                Existing winner = find(documentType, key);
                if (winner == null) {
                    throw concurrent;
                }
                return replay(winner, requestHash, load);
            });
        }
    }

    private <T> IdempotentResult<T> replay(Existing existing, String requestHash, Function<UUID, T> load) {
        if (!existing.requestHash().equals(requestHash) || existing.documentId() == null) {
            throw ProblemException.conflict("/errors/common/idempotency-key-reuse", "Idempotency-Key đã dùng",
                    "Khóa này đã dùng cho một yêu cầu có nội dung khác. Hãy tạo khóa mới.");
        }
        return new IdempotentResult<>(load.apply(existing.documentId()), true);
    }

    private Existing find(String documentType, UUID key) {
        List<Existing> rows = jdbc.query("""
                select request_hash, document_id from app.idempotency_record
                where store_id = ? and document_type = ? and idempotency_key = ?
                """, (rs, i) -> new Existing(rs.getString(1), rs.getObject(2, UUID.class)),
                context.storeId(), documentType, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public String hash(Object request) {
        try {
            byte[] bytes = canonicalJson.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
