import { profilePath, query, request } from './client';
import type { Memory } from './types';

export interface GraphNode {
  id: string;
  type: string;
  name: string;
  degree: number;
  /** Hops from the focus; -1 outside a neighbourhood. */
  hop: number;
}

export interface GraphLink {
  source: string;
  target: string;
  relation: string;
  memoryId: string;
}

export interface GraphOverview {
  entityCount: number;
  edgeCount: number;
  types: { type: string; count: number }[];
  nodes: GraphNode[];
  links: GraphLink[];
  truncated: boolean;
}

export interface GraphNeighborhood {
  focusId: string;
  nodes: GraphNode[];
  links: GraphLink[];
  truncated: boolean;
  totalNeighbors: number;
}

export interface GraphEntity {
  entity: GraphNode;
  relations: { direction: 'out' | 'in' | string; relation: string; otherId: string; otherName: string; otherType: string; memoryId: string }[];
  memories: Memory[];
  relationTotals?: { relation: string; direction: 'out' | 'in'; count: number }[];
}

export function graphOverview(profile: string, limit = 120, types?: string[], signal?: AbortSignal): Promise<GraphOverview> {
  return request<GraphOverview>(profilePath(profile, `/graph/overview${query({ limit, types })}`), { signal });
}

export async function graphSearch(profile: string, q: string, signal?: AbortSignal): Promise<GraphNode[]> {
  const result = await request<{ matches: GraphNode[] }>(profilePath(profile, `/graph/search${query({ q, limit: 12 })}`), { signal });
  return result?.matches ?? [];
}

export function graphNeighborhood(profile: string, entity: string, depth: number, limit: number, signal?: AbortSignal): Promise<GraphNeighborhood> {
  return request<GraphNeighborhood>(profilePath(profile, `/graph/neighborhood${query({ entity, depth, limit })}`), { signal });
}

export function graphEntity(profile: string, id: string, signal?: AbortSignal): Promise<GraphEntity> {
  return request<GraphEntity>(profilePath(profile, `/graph/entities/${encodeURIComponent(id)}`), { signal });
}

const KNOWN: Record<string, string> = {
  tool: '#7CCB9F',
  concept: '#A9CDF2',
  file: '#B9A0EE',
  project: '#E8B062',
  command: '#E89AAE',
  class: '#6CC8C8',
  person: '#F0C27E'
};
const EXTRA = ['#8FBCE9', '#9FD9BB', '#CDB9F5', '#F3B8AC', '#C9C5BD'];

/** Stable colour per entity type: the canvas's four, then a small palette for the long tail. */
export function typeColor(type: string): string {
  if (KNOWN[type]) return KNOWN[type];
  let h = 0;
  for (let i = 0; i < type.length; i++) h = (h * 31 + type.charCodeAt(i)) >>> 0;
  return EXTRA[h % EXTRA.length];
}
