import type { ReactNode } from 'react';
import { ArrowDown, ArrowUp } from 'lucide-react';

export interface Column<F extends string> {
  /** Field to sort by, or null for a column that can't be sorted. */
  field: F | null;
  label: string;
  /** Right-align the header, for numeric columns. */
  numeric?: boolean;
}

interface SortableHeaderProps<F extends string> {
  columns: Column<F>[];
  sort: F;
  dir: 'asc' | 'desc';
  onSort: (field: F) => void;
  /** Content of an extra first header cell, e.g. a select-all checkbox. */
  leading?: ReactNode;
}

/** Table header row whose column titles toggle sorting. */
export function SortableHeader<F extends string>({ columns, sort, dir, onSort, leading }: SortableHeaderProps<F>) {
  return (
    <thead>
      <tr>
        {leading !== undefined && (
          <th scope="col" className="table__select">
            {leading}
          </th>
        )}
        {columns.map((col) => (
          <th
            key={col.label}
            scope="col"
            className={col.numeric ? 'num' : undefined}
            aria-sort={col.field && sort === col.field ? (dir === 'asc' ? 'ascending' : 'descending') : undefined}
          >
            {col.field ? (
              <button type="button" className="th-sort" onClick={() => onSort(col.field!)}>
                {col.label}
                {sort === col.field &&
                  (dir === 'asc' ? <ArrowUp size={13} aria-hidden="true" /> : <ArrowDown size={13} aria-hidden="true" />)}
              </button>
            ) : (
              col.label
            )}
          </th>
        ))}
      </tr>
    </thead>
  );
}
