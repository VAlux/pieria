import { describe, expect, it } from 'vitest';
import type { HarvesterField } from '../../api/sources';
import { SOURCE_ID_PATTERN, configChanges, formatInterval, initialValues, parseInterval, parseValues } from './form';

const field = (key: string, kind: HarvesterField['kind'], extra: Partial<HarvesterField> = {}): HarvesterField => ({
  key, kind, label: extra.label ?? key, hint: null, required: false, options: [], defaultValue: null, ...extra
});

const FIELDS: HarvesterField[] = [
  field('clientId', 'string', { label: 'Client ID', required: true }),
  field('includeChats', 'bool', { defaultValue: true }),
  field('channels', 'list'),
  field('sinceDays', 'int', { label: 'Days', defaultValue: 90 }),
  field('refreshToken', 'secret'),
  field('mode', 'enum', { label: 'Mode', options: ['a', 'b'] }),
  field('token', 'secret', { label: 'Token', required: true })
];

describe('initialValues', () => {
  it('initialValuesPreferConfigOverDefaults', () => {
    const values = initialValues(FIELDS, { sinceDays: 30, channels: ['Eng/Ops', 'Sales'], token: 'leaked' });

    expect(values.sinceDays).toBe('30');
    expect(values.channels).toBe('Eng/Ops\nSales');
    expect(values.includeChats).toBe(true);
    expect(values.clientId).toBe('');
    expect(values.token).toBe('');
    expect(values.refreshToken).toBe('');
  });
});

describe('parseValues', () => {
  const base = { ...initialValues(FIELDS), clientId: 'abc', token: 'x' };

  it('parseSplitsListsOnLinesAndCommas', () => {
    expect(parseValues(FIELDS, { ...base, channels: 'Eng/Ops\n Sales , \n' }).config.channels).toEqual(['Eng/Ops', 'Sales']);
  });

  it('parseKeepsSecretsOutOfConfig', () => {
    const parsed = parseValues(FIELDS, base);

    expect(parsed.secrets).toEqual({ token: 'x' });
    expect(parsed.config).not.toHaveProperty('token');
    expect(parsed.config).not.toHaveProperty('refreshToken');
    expect(parsed.config).toEqual({ clientId: 'abc', includeChats: true, sinceDays: 90 });
    expect(parsed.errors).toEqual({});
  });

  it('parseReportsEveryError', () => {
    const parsed = parseValues(FIELDS, { ...base, clientId: ' ', sinceDays: '1.5', mode: 'c' });

    expect(parsed.errors).toEqual({
      clientId: 'Client ID is required',
      sinceDays: 'Days must be a whole number',
      mode: 'Mode must be one of a, b'
    });
  });

  it('secretOptionalSkipsTheRequiredSecret', () => {
    expect(parseValues(FIELDS, { ...base, token: '' }).errors).toEqual({ token: 'Token is required' });
    expect(parseValues(FIELDS, { ...base, token: '' }, { secretOptional: true }).errors).toEqual({});
  });
});

describe('configChanges', () => {
  it('configChangesClearsRemovedKeysAndSkipsSecrets', () => {
    const fields = [field('a', 'string'), field('b', 'string'), field('c', 'string'), field('token', 'secret')];

    expect(configChanges(fields, { a: '1', b: '2', token: 'x' }, { a: '1', c: '3' })).toEqual({ b: null, c: '3' });
  });

  it('comparesListsByValue', () => {
    expect(configChanges([field('l', 'list')], { l: ['x', 'y'] }, { l: ['x', 'y'] })).toEqual({});
  });
});

describe('intervals', () => {
  it('intervalRoundTrips', () => {
    expect(parseInterval('6h')).toEqual({ seconds: 21600 });
    expect(formatInterval(21600)).toBe('6h');
    expect(formatInterval(86400)).toBe('1d');
    expect(formatInterval(1800)).toBe('30m');
    expect(formatInterval(90)).toBe('90s');
    expect(parseInterval('90')).toEqual({ seconds: 90 });
    expect(parseInterval('90s')).toEqual({ seconds: 90 });
    expect(parseInterval('30')).toEqual({ error: 'The interval must be at least 60 seconds' });
    expect(parseInterval('soon')).toEqual({ error: 'Use a form like 30m, 6h or 1d' });
  });
});

describe('source ids', () => {
  it('sourceIdPattern', () => {
    expect(SOURCE_ID_PATTERN.test('teams-chats')).toBe(true);
    expect(SOURCE_ID_PATTERN.test('Teams')).toBe(false);
    expect(SOURCE_ID_PATTERN.test('-x')).toBe(false);
  });
});
