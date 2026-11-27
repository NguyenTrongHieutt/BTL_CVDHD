package vn.nhom17.banhang.platform;

/** Hai luồng xử lý độc lập; công việc đọc tệp không chặn thao tác ghi ngắn hoặc bán hàng. */
public enum BackofficeCommandLane {
    QUICK, FILE
}
