import { useEffect, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { Page } from '../api/http';
import { Pagination } from './Pagination';
import { SearchBox } from './SearchBox';

interface ListPageProps<T> {
  module: string;
  title: string;
  intro: ReactNode;
  /** Buttons beside the title. */
  actions?: ReactNode;
  searchLabel: string;
  searchPlaceholder: string;
  /** More filters, beside the search box. */
  filters?: ReactNode;
  /** One and many of what is listed. */
  noun: [string, string];
  list: {
    searchText: string;
    setSearchText: (text: string) => void;
    page: Page<T> | undefined;
    loading: boolean;
    error: string | null;
    reload: () => void;
    update: (changes: Record<string, string | number>) => void;
  };
  notice?: string | null;
  onNoticeGone?: () => void;
  empty: string;
  /** Between the filters and the table. */
  before?: ReactNode;
  /** The table, for a page with something on it. */
  children: (rows: T[]) => ReactNode;
}

/** The frame of a list page: title, search and filters, table, and paging. */
export function ListPage<T>({ module, title, intro, actions, searchLabel, searchPlaceholder, filters, noun, list, notice, onNoticeGone, empty, before, children }: ListPageProps<T>) {
  useEffect(() => {
    if (!notice || !onNoticeGone) return;
    const handle = setTimeout(onNoticeGone, 5000);
    return () => clearTimeout(handle);
  }, [notice, onNoticeGone]);

  const { page } = list;
  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>{module}</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{title}</span>
      </nav>
      <header className="page__header">
        <div>
          <h1>{title}</h1>
          <p className="muted">{intro}</p>
        </div>
        {actions && <div className="button-row">{actions}</div>}
      </header>
      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}
      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox value={list.searchText} onChange={list.setSearchText} placeholder={searchPlaceholder} label={searchLabel} />
        </div>
        {filters}
        <span className="toolbar__count muted" aria-live="polite">
          {page ? `${page.totalElements.toLocaleString()} ${noun[page.totalElements === 1 ? 0 : 1]}` : ''}
        </span>
      </div>
      {before}
      {list.error && (
        <div className="notice" role="alert">
          <p>{list.error}</p>
          <button type="button" className="button" onClick={list.reload}>
            Try again
          </button>
        </div>
      )}
      {page && (
        <div className={`table-wrap${list.loading ? ' table-wrap--loading' : ''}`}>
          {page.content.length > 0 ? (
            children(page.content)
          ) : (
            <div className="empty">
              <p>{empty}</p>
            </div>
          )}
        </div>
      )}
      {page && page.totalElements > 0 && <Pagination page={page} onPage={(p) => list.update({ page: p })} onSize={(s) => list.update({ size: s })} />}
    </div>
  );
}

/** A labelled drop-down filter. */
export function FilterSelect({ label, value, options, onChange }: { label: string; value: string; options: { value: string; label: string }[]; onChange: (value: string) => void }) {
  return (
    <label className="select-inline">
      <span>{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">All</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
}
