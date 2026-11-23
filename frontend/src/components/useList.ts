import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { errorMessage, type Page } from '../api/http';
import { PAGE_SIZES } from './Pagination';

type LoadState<T> =
  | { status: 'loading'; previous?: Page<T> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<T> };

/**
 * A searchable, paged list whose query lives in the address, so it survives a reload and can be shared.
 *
 * @param load  must not change between renders
 * @param fixed always part of the request, whatever is in the address; must not change either
 */
export function useList<T>(load: (params: URLSearchParams, signal: AbortSignal) => Promise<Page<T>>, fixed?: Record<string, string>) {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const [searchText, setSearchText] = useState(q);
  const [state, setState] = useState<LoadState<T>>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);

  /** Changes the query, going back to the first page unless told which. Empty values are left out. */
  const update = useCallback(
    (changes: Record<string, string | number>) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          next.delete('page');
          for (const [key, value] of Object.entries(changes)) {
            if (value === '' || (key === 'page' && value === 0)) next.delete(key);
            else next.set(key, String(value));
          }
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  useEffect(() => {
    const handle = setTimeout(() => {
      if (q !== searchText.trim()) update({ q: searchText.trim() });
    }, 250);
    return () => clearTimeout(handle);
  }, [searchText, q, update]);

  const key = params.toString();
  useEffect(() => {
    const controller = new AbortController();
    setState((current) => ({
      status: 'loading',
      previous: current.status === 'ready' ? current.page : current.status === 'loading' ? current.previous : undefined,
    }));
    const query = new URLSearchParams(key);
    if (!PAGE_SIZES.includes(Number(query.get('size')))) query.set('size', String(PAGE_SIZES[0]));
    if (!/^[0-9]+$/.test(query.get('page') ?? '')) query.set('page', '0');
    for (const [name, value] of Object.entries(fixed ?? {})) query.set(name, value);
    load(query, controller.signal)
      .then((page) => setState({ status: 'ready', page }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [key, reloadKey, load, fixed]);

  return {
    params,
    update,
    searchText,
    setSearchText,
    page: state.status === 'ready' ? state.page : state.status === 'loading' ? state.previous : undefined,
    loading: state.status === 'loading',
    error: state.status === 'error' ? state.message : null,
    reload: useCallback(() => setReloadKey((n) => n + 1), []),
  };
}
