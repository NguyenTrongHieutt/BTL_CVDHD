package vn.nhom17.banhang.catalog.internal.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Quy tắc số điện thoại Khách hàng; khớp thật trên database nằm ở {@code CustomerIntegrationTest}. */
class CustomerPhonesTest {

    @Test
    void localAndInternationalFormsAreStoredAsTheLocalForm() {
        assertThat(CustomerPhones.normalize("0912345678")).isEqualTo("0912345678");
        assertThat(CustomerPhones.normalize("0912 345 678")).isEqualTo("0912345678");
        assertThat(CustomerPhones.normalize(" 0912.345.678 ")).isEqualTo("0912345678");
        assertThat(CustomerPhones.normalize("0912-345-678")).isEqualTo("0912345678");
        assertThat(CustomerPhones.normalize("+84912345678")).isEqualTo("0912345678");
        assertThat(CustomerPhones.normalize("+84 912 345 678")).isEqualTo("0912345678");
        // Khoảng trắng Unicode, ký tự định dạng vô hình được chuẩn hóa như trường văn bản
        assertThat(CustomerPhones.normalize(" 0912　345​678")).isEqualTo("0912345678");
    }

    @Test
    void anythingElseIsInvalid() {
        for (String invalid : new String[] {null, "", "  ", "123", "0812", "091234567", "09123456789", "+8491234567",
                "+840912345678", "84912345678", "1912345678", "0912a45678", "0912\t345678", "0912_345_678",
                "(0912)345678", "+84-0912345678", "０９１２３４５６７８"}) {
            assertThat(CustomerPhones.normalize(invalid)).as(String.valueOf(invalid)).isNull();
        }
        assertThat(CustomerPhones.isBlank(null)).isTrue();
        assertThat(CustomerPhones.isBlank(" ​ ")).isTrue();
        assertThat(CustomerPhones.isBlank("0")).isFalse();
    }

    @Test
    void searchComparesDigitsOnlyAndReadsPlus84AsZero() {
        assertThat(CustomerPhones.searchDigits("345 678")).isEqualTo("345678");
        assertThat(CustomerPhones.searchDigits("0912.345-678")).isEqualTo("0912345678");
        assertThat(CustomerPhones.searchDigits("+84 912")).isEqualTo("0912");
        assertThat(CustomerPhones.searchDigits("+84912345678")).isEqualTo("0912345678");
        assertThat(CustomerPhones.searchDigits("(091) 234")).isEqualTo("091234");
        assertThat(CustomerPhones.searchDigits("+912")).isEqualTo("912");
        // Có chữ cái là tìm theo tên; không có chữ số thì không tìm theo số
        assertThat(CustomerPhones.searchDigits("Hoa 345")).isNull();
        assertThat(CustomerPhones.searchDigits("chi hoa")).isNull();
        assertThat(CustomerPhones.searchDigits("%")).isNull();
        assertThat(CustomerPhones.searchDigits("-")).isNull();
        assertThat(CustomerPhones.searchDigits(null)).isNull();
    }
}
