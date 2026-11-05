/**
 * Kiểu dùng chung cho mọi module (AD-8, AD-19).
 * Người giữ: Anh (thư mục shared/).
 */

/** Tiền: số nguyên, đơn vị đồng (VND). Backend tính, frontend chỉ hiển thị, không tự tính (AD-8). */
export type Money = number;

/** Số lượng: số nguyên. */
export type Quantity = number;

/** Khóa chính dạng UUID. */
export type Uuid = string;

/** Ngày theo lịch Việt Nam, dạng `yyyy-MM-dd`. */
export type IsoDate = string;

/** Thời điểm dạng ISO-8601, backend trả theo UTC. */
export type IsoDateTime = string;

/** Khoảng ngày theo lịch Việt Nam, bao gồm cả hai đầu, tối đa 366 ngày (AD-19). */
export interface DateRange {
  fromDate: IsoDate;
  toDate: IsoDate;
}

/** Module của hệ thống, cũng là đoạn đầu của query key và route (AD-20). */
export type ModuleId = 'platform' | 'catalog' | 'inventory' | 'sales' | 'reporting' | 'assistant';
