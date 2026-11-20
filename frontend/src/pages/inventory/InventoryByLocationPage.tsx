import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  getLocationInventory,
  listInventoryLocations,
  type InventoryLocationQuery,
  type LocationDetail,
  type LocationSummary,
} from '../../api/inventory';
import { listUoms } from '../../api/uoms';
import { errorMessage, type Page } from '../../api/http';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { LocationContents } from './LocationContents';

type ListState =
  | { status: 'loading'; previous?: Page<LocationSummary> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<LocationSummary> };

type DetailState = { status: 'idle' } | { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; detail: LocationDetail };

/** Locations on the left with what they hold; the chosen location's full contents on the right. */
export function InventoryByLocationPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);
  const selectedId = params.get('location');

  const [searchText, setSearchText] = useState(query.q);
  const [list, setList] = useState<ListState>({ status: 'loading' });
  const [detail, setDetail] = useState<DetailState>({ status: 'idle' });
  const [baseCode, setBaseCode] = useState('EA');
  const [reloadKey, setReloadKey] = useState(0);

  const update = (changes: Partial<InventoryLocationQuery> & { location?: string | null }) => {
    setParams(
      (current) => {
        const next = writeQuery({ ...readQuery(current), page: 0, ...changes });
        const location = changes.location !== undefined ? changes.location : current.get('location');
        if (location) next.set('location', location);
        return next;
      },
      { replace: true },
    );
  };

  useEffect(() => {
    const controller = new AbortController();
    listUoms(controller.signal)
      .then((uoms) => setBaseCode(uoms.find((u) => u.base)?.uomCode ?? 'EA'))
      .catch(() => {
        /* keep EA */
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const handle = setTimeout(() => {
      if (query.q !== searchText.trim()) update({ q: searchText.trim() });
    }, 250);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchText, query.q]);

  const listKey = writeQuery(query).toString();
  useEffect(() => {
    const controller = new AbortController();
    setList((current) => ({
      status: 'loading',
      previous: current.status === 'ready' ? current.page : current.status === 'loading' ? current.previous : undefined,
    }));
    listInventoryLocations(readQuery(new URLSearchParams(listKey)), controller.signal)
      .then((page) => setList({ status: 'ready', page }))
      .catch((error) => {
        if (!controller.signal.aborted) setList({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [listKey, reloadKey]);

  useEffect(() => {
    if (!selectedId) {
      setDetail({ status: 'idle' });
      return;
    }
    const controller = new AbortController();
    setDetail({ status: 'loading' });
    getLocationInventory(selectedId, controller.signal)
      .then((d) => setDetail({ status: 'ready', detail: d }))
      .catch((error) => {
        if (!controller.signal.aborted) setDetail({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [selectedId, reloadKey]);

  const page = list.status === 'ready' ? list.page : list.status === 'loading' ? list.previous : undefined;

  return (
    <div className="page page--wide">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Inventory</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Inventory by Location</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Inventory by Location</h1>
          <p className="muted">Choose a location to see its items, the LPNs in it, and what is inside each LPN.</p>
        </div>
      </header>

      <div className="split">
        <div className="split__list">
          <div className="toolbar">
            <div className="toolbar__search">
              <SearchBox value={searchText} onChange={setSearchText} placeholder="Search location ID…" label="Search locations" />
            </div>
            <label className="check check--compact">
              <input type="checkbox" checked={!query.occupied} onChange={(e) => update({ occupied: !e.target.checked })} />
              <span className="check__label">Show empty locations</span>
            </label>
          </div>

          {list.status === 'error' && (
            <div className="notice" role="alert">
              <p>{list.message}</p>
              <button type="button" className="button" onClick={() => setReloadKey((k) => k + 1)}>
                Try again
              </button>
            </div>
          )}

          {page && (
            <div className={`table-wrap${list.status === 'loading' ? ' table-wrap--loading' : ''}`}>
              <table className="table" aria-label="Locations">
                <thead>
                  <tr>
                    <th scope="col">Location</th>
                    <th scope="col">Contents</th>
                    <th scope="col" className="num">
                      Quantity
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {page.content.map((s) => {
                    const selected = s.location.id === selectedId;
                    return (
                      <tr
                        key={s.location.id}
                        className={`table__row--link${selected ? ' table__row--selected' : ''}`}
                        aria-selected={selected}
                        onClick={() => update({ location: s.location.id, page: query.page })}
                      >
                        <th scope="row">
                          <button
                            type="button"
                            className="link-button code"
                            aria-current={selected ? 'true' : undefined}
                            onClick={(e) => {
                              e.stopPropagation();
                              update({ location: s.location.id, page: query.page });
                            }}
                          >
                            {s.location.locationId}
                          </button>
                        </th>
                        <td className="muted">{describe(s)}</td>
                        <td className="num">
                          {s.totalQuantity > 0 ? `${s.totalQuantity.toLocaleString()} ${baseCode}` : <span className="muted">—</span>}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {page.content.length === 0 && (
                <div className="empty">
                  <p>{query.q ? 'No locations match your search.' : 'No locations hold any stock yet.'}</p>
                </div>
              )}
            </div>
          )}

          {page && page.totalElements > 0 && (
            <Pagination page={page} onPage={(p) => update({ page: p })} onSize={(s) => update({ size: s })} />
          )}
        </div>

        <div className="split__detail" aria-live="polite">
          {detail.status === 'idle' && <p className="contents__placeholder">Choose a location to see what it holds.</p>}
          {detail.status === 'loading' && <p className="contents__placeholder">Loading…</p>}
          {detail.status === 'error' && (
            <div className="notice" role="alert">
              <p>{detail.message}</p>
            </div>
          )}
          {detail.status === 'ready' && <LocationContents detail={detail.detail} baseCode={baseCode} />}
        </div>
      </div>
    </div>
  );
}

function describe(s: LocationSummary): string {
  if (s.totalQuantity === 0 && s.containerCount === 0) return 'Empty';
  const parts: string[] = [];
  if (s.containerCount > 0) parts.push(`${s.containerCount} LPN${s.containerCount === 1 ? '' : 's'}`);
  if (s.itemCount > 0) parts.push(`${s.itemCount} item${s.itemCount === 1 ? '' : 's'}`);
  if (s.containerCount > 0 && s.totalQuantity === 0) parts.push('no stock');
  return parts.join(' · ');
}

function readQuery(params: URLSearchParams): InventoryLocationQuery {
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    occupied: params.get('empty') !== 'show',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: InventoryLocationQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (!q.occupied) params.set('empty', 'show');
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}
