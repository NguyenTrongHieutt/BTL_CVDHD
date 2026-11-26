import type { InputRef } from 'antd';
import { useRef, useState } from 'react';

/** Keep the dialog mounted until Ant Design has restored focus to its opener. */
export function useCatalogDialog(onClose: () => void) {
  const [open, setOpen] = useState(true);
  const initialInput = useRef<InputRef>(null);

  return {
    initialInput,
    close: () => setOpen(false),
    modalProps: {
      open,
      rootClassName: 'catalog-dialog',
      afterOpenChange: (visible: boolean) => {
        if (visible) initialInput.current?.focus();
        else onClose();
      },
    },
  };
}
