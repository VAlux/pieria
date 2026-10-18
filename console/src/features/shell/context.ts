import { createContext, useContext } from 'react';
import { useParams } from 'react-router';

export interface ShellApi {
  openSwitcher: () => void;
  openPalette: () => void;
}

export const ShellContext = createContext<ShellApi>({ openSwitcher: () => undefined, openPalette: () => undefined });

export function useShell(): ShellApi {
  return useContext(ShellContext);
}

/** The profile from the URL. Every screen lives under /p/:profile. */
export function useProfile(): string {
  const { profile } = useParams();
  return profile ?? '';
}
