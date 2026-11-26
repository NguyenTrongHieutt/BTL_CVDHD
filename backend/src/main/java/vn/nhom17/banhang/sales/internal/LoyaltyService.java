package vn.nhom17.banhang.sales.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.StoreSettings;
import vn.nhom17.banhang.sales.LoyaltyQuery;

/** AD-17. Các cập nhật điểm nằm trong transaction chứng từ, cùng với tiền và tồn kho. */
@Service
class LoyaltyService implements LoyaltyQuery {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;

    LoyaltyService(JdbcTemplate jdbc, CurrentContext context, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public int balanceOf(UUID customerId) {
        var balances = jdbc.query("""
                SELECT balance FROM app.loyalty_account WHERE store_id = ? AND customer_id = ?
                """, (rs, row) -> rs.getInt("balance"), context.storeId(), customerId);
        return balances.isEmpty() ? 0 : balances.getFirst();
    }

    /** Khóa tài khoản trước khi báo giá để chốt đơn; caller đã kiểm tra khách hàng trong Cửa hàng. */
    int lockBalance(UUID customerId) {
        UUID storeId = context.storeId();
        // Khóa transaction tồn tại cả khi tài khoản chưa được tạo; không ghi số dư trước StockService.apply.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (rs, row) -> 0,
                "loyalty:" + storeId + ":" + customerId);
        var balances = jdbc.query("""
                SELECT balance FROM app.loyalty_account WHERE store_id = ? AND customer_id = ? FOR UPDATE
                """, (rs, row) -> rs.getInt("balance"), storeId, customerId);
        return balances.isEmpty() ? 0 : balances.getFirst();
    }

    QuoteLoyalty quote(int balance, long payableBeforePoints, int redeemed, StoreSettings.View settings) {
        if (redeemed > balance) {
            throw insufficient("Khách hàng không đủ điểm để sử dụng. Tải lại số dư và chọn lại số điểm.");
        }
        // Cài đặt cho phép giá trị điểm bằng 0; khi đó chưa có giá trị quy đổi để sử dụng điểm.
        int maxRedeemable = settings.loyaltyPointValue() == 0 ? 0
                : (int) Math.min(balance, payableBeforePoints / settings.loyaltyPointValue());
        if (redeemed > maxRedeemable) {
            throw invalid(settings.loyaltyPointValue() == 0
                    ? "Cửa hàng chưa cài giá trị quy đổi điểm tích lũy."
                    : "Số điểm sử dụng không được vượt số tiền còn phải trả sau giảm giá.");
        }
        // Giới hạn theo tiền phải trả trước khi nhân nên tích luôn nằm trong phạm vi tiền hợp lệ.
        long paid = payableBeforePoints - (long) redeemed * settings.loyaltyPointValue();
        long earned = paid / settings.loyaltyEarnAmount();
        if (earned > Integer.MAX_VALUE || (long) balance - redeemed + earned > Integer.MAX_VALUE) {
            throw limit();
        }
        return new QuoteLoyalty(balance, settings.loyaltyEarnAmount(), settings.loyaltyPointValue(),
                (int) earned, redeemed, maxRedeemable);
    }

    void recordSale(UUID customerId, UUID orderId, QuoteLoyalty quote) {
        if (customerId == null) return;
        // Quote chốt đơn đã giữ khóa tài khoản tới hết transaction.
        updateBalance(customerId, checkedBalance((long) quote.balance() - quote.pointsRedeemed() + quote.pointsEarned()));
        ledger(customerId, -quote.pointsRedeemed(), "REDEEM", "SALE", orderId);
        ledger(customerId, quote.pointsEarned(), "EARN", "SALE", orderId);
    }

    Adjustment prepareCancellation(UUID customerId, UUID orderId, int earned, int redeemed) {
        if (customerId == null || (earned == 0 && redeemed == 0)) return null;
        int balance = lockBalance(customerId);
        // Hoàn điểm đã dùng và thu hồi điểm đã cộng theo số dư ròng, tránh từ chối oan khi có thể bù nhau.
        return new Adjustment(customerId, checkedBalance((long) balance + redeemed - earned),
                redeemed, earned, "SALE_CANCEL", orderId);
    }

    Adjustment prepareReturn(UUID customerId, UUID returnId, long originalPaid, long refundedBefore,
                             long refund, long originalEarnAmount, int originalEarned) {
        if (customerId == null || originalEarned == 0 || originalEarnAmount == 0) return null;
        // AD-17 / bộ tính đáp án: chỉ thu hồi chênh lệch điểm được giữ; trả hàng không hoàn điểm đã dùng.
        long before = (originalPaid - refundedBefore) / originalEarnAmount;
        long after = (originalPaid - refundedBefore - refund) / originalEarnAmount;
        int reverse = Math.toIntExact(before - after);
        if (reverse == 0) return null;
        int balance = lockBalance(customerId);
        return new Adjustment(customerId, checkedBalance((long) balance - reverse),
                0, reverse, "SALE_RETURN", returnId);
    }

    /** AD-17: chỉ ghi số dư và sổ điểm sau khi StockService.apply đã thành công. */
    void applyAdjustment(Adjustment adjustment) {
        if (adjustment == null) return;
        updateBalance(adjustment.customerId(), adjustment.balance());
        ledger(adjustment.customerId(), adjustment.restored(), "REVERSE_REDEEM",
                adjustment.documentType(), adjustment.documentId());
        ledger(adjustment.customerId(), -adjustment.reversed(), "REVERSE_EARN",
                adjustment.documentType(), adjustment.documentId());
    }

    static int requestedPoints(BigDecimal value) {
        if (value == null) return 0;
        try {
            int points = value.intValueExact();
            if (points >= 0) return points;
        } catch (ArithmeticException ignored) {
            // Không nhận phần lẻ hoặc giá trị vượt giới hạn integer của tài khoản và sổ điểm.
        }
        throw invalid("Số điểm sử dụng phải là số nguyên không âm trong phạm vi cho phép.");
    }

    private static int checkedBalance(long balance) {
        if (balance < 0) {
            throw insufficient("Không đủ điểm để thu hồi điểm đã tích từ đơn hàng. Giao dịch chưa được thực hiện.");
        }
        if (balance > Integer.MAX_VALUE) throw limit();
        return (int) balance;
    }

    private void updateBalance(UUID customerId, int balance) {
        jdbc.update("""
                INSERT INTO app.loyalty_account (store_id, customer_id, balance, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (store_id, customer_id) DO UPDATE
                    SET balance = EXCLUDED.balance, updated_at = EXCLUDED.updated_at
                """, context.storeId(), customerId, balance, clock.instant().atOffset(ZoneOffset.UTC));
    }

    private void ledger(UUID customerId, int delta, String reason, String documentType, UUID documentId) {
        if (delta == 0) return;
        jdbc.update("""
                INSERT INTO app.loyalty_ledger
                    (store_id, customer_id, delta, reason, document_type, document_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, context.storeId(), customerId, delta, reason, documentType, documentId,
                clock.instant().atOffset(ZoneOffset.UTC));
    }

    static ProblemException invalid(String message) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ", message,
                Map.of("errors", List.of(Map.of("field", "pointsRedeemed", "message", message))));
    }

    private static ProblemException insufficient(String message) {
        return ProblemException.conflict("/errors/sales/loyalty-insufficient", "Không đủ điểm tích lũy", message);
    }

    private static ProblemException limit() {
        return ProblemException.conflict("/errors/sales/loyalty-limit", "Vượt giới hạn điểm tích lũy",
                "Số điểm tích lũy vượt giới hạn cho phép. Giao dịch chưa được thực hiện.");
    }

    record QuoteLoyalty(int balance, long earnAmountPerPoint, long pointValue, int pointsEarned,
                        int pointsRedeemed, int maxRedeemablePoints) {}

    record Adjustment(UUID customerId, int balance, int restored, int reversed, String documentType, UUID documentId) {}
}
