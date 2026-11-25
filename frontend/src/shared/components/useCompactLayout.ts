import { useSyncExternalStore } from 'react';

const compactQuery = '(max-width: 767px)';
const isCompact = () => window.matchMedia(compactQuery).matches;

function subscribe(listener: () => void) {
  const media = window.matchMedia(compactQuery);
  media.addEventListener('change', listener);
  return () => media.removeEventListener('change', listener);
}

export function useCompactLayout() {
  return useSyncExternalStore(subscribe, isCompact, () => false);
}
