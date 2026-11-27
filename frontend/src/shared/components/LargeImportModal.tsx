import { Alert, Button, Flex, Modal, Progress, Switch, Table, Typography } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState } from 'react';
import type { ComponentType, ReactNode } from 'react';
import { isApiError, newIdempotencyKey } from '../api/client';
import {
  importUploadApi, sendMissingParts,
} from '../api/multipartImport';
import type {
  AcceptedJob, ImportCapabilities, ImportEdit, ImportKind, LargeImportError, LargeImportRow, UploadSession,
} from '../api/multipartImport';

interface Options {
  supplierId?: string;
  note: string;
  controls: ReactNode;
  editor: ComponentType<LargeImportEditorProps>;
}

export interface LargeImportEditorProps {
  row: LargeImportRow;
  errors: LargeImportError[];
  busy: boolean;
  onCancel: () => void;
  onApply: (edit: ImportEdit) => void;
}

interface Props<T> {
  kind: ImportKind;
  capabilities: ImportCapabilities;
  open?: boolean;
  onClose: () => void;
  onSaved: (result: T) => void;
  receipt?: Options;
  templates: ReactNode;
}
type Submission = { revision: number; operation: 'save' | 'recheck' | ImportEdit };

function detail(error: unknown) {
  if (isApiError(error)) return error.detail || error.title || 'Thao tác thất bại. Hãy thử lại.';
  if (error instanceof Error) return error.message;
  return 'Thao tác thất bại. Hãy thử lại.';
}

const phaseLabel = {
  checking: 'Đang kiểm tra file',
  uploading: 'Đang tải file',
  completing: 'Đang ghép các phần',
  previewing: 'Đang kiểm tra dữ liệu',
};
const size = (bytes: number) => new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 }).format(bytes / 1048576);
const prettyDate = (date: string) => new Intl.DateTimeFormat('vi-VN', {
  dateStyle: 'short', timeStyle: 'short',
}).format(new Date(date));
export function LargeImportModal<T>({ kind, capabilities, onClose, onSaved, receipt, templates, open = true }: Props<T>) {
  const query = useQueryClient();
  const fileInputId = useId();
  const progressId = useId();
  const [local, setLocal] = useState<UploadSession>();
  const [file, setFile] = useState<File>();
  const [error, setError] = useState<string>();
  const [phase, setPhase] = useState<keyof typeof phaseLabel>('checking');
  const [progress, setProgress] = useState(0);
  const [watch, setWatch] = useState<AcceptedJob>();
  const [page, setPage] = useState(0);
  const [onlyErrors, setOnlyErrors] = useState(false);
  const [edit, setEdit] = useState<LargeImportRow>();
  const controller = useRef<AbortController | undefined>(undefined);
  const creation = useRef<{ file: File; key: string } | undefined>(undefined);
  const delivered = useRef('');
  const sessionsKey = ['import-uploads', kind];
  const sessions = useQuery({ queryKey: sessionsKey, queryFn: () => importUploadApi.list(kind), enabled: open });
  const id = local?.uploadId;
  const jobKey = ['import-jobs', kind, id];
  const jobQuery = useQuery({
    queryKey: jobKey,
    queryFn: () => importUploadApi.job<T>(kind, id!),
    enabled: Boolean(id && local && local.status !== 'OPEN' && local.status !== 'CREATING'),
    retry: false,
  });
  const job = jobQuery.data;
  const tracked = watch?.statusUrl ?? (job?.state === 'SAVED' ? undefined : job?.saveStatusUrl ?? job?.statusUrl);
  const status = useQuery({
    queryKey: ['import-job-request', tracked],
    queryFn: () => importUploadApi.command(tracked!),
    enabled: Boolean(tracked),
    refetchInterval: (q) => q.state.data?.status === 'PENDING' || !q.state.data ? 1000 : false,
    retry: false,
  });
  const statusValue = status.data?.status;
  const pendingJob = Boolean(tracked && (statusValue === 'PENDING' || (!statusValue && !status.isError)));
  const preview = useQuery({
    queryKey: [...jobKey, 'rows', job?.revision, page, onlyErrors],
    queryFn: () => importUploadApi.rows(kind, id!, page, 20, onlyErrors),
    enabled: job?.state === 'READY',
    retry: false,
  });

  useEffect(() => () => controller.current?.abort(), []);
  useEffect(() => {
    if (statusValue === 'SUCCEEDED' || statusValue === 'FAILED') {
      void query.invalidateQueries({ queryKey: ['import-jobs', kind, id] });
    }
  }, [statusValue, query, kind, id]);
  useEffect(() => {
    if (job?.state === 'SAVED' && job.result && delivered.current !== job.jobId) {
      delivered.current = job.jobId;
      onSaved(job.result);
      void query.invalidateQueries({ queryKey: ['import-uploads', kind] });
    }
  }, [job, kind, onSaved, query]);

  function chooseFile(picked?: File) {
    setError(undefined);
    if (picked && (!/\.(csv|xlsx)$/i.test(picked.name) || picked.size < 1)) {
      setFile(undefined);
      setError('Chọn file CSV hoặc Excel .xlsx có dữ liệu.');
    } else if (picked && picked.size > capabilities.maxFileBytes) {
      setFile(undefined);
      setError('File tối đa ' + size(capabilities.maxFileBytes) + ' MiB.');
    } else {
      setFile(picked);
    }
  }

  function select(session: UploadSession) {
    if (session.uploadId === id) return;
    setLocal(session);
    setFile(undefined);
    setError(undefined);
    setWatch(undefined);
    setProgress(0);
    setPage(0);
    setOnlyErrors(false);
  }

  const start = useMutation({
    mutationFn: async () => {
      const supplierId = job?.supplierId ?? receipt?.supplierId;
      if (receipt && !supplierId) throw new Error('Chọn nhà cung cấp để xem trước phiếu nhập.');
      let session = local;
      if (!session || session.status === 'CREATING') {
        if (!file) throw new Error('Chọn file để tải lên.');
        if (creation.current?.file !== file) creation.current = { file, key: newIdempotencyKey() };
        session = await importUploadApi.create(kind, file, creation.current.key);
        setLocal(session);
      }
      if (session.status === 'OPEN') {
        if (!file) throw new Error('Chọn lại file gốc để tiếp tục tải.');
        controller.current?.abort();
        controller.current = new AbortController();
        session = await sendMissingParts(session, file, capabilities.maxParallelParts, controller.current.signal,
          (bytes) => setProgress(bytes), setPhase);
        setLocal(session);
      }
      setPhase('previewing');
      return importUploadApi.start(kind, session.uploadId, supplierId
        ? { supplierId, note: job?.note ?? receipt?.note ?? '' } : undefined);
    },
    onSuccess: (accepted) => { setWatch(accepted); setFile(undefined); },
    onError: (failure) => setError(detail(failure)),
    onSettled: () => { void query.invalidateQueries({ queryKey: sessionsKey }); },
  });

  const cancel = useMutation({
    mutationFn: () => importUploadApi.cancel(id!),
    onSuccess: () => {
      setLocal(undefined);
      setFile(undefined);
      setProgress(0);
      creation.current = undefined;
      void query.invalidateQueries({ queryKey: sessionsKey });
    },
    onError: (failure) => setError(detail(failure)),
  });
  const submit = useMutation({
    mutationFn: ({ operation, revision }: Submission) => {
      if (!id) throw new Error('Bản xem trước chưa sẵn sàng.');
      return operation === 'save' ? importUploadApi.confirm(kind, id, revision)
        : importUploadApi.recheck(kind, id, revision, operation === 'recheck' ? [] : [operation]);
    },
    onSuccess: (accepted) => {
      setWatch(accepted);
      setEdit(undefined);
      setPage(0);
    },
    onError: (failure) => setError(detail(failure)),
  });

  const work = start.isPending || submit.isPending || pendingJob;
  const commandProblem = statusValue === 'FAILED' ? status.data?.problem : undefined;
  const hasSessionFile = !!local && !['OPEN', 'CREATING'].includes(local.status);
  const stale = commandProblem?.type?.endsWith('preview-stale');
  const ready = job?.state === 'READY';
  const canSave = ready && !work && !stale && (receipt ? job.summary?.errorCount === 0
    : (job.summary?.validProducts ?? 0) > 0);

  function sendJob(operation: Submission['operation']) {
    if (!job) return;
    setError(undefined);
    submit.mutate({ operation, revision: job.revision });
  }

  function handleClose() {
    controller.current?.abort();
    onClose();
  }

  function resetSelection() {
    setLocal(undefined);
    setFile(undefined);
    setWatch(undefined);
    setError(undefined);
    setProgress(0);
    creation.current = undefined;
  }

  const columns = [
    { title: 'Dòng', dataIndex: 'row', width: 68 },
    { title: 'SKU', dataIndex: 'sku', render: (value?: string) => value || 'Tự sinh hoặc chưa nhập' },
    { title: 'Sản phẩm', dataIndex: 'productName' },
    { title: 'Mã cha', dataIndex: 'parentCode' },
    ...(receipt ? [{ title: 'Số lượng', dataIndex: 'quantity' }] : []),
    {
      title: 'Kết quả', key: 'status', render: (_: unknown, row: LargeImportRow) => <Flex vertical gap={4}>
        <Typography.Text type={row.valid ? undefined : 'danger'}>
          {row.valid ? ({ EXISTING: 'SKU đã có', NEW_VARIANT: 'Biến thể mới', NEW_PRODUCT: 'Sản phẩm mới' }[row.action ?? '']
            ?? 'Hợp lệ') : 'Có lỗi'}
        </Typography.Text>
        {preview.data?.errors.filter(entry => entry.row === row.row).map((entry, index) => (
          <Typography.Text type="danger" key={index}>{entry.message}</Typography.Text>
        ))}
        {receipt && <Button type="link" disabled={work} onClick={() => setEdit(row)}>Sửa dòng {row.row}</Button>}
      </Flex>,
    },
  ];

  return (
    <Modal open={open} width={ready ? 920 : 630}
      title={receipt ? 'Nhập kho từ file' : 'Nhập sản phẩm từ file'}
      onCancel={handleClose} mask={{ closable: false }}
      footer={[
        <Button key="close" onClick={handleClose}>Đóng</Button>,
        ready && !edit && <Button key="refresh" disabled={work} onClick={() => sendJob('recheck')}>
          Kiểm tra lại
        </Button>,
        ready ? <Button key="save" type="primary" loading={submit.isPending || (pendingJob && watch?.state === 'SAVING')}
          disabled={!canSave || Boolean(edit)} onClick={() => sendJob('save')}>
          {receipt ? 'Xác nhận nhập kho' : 'Lưu sản phẩm'}
        </Button> : job?.state === 'SAVED' ? null : <Button key="next" type="primary" loading={start.isPending}
          disabled={work || (!file && !hasSessionFile) || Boolean(receipt && !receipt.supplierId && !job?.supplierId)}
          onClick={() => { setError(undefined); start.mutate(); }}>
          {local?.status === 'OPEN' ? 'Tiếp tục tải' : 'Xem trước'}
        </Button>,
      ]}
    >
      <Flex vertical gap={14}>
        {error && <Alert type="error" role="alert" showIcon title={error} />}
        {commandProblem && <Alert type="error" role="alert" showIcon
          title={commandProblem.detail || commandProblem.title || 'Worker chưa xử lý được yêu cầu.'} />}
        {status.isError && <Alert type="warning" showIcon title="Tạm thời chưa đọc được trạng thái."
          action={<Button onClick={() => void status.refetch()}>Thử lại</Button>} />}
        {pendingJob && job?.state === 'QUEUED' && !start.isPending && <Button onClick={() => start.mutate()}>
          Gửi lại yêu cầu xem trước
        </Button>}
        {pendingJob && ready && job?.saveStatusUrl === tracked && !submit.isPending && <Button onClick={() =>
          sendJob('save')}>Gửi lại yêu cầu lưu</Button>}
        {pendingJob && ready && watch?.state === 'QUEUED' && submit.variables
          && submit.variables.operation !== 'save' && !submit.isPending && <Button onClick={() => {
          if (submit.variables) submit.mutate(submit.variables);
        }}>Gửi lại yêu cầu kiểm tra</Button>}
        {job?.state === 'SAVED' && <Alert type="success" showIcon
          title={receipt ? 'Đã lưu phiếu nhập và cập nhật tồn kho.' : 'Đã lưu sản phẩm.'}
          action={<Button onClick={resetSelection}>Nhập file khác</Button>} />}
        {!ready && job?.state !== 'SAVED' && <>
          {receipt && <fieldset disabled={work || Boolean(job)} style={{ border: 0, padding: 0, margin: 0 }}>
            {receipt.controls}
          </fieldset>}
          {!local && <>
            <Flex wrap gap={8}>{templates}</Flex>
            {sessions.isError && <Alert type="warning" showIcon title="Chưa tải được danh sách phiên cũ."
              action={<Button onClick={() => void sessions.refetch()}>Thử lại</Button>} />}
            {!!sessions.data?.length && <Flex vertical gap={6}>
              <Typography.Text strong>Lần nhập gần đây</Typography.Text>
              {sessions.data.map(session => <Flex key={session.uploadId} wrap gap={8} align="center">
                <Button onClick={() => select(session)}>{session.status === 'OPEN' ? 'Tiếp tục tải' : 'Mở lần nhập'}</Button>
                <Typography.Text>{session.fileName} · {size(session.sizeBytes)} MiB</Typography.Text>
              </Flex>)}
            </Flex>}
          </>}
          {local && <Flex gap={10} wrap align="center">
            <Typography.Text strong>{local.fileName}</Typography.Text>
            <Typography.Text type="secondary">Đến {prettyDate(local.expiresAt)}</Typography.Text>
            {!work && <Button onClick={resetSelection}>Quay lại</Button>}
            {!job && <Button danger disabled={work} loading={cancel.isPending} onClick={() => cancel.mutate()}>
              Hủy lần tải
            </Button>}
          </Flex>}
          {!hasSessionFile && <Flex vertical gap={5}>
            <label htmlFor={fileInputId}>{local ? 'Chọn lại file gốc' : 'Chọn file CSV hoặc Excel'}</label>
            <input id={fileInputId} type="file" accept=".csv,.xlsx" disabled={work}
              onChange={(event) => chooseFile(event.target.files?.[0])} />
            <Typography.Text type="secondary">Tối đa {size(capabilities.maxFileBytes)} MiB.</Typography.Text>
          </Flex>}
          {(start.isPending || progress > 0) && <Flex vertical gap={4}>
            <Typography.Text id={progressId} role="status">
              {phaseLabel[phase]} · {size(progress)}/{size(local?.sizeBytes ?? file?.size ?? 0)} MiB
            </Typography.Text>
            <Progress aria-labelledby={progressId}
              percent={Math.floor(100 * progress / (local?.sizeBytes || file?.size || 1))} />
          </Flex>}
        </>}
        {pendingJob && <Typography.Text role="status">
          {watch?.state === 'SAVING' || job?.saveStatusUrl === tracked ? 'Đang lưu dữ liệu…' : 'Đang kiểm tra dữ liệu…'}
        </Typography.Text>}
        {ready && <>
          <Typography.Text strong>
            {job.summary?.totalRows ?? 0} dòng · {job.summary?.errorCount ?? 0} lỗi
            {receipt ? ` · ${job.summary?.existingRows ?? 0} SKU đã có`
              : ` · ${job.summary?.validProducts ?? 0} sản phẩm hợp lệ`}
          </Typography.Text>
          {edit && receipt ? <receipt.editor row={edit}
            errors={preview.data?.errors.filter(entry => entry.row === edit.row) ?? []}
            busy={work} onCancel={() => setEdit(undefined)}
            onApply={sendJob} /> : <>
            <label><Switch checked={onlyErrors} onChange={value => { setOnlyErrors(value); setPage(0); }}
              aria-label="Chỉ xem dòng lỗi" /> Chỉ xem dòng lỗi</label>
            {preview.isError && <Alert type="error" title={detail(preview.error)} />}
            <Table rowKey="row" size="small" dataSource={preview.data?.items ?? []} columns={columns}
              loading={preview.isFetching} scroll={{ x: 540 }}
              pagination={{ current: page + 1, pageSize: 20, showSizeChanger: false, total: preview.data?.total ?? 0,
                onChange: number => setPage(number - 1) }} />
          </>}
        </>}
      </Flex>
    </Modal>
  );
}
