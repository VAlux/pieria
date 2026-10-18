import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode, RefObject } from 'react';
import { createPortal } from 'react-dom';
import { cx, IconButton } from './controls';
import styles from './overlay.module.css';

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** Keeps Tab inside the container, closes on Escape and restores focus to the opener. */
function useModalBehaviour(ref: RefObject<HTMLElement | null>, onClose: () => void, initialFocus?: RefObject<HTMLElement | null>) {
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const node = ref.current;
    const first = initialFocus?.current ?? node?.querySelector<HTMLElement>('[autofocus]') ?? node?.querySelector<HTMLElement>(FOCUSABLE) ?? node;
    first?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        closeRef.current();
        return;
      }
      if (event.key !== 'Tab' || !node) return;
      const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(el => el.offsetParent !== null || el === document.activeElement);
      if (items.length === 0) return;
      const firstItem = items[0];
      const lastItem = items[items.length - 1];
      if (event.shiftKey && document.activeElement === firstItem) { event.preventDefault(); lastItem.focus(); }
      else if (!event.shiftKey && document.activeElement === lastItem) { event.preventDefault(); firstItem.focus(); }
    };
    document.addEventListener('keydown', onKey, true);
    return () => {
      document.removeEventListener('keydown', onKey, true);
      opener?.focus?.();
    };
  }, [ref, initialFocus]);
}

interface DialogProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  hideTitle?: boolean;
  placement?: 'centered' | 'anchoredLeft';
  className?: string;
  initialFocus?: RefObject<HTMLElement | null>;
  showClose?: boolean;
}

export function Dialog({ title, onClose, children, footer, hideTitle, placement = 'centered', className, initialFocus, showClose = true }: DialogProps) {
  const ref = useRef<HTMLElement>(null);
  useModalBehaviour(ref, onClose, initialFocus);
  return createPortal(
    <>
      <div className={styles.scrim} onClick={onClose} aria-hidden="true" />
      <section ref={ref} role="dialog" aria-modal="true" aria-label={title} tabIndex={-1}
        className={cx(styles.dialog, styles[placement], className)}>
        {!hideTitle && (
          <div className={styles.dialogHead}>
            <h2 className={styles.dialogTitle}>{title}</h2>
            {showClose && <IconButton icon="close" label="Close" bare onClick={onClose} />}
          </div>
        )}
        {hideTitle ? children : <div className={styles.dialogBody}>{children}</div>}
        {footer && <div className={styles.dialogFoot}>{footer}</div>}
      </section>
    </>,
    document.body
  );
}

export function Drawer({ title, onClose, children, initialFocus }: { title: string; onClose: () => void; children: ReactNode; initialFocus?: RefObject<HTMLElement | null> }) {
  const ref = useRef<HTMLElement>(null);
  useModalBehaviour(ref, onClose, initialFocus);
  return createPortal(
    <>
      <div className={styles.scrim} onClick={onClose} aria-hidden="true" />
      <section ref={ref} role="dialog" aria-modal="true" aria-label={title} tabIndex={-1} className={styles.drawer}>
        {children}
      </section>
    </>,
    document.body
  );
}

interface PopoverProps {
  anchor: RefObject<HTMLElement | null>;
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  label: string;
  align?: 'start' | 'end';
  role?: 'menu' | 'dialog' | 'listbox';
}

/** Fixed-position panel under its anchor; closes on outside click, Escape, scroll or resize. */
export function Popover({ anchor, open, onClose, children, label, align = 'start', role = 'menu' }: PopoverProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null);

  useLayoutEffect(() => {
    if (!open || !anchor.current) return;
    const rect = anchor.current.getBoundingClientRect();
    const width = ref.current?.offsetWidth ?? 200;
    const left = align === 'end' ? rect.right - width : rect.left;
    setPosition({ top: rect.bottom + 6, left: Math.max(8, Math.min(left, window.innerWidth - width - 8)) });
  }, [open, anchor, align]);

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: MouseEvent) => {
      const target = event.target as Node;
      if (ref.current?.contains(target) || anchor.current?.contains(target)) return;
      onClose();
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.stopPropagation(); onClose(); anchor.current?.focus(); }
    };
    const onScroll = (event: Event) => { if (!ref.current?.contains(event.target as Node)) onClose(); };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey, true);
    window.addEventListener('resize', onClose);
    window.addEventListener('scroll', onScroll, true);
    ref.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus();
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey, true);
      window.removeEventListener('resize', onClose);
      window.removeEventListener('scroll', onScroll, true);
    };
  }, [open, onClose, anchor]);

  if (!open) return null;
  return createPortal(
    <div ref={ref} role={role} aria-label={label} className={styles.popover}
      style={position ? { top: position.top, left: position.left } : { visibility: 'hidden', top: 0, left: 0 }}
      onKeyDown={event => {
        if (role !== 'menu' || (event.key !== 'ArrowDown' && event.key !== 'ArrowUp')) return;
        event.preventDefault();
        const items = Array.from(ref.current?.querySelectorAll<HTMLElement>('[role^="menuitem"]:not([disabled])') ?? []);
        const index = items.indexOf(document.activeElement as HTMLElement);
        const next = event.key === 'ArrowDown' ? (index + 1) % items.length : (index - 1 + items.length) % items.length;
        items[next]?.focus();
      }}>
      {children}
    </div>,
    document.body
  );
}

interface MenuItemProps {
  children: ReactNode;
  onSelect: () => void;
  checked?: boolean;
  danger?: boolean;
  detail?: ReactNode;
  disabled?: boolean;
  title?: string;
}

export function MenuItem({ children, onSelect, checked, danger, detail, disabled, title }: MenuItemProps) {
  return (
    <button type="button" role={checked === undefined ? 'menuitem' : 'menuitemradio'} aria-checked={checked}
      className={cx(styles.menuItem, danger && styles.danger)} onClick={onSelect} disabled={disabled} title={title}>
      <span>{children}</span>
      {detail !== undefined && <span className={styles.menuDetail}>{detail}</span>}
    </button>
  );
}

export function MenuSeparator() {
  return <div role="separator" className={styles.menuSeparator} />;
}

export function MenuHeading({ children }: { children: ReactNode }) {
  return <div className={styles.menuHeading}>{children}</div>;
}

/** Popover state bound to an anchor element. */
export function usePopover<T extends HTMLElement = HTMLButtonElement>() {
  const anchor = useRef<T>(null);
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  const toggle = useCallback(() => setOpen(o => !o), []);
  return { anchor, open, close, toggle, setOpen };
}

/**
 * Wraps a control the canvas draws but the daemon can't serve right now (or in this setup).
 * The child should already be disabled; the reason shows on hover and focus.
 */
export function DisabledHint({ reason, children }: { reason: string; children: ReactNode }) {
  return (
    <span className={styles.hint} tabIndex={0} aria-label={reason}>
      {children}
      <span role="tooltip" className={styles.tip}>{reason}</span>
    </span>
  );
}

export function Tooltip({ text, children }: { text: string; children: ReactNode }) {
  return (
    <span className={styles.hint}>
      {children}
      <span role="tooltip" className={styles.tip}>{text}</span>
    </span>
  );
}

/* ---------- Toasts ---------- */

interface ToastMessage {
  id: number;
  title: string;
  detail?: string;
  tone: 'info' | 'error';
}

interface ToastApi {
  show: (title: string, detail?: string, tone?: 'info' | 'error') => void;
}

const ToastContext = createContext<ToastApi>({ show: () => undefined });

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastMessage[]>([]);
  const nextId = useRef(1);
  const show = useCallback((title: string, detail?: string, tone: 'info' | 'error' = 'info') => {
    const id = nextId.current++;
    setItems(current => [...current, { id, title, detail, tone }]);
    setTimeout(() => setItems(current => current.filter(item => item.id !== id)), tone === 'error' ? 8000 : 4000);
  }, []);
  const api = useMemo(() => ({ show }), [show]);
  return (
    <ToastContext.Provider value={api}>
      {children}
      {createPortal(
        <div className={styles.toastStack} role="status" aria-live="polite">
          {items.map(item => (
            <div key={item.id} className={cx(styles.toast, item.tone === 'error' && styles.error)}>
              <span><strong>{item.title}</strong>{item.detail}</span>
            </div>
          ))}
        </div>,
        document.body
      )}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  return useContext(ToastContext);
}
