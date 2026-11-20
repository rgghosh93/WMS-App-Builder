import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { Page } from '../api/http';

export const PAGE_SIZES = [25, 50, 100];

interface PaginationProps {
  page: Page<unknown>;
  onPage: (page: number) => void;
  onSize: (size: number) => void;
}

export function Pagination({ page, onPage, onSize }: PaginationProps) {
  const first = page.page * page.size + 1;
  const last = Math.min(first + page.content.length - 1, page.totalElements);
  return (
    <div className="pagination">
      <label className="select-inline">
        <span>Rows</span>
        <select value={page.size} onChange={(e) => onSize(Number(e.target.value))}>
          {PAGE_SIZES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </label>
      <span className="muted">
        {first.toLocaleString()}–{last.toLocaleString()} of {page.totalElements.toLocaleString()}
      </span>
      <div className="button-row">
        <button type="button" className="icon-button" aria-label="Previous page" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>
          <ChevronLeft size={18} aria-hidden="true" />
        </button>
        <button
          type="button"
          className="icon-button"
          aria-label="Next page"
          disabled={page.page + 1 >= page.totalPages}
          onClick={() => onPage(page.page + 1)}
        >
          <ChevronRight size={18} aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
