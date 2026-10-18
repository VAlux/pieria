import { describe, expect, it } from 'vitest';
import { changedKeys, flatten, parseField, summarize, unflatten } from './model';

describe('override maps', () => {
  it('round-trips between nested and dotted keys', () => {
    const nested = { retrieval: { 'rrf-k': 60, 'weight-graph': 1.5 }, wiki: { enabled: true } };
    const flat = flatten(nested);
    expect(flat).toEqual({ 'retrieval.rrf-k': 60, 'retrieval.weight-graph': 1.5, 'wiki.enabled': true });
    expect(unflatten(flat)).toEqual(nested);
  });

  it('drops null leaves from sparse overrides', () => {
    expect(flatten({ retrieval: { 'rrf-k': null } })).toEqual({});
  });

  it('reports changed, added and removed keys', () => {
    expect(changedKeys({ a: 1, b: true }, { a: '1', c: 2 }).sort()).toEqual(['b', 'c']);
  });
});

describe('parseField', () => {
  it('parses each kind and rejects bad input', () => {
    expect(parseField({ kind: 'int', label: 'Depth' }, '3')).toEqual({ value: 3 });
    expect(parseField({ kind: 'int', label: 'Depth' }, '3.5').error).toMatch(/whole number/);
    expect(parseField({ kind: 'double', label: 'Threshold' }, '0.78')).toEqual({ value: 0.78 });
    expect(parseField({ kind: 'weight', label: 'Weight' }, '-1').error).toMatch(/negative/);
    expect(parseField({ kind: 'enum', label: 'Mode', options: ['A', 'B'] }, 'C').error).toMatch(/one of A, B/);
    expect(parseField({ kind: 'bool', label: 'On' }, 'true')).toEqual({ value: true });
  });
});

describe('summarize', () => {
  it('describes a collapsed section briefly', () => {
    const fields = [
      { key: 'retrieval.graph-depth', label: 'Depth', kind: 'int' },
      { key: 'retrieval.graph-fanout', label: 'Fanout', kind: 'int' },
      { key: 'ingestion.graph-from-extraction', label: 'Extract graph during ingest', kind: 'bool' }
    ] as never;
    expect(summarize(fields, { 'retrieval.graph-depth': 2, 'retrieval.graph-fanout': 20, 'ingestion.graph-from-extraction': false }))
      .toBe('depth 2 · fanout 20 · extract graph during ingest off');
  });
});
