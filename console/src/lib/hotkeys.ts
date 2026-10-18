import { useEffect, useRef } from 'react';

export const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

/** Label for the primary modifier: ⌘ on macOS, Ctrl elsewhere. */
export const mod = isMac ? '⌘' : 'Ctrl+';

export interface Combo {
  key: string;
  /** ⌘ on macOS, Ctrl elsewhere. */
  mod?: boolean;
  shift?: boolean;
  alt?: boolean;
}

/** Parses "mod+shift+p", "/", "j", "Escape". */
export function parseCombo(text: string): Combo {
  const parts = text.split('+');
  const key = parts.pop() ?? '';
  return {
    key,
    mod: parts.includes('mod'),
    shift: parts.includes('shift'),
    alt: parts.includes('alt')
  };
}

export function matches(event: KeyboardEvent, combo: Combo): boolean {
  const modPressed = isMac ? event.metaKey : event.ctrlKey;
  if (Boolean(combo.mod) !== modPressed) return false;
  if (Boolean(combo.shift) !== event.shiftKey) return false;
  if (Boolean(combo.alt) !== event.altKey) return false;
  return event.key.toLowerCase() === combo.key.toLowerCase();
}

export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  const tag = target.tagName;
  if (tag === 'TEXTAREA' || tag === 'SELECT') return true;
  if (tag === 'INPUT') {
    const type = (target as HTMLInputElement).type;
    return !['checkbox', 'radio', 'button', 'submit', 'range'].includes(type);
  }
  return false;
}

interface HotkeyOptions {
  enabled?: boolean;
  /** Fire even while focus is in a text field (default only for modifier combos). */
  allowInInputs?: boolean;
}

/** Binds a key combo on window. Plain-key shortcuts are ignored while the user is typing. */
export function useHotkey(combo: string, handler: (event: KeyboardEvent) => void, options: HotkeyOptions = {}): void {
  const handlerRef = useRef(handler);
  handlerRef.current = handler;
  const { enabled = true } = options;
  const parsed = parseCombo(combo);
  const allowInInputs = options.allowInInputs ?? Boolean(parsed.mod);
  useEffect(() => {
    if (!enabled) return;
    const listener = (event: KeyboardEvent) => {
      if (event.defaultPrevented) return;
      if (!allowInInputs && isTypingTarget(event.target)) return;
      if (!matches(event, parsed)) return;
      event.preventDefault();
      handlerRef.current(event);
    };
    window.addEventListener('keydown', listener);
    return () => window.removeEventListener('keydown', listener);
  }, [enabled, allowInInputs, combo]);
}
