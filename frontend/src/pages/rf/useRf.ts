import { useState } from 'react';
import { ApiError, errorMessage } from '../../api/http';

/** Running one thing at a time on a device: busy, what went wrong, and what was done. */
export function useRf(onSignedOut: () => void) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  /**
   * @param ask takes an answer from the server that is a question rather than an error; true if it was
   * @returns whether it worked
   */
  const run = async (action: () => Promise<void>, ask?: (e: ApiError) => boolean): Promise<boolean> => {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      await action();
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) onSignedOut();
      else if (!(e instanceof ApiError && ask?.(e))) setError(errorMessage(e));
      return false;
    } finally {
      setBusy(false);
    }
  };

  const clear = () => {
    setError(null);
    setDone(null);
  };

  /** Shows a mistake found here, even when it is the same one twice. */
  const problem = (message: string) => {
    clear();
    setTimeout(() => setError(message));
  };

  return { busy, error, done, setDone, run, clear, problem };
}
