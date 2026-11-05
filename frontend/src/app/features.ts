/**
 * Danh sách feature ghép vào ứng dụng (AD-20). Người giữ: Anh (khung giao diện, CB-05).
 * Thứ tự ở đây không quyết định thứ tự menu; xem `menu.ts`.
 */
import { assistantFeature } from '../features/assistant';
import { catalogFeature } from '../features/catalog';
import { inventoryFeature } from '../features/inventory';
import { platformFeature } from '../features/platform';
import { reportingFeature } from '../features/reporting';
import { salesFeature } from '../features/sales';
import type { FeatureDefinition } from '../shared/routing';

export const appFeatures: readonly FeatureDefinition[] = [
  platformFeature,
  catalogFeature,
  salesFeature,
  inventoryFeature,
  reportingFeature,
  assistantFeature,
];
