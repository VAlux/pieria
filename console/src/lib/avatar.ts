const HUES = ['#E8B062', '#7DB4E8', '#7CCB9F', '#B9A0EE', '#E89AAE', '#6CC8C8'];
const GROUNDS = ['#3A2F1E', '#1E2A38', '#1D3328', '#2A2340', '#3A2229', '#1B3333'];

function hash(name: string): number {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return h % HUES.length;
}

/** Stable two-letter avatar colours for a profile name. */
export function avatarFor(name: string): { initials: string; color: string; background: string } {
  const parts = name.split(/[-_.\s]/).filter(Boolean);
  const initials = (parts.length > 1 ? parts[0][0] + parts[1][0] : name.slice(0, 2)).toLowerCase();
  const index = hash(name);
  return { initials, color: HUES[index], background: GROUNDS[index] };
}
