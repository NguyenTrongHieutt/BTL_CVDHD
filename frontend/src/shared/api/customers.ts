import { api, isApiError } from './client';
import { pageRequestToQuery } from './pagination';
import type { PageRequest, PageResponse } from './pagination';
import type { Customer, CustomerRequest } from '../types/catalog';
import type { Uuid } from '../types/common';

export const CUSTOMERS_PATH = '/api/v1/catalog/customers';
export const customersQueryKey = ['catalog', 'customers'] as const;
export const CUSTOMER_PHONE_TAKEN = '/errors/catalog/customer-phone-taken';

export function fetchCustomers(
  request: PageRequest,
  q?: string,
  signal?: AbortSignal,
): Promise<PageResponse<Customer>> {
  return api.get<PageResponse<Customer>>(CUSTOMERS_PATH, {
    query: { ...pageRequestToQuery(request), q: q || undefined },
    signal,
  });
}

export function customerPath(id: Uuid): string {
  return `${CUSTOMERS_PATH}/${encodeURIComponent(id)}`;
}

export function fetchCustomer(id: Uuid, signal?: AbortSignal): Promise<Customer> {
  return api.get<Customer>(customerPath(id), { signal });
}

export function createCustomer(request: CustomerRequest, idempotencyKey?: string): Promise<Customer> {
  return api.post<Customer>(CUSTOMERS_PATH, request, { idempotencyKey });
}

export function existingCustomerIdOf(error: unknown): Uuid | null {
  if (!isApiError(error) || error.type !== CUSTOMER_PHONE_TAKEN) return null;
  const id = error.extensions.existingCustomerId;
  return typeof id === 'string' && id !== '' ? id : null;
}
