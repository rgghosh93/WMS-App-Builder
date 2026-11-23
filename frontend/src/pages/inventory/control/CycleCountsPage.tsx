import { useCallback, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { COUNT_STATUSES, countStatusLabel, listCounts, type Count } from '../../../api/control';
import { FilterSelect, ListPage } from '../../../components/ListPage';
import { useList } from '../../../components/useList';
import { Can } from '../../../session/SessionContext';
import { formatDateTime } from '../../outbound/OrdersPage';
import { PlanCountsForm } from './PlanCountsForm';

/** Counts planned, under way and done, newest first. */
export function CycleCountsPage() {
  const list = useList<Count>(listCounts);
  const navigate = useNavigate();
  const [planning, setPlanning] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <ListPage
      module="Inventory"
      title="Cycle Counts"
      intro={
        <>
          Locations are counted on a mobile device under <Link to="/rf/count">Count</Link>, without seeing what is expected. Nothing can be moved or picked
          there until the count is settled.
        </>
      }
      actions={
        <Can permission="INVENTORY">
          <button type="button" className="button button--primary" onClick={() => setPlanning(true)}>
            <Plus size={16} aria-hidden="true" />
            Plan counts
          </button>
        </Can>
      }
      searchLabel="Search counts"
      searchPlaceholder="Search number, location or person…"
      filters={<FilterSelect label="Status" value={list.params.get('status') ?? ''} options={COUNT_STATUSES} onChange={(status) => list.update({ status })} />}
      noun={['count', 'counts']}
      list={list}
      notice={notice}
      onNoticeGone={useCallback(() => setNotice(null), [])}
      empty={list.params.toString() ? 'No counts match your search.' : 'No counts yet.'}
      before={
        planning && (
          <PlanCountsForm
            onClose={() => setPlanning(false)}
            onDone={({ created, skipped }) => {
              setPlanning(false);
              setNotice(`Planned ${created} count${created === 1 ? '' : 's'}${skipped > 0 ? `; ${skipped} already had one` : ''}`);
              list.reload();
            }}
          />
        )
      }
    >
      {(rows) => (
        <table className="table" aria-label="Counts">
          <thead>
            <tr>
              <th scope="col">Count</th>
              <th scope="col">Location</th>
              <th scope="col">Status</th>
              <th scope="col" className="num">
                Expected
              </th>
              <th scope="col" className="num">
                Counted
              </th>
              <th scope="col">Counted by</th>
              <th scope="col">Planned</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id} className="table__row--link" onClick={() => navigate(`/inventory/counts/${c.id}`)}>
                <th scope="row">
                  <Link className="code" to={`/inventory/counts/${c.id}`} onClick={(e) => e.stopPropagation()}>
                    {c.number}
                  </Link>
                </th>
                <td className="code">{c.location}</td>
                <td>
                  <span className={`badge badge--count-${c.status.toLowerCase()}`}>{countStatusLabel(c.status)}</span>
                </td>
                <td className="num">{c.countedAt ? `${c.expected.toLocaleString()} EA` : <span className="muted">—</span>}</td>
                <td className="num">{c.countedAt ? `${c.counted.toLocaleString()} EA` : <span className="muted">—</span>}</td>
                <td>{c.counter ?? <span className="muted">—</span>}</td>
                <td className="muted">{formatDateTime(c.plannedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </ListPage>
  );
}
