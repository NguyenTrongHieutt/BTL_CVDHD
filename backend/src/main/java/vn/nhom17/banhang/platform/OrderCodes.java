package vn.nhom17.banhang.platform;

/**
 * Cấp mã Đơn hàng {@code DH-xxxxxx} (AD-8): {@code UPDATE … RETURNING} trên {@code order_counter}, phải gọi trong
 * transaction của Đơn hàng, sau khi đã kiểm tra {@code Idempotency-Key} (AD-7).
 */
public interface OrderCodes {

    String nextOrderCode();
}
