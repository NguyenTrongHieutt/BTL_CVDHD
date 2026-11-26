import { skipToken, useQuery } from '@tanstack/react-query';
import type { Uuid } from '../types/common';
import type { CustomerLoyalty } from '../types/sales';
import { api } from './client';

export const customerLoyaltyKey = ['sales', 'loyalty'] as const;
export const MAX_LOYALTY_POINTS = 2_147_483_647;

export function useCustomerLoyalty(customerId: Uuid | undefined) {
  return useQuery({
    queryKey: [...customerLoyaltyKey, customerId],
    queryFn: customerId ? ({ signal }) => api.get<CustomerLoyalty>(
      `/api/v1/sales/customers/${customerId}/loyalty`, { signal },
    ) : skipToken,
    retry: false,
  });
}
