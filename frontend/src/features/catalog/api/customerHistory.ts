import { api } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/pagination';
import type { IsoDateTime, Money, Uuid } from '../../../shared/types/common';
import type { OrderStatus } from '../../../shared/types/sales';

export interface CustomerOrder {
  orderId: Uuid;
  orderCode: string;
  createdAt: IsoDateTime;
  status: OrderStatus;
  total: Money;
  sellerName: string;
}

interface PurchaseSummary {
  orderCount: number;
  totalSpent: Money;
  lastPurchaseAt: IsoDateTime | null;
}

export function fetchPurchaseSummary(customerId: Uuid, signal: AbortSignal) {
  return api.get<PurchaseSummary>(`/api/v1/sales/customers/${encodeURIComponent(customerId)}/purchase-summary`, { signal });
}

export function fetchCustomerOrders(customerId: Uuid, page: number, size: number, signal: AbortSignal) {
  return api.get<PageResponse<CustomerOrder>>('/api/v1/sales/orders', { query: { customerId, page, size }, signal });
}
