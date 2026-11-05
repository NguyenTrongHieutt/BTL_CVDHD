/**
 * features/assistant — Trợ lý AI (Hỏi đáp dữ liệu). Chỉ Chủ cửa hàng (PRD 4.7).
 * Người giữ: Lân (AD-20). Route: `/assistant` (không dùng `/ai/…`).
 */
import { OWNER_ONLY } from '../../shared/routing';
import type { FeatureDefinition } from '../../shared/routing';
import { AssistantPage } from './pages/AssistantPage';

export const assistantFeature: FeatureDefinition = {
  module: 'assistant',
  owner: 'Lân',
  routes: [
    {
      path: '/assistant',
      title: 'Trợ lý AI',
      roles: OWNER_ONLY,
      Component: AssistantPage,
      menu: { group: 'assistant', order: 10 },
    },
  ],
};
