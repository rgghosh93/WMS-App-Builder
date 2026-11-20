import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  getContainerInventory,
  listInventoryContainers,
  type ContainerDetail,
  type ContainerKind,
  type ContainerSummary,
  type InventoryContainerQuery,
} from '../../api/inventory';
import { CONTAINER_CATEGORIES, categoryLabel, type ContainerCategory } from '../../api/containerTypes';
import { containerStatusLabel } from '../../api/containers';
import { listUoms } from '../../api/uoms';
import { errorMessage, type Page } from '../../api/http';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { CategoryIcon, ContainerTree } from './InventoryTree';

type ListState =
  | { status: 'loading'; previous?: Page<ContainerSummary> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<ContainerSummary> };

type DetailState = { status: 'idle' } | { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; detail: ContainerDetail };

const CATEGORY_VALUES = CONTAINER_CATEGORIES.map((c) => c.value);

/** Every container holding stock (including ones inside other containers); the chosen one's contents on the right. */
export function InventoryByContainerPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);
  const selectedId = params.get('container');

  const [searchText, setSearchText] = useState(query.q);
  const [list, setList] = useState<ListState>({ status: 'loading' });
  const [detail, setDetail] = useState<DetailState>({ status: 'idle' });
  const [baseCode, setBaseCode] = useState('EA');
  const [reloadKey, setReloadKey] = useState(0);

  const update = (changes: Partial<InventoryContainerQuery> & { container?: string | null }) => {
    setParams(
      (current) => {
        const next = writeQuery({ ...readQuery(current), page: 0, ...changes });
        const container = changes.container !== undefined ? changes.container : current.get('container');
        if (container) next.set('container', container);
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
    listInventoryContainers(readQuery(new URLSearchParams(listKey)), controller.signal)
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
    getContainerInventory(selectedId, controller.signal)
      .then((d) => setDetail({ status: 'ready', detail: d }))
      .catch((error) => {
        if (!controller.signal.aborted) setDetail({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [selectedId, reloadKey]);

  const page = list.status === 'ready' ? list.page : list.status === 'loading' ? list.previous : undefined;
  const filtered = Boolean(query.q || query.category || query.kind);

  return (
    <div className="page page--wide">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Inventory</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Inventory by Container</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Inventory by Container</h1>
          <p className="muted">Every LPN that holds stock, including LPNs inside other LPNs. Choose one to see what is in it.</p>
        </div>
      </header>

      <div className="split">
        <div className="split__list">
          <div className="toolbar">
            <div className="toolbar__search">
              <SearchBox value={searchText} onChange={setSearchText} placeholder="Search LPN…" label="Search LPNs" />
            </div>
            <label className="select-inline">
              <span>Category</span>
              <select value={query.category} onChange={(e) => update({ category: e.target.value as ContainerCategory | '' })}>
                <option value="">All</option>
                {CONTAINER_CATEGORIES.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="select-inline">
              <span>Kind</span>
              <select value={query.kind} onChange={(e) => update({ kind: e.target.value as ContainerKind })}>
                <option value="">All</option>
                <option value="permanent">Permanent containers</option>
                <option value="inventory">Inventory LPNs</option>
              </select>
            </label>
            <span className="toolbar__count muted" aria-live="polite">
              {page ? `${page.totalElements.toLocaleString()} LPN${page.totalElements === 1 ? '' : 's'}` : ''}
            </span>
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
              <table className="table" aria-label="Containers">
                <thead>
                  <tr>
                    <th scope="col">LPN</th>
                    <th scope="col">Where</th>
                    <th scope="col">Contents</th>
                    <th scope="col" className="num">
                      Quantity
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {page.content.map((c) => {
                    const selected = c.id === selectedId;
                    return (
                      <tr
                        key={c.id}
                        className={`table__row--link${selected ? ' table__row--selected' : ''}`}
                        aria-selected={selected}
                        onClick={() => update({ container: c.id, page: query.page })}
                      >
                        <th scope="row">
                          <button
                            type="button"
                            className="link-button code"
                            aria-current={selected ? 'true' : undefined}
                            onClick={(e) => {
                              e.stopPropagation();
                              update({ container: c.id, page: query.page });
                            }}
                          >
                            {c.lpnId}
                          </button>
                          <span className="muted cell__sub">
                            {categoryLabel(c.containerType.category)} · {c.containerType.containerTypeId}
                            {c.permanent ? ' · Permanent' : ''}
                          </span>
                        </th>
                        <td>
                          <span className="code">{c.location?.locationId ?? '—'}</span>
                          {c.path.length > 0 && (
                            <span className="muted cell__sub">in {c.path.map((p) => p.lpnId).join(' › ')}</span>
                          )}
                        </td>
                        <td className="muted">{describe(c)}</td>
                        <td className="num">
                          {c.totalQuantity.toLocaleString()} {baseCode}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {page.content.length === 0 && (
                <div className="empty">
                  <p>{filtered ? 'No LPNs with stock match your search.' : 'No LPNs hold any stock yet.'}</p>
                </div>
              )}
            </div>
          )}

          {page && page.totalElements > 0 && (
            <Pagination page={page} onPage={(p) => update({ page: p })} onSize={(s) => update({ size: s })} />
          )}
        </div>

        <div className="split__detail" aria-live="polite">
          {detail.status === 'idle' && <p className="contents__placeholder">Choose an LPN to see what is inside it.</p>}
          {detail.status === 'loading' && <p className="contents__placeholder">Loading…</p>}
          {detail.status === 'error' && (
            <div className="notice" role="alert">
              <p>{detail.message}</p>
            </div>
          )}
          {detail.status === 'ready' && <ContainerContents detail={detail.detail} baseCode={baseCode} />}
        </div>
      </div>
    </div>
  );
}

function ContainerContents({ detail, baseCode }: { detail: ContainerDetail; baseCode: string }) {
  const c = detail.container;
  const nested = countNested(c.children);
  return (
    <section className="contents" aria-labelledby="contents-title">
      <header className="contents__header">
        <div>
          <h2 id="contents-title" className="contents__title">
            <CategoryIcon category={c.containerType.category} />
            <span className="code">{c.lpnId}</span>
            <span className={`badge badge--${c.status.toLowerCase().replace('_', '-')}`}>{containerStatusLabel(c.status)}</span>
            {c.permanent && <span className="tag">Permanent</span>}
          </h2>
          <p className="muted">
            {categoryLabel(c.containerType.category)} · {c.containerType.containerTypeId} {c.containerType.description && `· ${c.containerType.description}`}
          </p>
          <p className="contents__where">
            <span className="muted">Where: </span>
            {detail.location ? (
              <Link className="code" to={`/inventory/by-location?location=${detail.location.id}`}>
                {detail.location.locationId}
              </Link>
            ) : (
              <span className="muted">No location recorded</span>
            )}
            {detail.path.map((p) => (
              <span key={p.id}>
                <span className="muted" aria-hidden="true">
                  {' › '}
                </span>
                <Link className="code" to={`/inventory/by-container?container=${p.id}`}>
                  {p.lpnId}
                </Link>
              </span>
            ))}
            <span className="muted" aria-hidden="true">
              {' › '}
            </span>
            <span className="code">{c.lpnId}</span>
          </p>
        </div>
        <dl className="summary summary--small">
          <div>
            <dt>Quantity</dt>
            <dd>
              {c.totalQuantity.toLocaleString()} <span className="summary__unit">{baseCode}</span>
            </dd>
          </div>
          <div>
            <dt>Items</dt>
            <dd>{c.itemCount}</dd>
          </div>
          <div>
            <dt>LPNs inside</dt>
            <dd>{nested}</dd>
          </div>
        </dl>
      </header>

      <ContainerTree nodes={[c]} baseCode={baseCode} label={`Contents of ${c.lpnId}`} linkContainers currentContainerId={c.id} />
    </section>
  );
}

function countNested(children: ContainerDetail['container']['children']): number {
  return children.reduce((sum, child) => sum + 1 + countNested(child.children), 0);
}

function describe(c: ContainerSummary): string {
  const parts: string[] = [];
  if (c.itemCount > 0) parts.push(`${c.itemCount} item${c.itemCount === 1 ? '' : 's'}`);
  if (c.nestedCount > 0) parts.push(`${c.nestedCount} LPN${c.nestedCount === 1 ? '' : 's'} inside`);
  return parts.join(' · ');
}

function readQuery(params: URLSearchParams): InventoryContainerQuery {
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  const category = params.get('category') as ContainerCategory | null;
  const kind = params.get('kind');
  return {
    q: params.get('q') ?? '',
    category: category && CATEGORY_VALUES.includes(category) ? category : '',
    kind: kind === 'permanent' || kind === 'inventory' ? kind : '',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: InventoryContainerQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.category) params.set('category', q.category);
  if (q.kind) params.set('kind', q.kind);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}
