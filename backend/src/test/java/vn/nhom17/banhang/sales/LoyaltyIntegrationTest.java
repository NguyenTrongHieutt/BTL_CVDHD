package vn.nhom17.banhang.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

/** KH-04 trên HTTP và PostgreSQL thật; mỗi ca dùng Cửa hàng riêng để cô lập cấu hình và số dư. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoyaltyIntegrationTest {
    private static final String ORDERS = "/api/v1/sales/orders";
    private static final String LOYALTY = "/api/v1/sales/customers/{id}/loyalty";
    private static final String INSUFFICIENT = "/errors/sales/loyalty-insufficient";
    private static final AtomicLong PHONES = new AtomicLong(10000000);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper json;
    @Autowired Clock clock;

    @Test
    void ownerAndStaffReadOnlyTheirStoresActiveCustomerBalanceAndCurrentRates() throws Exception {
        Session owner = newStore();
        Session staff = newStaff(owner);
        UUID customer = customer(owner);
        Session other = newStore();
        UUID foreignCustomer = customer(other);
        sell(owner, product(owner, 50000), customer, 1, null, 50000);
        configure(owner, 20000, 750);

        for (Session caller : List.of(owner, staff)) {
            mvc.perform(get(LOYALTY, customer).cookie(caller.cookie())
                            .param("storeId", other.storeId().toString()).header("X-Store-Id", other.storeId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(5))
                    .andExpect(jsonPath("$.earnAmountPerPoint").value(20000))
                    .andExpect(jsonPath("$.pointValue").value(750));
            mvc.perform(get(LOYALTY, foreignCustomer).cookie(caller.cookie()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        }
        balance(other, foreignCustomer, 0);
        UUID inactive = customer(owner);
        asStore(owner, () -> jdbc.update("UPDATE app.customer SET deleted_at = now() WHERE id = ?", inactive));
        mvc.perform(get(LOYALTY, inactive).cookie(staff.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(get(LOYALTY, UUID.randomUUID()).cookie(owner.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(get(LOYALTY, customer)).andExpect(status().isUnauthorized());
    }

    @Test
    void aNewCustomerHasZeroPointsBeforeTheirFirstPurchase() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        mvc.perform(get(LOYALTY, customer).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.earnAmountPerPoint").value(10000))
                .andExpect(jsonPath("$.pointValue").value(1000));
    }

    @ParameterizedTest
    @CsvSource({"9999, 0", "10000, 1", "19999, 1"})
    void earningRoundsDownAtTheConfiguredPaidAmountBoundary(long paid, int earned) throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        UUID variant = product(owner, paid);
        Map<String, Object> cart = cart(variant, customer, 1, null, paid);
        quote(owner, cart).andExpect(status().isOk())
                .andExpect(jsonPath("$.loyalty.pointsEarned").value(earned));
        create(owner, cart, UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.pointsEarned").value(earned));
        balance(owner, customer, earned);
    }

    @Test
    void onlyThePaidAmountAfterLineAndOrderDiscountsEarnsPoints() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        UUID variant = product(owner, 49999);
        Map<String, Object> cart = cart(variant, customer, 2, null, 77655);
        cart.put("lines", List.of(Map.of("variantId", variant, "quantity", 2,
                "discount", Map.of("type", "AMOUNT", "value", 9998))));
        cart.put("orderDiscount", Map.of("type", "AMOUNT", "value", 12345));

        quote(owner, cart).andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(99998))
                .andExpect(jsonPath("$.lineDiscountTotal").value(9998))
                .andExpect(jsonPath("$.orderDiscount").value(12345))
                .andExpect(jsonPath("$.total").value(77655))
                .andExpect(jsonPath("$.pointsDiscount").value(0))
                .andExpect(jsonPath("$.loyalty.balance").value(0))
                .andExpect(jsonPath("$.loyalty.pointsEarned").value(7))
                .andExpect(jsonPath("$.loyalty.pointsRedeemed").value(0))
                .andExpect(jsonPath("$.loyalty.maxRedeemablePoints").value(0));
        UUID order = orderId(create(owner, cart, UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.pointsEarned").value(7))
                .andExpect(jsonPath("$.pointsRedeemed").value(0))
                .andExpect(jsonPath("$.pointsDiscount").value(0)));
        balance(owner, customer, 7);
        assertThat(value(owner, "SELECT amount FROM app.payment WHERE order_id = ?", order)).isEqualTo(77655);
        assertThat(reasonDelta(owner, customer, "EARN")).isEqualTo(7);
    }

    @Test
    void redeemingUsesCurrentRatesEarnsOnRemainingCashAndReplaysWithoutSpendingAgain() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        sell(owner, product(owner, 100000), customer, 1, null, 100000);
        configure(owner, 20000, 750);
        UUID variant = product(owner, 40000);
        Map<String, Object> cart = cart(variant, customer, 1, 6, 35500);

        quote(owner, cart).andExpect(status().isOk())
                .andExpect(jsonPath("$.pointsDiscount").value(4500))
                .andExpect(jsonPath("$.total").value(35500))
                .andExpect(jsonPath("$.lines[0].netLineAmount").value(35500))
                .andExpect(jsonPath("$.loyalty.balance").value(10))
                .andExpect(jsonPath("$.loyalty.earnAmountPerPoint").value(20000))
                .andExpect(jsonPath("$.loyalty.pointValue").value(750))
                .andExpect(jsonPath("$.loyalty.pointsEarned").value(1))
                .andExpect(jsonPath("$.loyalty.pointsRedeemed").value(6))
                .andExpect(jsonPath("$.loyalty.maxRedeemablePoints").value(10));
        UUID key = UUID.randomUUID();
        UUID order = orderId(create(owner, cart, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.pointsEarned").value(1))
                .andExpect(jsonPath("$.pointsRedeemed").value(6))
                .andExpect(jsonPath("$.pointsDiscount").value(4500)));
        balance(owner, customer, 5);
        configure(owner, 1000, 5000);
        create(owner, cart, key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.pointsEarned").value(1))
                .andExpect(jsonPath("$.pointsRedeemed").value(6))
                .andExpect(jsonPath("$.pointsDiscount").value(4500));
        balance(owner, customer, 5);
        assertThat(reasonDelta(owner, customer, "REDEEM")).isEqualTo(-6);
        assertThat(value(owner, "SELECT count(*) FROM app.payment WHERE order_id = ?", order)).isEqualTo(1);
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(9);
    }

    @Test
    void anOmittedRedemptionAndExplicitZeroReplayTheSameGuestOrder() throws Exception {
        Session owner = newStore();
        UUID variant = product(owner, 20000);
        Map<String, Object> cart = cart(variant, null, 1, null, 20000);
        UUID key = UUID.randomUUID();
        UUID order = orderId(create(owner, cart, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.pointsEarned").value(0)));
        cart.put("pointsRedeemed", 0);
        create(owner, cart, key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.orderId").value(order.toString()));
        assertThat(value(owner, "SELECT count(*) FROM app.loyalty_ledger")).isZero();
        assertThat(value(owner, "SELECT count(*) FROM app.loyalty_account")).isZero();
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(9);
    }

    @Test
    void preLoyaltyStoredRequestHashesStillReplayWithOmittedOrZeroRedemption() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        UUID variant = product(owner, 50000);
        UUID order = historicalOrder(owner, customer, variant, "DH-900001");
        UUID key = UUID.randomUUID();
        // JSON theo thứ tự record trước KH-04; Jackson giữ thứ tự constructor khi hash record.
        String oldJson = """
                {"lines":[{"variantId":"%s","quantity":1,"discount":null}],"orderDiscount":null,"paymentMethod":"CASH","cashReceived":50000,"quotedTotal":50000,"customerId":"%s"}
                """.formatted(variant, customer).strip();
        String oldHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(oldJson.getBytes(StandardCharsets.UTF_8)));
        asStore(owner, () -> jdbc.update("""
                INSERT INTO app.idempotency_record (store_id, document_type, idempotency_key, request_hash, document_id)
                VALUES (?, 'SALE', ?, ?, ?)
                """, owner.storeId(), key, oldHash, order));
        Map<String, Object> cart = cart(variant, customer, 1, null, 50000);
        create(owner, cart, key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.pointsEarned").value(0));
        cart.put("pointsRedeemed", 0);
        create(owner, cart, key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
        balance(owner, customer, 0);
        assertThat(value(owner, "SELECT count(*) FROM app.sales_order")).isEqualTo(1);
        assertThat(value(owner, "SELECT count(*) FROM app.loyalty_ledger")).isZero();
    }

    @Test
    void theLegacyPointsToRedeemAliasQuotesAndReplaysTheCanonicalRequest() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        sell(owner, product(owner, 50000), customer, 1, null, 50000);
        UUID variant = product(owner, 10000);
        Map<String, Object> cart = cart(variant, customer, 1, null, 8000);
        cart.put("pointsToRedeem", 2);
        quote(owner, cart).andExpect(status().isOk())
                .andExpect(jsonPath("$.pointsDiscount").value(2000))
                .andExpect(jsonPath("$.loyalty.pointsRedeemed").value(2));
        UUID key = UUID.randomUUID();
        UUID order = orderId(create(owner, cart, key).andExpect(status().isCreated()));
        cart.remove("pointsToRedeem");
        cart.put("pointsRedeemed", 2);
        create(owner, cart, key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.orderId").value(order.toString()));
        balance(owner, customer, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0.5", "2147483648"})
    void quoteAndCreateRejectNonWholeOrOutOfRangePointRequests(String points) throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        UUID variant = product(owner, 10000);
        Map<String, Object> cart = cart(variant, customer, 1, new BigDecimal(points), 10000);
        validation(quote(owner, cart));
        validation(create(owner, cart, UUID.randomUUID()));
        balance(owner, customer, 0);
        assertThat(value(owner, "SELECT count(*) FROM app.sales_order")).isZero();
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(10);
    }

    @Test
    void guestForeignCustomerInsufficientBalanceAndCartLimitCannotRedeem() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        UUID foreignCustomer = customer(newStore());
        UUID variant = product(owner, 1000);
        for (UUID selected : new UUID[] {null, foreignCustomer}) {
            Map<String, Object> cart = cart(variant, selected, 1, 1, 0);
            validation(quote(owner, cart));
            validation(create(owner, cart, UUID.randomUUID()));
        }
        Map<String, Object> cart = cart(variant, customer, 1, 1, 0);
        insufficient(quote(owner, cart));
        insufficient(create(owner, cart, UUID.randomUUID()));
        sell(owner, product(owner, 100000), customer, 1, null, 100000);
        cart.put("pointsRedeemed", 2);
        validation(quote(owner, cart));
        validation(create(owner, cart, UUID.randomUUID()));
        balance(owner, customer, 10);
        assertThat(value(owner, "SELECT count(*) FROM app.sales_order")).isEqualTo(1);
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(10);
        assertThat(reasonDelta(owner, customer, "REDEEM")).isZero();
    }

    @Test
    void theRedemptionCapUsesTheAmountAfterAllOtherDiscountsAndZeroPointValueDisablesRedeeming() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        sell(owner, product(owner, 100000), customer, 1, null, 100000);
        UUID variant = product(owner, 10000);
        Map<String, Object> cart = cart(variant, customer, 1, 3, 500);
        cart.put("lines", List.of(Map.of("variantId", variant, "quantity", 1,
                "discount", Map.of("type", "AMOUNT", "value", 2000))));
        cart.put("orderDiscount", Map.of("type", "AMOUNT", "value", 4500));
        quote(owner, cart).andExpect(status().isOk())
                .andExpect(jsonPath("$.loyalty.maxRedeemablePoints").value(3))
                .andExpect(jsonPath("$.total").value(500));
        cart.put("pointsRedeemed", 4);
        validation(quote(owner, cart));
        configure(owner, 10000, 0);
        cart.put("pointsRedeemed", 0);
        quote(owner, cart).andExpect(status().isOk())
                .andExpect(jsonPath("$.loyalty.maxRedeemablePoints").value(0))
                .andExpect(jsonPath("$.pointsDiscount").value(0))
                .andExpect(jsonPath("$.total").value(3500));
        cart.put("pointsRedeemed", 1);
        validation(quote(owner, cart));
    }

    @Test
    void concurrentSalesCannotSpendTheSamePointsEvenWhenTheySellDifferentVariants() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        sell(owner, product(owner, 50000), customer, 1, null, 50000);
        UUID first = product(owner, 5000);
        UUID second = product(owner, 5000);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<MockHttpServletResponse> responses;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> concurrentCreate(owner, cart(first, customer, 1, 5, 0), ready, start));
            var b = executor.submit(() -> concurrentCreate(owner, cart(second, customer, 1, 5, 0), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            responses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
        String failed = responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow().getContentAsString();
        assertThat((String) JsonPath.read(failed, "$.type")).isEqualTo(INSUFFICIENT);
        balance(owner, customer, 0);
        assertThat(value(owner, "SELECT count(*) FROM app.sales_order")).isEqualTo(2);
        assertThat(value(owner, "SELECT count(*) FROM app.payment")).isEqualTo(2);
        assertThat(value(owner, "SELECT sum(quantity) FROM app.stock WHERE variant_id IN (?, ?)", first, second))
                .isEqualTo(19);
        assertThat(reasonDelta(owner, customer, "REDEEM")).isEqualTo(-5);
        assertThat(value(owner, "SELECT count(*) FROM app.loyalty_ledger WHERE reason = 'REDEEM'")).isEqualTo(1);
    }

    @Test
    void cancellationRestoresSpentPointsAndReversesOriginalEarningOnceAfterRatesChange() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        sell(owner, product(owner, 100000), customer, 1, null, 100000);
        UUID variant = product(owner, 25000);
        Sale sale = sell(owner, variant, customer, 1, 4, 21000);
        balance(owner, customer, 8);
        configure(owner, 1, 9999);
        UUID key = UUID.randomUUID();
        cancel(owner, sale.orderId(), key).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        balance(owner, customer, 10);
        cancel(owner, sale.orderId(), key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
        balance(owner, customer, 10);
        assertThat(reasonDelta(owner, customer, "REVERSE_EARN")).isEqualTo(-2);
        assertThat(reasonDelta(owner, customer, "REVERSE_REDEEM")).isEqualTo(4);
        assertThat(value(owner, "SELECT count(*) FROM app.loyalty_ledger WHERE reason = 'REVERSE_EARN'"))
                .isEqualTo(1);
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(10);
    }

    @Test
    void successivePartialReturnsReverseTheFloorOfRemainingPaidAtTheOriginalEarnRate() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        UUID variant = product(owner, 15001);
        Map<String, Object> cart = cart(variant, customer, 3, null, 45001);
        cart.put("orderDiscount", Map.of("type", "AMOUNT", "value", 2));
        UUID order = orderId(create(owner, cart, UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.pointsEarned").value(4)));
        configure(owner, 500, 200);
        UUID firstKey = UUID.randomUUID();
        refund(owner, order, 1, 15000, firstKey).andExpect(status().isCreated());
        balance(owner, customer, 3);
        refund(owner, order, 1, 15000, firstKey).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
        balance(owner, customer, 3);
        refund(owner, order, 1, 15000, UUID.randomUUID()).andExpect(status().isCreated());
        balance(owner, customer, 1);
        refund(owner, order, 1, 15001, UUID.randomUUID()).andExpect(status().isCreated());
        balance(owner, customer, 0);
        assertThat(reasonDelta(owner, customer, "REVERSE_EARN")).isEqualTo(-4);
        assertThat(value(owner, "SELECT count(*) FROM app.loyalty_ledger WHERE reason = 'REVERSE_EARN'"))
                .isEqualTo(3);
        assertThat(value(owner, "SELECT sum(refund_amount) FROM app.sales_return WHERE order_id = ?", order))
                .isEqualTo(45001);
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(10);
        mvc.perform(get(ORDERS + "/{id}/returns", order).cookie(owner.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RETURNED"));
    }

    @Test
    void refundsUsePointsDiscountedLineAmountsAndDoNotRestoreSpentPoints() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        sell(owner, product(owner, 100000), customer, 1, null, 100000);
        UUID variant = product(owner, 10000);
        Map<String, Object> cart = cart(variant, customer, 3, 2, 27999);
        cart.put("orderDiscount", Map.of("type", "AMOUNT", "value", 1));
        UUID order = orderId(create(owner, cart, UUID.randomUUID()).andExpect(status().isCreated()));
        balance(owner, customer, 10);
        mvc.perform(post(ORDERS + "/{id}/returns/quote", order).cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[{\"lineNo\":1,\"quantity\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(9333));
        refund(owner, order, 1, 9333, UUID.randomUUID()).andExpect(status().isCreated());
        balance(owner, customer, 9);
        refund(owner, order, 2, 18666, UUID.randomUUID()).andExpect(status().isCreated());
        balance(owner, customer, 8);
        assertThat(reasonDelta(owner, customer, "REVERSE_EARN")).isEqualTo(-2);
        assertThat(reasonDelta(owner, customer, "REVERSE_REDEEM")).isZero();
        assertThat(value(owner, "SELECT sum(refund_amount) FROM app.sales_return WHERE order_id = ?", order))
                .isEqualTo(27999);
    }

    @Test
    void multipleLinesShareThePointsDiscountAndEachRefundUsesItsOriginalNetAmount() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        sell(owner, product(owner, 100000), customer, 1, null, 100000);
        UUID first = product(owner, 11000);
        UUID second = product(owner, 19000);
        Map<String, Object> cart = cart(first, customer, 1, 3, 17000);
        cart.put("lines", List.of(
                Map.of("variantId", first, "quantity", 1, "discount", Map.of("type", "AMOUNT", "value", 1000)),
                Map.of("variantId", second, "quantity", 1)));
        cart.put("orderDiscount", Map.of("type", "AMOUNT", "value", 9000));
        quote(owner, cart).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(17000))
                .andExpect(jsonPath("$.loyalty.pointsEarned").value(1))
                .andExpect(jsonPath("$.lines[0].netLineAmount").value(5863))
                .andExpect(jsonPath("$.lines[1].netLineAmount").value(11137));
        UUID order = orderId(create(owner, cart, UUID.randomUUID()).andExpect(status().isCreated()));
        balance(owner, customer, 8);
        refund(owner, order, 1, 1, 5863, UUID.randomUUID()).andExpect(status().isCreated());
        balance(owner, customer, 8);
        refund(owner, order, 2, 1, 11137, UUID.randomUUID()).andExpect(status().isCreated());
        balance(owner, customer, 7);
        assertThat(value(owner, "SELECT sum(refund_amount) FROM app.sales_return WHERE order_id = ?", order))
                .isEqualTo(17000);
        assertThat(reasonDelta(owner, customer, "REVERSE_EARN")).isEqualTo(-1);
    }

    @Test
    void cancellationUsesTheNetPointAdjustmentWhenRestoringSpentPointsCoversTheReversal() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        sell(owner, product(owner, 50000), customer, 1, null, 50000);
        Sale sale = sell(owner, product(owner, 55000), customer, 1, 5, 50000);
        sell(owner, product(owner, 5000), customer, 1, 5, 0);
        balance(owner, customer, 0);
        cancel(owner, sale.orderId(), UUID.randomUUID()).andExpect(status().isOk());
        balance(owner, customer, 0);
        assertThat(reasonDelta(owner, customer, "REVERSE_EARN")).isEqualTo(-5);
        assertThat(reasonDelta(owner, customer, "REVERSE_REDEEM")).isEqualTo(5);
    }

    @Test
    void earningPastTheIntegerBalanceLimitRejectsTheWholeSale() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        configure(owner, 1, 1000);
        sell(owner, product(owner, 2147483647L), customer, 1, null, 2147483647L);
        UUID variant = product(owner, 1);
        Map<String, Object> cart = cart(variant, customer, 1, null, 1);
        quote(owner, cart).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/sales/loyalty-limit"));
        create(owner, cart, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/sales/loyalty-limit"));
        balance(owner, customer, Integer.MAX_VALUE);
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(10);
        assertThat(value(owner, "SELECT count(*) FROM app.sales_order")).isEqualTo(1);
        assertThat(reasonDelta(owner, customer, "EARN")).isEqualTo(2147483647L);
    }

    @Test
    void insufficientPointsRollbackTheWholeCancellationAndTheSameKeyCanRetryAfterReplenishing() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        UUID variant = product(owner, 100000);
        Sale sale = sell(owner, variant, customer, 1, null, 100000);
        sell(owner, product(owner, 10000), customer, 1, 10, 0);
        UUID key = UUID.randomUUID();
        insufficient(cancel(owner, sale.orderId(), key));
        balance(owner, customer, 0);
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(9);
        assertThat(value(owner, "SELECT count(*) FROM app.sales_order WHERE id = ? AND status = 'COMPLETED' "
                + "AND cancelled_at IS NULL", sale.orderId())).isEqualTo(1);
        assertThat(value(owner, "SELECT count(*) FROM app.stock_ledger WHERE document_type = 'SALE_CANCEL' "
                + "AND document_id = ?", sale.orderId())).isZero();
        assertThat(value(owner, "SELECT count(*) FROM app.audit_log WHERE action = 'sales.order.cancel' "
                + "AND entity_id = ?", sale.orderId().toString())).isZero();
        assertThat(value(owner, "SELECT count(*) FROM app.idempotency_record WHERE idempotency_key = ?", key)).isZero();
        assertThat(reasonDelta(owner, customer, "REVERSE_EARN")).isZero();
        sell(owner, product(owner, 100000), customer, 1, null, 100000);
        cancel(owner, sale.orderId(), key).andExpect(status().isOk());
        balance(owner, customer, 0);
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(10);
    }

    @Test
    void insufficientPointsRollbackTheWholeReturnAndTheSameKeyCanRetryAfterReplenishing() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        UUID variant = product(owner, 100000);
        Sale sale = sell(owner, variant, customer, 1, null, 100000);
        sell(owner, product(owner, 10000), customer, 1, 10, 0);
        UUID key = UUID.randomUUID();
        insufficient(refund(owner, sale.orderId(), 1, 100000, key));
        balance(owner, customer, 0);
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(9);
        assertThat(value(owner, "SELECT count(*) FROM app.sales_return WHERE order_id = ?", sale.orderId())).isZero();
        assertThat(value(owner, "SELECT count(*) FROM app.sales_return_line")).isZero();
        assertThat(value(owner, "SELECT count(*) FROM app.stock_ledger WHERE document_type = 'SALE_RETURN'")).isZero();
        assertThat(value(owner, "SELECT count(*) FROM app.idempotency_record WHERE idempotency_key = ?", key)).isZero();
        assertThat(value(owner, "SELECT count(*) FROM app.sales_order WHERE id = ? AND status = 'COMPLETED'",
                sale.orderId())).isEqualTo(1);
        assertThat(reasonDelta(owner, customer, "REVERSE_EARN")).isZero();
        sell(owner, product(owner, 100000), customer, 1, null, 100000);
        refund(owner, sale.orderId(), 1, 100000, key).andExpect(status().isCreated());
        balance(owner, customer, 0);
        assertThat(value(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(10);
    }

    @Test
    void cancellingAndReturningHistoricalOrdersWithoutRecordedEarningLeavePointsUntouched() throws Exception {
        Session owner = newStore();
        UUID customer = customer(owner);
        sell(owner, product(owner, 50000), customer, 1, null, 50000);
        UUID cancellation = historicalOrder(owner, customer, product(owner, 50000), "DH-900001");
        UUID returned = historicalOrder(owner, customer, product(owner, 50000), "DH-900002");
        cancel(owner, cancellation, UUID.randomUUID()).andExpect(status().isOk());
        refund(owner, returned, 1, 50000, UUID.randomUUID()).andExpect(status().isCreated());
        balance(owner, customer, 5);
        assertThat(reasonDelta(owner, customer, "REVERSE_EARN")).isZero();
        assertThat(reasonDelta(owner, customer, "REVERSE_REDEEM")).isZero();
    }

    private Session newStore() throws Exception {
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "storeName", "Cửa hàng thử KH-04", "displayName", "Chủ thử",
                                "loginKey", "kh04." + UUID.randomUUID().toString().replace("-", "") + "@test.vn",
                                "password", "matkhau123"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return new Session(response.getCookie("access_token"),
                UUID.fromString(JsonPath.read(response.getContentAsString(), "$.storeId")),
                UUID.fromString(JsonPath.read(response.getContentAsString(), "$.userId")));
    }

    private Session newStaff(Session owner) throws Exception {
        String loginKey = "kh04." + UUID.randomUUID().toString().replace("-", "");
        String staff = mvc.perform(post("/api/v1/platform/users").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "loginKey", loginKey, "displayName", "Nhân viên thử", "password", "matkhau123"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", loginKey, "password", "matkhau123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        return new Session(cookie, owner.storeId(), UUID.fromString(JsonPath.read(staff, "$.userId")));
    }

    private UUID customer(Session owner) throws Exception {
        String body = mvc.perform(post("/api/v1/catalog/customers").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"09%d\"}".formatted(PHONES.getAndIncrement())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.customerId"));
    }

    private UUID product(Session owner, long price) throws Exception {
        String body = mvc.perform(post("/api/v1/catalog/products").cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Hàng thử KH-04 " + UUID.randomUUID(),
                                "salePrice", price, "openingQuantity", 10, "openingUnitCost", 100))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.variants[0].id"));
    }

    private void configure(Session owner, long earnAmount, long pointValue) throws Exception {
        mvc.perform(put("/api/v1/platform/store").cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Cửa hàng thử KH-04", "expiryWarningDays", 30,
                                "loyaltyEarnAmount", earnAmount, "loyaltyPointValue", pointValue))))
                .andExpect(status().isOk());
    }

    private static Map<String, Object> cart(UUID variant, UUID customer, int quantity, Object points, long total) {
        Map<String, Object> cart = new LinkedHashMap<>();
        cart.put("lines", List.of(Map.of("variantId", variant, "quantity", quantity)));
        if (customer != null) cart.put("customerId", customer);
        if (points != null) cart.put("pointsRedeemed", points);
        cart.put("paymentMethod", "CASH");
        cart.put("cashReceived", total);
        cart.put("quotedTotal", total);
        return cart;
    }

    private ResultActions quote(Session session, Map<String, Object> cart) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(cart);
        request.remove("quotedTotal");
        return mvc.perform(post(ORDERS + "/quote").cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
    }

    private ResultActions create(Session session, Map<String, Object> cart, UUID key) throws Exception {
        return mvc.perform(post(ORDERS).cookie(session.cookie()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cart)));
    }

    private Sale sell(Session session, UUID variant, UUID customer, int quantity, Object points, long total) throws Exception {
        return new Sale(orderId(create(session, cart(variant, customer, quantity, points, total), UUID.randomUUID())
                .andExpect(status().isCreated())), variant);
    }

    private UUID orderId(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.orderId"));
    }

    private ResultActions cancel(Session session, UUID order, UUID key) throws Exception {
        return mvc.perform(post(ORDERS + "/{id}/cancel", order).cookie(session.cookie()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Khách đổi ý\"}"));
    }

    private ResultActions refund(Session session, UUID order, int quantity, long refund, UUID key) throws Exception {
        return refund(session, order, 1, quantity, refund, key);
    }

    private ResultActions refund(Session session, UUID order, int line, int quantity, long refund, UUID key) throws Exception {
        return mvc.perform(post(ORDERS + "/{id}/returns", order).cookie(session.cookie()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "lines", List.of(Map.of("lineNo", line, "quantity", quantity)),
                        "refundMethod", "CASH", "quotedRefundTotal", refund))));
    }

    private MockHttpServletResponse concurrentCreate(Session session, Map<String, Object> cart,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Không mở được cổng chạy đồng thời");
        return create(session, cart, UUID.randomUUID()).andReturn().getResponse();
    }

    private void balance(Session session, UUID customer, int balance) throws Exception {
        mvc.perform(get(LOYALTY, customer).cookie(session.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(balance));
    }

    private void validation(ResultActions request) throws Exception {
        request.andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("/errors/common/validation"));
    }

    private void insufficient(ResultActions request) throws Exception {
        request.andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(INSUFFICIENT));
    }

    private long reasonDelta(Session session, UUID customer, String reason) {
        return value(session, "SELECT coalesce(sum(delta), 0) FROM app.loyalty_ledger WHERE customer_id = ? AND reason = ?",
                customer, reason);
    }

    private long value(Session session, String sql, Object... args) {
        return asStore(session, () -> jdbc.queryForObject(sql, Long.class, args));
    }

    private <T> T asStore(Session session, Supplier<T> work) {
        return TenantScope.runAs(session.storeId(), () -> new TransactionTemplate(transactions).execute(tx -> work.get()));
    }

    /** Dữ liệu đơn trước KH-04: migration để points_earned và loyalty_earn_amount bằng 0. */
    private UUID historicalOrder(Session owner, UUID customer, UUID variant, String code) {
        UUID order = UUID.randomUUID();
        asStore(owner, () -> {
            jdbc.update("""
                    INSERT INTO app.sales_order
                        (id, store_id, order_code, customer_id, status, subtotal, total_amount, created_by, created_at)
                    VALUES (?, ?, ?, ?, 'COMPLETED', 50000, 50000, ?, ?)
                    """, order, owner.storeId(), code, customer, owner.userId(), clock.instant().atOffset(ZoneOffset.UTC));
            jdbc.update("""
                    INSERT INTO app.sales_order_line
                        (store_id, order_id, line_no, variant_id, product_name, quantity, unit_price, net_line_amount, unit_cost)
                    VALUES (?, ?, 1, ?, 'Hàng lịch sử KH-04', 1, 50000, 50000, 100)
                    """, owner.storeId(), order, variant);
            return null;
        });
        return order;
    }

    private record Session(Cookie cookie, UUID storeId, UUID userId) {}
    private record Sale(UUID orderId, UUID variantId) {}
}
