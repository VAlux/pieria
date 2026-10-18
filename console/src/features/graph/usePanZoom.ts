import { useRef, useState } from 'react';
import type { CSSProperties, PointerEvent, WheelEvent } from 'react';

/** Drag to pan, wheel to zoom (0.5×–3×). Remount the view to reset ("Fit"). */
export function usePanZoom() {
  const [view, setView] = useState({ x: 0, y: 0, k: 1 });
  const drag = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);
  const handlers = {
    onPointerDown: (e: PointerEvent<HTMLDivElement>) => {
      if ((e.target as HTMLElement).closest('button')) return;
      drag.current = { x: e.clientX, y: e.clientY, ox: view.x, oy: view.y };
      e.currentTarget.setPointerCapture(e.pointerId);
    },
    onPointerMove: (e: PointerEvent<HTMLDivElement>) => {
      const d = drag.current;
      if (!d) return;
      setView(v => ({ ...v, x: d.ox + e.clientX - d.x, y: d.oy + e.clientY - d.y }));
    },
    onPointerUp: () => { drag.current = null; },
    onWheel: (e: WheelEvent<HTMLDivElement>) => {
      if (!e.ctrlKey && !e.metaKey && Math.abs(e.deltaY) < 4) return;
      setView(v => ({ ...v, k: Math.min(3, Math.max(0.5, v.k * (e.deltaY > 0 ? 0.92 : 1.08))) }));
    }
  };
  const style: CSSProperties = { transform: `translate(${view.x}px, ${view.y}px) scale(${view.k})` };
  return { handlers, style };
}
