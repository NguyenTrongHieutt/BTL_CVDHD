/**
 * Thông tin Cửa hàng (FR-48, TK-05). Người giữ: Hiếu — features/platform (AD-20). Chỉ Chủ cửa hàng (route
 * `OWNER_ONLY`; backend vẫn kiểm, Nhân viên nhận `403`).
 *
 * Một form sửa toàn bộ (`PUT /api/v1/platform/store` thay mọi trường): tên, số điện thoại, địa chỉ, chân hóa đơn in
 * trên hóa đơn; tài khoản ngân hàng nhận VietQR (AD-22); tỷ lệ tích điểm; số ngày cảnh báo hết hạn.
 *
 * - Ô chọn ngân hàng là danh sách tĩnh các ngân hàng phổ biến (tên + BIN NAPAS) và "Ngân hàng khác" để nhập BIN.
 * - Ba ô ngân hàng cùng trống (tắt chuyển khoản) hoặc cùng có; thiếu một phần thì báo dưới ô thiếu.
 * - Tên chủ tài khoản tự đổi sang chữ in hoa không dấu khi gõ.
 * - Kiểm phía client giống backend; lỗi `errors[]` của backend hiện dưới ô tương ứng, lỗi khác ở khung phía trên.
 * - Đổi tài khoản nhận chuyển khoản hoặc tắt chuyển khoản thì hỏi xác nhận trước khi lưu.
 * - Lưu xong cập nhật `Me.storeName` (invalidate `['platform', 'me']`): tên ở thanh trên cùng đổi theo ngay.
 * - Dữ liệu mới về (tải lại nền, lưu ở nơi khác) khi form chưa sửa thì form hiện dữ liệu mới, để `PUT` thay toàn bộ
 *   không ghi đè bằng giá trị cũ.
 *
 * Ô tiền, số là ô chữ (không dùng `InputNumber`, vốn lặng lẽ bỏ hoặc kẹp giá trị sai): chỉ nhận chữ số liền hoặc
 * nhóm nghìn đúng bằng dấu chấm (tự nhóm khi gõ); ô số ngày không nhận dấu chấm.
 */
import { App, Button, Card, Col, Form, Input, Row, Select, Skeleton, Typography } from 'antd';
import type { FormInstance } from 'antd';
import { useEffect } from 'react';
import { isApiError } from '../../../shared/api/client';
import { ErrorAlert, useConfirm, useDocumentTitle } from '../../../shared/components';
import type { ConfirmOptions } from '../../../shared/components';
import type { StoreInfo, UpdateStoreRequest } from '../../../shared/types/platform';
import {
  hasInvalidStoreChars,
  normalizeBankAccountName,
  normalizeStoreText,
  storeTextLength,
  toBankAccountName,
  useStoreInfo,
  useUpdateStore,
} from '../api/store';

const NAME_MAX = 200;
const PHONE_MAX = 20;
const ADDRESS_MAX = 300;
const INVOICE_FOOTER_MAX = 300;
const ACCOUNT_NAME_MAX = 50;
const EXPIRY_WARNING_DAYS_MAX = 365;
/** Tiền lớn nhất backend nhận: số nguyên lớn nhất JavaScript còn biểu diễn đúng. */
const MONEY_MAX = Number.MAX_SAFE_INTEGER;

const PHONE_CHARS = /^[0-9 +.-]+$/;
const BANK_BIN = /^[0-9]{6}$/;
const ACCOUNT_NO = /^[0-9]{6,19}$/;
const ACCOUNT_NAME = /^[A-Z ]+$/;

/** Ngân hàng phổ biến và mã BIN NAPAS (dùng trong VietQR). Ngân hàng khác thì chọn "Ngân hàng khác" rồi nhập BIN. */
const BANKS: readonly { name: string; bin: string }[] = [
  { name: 'Vietcombank', bin: '970436' },
  { name: 'VietinBank', bin: '970415' },
  { name: 'BIDV', bin: '970418' },
  { name: 'Agribank', bin: '970405' },
  { name: 'Techcombank', bin: '970407' },
  { name: 'MB', bin: '970422' },
  { name: 'ACB', bin: '970416' },
  { name: 'VPBank', bin: '970432' },
  { name: 'TPBank', bin: '970423' },
  { name: 'Sacombank', bin: '970403' },
  { name: 'HDBank', bin: '970437' },
  { name: 'VIB', bin: '970441' },
  { name: 'SHB', bin: '970443' },
  { name: 'Eximbank', bin: '970431' },
  { name: 'MSB', bin: '970426' },
  { name: 'OCB', bin: '970448' },
  { name: 'SeABank', bin: '970440' },
  { name: 'LPBank', bin: '970449' },
];

/** Giá trị của ô chọn ngân hàng khi chọn "Ngân hàng khác". */
const OTHER_BANK = 'OTHER';
const OTHER_BANK_LABEL = 'Ngân hàng khác (nhập mã BIN)';

const BANK_OPTIONS = [
  ...BANKS.map((bank) => ({ value: bank.bin, label: `${bank.name} (${bank.bin})` })),
  { value: OTHER_BANK, label: OTHER_BANK_LABEL },
];

/** Ô tiền, số giữ nguyên chữ người dùng gõ; đổi thành số khi gửi. `bankChoice` là BIN trong danh sách hoặc `OTHER`. */
interface StoreFormValues {
  name: string;
  phone?: string;
  address?: string;
  invoiceFooter?: string;
  bankChoice?: string;
  bankBinOther?: string;
  bankAccountNo?: string;
  bankAccountName?: string;
  loyaltyEarnAmount?: string;
  loyaltyPointValue?: string;
  expiryWarningDays?: string;
}

type FormField = keyof StoreFormValues;

/** Chữ thường, bỏ dấu, để tìm ngân hàng không cần gõ dấu, hoa thường. */
function foldText(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').replace(/[đĐ]/g, 'd').toLowerCase();
}

const PLAIN_DIGITS = /^\d+$/;
const GROUPED_DIGITS = /^\d{1,3}(\.\d{3})+$/;

/**
 * Đọc ô số: chữ số liền ("20000"), hoặc nhóm nghìn đúng bằng dấu chấm ("20.000") khi `grouped`. Trống thì `null`;
 * dấu chấm đặt sai ("7.5", "1.00.000") hay ký tự khác thì `NaN`.
 */
function parseWholeNumber(text: string | null | undefined, grouped = true): number | null {
  const trimmed = (text ?? '').trim();
  if (trimmed === '') return null;
  if (PLAIN_DIGITS.test(trimmed)) return Number(trimmed);
  if (grouped && GROUPED_DIGITS.test(trimmed)) return Number(trimmed.replace(/\./g, ''));
  return Number.NaN;
}

function isWholeNumberText(text: string | undefined): boolean {
  const value = parseWholeNumber(text);
  return value !== null && !Number.isNaN(value);
}

/**
 * Tự nhóm nghìn khi gõ ("20000" → "20.000"). Nhóm lại khi ô là số hợp lệ, hoặc vừa gõ thêm, xóa một chữ số trong số
 * đã nhóm ("20.0000" → "200.000"). Dấu chấm đặt sai như "7.5" để nguyên cho luật kiểm báo lỗi. Dùng làm `normalize`
 * của `Form.Item` (nhận cả giá trị trước đó).
 */
function groupThousands(value: string | undefined, prevValue?: string): string | undefined {
  if (value === undefined) return value;
  const text = value.trim();
  const previous = (prevValue ?? '').trim();
  const editedOneDigit =
    isWholeNumberText(previous) &&
    /^[\d.]+$/.test(text) &&
    text.replace(/\d/g, '') === previous.replace(/\d/g, '') &&
    Math.abs(text.length - previous.length) === 1;
  if (!editedOneDigit && !isWholeNumberText(text)) return value;
  return BigInt(text.replace(/\./g, '')).toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}

function stripSpaces(value: string | undefined): string | undefined {
  return value?.replace(/\s/g, '');
}

function toFormValues(store: StoreInfo): StoreFormValues {
  const known = store.bankBin !== null && BANKS.some((bank) => bank.bin === store.bankBin);
  return {
    name: store.name,
    phone: store.phone ?? '',
    address: store.address ?? '',
    invoiceFooter: store.invoiceFooter ?? '',
    bankChoice: store.bankBin === null ? undefined : known ? store.bankBin : OTHER_BANK,
    bankBinOther: store.bankBin !== null && !known ? store.bankBin : '',
    bankAccountNo: store.bankAccountNo ?? '',
    bankAccountName: store.bankAccountName ?? '',
    loyaltyEarnAmount: groupThousands(String(store.loyaltyEarnAmount)),
    loyaltyPointValue: groupThousands(String(store.loyaltyPointValue)),
    expiryWarningDays: String(store.expiryWarningDays),
  };
}

function bankBinOf(values: Pick<StoreFormValues, 'bankChoice' | 'bankBinOther'>): string {
  return values.bankChoice === OTHER_BANK ? normalizeStoreText(values.bankBinOther) : (values.bankChoice ?? '');
}

function optional(text: string): string | null {
  return text === '' ? null : text;
}

function toRequest(values: StoreFormValues): UpdateStoreRequest {
  return {
    name: normalizeStoreText(values.name),
    phone: optional(normalizeStoreText(values.phone)),
    address: optional(normalizeStoreText(values.address)),
    invoiceFooter: optional(normalizeStoreText(values.invoiceFooter)),
    bankBin: optional(bankBinOf(values)),
    bankAccountNo: optional(normalizeStoreText(values.bankAccountNo)),
    bankAccountName: optional(normalizeBankAccountName(values.bankAccountName)),
    // Các luật kiểm đã chặn số trống hoặc sai
    loyaltyEarnAmount: parseWholeNumber(values.loyaltyEarnAmount) ?? 0,
    loyaltyPointValue: parseWholeNumber(values.loyaltyPointValue) ?? 0,
    expiryWarningDays: parseWholeNumber(values.expiryWarningDays, false) ?? 0,
  };
}

/** Đặt giá trị của form theo `store`, coi như chưa sửa gì (`touched = false`) và xóa lỗi cũ. */
function showStore(form: FormInstance<StoreFormValues>, store: StoreInfo) {
  const values = toFormValues(store);
  form.setFields(
    (Object.keys(values) as FormField[]).map((name) => ({ name, value: values[name], touched: false, errors: [] })),
  );
}

type BankAccount = Pick<StoreInfo, 'bankBin' | 'bankAccountNo' | 'bankAccountName'>;

function hasBankAccount(account: BankAccount): boolean {
  return account.bankBin !== null && account.bankAccountNo !== null && account.bankAccountName !== null;
}

function describeBankAccount(account: BankAccount): string {
  const bank = BANKS.find((b) => b.bin === account.bankBin);
  const bankName = bank ? bank.name : `BIN ${account.bankBin}`;
  return `${bankName} – ${account.bankAccountNo} – ${account.bankAccountName}`;
}

/**
 * Câu hỏi xác nhận khi lần lưu làm đổi tài khoản đang nhận chuyển khoản hoặc tắt chuyển khoản; `null` khi không cần
 * hỏi (chưa có tài khoản, hoặc tài khoản giữ nguyên).
 */
function bankChangeQuestion(saved: StoreInfo, next: UpdateStoreRequest): ConfirmOptions | null {
  if (!hasBankAccount(saved)) return null;
  if (!hasBankAccount(next)) {
    return {
      title: 'Tắt thanh toán chuyển khoản?',
      content: `Màn thanh toán sẽ không còn mã VietQR của tài khoản ${describeBankAccount(saved)}.`,
      okText: 'Tắt chuyển khoản',
      cancelText: 'Không lưu',
      danger: true,
    };
  }
  const same =
    saved.bankBin === next.bankBin &&
    saved.bankAccountNo === next.bankAccountNo &&
    saved.bankAccountName === next.bankAccountName;
  if (same) return null;
  return {
    title: 'Đổi tài khoản nhận chuyển khoản?',
    content:
      `Từ nay tiền khách chuyển khoản vào tài khoản ${describeBankAccount(next)} ` +
      `(trước đây: ${describeBankAccount(saved)}). Kiểm tra kỹ số tài khoản trước khi lưu.`,
    okText: 'Đổi tài khoản',
    cancelText: 'Không lưu',
    danger: true,
  };
}

/** Ô ngân hàng nào đang có giá trị, để kiểm "cùng trống hoặc cùng có". */
function bankPresence(form: FormInstance<StoreFormValues>) {
  const values = form.getFieldsValue(['bankChoice', 'bankBinOther', 'bankAccountNo', 'bankAccountName']);
  return {
    bank: (values.bankChoice ?? '') !== '',
    accountNo: normalizeStoreText(values.bankAccountNo) !== '',
    accountName: normalizeBankAccountName(values.bankAccountName) !== '',
  };
}

/**
 * Lỗi của backend đặt được hết dưới các ô; `null` nếu có phần không thuộc ô nào (khi đó hiện khung báo lỗi chung).
 * Lỗi trên `bankBin` hiện dưới ô nhập BIN khi đang chọn "Ngân hàng khác", ngược lại dưới ô chọn ngân hàng.
 */
function formErrorsOf(error: unknown, otherBank: boolean): { name: FormField; errors: string[] }[] | null {
  if (!isApiError(error) || error.errors.length === 0) return null;
  const fieldOf = (field: string): FormField | null => {
    if (field === 'bankBin') return otherBank ? 'bankBinOther' : 'bankChoice';
    const fields: readonly string[] = [
      'name',
      'phone',
      'address',
      'invoiceFooter',
      'bankAccountNo',
      'bankAccountName',
      'loyaltyEarnAmount',
      'loyaltyPointValue',
      'expiryWarningDays',
    ];
    return fields.includes(field) ? (field as FormField) : null;
  };
  const byField = new Map<FormField, string[]>();
  for (const { field, message } of error.errors) {
    const name = fieldOf(field);
    if (!name) return null;
    byField.set(name, [...(byField.get(name) ?? []), message]);
  }
  return [...byField].map(([name, errors]) => ({ name, errors }));
}

/** Độ dài tối đa và ký tự không hợp lệ, kiểm như backend. */
function textRules(label: string, max: number) {
  return [
    {
      validator: (_: unknown, value?: string) =>
        !hasInvalidStoreChars(value) ? Promise.resolve() : Promise.reject(new Error(`${label} có ký tự không hợp lệ`)),
    },
    {
      validator: (_: unknown, value?: string) =>
        storeTextLength(value) <= max ? Promise.resolve() : Promise.reject(new Error(`${label} tối đa ${max} ký tự`)),
    },
  ];
}

/**
 * Số nguyên bắt buộc từ `min` (0 hoặc 1) tới `max`, câu báo lỗi như backend. `grouped`: nhận nhóm nghìn bằng dấu
 * chấm (ô tiền); ô số ngày thì không.
 */
function wholeNumberRule(label: string, min: number, max: number, tooLarge: string, grouped = true) {
  const tooSmall = min > 0 ? `${label} phải lớn hơn 0` : `${label} không được âm`;
  return {
    validator: (_: unknown, text?: string) => {
      const value = parseWholeNumber(text, grouped);
      if (value === null) return Promise.reject(new Error(`Nhập ${label.charAt(0).toLowerCase()}${label.slice(1)}`));
      if (Number.isNaN(value)) {
        return Promise.reject(new Error(text?.trim().startsWith('-') ? tooSmall : `${label} phải là số nguyên`));
      }
      if (value < min) return Promise.reject(new Error(tooSmall));
      if (value > max) return Promise.reject(new Error(tooLarge));
      return Promise.resolve();
    },
  };
}

function StoreForm({ store }: { store: StoreInfo }) {
  const [form] = Form.useForm<StoreFormValues>();
  const { message } = App.useApp();
  const confirm = useConfirm();
  const update = useUpdateStore();
  const bankChoice = Form.useWatch('bankChoice', form);
  const otherBank = bankChoice === OTHER_BANK;
  const pending = update.isPending;

  // `initialValues` chỉ áp dụng lúc mở màn. Dữ liệu mới về sau đó (tải lại nền khi quay lại màn, lưu ở nơi khác) thì
  // form chưa sửa hiện dữ liệu mới; form đang sửa thì giữ chữ người dùng đã gõ.
  useEffect(() => {
    if (!form.isFieldsTouched()) showStore(form, store);
  }, [form, store]);

  const submit = async (values: StoreFormValues) => {
    const request = toRequest(values);
    const question = bankChangeQuestion(store, request);
    if (question && !(await confirm(question))) return;
    update.mutate(request, {
      onSuccess: (saved) => {
        showStore(form, saved);
        void message.success('Đã lưu Thông tin Cửa hàng');
      },
      onError: (error) => {
        const errors = formErrorsOf(error, values.bankChoice === OTHER_BANK);
        if (errors) form.setFields(errors);
      },
    });
  };

  const onFinish = (values: StoreFormValues) => {
    void submit(values);
  };

  /** Ô ngân hàng bắt buộc khi một trong hai ô còn lại có giá trị. */
  const requiredWithOtherBankFields = (self: 'bank' | 'accountNo' | 'accountName', missing: string) => ({
    validator: () => {
      const presence = bankPresence(form);
      const othersFilled = Object.entries(presence).some(([key, filled]) => key !== self && filled);
      return !presence[self] && othersFilled ? Promise.reject(new Error(missing)) : Promise.resolve();
    },
  });

  const numberInputProps = { inputMode: 'numeric' as const, autoComplete: 'off' };

  return (
    <>
      {update.isError && !formErrorsOf(update.error, otherBank) ? <ErrorAlert error={update.error} /> : null}
      <Form<StoreFormValues>
        form={form}
        layout="vertical"
        requiredMark={false}
        onFinish={onFinish}
        disabled={pending}
        initialValues={toFormValues(store)}
        style={{ maxWidth: 760 }}
      >
        <Card title="Thông tin in trên hóa đơn" style={{ marginBottom: 16 }}>
          <Form.Item
            name="name"
            label="Tên Cửa hàng"
            rules={[
              { required: true, whitespace: true, message: 'Nhập tên Cửa hàng' },
              ...textRules('Tên Cửa hàng', NAME_MAX),
            ]}
          >
            <Input autoComplete="organization" />
          </Form.Item>
          <Row gutter={16}>
            <Col xs={24} sm={10}>
              <Form.Item
                name="phone"
                label="Số điện thoại"
                rules={[
                  {
                    validator: (_: unknown, value?: string) => {
                      const phone = normalizeStoreText(value);
                      if (phone === '') return Promise.resolve();
                      if (!PHONE_CHARS.test(phone)) {
                        return Promise.reject(new Error('Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . -'));
                      }
                      if (!/[0-9]/.test(phone)) return Promise.reject(new Error('Số điện thoại phải có chữ số'));
                      if ([...phone].length > PHONE_MAX) {
                        return Promise.reject(new Error(`Số điện thoại tối đa ${PHONE_MAX} ký tự`));
                      }
                      return Promise.resolve();
                    },
                  },
                ]}
              >
                <Input inputMode="tel" autoComplete="tel" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={14}>
              <Form.Item name="address" label="Địa chỉ" rules={textRules('Địa chỉ', ADDRESS_MAX)}>
                <Input autoComplete="street-address" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="invoiceFooter"
            label="Chân hóa đơn"
            extra="Dòng in cuối hóa đơn."
            rules={textRules('Chân hóa đơn', INVOICE_FOOTER_MAX)}
          >
            <Input placeholder="Cảm ơn quý khách, hẹn gặp lại!" autoComplete="off" />
          </Form.Item>
        </Card>

        <Card title="Tài khoản nhận chuyển khoản (VietQR)" style={{ marginBottom: 16 }}>
          <Typography.Paragraph type="secondary">
            Khách quét mã VietQR để chuyển tiền vào tài khoản này. Để trống cả ba ô thì tắt thanh toán chuyển khoản.
          </Typography.Paragraph>
          <Row gutter={16}>
            <Col xs={24} sm={otherBank ? 14 : 24}>
              <Form.Item
                name="bankChoice"
                label="Ngân hàng"
                dependencies={['bankAccountNo', 'bankAccountName']}
                rules={[requiredWithOtherBankFields('bank', 'Chọn ngân hàng')]}
              >
                <Select
                  allowClear
                  placeholder="Chưa chọn"
                  options={BANK_OPTIONS}
                  showSearch={{
                    filterOption: (input, option) => foldText(String(option?.label ?? '')).includes(foldText(input)),
                  }}
                  notFoundContent={`Không thấy ngân hàng này. Chọn "${OTHER_BANK_LABEL}".`}
                />
              </Form.Item>
            </Col>
            {otherBank ? (
              <Col xs={24} sm={10}>
                <Form.Item
                  name="bankBinOther"
                  label="Mã BIN ngân hàng"
                  normalize={stripSpaces}
                  rules={[
                    { required: true, message: 'Nhập mã BIN của ngân hàng' },
                    { pattern: BANK_BIN, message: 'Mã ngân hàng (BIN) gồm đúng 6 chữ số' },
                  ]}
                >
                  <Input inputMode="numeric" autoComplete="off" />
                </Form.Item>
              </Col>
            ) : null}
          </Row>
          <Row gutter={16}>
            <Col xs={24} sm={10}>
              <Form.Item
                name="bankAccountNo"
                label="Số tài khoản"
                normalize={stripSpaces}
                dependencies={['bankChoice', 'bankAccountName']}
                rules={[
                  requiredWithOtherBankFields('accountNo', 'Nhập số tài khoản'),
                  {
                    validator: (_: unknown, value?: string) => {
                      const accountNo = normalizeStoreText(value);
                      return accountNo === '' || ACCOUNT_NO.test(accountNo)
                        ? Promise.resolve()
                        : Promise.reject(new Error('Số tài khoản gồm 6 đến 19 chữ số'));
                    },
                  },
                ]}
              >
                <Input inputMode="numeric" autoComplete="off" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={14}>
              <Form.Item
                name="bankAccountName"
                label="Tên chủ tài khoản"
                extra="Chữ in hoa không dấu, như trên thẻ ngân hàng."
                normalize={toBankAccountName}
                dependencies={['bankChoice', 'bankAccountNo']}
                rules={[
                  requiredWithOtherBankFields('accountName', 'Nhập tên chủ tài khoản'),
                  {
                    validator: (_: unknown, value?: string) => {
                      const accountName = normalizeBankAccountName(value);
                      if (accountName === '') return Promise.resolve();
                      if (!ACCOUNT_NAME.test(accountName)) {
                        return Promise.reject(
                          new Error('Tên chủ tài khoản chỉ gồm chữ in hoa không dấu và khoảng trắng'),
                        );
                      }
                      if (accountName.length > ACCOUNT_NAME_MAX) {
                        return Promise.reject(new Error(`Tên chủ tài khoản tối đa ${ACCOUNT_NAME_MAX} ký tự`));
                      }
                      return Promise.resolve();
                    },
                  },
                ]}
              >
                <Input autoComplete="off" />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Card title="Tích điểm và cảnh báo hết hạn" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col xs={24} sm={8}>
              <Form.Item
                name="loyaltyEarnAmount"
                label="Số tiền để được 1 điểm"
                normalize={groupThousands}
                rules={[wholeNumberRule('Số tiền để được 1 điểm', 1, MONEY_MAX, 'Số tiền để được 1 điểm quá lớn')]}
              >
                <Input {...numberInputProps} suffix="₫" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item
                name="loyaltyPointValue"
                label="Giá trị 1 điểm khi đổi"
                normalize={groupThousands}
                rules={[wholeNumberRule('Giá trị 1 điểm', 0, MONEY_MAX, 'Giá trị 1 điểm quá lớn')]}
              >
                <Input {...numberInputProps} suffix="₫" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item
                name="expiryWarningDays"
                label="Cảnh báo hết hạn trước"
                rules={[
                  wholeNumberRule(
                    'Số ngày cảnh báo hết hạn',
                    0,
                    EXPIRY_WARNING_DAYS_MAX,
                    `Số ngày cảnh báo hết hạn tối đa ${EXPIRY_WARNING_DAYS_MAX} ngày`,
                    false,
                  ),
                ]}
              >
                <Input {...numberInputProps} suffix="ngày" />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Button type="primary" htmlType="submit" loading={pending}>
          Lưu
        </Button>
      </Form>
    </>
  );
}

export function StoreSettingsPage() {
  useDocumentTitle('Thông tin Cửa hàng');
  const store = useStoreInfo();
  return (
    <div>
      <Typography.Title level={3} style={{ marginTop: 0, marginBottom: 16 }}>
        Thông tin Cửa hàng
      </Typography.Title>
      {store.isError ? <ErrorAlert error={store.error} /> : null}
      {store.isPending ? <Skeleton active /> : null}
      {store.data ? <StoreForm store={store.data} /> : null}
    </div>
  );
}
