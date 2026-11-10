import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useMe } from '../../../shared/api/session';
import type { Me, StoreInfo } from '../../../shared/types/platform';
import { StoreSettingsPage } from './StoreSettingsPage';

const STORE_PATH = '/api/v1/platform/store';
const ME_PATH = '/api/v1/platform/me';

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

const SAVED: StoreInfo = {
  name: 'Tạp hóa Hoa',
  phone: '0912 345 678',
  address: '12 Lê Lợi, Quận 1',
  invoiceFooter: 'Cảm ơn quý khách!',
  bankBin: '970436',
  bankAccountNo: '0011001234567',
  bankAccountName: 'NGUYEN VAN A',
  loyaltyEarnAmount: 10000,
  loyaltyPointValue: 1000,
  expiryWarningDays: 30,
};

let store: StoreInfo;
/** Khi có, `PUT` trả phản hồi này thay vì lưu. */
let putResponse: Response | undefined;
/** `true`: `GET` Thông tin Cửa hàng không bao giờ trả lời (màn chỉ còn dữ liệu trong cache). */
let holdStoreGets: boolean;

/** Backend giả: giữ Thông tin Cửa hàng trong bộ nhớ; `/me` trả tên Cửa hàng đang lưu. */
function backend() {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    if (url.pathname === STORE_PATH && method === 'GET') {
      if (holdStoreGets) return new Promise<Response>(() => {});
      return json(store);
    }
    if (url.pathname === STORE_PATH && method === 'PUT') {
      if (putResponse) return putResponse;
      store = JSON.parse(String(init?.body)) as StoreInfo;
      return json(store);
    }
    if (url.pathname === ME_PATH && method === 'GET') {
      const me: Me = { userId: 'u-1', storeId: 's-1', storeName: store.name, role: 'OWNER', displayName: 'Hoa' };
      return json(me);
    }
    return json({ type: '/errors/common/not-found', title: 'Không tìm thấy', status: 404 }, 404);
  });
}

/** Tên Cửa hàng như ở thanh trên cùng của `AppLayout` (đọc `Me.storeName`). */
function TopBar() {
  const me = useMe();
  return <header aria-label="Thanh trên cùng">{me.data?.storeName}</header>;
}

function newQueryClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
}

function renderPage(queryClient = newQueryClient()) {
  return render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <TopBar />
        <StoreSettingsPage />
      </AntdApp>
    </QueryClientProvider>,
  );
}

function field(label: string): HTMLInputElement {
  return screen.getByLabelText(label);
}

function type(label: string, value: string) {
  fireEvent.change(field(label), { target: { value } });
}

/** Gõ vào ô tìm của ô chọn ngân hàng (danh sách cuộn ảo chỉ vẽ các dòng đầu), rồi bấm lựa chọn. */
async function chooseBank(search: string, option: string) {
  fireEvent.mouseDown(field('Ngân hàng'));
  fireEvent.change(field('Ngân hàng'), { target: { value: search } });
  const found = await waitFor(() => {
    const options = [...document.querySelectorAll(`.ant-select-item-option[title="${option}"]`)];
    if (options.length === 0) throw new Error(`chưa thấy lựa chọn ${option}`);
    return options[options.length - 1];
  });
  fireEvent.click(found);
}

function save() {
  fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
}

/** Chờ hộp xác nhận `title` (antd ghi tiêu đề hai lần) rồi bấm nút `button` của hộp mở sau cùng. */
async function answerConfirm(title: string, button: 'Đổi tài khoản' | 'Tắt chuyển khoản' | 'Không lưu') {
  await screen.findAllByText(title);
  const buttons = screen.getAllByRole('button', { name: button });
  fireEvent.click(buttons[buttons.length - 1]);
}

/** Ô của form (nhãn, ô nhập, lỗi) có nhãn `label`. */
function formItem(label: string): HTMLElement {
  const item = field(label).closest<HTMLElement>('.ant-form-item');
  if (!item) throw new Error(`không thấy ô ${label}`);
  return item;
}

function puts() {
  return fetchMock.mock.calls.filter(
    ([input, init]) => init?.method === 'PUT' && new URL(String(input), 'http://localhost').pathname === STORE_PATH,
  );
}

function bodyOf(call: Parameters<typeof fetch>): unknown {
  return JSON.parse(String(call[1]?.body));
}

async function renderLoaded() {
  renderPage();
  await screen.findByDisplayValue(store.name);
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  store = { ...SAVED };
  putResponse = undefined;
  holdStoreGets = false;
  backend();
});

describe('Thông tin Cửa hàng (FR-48, TK-05)', () => {
  it('hiện thông tin đang lưu, ngân hàng hiện theo tên', async () => {
    await renderLoaded();
    expect(field('Số điện thoại').value).toBe('0912 345 678');
    expect(field('Địa chỉ').value).toBe('12 Lê Lợi, Quận 1');
    expect(field('Chân hóa đơn').value).toBe('Cảm ơn quý khách!');
    expect(screen.getByText('Vietcombank (970436)')).toBeTruthy();
    expect(screen.queryByLabelText('Mã BIN ngân hàng')).toBeNull();
    expect(field('Số tài khoản').value).toBe('0011001234567');
    expect(field('Tên chủ tài khoản').value).toBe('NGUYEN VAN A');
    expect(field('Số tiền để được 1 điểm').value).toBe('10.000');
    expect(field('Giá trị 1 điểm khi đổi').value).toBe('1.000');
    expect(field('Cảnh báo hết hạn trước').value).toBe('30');
  });

  it('lưu toàn bộ trường; tên ở thanh trên cùng đổi theo, không cần đăng nhập lại', async () => {
    await renderLoaded();
    expect(await screen.findByText('Tạp hóa Hoa', { selector: 'header' })).toBeTruthy();

    type('Tên Cửa hàng', '  Tạp hóa Hoa Mới ');
    type('Số tiền để được 1 điểm', '20000');
    expect(field('Số tiền để được 1 điểm').value).toBe('20.000');
    type('Chân hóa đơn', '');
    save();

    expect(await screen.findByText('Đã lưu Thông tin Cửa hàng')).toBeTruthy();
    expect(puts()).toHaveLength(1);
    expect(bodyOf(puts()[0])).toEqual({
      ...SAVED,
      name: 'Tạp hóa Hoa Mới',
      invoiceFooter: null,
      loyaltyEarnAmount: 20000,
    });
    // `PUT` không phải chứng từ: không có Idempotency-Key
    expect(new Headers(puts()[0][1]?.headers).get('Idempotency-Key')).toBeNull();
    expect(await screen.findByText('Tạp hóa Hoa Mới', { selector: 'header' })).toBeTruthy();
    expect(field('Tên Cửa hàng').value).toBe('Tạp hóa Hoa Mới');
  });

  it('tên chủ tài khoản tự đổi sang chữ in hoa không dấu khi gõ', async () => {
    await renderLoaded();
    type('Tên chủ tài khoản', 'Nguyễn  Văn Đức ');
    expect(field('Tên chủ tài khoản').value).toBe('NGUYEN  VAN DUC ');
    save();
    await answerConfirm('Đổi tài khoản nhận chuyển khoản?', 'Đổi tài khoản');
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(bodyOf(puts()[0])).toMatchObject({ bankAccountName: 'NGUYEN VAN DUC' });
  });

  it('thiếu một phần tài khoản ngân hàng thì báo dưới ô thiếu và không gửi', async () => {
    await renderLoaded();
    type('Số tài khoản', '');
    save();
    expect(await screen.findByText('Nhập số tài khoản')).toBeTruthy();

    type('Tên chủ tài khoản', '');
    save();
    expect(await screen.findByText('Nhập tên chủ tài khoản')).toBeTruthy();
    expect(puts()).toHaveLength(0);
  });

  it('để trống cả ba ô ngân hàng thì tắt chuyển khoản', async () => {
    store = { ...SAVED, bankBin: null, bankAccountNo: null, bankAccountName: null };
    await renderLoaded();
    save();
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(bodyOf(puts()[0])).toMatchObject({ bankBin: null, bankAccountNo: null, bankAccountName: null });

    // Chỉ có số tài khoản: phải chọn ngân hàng và nhập tên chủ tài khoản
    type('Số tài khoản', '0011001234567');
    save();
    expect(await screen.findByText('Chọn ngân hàng')).toBeTruthy();
    expect(screen.getByText('Nhập tên chủ tài khoản')).toBeTruthy();
    expect(puts()).toHaveLength(1);
  });

  it('kiểm định dạng ô ngân hàng như backend', async () => {
    await renderLoaded();
    type('Số tài khoản', '12345');
    type('Tên chủ tài khoản', 'NGUYEN VAN A1');
    save();
    expect(await screen.findByText('Số tài khoản gồm 6 đến 19 chữ số')).toBeTruthy();
    expect(screen.getByText('Tên chủ tài khoản chỉ gồm chữ in hoa không dấu và khoảng trắng')).toBeTruthy();

    // Dấu cách trong số tài khoản bị bỏ khi gõ
    type('Số tài khoản', '0011 0012 34567');
    expect(field('Số tài khoản').value).toBe('0011001234567');
    expect(puts()).toHaveLength(0);
  });

  it('chọn "Ngân hàng khác" thì nhập mã BIN 6 chữ số', async () => {
    await renderLoaded();
    await chooseBank('khac', 'Ngân hàng khác (nhập mã BIN)');
    await screen.findByLabelText('Mã BIN ngân hàng');
    type('Mã BIN ngân hàng', '97043');
    save();
    expect(await screen.findByText('Mã ngân hàng (BIN) gồm đúng 6 chữ số')).toBeTruthy();
    expect(puts()).toHaveLength(0);

    type('Mã BIN ngân hàng', '970999');
    save();
    await answerConfirm('Đổi tài khoản nhận chuyển khoản?', 'Đổi tài khoản');
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(bodyOf(puts()[0])).toMatchObject({ bankBin: '970999' });
  });

  it('chọn ngân hàng trong danh sách thì gửi BIN NAPAS của ngân hàng đó', async () => {
    await renderLoaded();
    await chooseBank('bidv', 'BIDV (970418)');
    save();
    await answerConfirm('Đổi tài khoản nhận chuyển khoản?', 'Đổi tài khoản');
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(bodyOf(puts()[0])).toMatchObject({ bankBin: '970418' });
  });

  it('BIN đang lưu không có trong danh sách thì hiện ở ô "Ngân hàng khác"', async () => {
    store = { ...SAVED, bankBin: '970999' };
    await renderLoaded();
    expect(screen.getByText('Ngân hàng khác (nhập mã BIN)')).toBeTruthy();
    expect(field('Mã BIN ngân hàng').value).toBe('970999');
  });

  it('kiểm tên và các ô số trước khi gửi', async () => {
    await renderLoaded();
    type('Tên Cửa hàng', '   ');
    type('Số điện thoại', '0912abc');
    type('Số tiền để được 1 điểm', '0');
    type('Giá trị 1 điểm khi đổi', '1,5');
    type('Cảnh báo hết hạn trước', '366');
    save();
    expect(await screen.findByText('Nhập tên Cửa hàng')).toBeTruthy();
    expect(screen.getByText('Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . -')).toBeTruthy();
    expect(screen.getByText('Số tiền để được 1 điểm phải lớn hơn 0')).toBeTruthy();
    expect(screen.getByText('Giá trị 1 điểm phải là số nguyên')).toBeTruthy();
    expect(screen.getByText('Số ngày cảnh báo hết hạn tối đa 365 ngày')).toBeTruthy();
    expect(puts()).toHaveLength(0);
  });

  it('đổi số tài khoản thì hỏi xác nhận; chọn "Không lưu" thì không gửi', async () => {
    await renderLoaded();
    type('Số tài khoản', '9990001234');
    save();
    await answerConfirm('Đổi tài khoản nhận chuyển khoản?', 'Không lưu');
    expect(screen.getAllByText(/Vietcombank – 9990001234 – NGUYEN VAN A/).length).toBeGreaterThan(0);
    await waitFor(() => expect(puts()).toHaveLength(0));

    save();
    await answerConfirm('Đổi tài khoản nhận chuyển khoản?', 'Đổi tài khoản');
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(bodyOf(puts()[0])).toMatchObject({ bankAccountNo: '9990001234' });
  });

  it('xóa cả ba ô ngân hàng thì hỏi xác nhận tắt chuyển khoản', async () => {
    await renderLoaded();
    const clear = formItem('Ngân hàng').querySelector('.ant-select-clear');
    if (!clear) throw new Error('không thấy nút xóa của ô Ngân hàng');
    fireEvent.click(clear);
    type('Số tài khoản', '');
    type('Tên chủ tài khoản', '');
    save();
    await answerConfirm('Tắt thanh toán chuyển khoản?', 'Không lưu');
    await waitFor(() => expect(puts()).toHaveLength(0));

    save();
    await answerConfirm('Tắt thanh toán chuyển khoản?', 'Tắt chuyển khoản');
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(bodyOf(puts()[0])).toMatchObject({ bankBin: null, bankAccountNo: null, bankAccountName: null });
  });

  it('ô số chỉ nhận chữ số liền hoặc nhóm nghìn đúng; ô số ngày không nhận dấu chấm', async () => {
    await renderLoaded();
    type('Số tiền để được 1 điểm', '1.5');
    expect(field('Số tiền để được 1 điểm').value).toBe('1.5');
    type('Giá trị 1 điểm khi đổi', '1.00.000');
    type('Cảnh báo hết hạn trước', '7.5');
    save();
    expect(await screen.findByText('Số tiền để được 1 điểm phải là số nguyên')).toBeTruthy();
    expect(screen.getByText('Giá trị 1 điểm phải là số nguyên')).toBeTruthy();
    expect(screen.getByText('Số ngày cảnh báo hết hạn phải là số nguyên')).toBeTruthy();

    type('Cảnh báo hết hạn trước', '1.000');
    type('Số tiền để được 1 điểm', '-5');
    save();
    expect(await screen.findByText('Số tiền để được 1 điểm phải lớn hơn 0')).toBeTruthy();
    expect(screen.getAllByText('Số ngày cảnh báo hết hạn phải là số nguyên').length).toBeGreaterThan(0);
    expect(puts()).toHaveLength(0);

    // Gõ số liền thì tự nhóm; gõ thêm một chữ số vào số đã nhóm thì nhóm lại
    type('Số tiền để được 1 điểm', '20.000');
    type('Giá trị 1 điểm khi đổi', '1500');
    expect(field('Giá trị 1 điểm khi đổi').value).toBe('1.500');
    type('Giá trị 1 điểm khi đổi', '1.5000');
    expect(field('Giá trị 1 điểm khi đổi').value).toBe('15.000');
    type('Cảnh báo hết hạn trước', '7');
    save();
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(bodyOf(puts()[0])).toMatchObject({ loyaltyEarnAmount: 20000, loyaltyPointValue: 15000, expiryWarningDays: 7 });
  });

  it('lưu xong mở lại màn thì form hiện giá trị đã lưu ngay từ cache', async () => {
    const queryClient = newQueryClient();
    const view = renderPage(queryClient);
    await screen.findByDisplayValue('Tạp hóa Hoa');
    type('Tên Cửa hàng', 'Tạp hóa Hoa Mới');
    type('Địa chỉ', '34 Trần Phú');
    save();
    expect(await screen.findByText('Đã lưu Thông tin Cửa hàng')).toBeTruthy();
    view.unmount();

    // Máy chủ không trả lời lần tải lại: form chỉ có dữ liệu trong cache
    holdStoreGets = true;
    renderPage(queryClient);
    expect(await screen.findByDisplayValue('Tạp hóa Hoa Mới')).toBeTruthy();
    expect(field('Địa chỉ').value).toBe('34 Trần Phú');
  });

  it('dữ liệu mới về khi form chưa sửa thì form hiện dữ liệu mới', async () => {
    const queryClient = newQueryClient();
    // Cache còn bản cũ; máy chủ đã có bản mới (sửa ở nơi khác)
    queryClient.setQueryData(['platform', 'store'], { ...SAVED, name: 'Tên cũ', address: 'Địa chỉ cũ' });
    renderPage(queryClient);
    await screen.findByDisplayValue('Tên cũ');
    expect(await screen.findByDisplayValue('Tạp hóa Hoa')).toBeTruthy();
    expect(field('Địa chỉ').value).toBe('12 Lê Lợi, Quận 1');
  });

  it('lỗi trên bankBin khi đang chọn "Ngân hàng khác" hiện dưới ô Mã BIN', async () => {
    putResponse = json(
      {
        type: '/errors/common/validation',
        title: 'Dữ liệu không hợp lệ',
        status: 400,
        requestId: 'req-3',
        errors: [{ field: 'bankBin', message: 'Mã ngân hàng (BIN) gồm đúng 6 chữ số' }],
      },
      400,
    );
    await renderLoaded();
    await chooseBank('khac', 'Ngân hàng khác (nhập mã BIN)');
    await screen.findByLabelText('Mã BIN ngân hàng');
    type('Mã BIN ngân hàng', '970 999');
    expect(field('Mã BIN ngân hàng').value).toBe('970999');
    save();
    await answerConfirm('Đổi tài khoản nhận chuyển khoản?', 'Đổi tài khoản');
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(await within(formItem('Mã BIN ngân hàng')).findByText('Mã ngân hàng (BIN) gồm đúng 6 chữ số')).toBeTruthy();
    expect(within(formItem('Ngân hàng')).queryByText('Mã ngân hàng (BIN) gồm đúng 6 chữ số')).toBeNull();
  });

  it('lỗi theo trường của backend hiện dưới ô tương ứng', async () => {
    putResponse = json(
      {
        type: '/errors/common/validation',
        title: 'Dữ liệu không hợp lệ',
        status: 400,
        requestId: 'req-1',
        errors: [
          { field: 'bankAccountName', message: 'Tên chủ tài khoản chỉ gồm chữ in hoa không dấu và khoảng trắng' },
          { field: 'bankBin', message: 'Mã ngân hàng (BIN) gồm đúng 6 chữ số' },
        ],
      },
      400,
    );
    await renderLoaded();
    save();
    expect(await screen.findByText('Tên chủ tài khoản chỉ gồm chữ in hoa không dấu và khoảng trắng')).toBeTruthy();
    expect(screen.getByText('Mã ngân hàng (BIN) gồm đúng 6 chữ số')).toBeTruthy();
    expect(screen.queryByText('Dữ liệu nhập chưa hợp lệ.')).toBeNull();
  });

  it('lỗi khác hiện ở khung báo lỗi phía trên form', async () => {
    putResponse = json(
      { type: '/errors/common/forbidden', title: 'Không có quyền', status: 403, requestId: 'req-2' },
      403,
    );
    await renderLoaded();
    save();
    expect(await screen.findByText('Bạn không có quyền thực hiện thao tác này.')).toBeTruthy();
  });
});
