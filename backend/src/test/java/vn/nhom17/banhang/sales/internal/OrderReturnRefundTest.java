package vn.nhom17.banhang.sales.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OrderReturnRefundTest {
    @Test
    void roundsDownForPartialReturnsAndIncludesTheRemainderOnTheLastReturn() {
        assertEquals(33333, OrderReturnController.refundAmount(100000, 3, 0, 0, 1));
        assertEquals(33333, OrderReturnController.refundAmount(100000, 3, 1, 33333, 1));
        assertEquals(33334, OrderReturnController.refundAmount(100000, 3, 2, 66666, 1));
        assertEquals(22501, OrderReturnController.refundAmount(90001, 4, 3, 67500, 1));
    }

    @Test
    void handlesFreeItemsAndAvoidsOverflowBeforeDividing() {
        assertEquals(0, OrderReturnController.refundAmount(0, 2, 0, 0, 1));
        assertEquals(6148914691236517204L, OrderReturnController.refundAmount(Long.MAX_VALUE, 3, 0, 0, 2));
    }
}
