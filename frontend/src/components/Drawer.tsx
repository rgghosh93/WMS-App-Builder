import { useEffect, useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';

interface DrawerProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  /** A wider panel, for forms with repeating sections. */
  wide?: boolean;
}

/** A side panel over the page, used for create/edit forms. Closes on Escape or backdrop click. */
export function Drawer({ title, onClose, children, footer, wide }: DrawerProps) {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  // Runs once per opening: focus the first field, close on Escape, restore focus afterwards.
  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null;
    panelRef.current?.querySelector<HTMLElement>('input, select, textarea')?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCloseRef.current();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      previouslyFocused?.focus?.();
    };
  }, []);

  return (
    <div className="drawer-layer">
      <div className="drawer-backdrop" onClick={onClose} aria-hidden="true" />
      <div className={`drawer${wide ? ' drawer--wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby={titleId} ref={panelRef}>
        <header className="drawer__header">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="icon-button" aria-label="Close" onClick={onClose}>
            <X size={18} aria-hidden="true" />
          </button>
        </header>
        <div className="drawer__body">{children}</div>
        {footer && <footer className="drawer__footer">{footer}</footer>}
      </div>
    </div>
  );
}
