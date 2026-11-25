/**
 * Danh mục (FR-4, SP-04). Người giữ: Hiếu — features/catalog (AD-20). Chỉ Chủ cửa hàng (route `OWNER_ONLY`).
 *
 * Bảng Danh mục (sắp theo tên không dấu, kèm số Sản phẩm) có phân trang phía server. Thêm và đổi tên trong hộp
 * thoại; xóa có xác nhận. Thao tác xong bảng tải lại ngay (invalidate `['catalog', 'categories']`). Trùng tên
 * (`409 category-name-taken`) báo ngay dưới ô tên; xóa Danh mục còn Sản phẩm (`409 category-in-use`) báo ở khung
 * lỗi đóng được phía trên bảng, bằng câu tiếng Việt của `messages.vi.ts`. Nút "Xóa" tắt khi Danh mục còn Sản phẩm;
 * backend vẫn kiểm lại.
 *
 * Không dùng `DataTable` dùng chung: màn này cần tự lùi một trang khi vừa xóa dòng cuối cùng của một trang sau
 * trang đầu, nên tự giữ trang hiện tại.
 */
import { PlusOutlined } from '@ant-design/icons';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { App, Button, Flex, Form, Input, Modal, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import { isApiError } from '../../../shared/api/client';
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/pagination';
import type { PageRequest } from '../../../shared/api/pagination';
import { ErrorAlert, RecordTable, fromAntdChange, toAntdPagination, useConfirm, useDocumentTitle } from '../../../shared/components';
import { PROBLEM_MESSAGES } from '../../../shared/messages.vi';
import type { Category } from '../../../shared/types/catalog';
import {
  categoriesQueryKey,
  fetchCategories,
  normalizeCategoryName,
  useCreateCategory,
  useDeleteCategory,
  useRenameCategory,
} from '../api/categories';
import { useCatalogDialog } from '../components/useCatalogDialog';

const NAME_MAX = 100;
const NAME_TAKEN = '/errors/catalog/category-name-taken';
const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

type Editing = { mode: 'create' } | { mode: 'rename'; category: Category };

interface CategoryFormValues {
  name: string;
}

/** Lỗi của backend hiện được dưới ô tên (trùng tên, hoặc `errors[]` trên trường `name`); `null` nếu không. */
function nameErrorsOf(error: unknown): string[] | null {
  if (!isApiError(error)) return null;
  if (error.type === NAME_TAKEN) {
    const { message, hint } = PROBLEM_MESSAGES[NAME_TAKEN];
    return hint ? [message, hint] : [message];
  }
  const messages = error.errors.filter((e) => e.field === 'name').map((e) => e.message);
  return messages.length > 0 && messages.length === error.errors.length ? messages : null;
}

function CategoryFormModal({ editing, onClose }: { editing: Editing; onClose: () => void }) {
  const [form] = Form.useForm<CategoryFormValues>();
  const { initialInput, close, modalProps } = useCatalogDialog(onClose);
  const { message } = App.useApp();
  const create = useCreateCategory();
  const rename = useRenameCategory();
  const mutation = editing.mode === 'create' ? create : rename;
  // Đang gửi thì không cho đóng hộp thoại: đóng sẽ bỏ mất callback của yêu cầu đang chạy.
  const pending = mutation.isPending;

  const showNameErrors = (error: unknown) => {
    const errors = nameErrorsOf(error);
    if (errors) form.setFields([{ name: 'name', errors }]);
  };

  const onFinish = ({ name }: CategoryFormValues) => {
    if (editing.mode === 'create') {
      create.mutate(
        { name },
        {
          onSuccess: (category) => {
            void message.success(`Đã thêm Danh mục "${category.name}"`);
            close();
          },
          onError: showNameErrors,
        },
      );
      return;
    }
    if (normalizeCategoryName(name) === editing.category.name) {
      close();
      return;
    }
    rename.mutate(
      { id: editing.category.id, name },
      {
        onSuccess: (category) => {
          void message.success(`Đã đổi tên Danh mục thành "${category.name}"`);
          close();
        },
        onError: showNameErrors,
      },
    );
  };

  return (
    <Modal
      {...modalProps}
      title={editing.mode === 'create' ? 'Thêm Danh mục' : 'Đổi tên Danh mục'}
      okText={editing.mode === 'create' ? 'Thêm' : 'Lưu'}
      cancelText="Hủy"
      onOk={() => form.submit()}
      onCancel={() => {
        if (!pending) close();
      }}
      confirmLoading={pending}
      cancelButtonProps={{ disabled: pending }}
      closable={!pending}
      keyboard={!pending}
      mask={{ closable: false }}
    >
      {mutation.isError && !nameErrorsOf(mutation.error) ? <ErrorAlert error={mutation.error} /> : null}
      <Form<CategoryFormValues>
        form={form}
        layout="vertical"
        onFinish={onFinish}
        scrollToFirstError={{ focus: true }}
        disabled={pending}
        initialValues={{ name: editing.mode === 'rename' ? editing.category.name : '' }}
      >
        <Form.Item
          name="name"
          label="Tên Danh mục"
          rules={[
            { required: true, whitespace: true, message: 'Nhập tên Danh mục' },
            {
              // Đếm như backend: sau khi chuẩn NFC (chữ có dấu gõ dạng tổ hợp là một ký tự) và cắt khoảng trắng.
              transform: (value?: string) => (value === undefined ? undefined : normalizeCategoryName(value)),
              max: NAME_MAX,
              message: `Tên Danh mục tối đa ${NAME_MAX} ký tự`,
            },
          ]}
        >
          <Input ref={initialInput} autoComplete="off" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

export function CategoriesPage() {
  useDocumentTitle('Danh mục');
  const [editing, setEditing] = useState<Editing | null>(null);
  const [request, setRequest] = useState<PageRequest>({ page: 0, size: DEFAULT_PAGE_SIZE });
  const list = useQuery({
    queryKey: [...categoriesQueryKey, { page: request.page, size: request.size }],
    queryFn: ({ signal }) => fetchCategories(request, signal),
    placeholderData: keepPreviousData,
  });
  const remove = useDeleteCategory();
  const confirm = useConfirm();
  const { message } = App.useApp();

  const openForm = (next: Editing) => {
    remove.reset();
    setEditing(next);
  };

  const onDelete = async (category: Category) => {
    const ok = await confirm({
      title: `Xóa Danh mục "${category.name}"?`,
      content: 'Chỉ xóa được Danh mục không còn Sản phẩm nào.',
      okText: 'Xóa',
      danger: true,
    });
    if (!ok) return;
    const lastRowOfLaterPage = request.page > 0 && list.data?.items.length === 1;
    remove.mutate(category.id, {
      onSuccess: () => {
        void message.success(`Đã xóa Danh mục "${category.name}"`);
        if (lastRowOfLaterPage) setRequest((current) => ({ ...current, page: Math.max(0, current.page - 1) }));
      },
    });
  };

  const columns: TableColumnsType<Category> = [
    { title: 'Tên Danh mục', dataIndex: 'name', key: 'name' },
    {
      title: 'Số Sản phẩm',
      dataIndex: 'productCount',
      key: 'productCount',
      align: 'right',
      width: 140,
      render: (count: number) => count.toLocaleString('vi-VN'),
    },
    {
      title: 'Thao tác',
      key: 'actions',
      width: 180,
      render: (_, category) => {
        const inUse = category.productCount > 0;
        const reasonId = `category-delete-reason-${category.id}`;
        return (
          <Flex vertical align="flex-start" gap={4}>
            <Flex gap={8} wrap>
              <Button
                type="link"
                size="small"
                aria-label={`Đổi tên ${category.name}`}
                onClick={() => openForm({ mode: 'rename', category })}
              >
                Đổi tên
              </Button>
              <Button
                type="link"
                size="small"
                danger
                aria-label={`Xóa ${category.name}`}
                aria-describedby={inUse ? reasonId : undefined}
                disabled={inUse || remove.isPending}
                loading={remove.isPending && remove.variables === category.id}
                onClick={() => void onDelete(category)}
              >
                Xóa
              </Button>
            </Flex>
            {inUse ? (
              <Typography.Text id={reasonId} type="secondary">Còn Sản phẩm nên chưa thể xóa.</Typography.Text>
            ) : null}
          </Flex>
        );
      },
    },
  ];

  return (
    <div className="catalog-page">
      <Flex justify="space-between" align="center" wrap gap={8} style={{ marginBottom: 16 }}>
        <Typography.Title level={1} style={{ margin: 0 }}>
          Danh mục
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openForm({ mode: 'create' })}>
          Thêm Danh mục
        </Button>
      </Flex>
      {remove.isError ? <ErrorAlert error={remove.error} onClose={() => remove.reset()} /> : null}
      {list.isError ? <ErrorAlert error={list.error} onRetry={() => void list.refetch()} retrying={list.isFetching} /> : null}
      {!list.isError || list.data ? (
        <RecordTable<Category>
          compactColumns={{ title: 'name', actions: 'actions' }}
          rowKey="id"
          columns={columns}
          dataSource={list.data?.items ?? []}
          loading={list.isFetching}
          scroll={{ x: 420 }}
          locale={{ emptyText: list.isPending ? 'Đang tải…' : 'Chưa có Danh mục nào. Bấm "Thêm Danh mục" để tạo.' }}
          pagination={{
            ...toAntdPagination(list.data, request),
            showSizeChanger: true,
            pageSizeOptions: PAGE_SIZE_OPTIONS,
            showTotal: (total) => `Tổng ${total.toLocaleString('vi-VN')} dòng`,
          }}
          onChange={(pagination, _filters, sorter) => setRequest((current) => fromAntdChange(pagination, sorter, current))}
        />
      ) : null}
      {editing ? <CategoryFormModal editing={editing} onClose={() => setEditing(null)} /> : null}
    </div>
  );
}
