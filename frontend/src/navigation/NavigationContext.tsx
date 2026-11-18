import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { fetchModules, type ModuleDefinition } from '../api/navigation';

type NavigationState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; modules: ModuleDefinition[] };

interface NavigationContextValue {
  state: NavigationState;
  reload: () => void;
}

const NavigationContext = createContext<NavigationContextValue | null>(null);

/** Loads the page directory once and shares it with the sidebar and the home screen. */
export function NavigationProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<NavigationState>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    fetchModules(controller.signal)
      .then((modules) => setState({ status: 'ready', modules }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        const message = error instanceof Error ? error.message : 'Could not load the page directory';
        setState({ status: 'error', message });
      });
    return () => controller.abort();
  }, [attempt]);

  const reload = useCallback(() => setAttempt((n) => n + 1), []);

  return <NavigationContext.Provider value={{ state, reload }}>{children}</NavigationContext.Provider>;
}

export function useNavigation(): NavigationContextValue {
  const value = useContext(NavigationContext);
  if (!value) throw new Error('useNavigation must be used inside NavigationProvider');
  return value;
}
