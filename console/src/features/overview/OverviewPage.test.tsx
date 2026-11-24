import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { OverviewPage } from './OverviewPage';
import { mockApi, renderAt } from '../../test/render';

afterEach(() => vi.restoreAllMocks());

const stats = (spend: unknown) => ({
  name: 'empty', createdAt: '2026-01-01T00:00:00Z', totalActive: 0, byType: {}, superseded: 0, sessions: 0,
  firstMemoryAt: null, lastMemoryAt: null, vectorizationBacklog: 0,
  impact: { recalls: 0, tokensSaved: 0, tokensIngested: 0, tokensStored: 0, contextWindowTokens: 200_000, pricePerMillionTokens: 0 },
  spend
});

function show(spend: unknown) {
  mockApi({ '/v1/profiles/empty/stats': stats(spend) });
  renderAt('/p/:profile', '/p/empty', <OverviewPage />);
}

describe('OverviewPage', () => {
  it('rendersAProfileThatHasNeverCalledAModel', async () => {
    show(null);

    expect(await screen.findByText('Active memories')).toBeInTheDocument();
    expect(screen.getByText('Memory mix')).toBeInTheDocument();
    expect(screen.queryByText('Inference spend')).toBeNull();
  });

  it('showsSpendPerTierOnceTheProfileHasCalledAModel', async () => {
    show({
      tiers: [{ tier: 'EXTRACTION', calls: 3, promptTokens: 1200, completionTokens: 300, costUsd: 0.5 }],
      totalPromptTokens: 1200, totalCompletionTokens: 300, totalCostUsd: 0.5, costAvailable: true
    });

    expect(await screen.findByText('Inference spend')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'extraction' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '$0.50' })).toBeInTheDocument();
  });
});
