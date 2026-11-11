package vn.nhom17.banhang.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

/** Chuẩn hóa trường văn bản dùng chung của catalog. Ký tự vô hình viết bằng mã thoát để thấy rõ trong mã nguồn. */
class TextFieldsTest {

    static final TextFields.Rule NAME = TextFields.Rule.required("Tên Sản phẩm", 5);
    static final TextFields.Rule DESCRIPTION = TextFields.Rule.optional("Mô tả", 20).allowingLineBreaks();

    String check(String raw, TextFields.Rule rule, FieldErrors errors) {
        return TextFields.check(raw, "f", rule, errors);
    }

    @Test
    void unicodeSpacesInsideBecomeOrdinarySpaces() {
        FieldErrors errors = new FieldErrors();
        assertThat(check("B\u00a0i\u3000a\u2009x", DESCRIPTION, errors)).isEqualTo("B i a x");
        assertThat(check("B\u00a0i\u3000a", NAME, errors)).isEqualTo("B i a");
        assertThat(check("\u00a0Bia\u202f", NAME, errors)).isEqualTo("Bia");
        assertThat(errors.isEmpty()).isTrue();
    }

    @Test
    void lineAndParagraphSeparatorsAreRejectedInSingleLineFieldsOnly() {
        FieldErrors errors = new FieldErrors();
        assertThat(check("Bi\u2028a", NAME, errors)).isNull();
        assertThat(check("Bi\u2029a", NAME, errors)).isNull();
        assertThat(errors.isEmpty()).isFalse();

        FieldErrors none = new FieldErrors();
        assertThat(check("a\u2028b", DESCRIPTION, none)).isEqualTo("a\u2028b");
        assertThat(none.isEmpty()).isTrue();
    }

    @Test
    void loneSurrogatesAreRejected() {
        for (String raw : java.util.List.of("a\ud83d", "\ude00b", "a\ud83db")) {
            FieldErrors errors = new FieldErrors();
            assertThat(check(raw, NAME, errors)).isNull();
            assertThat(check(raw, DESCRIPTION, errors)).isNull();
            assertThat(errors.toProblem().properties().get("errors").toString()).contains("có ký tự không hợp lệ");
        }
    }

    @Test
    void joinersAreKeptInMultilineFieldsAndRemovedFromSingleLineFields() {
        String family = "\ud83d\udc68\u200d\ud83d\udc69";
        FieldErrors errors = new FieldErrors();
        assertThat(check(family + "\u200b", DESCRIPTION, errors)).isEqualTo(family);
        assertThat(check("x\u200cy", DESCRIPTION, errors)).isEqualTo("x\u200cy");
        assertThat(check("x\u200cy\u200d", NAME, errors)).isEqualTo("xy");
        assertThat(errors.isEmpty()).isTrue();
    }

    @Test
    void lengthIsCountedInCodePointsAfterNormalization() {
        FieldErrors errors = new FieldErrors();
        String nfd = Normalizer.normalize("ệ", Normalizer.Form.NFD);
        assertThat(nfd.codePointCount(0, nfd.length())).isEqualTo(3);
        assertThat(check(nfd.repeat(5), NAME, errors)).isEqualTo("ệ".repeat(5));
        String emoji = "\ud83d\ude00";
        assertThat(check(emoji.repeat(5), NAME, errors)).isEqualTo(emoji.repeat(5));
        assertThat(errors.isEmpty()).isTrue();

        assertThat(check(nfd.repeat(6), NAME, errors)).isNull();
        assertThat(check(emoji.repeat(6), NAME, errors)).isNull();
        assertThat(errors.isEmpty()).isFalse();
    }
}
