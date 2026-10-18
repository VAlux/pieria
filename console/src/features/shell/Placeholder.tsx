import { EmptyState, Page, PageHeader } from '../../ui/display';
import { useProfile } from './context';

/** Temporary stand-in while a screen is rebuilt; links to the legacy console view. */
export function Placeholder({ screen, legacyView }: { screen: string; legacyView: string }) {
  const profile = useProfile();
  const legacy = `/index.html?${new URLSearchParams({ view: legacyView, profile })}`;
  return (
    <Page>
      <PageHeader crumb={profile} title={screen} />
      <EmptyState title={`${screen} is being rebuilt`} actions={<a href={legacy}>Open it in the previous console →</a>}>
        This screen has not been moved to the new console yet.
      </EmptyState>
    </Page>
  );
}
