import { useQuery } from '@tanstack/react-query';
import { qk } from '../../api/queries';
import { getSource, listHarvesters, listSources, type Harvester } from '../../api/sources';

/** Faster while anything runs, so a run's end shows up without a reload. */
const poll = (running: boolean) => (running ? 2_000 : 15_000);

export function useHarvesters() {
  return useQuery({ queryKey: qk.harvesters, queryFn: ({ signal }) => listHarvesters(signal), staleTime: 60_000 });
}

export function useSources(profile: string) {
  return useQuery({
    queryKey: qk.sources(profile),
    queryFn: ({ signal }) => listSources(profile, signal),
    enabled: Boolean(profile),
    refetchInterval: query => poll(query.state.data?.some(s => s.running) ?? false)
  });
}

export function useSource(profile: string, id: string) {
  return useQuery({
    queryKey: qk.source(profile, id),
    queryFn: ({ signal }) => getSource(profile, id, signal),
    enabled: Boolean(profile && id),
    refetchInterval: query => poll(query.state.data?.running ?? false)
  });
}

export function harvesterFor(harvesters: Harvester[] | undefined, id: string): Harvester | undefined {
  return harvesters?.find(h => h.id === id);
}
