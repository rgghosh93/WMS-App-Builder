import { Link } from 'react-router-dom';
import { pickMethodLabel, statusClass, taskStatusLabel, type PickMethod, type PickTaskDetailView } from '../../api/orders';

/** The details of one pick task: one row per allocation, in pick order. */
export function PickTaskDetailsTable({
  details,
  label,
  showOrder = false,
}: {
  details: PickTaskDetailView[];
  label: string;
  /** Show which order each pick is for (batch tasks). */
  showOrder?: boolean;
}) {
  return (
    <div className="table-wrap">
      <table className="table" aria-label={label}>
        <thead>
          <tr>
            <th scope="col" className="num">
              Detail
            </th>
            {showOrder && <th scope="col">Order</th>}
            <th scope="col" className="num">
              Line
            </th>
            <th scope="col">Item</th>
            <th scope="col" className="num">
              Quantity
            </th>
            <th scope="col">From location</th>
            <th scope="col">From LPN</th>
            <th scope="col">Lot / serial</th>
            <th scope="col">Status</th>
          </tr>
        </thead>
        <tbody>
          {details.map((d) => (
            <tr key={d.id}>
              <td className="num">{d.detailNumber}</td>
              {showOrder && (
                <td>
                  <Link className="code" to={`/outbound/orders/${d.order.id}`}>
                    {d.order.orderNumber}
                  </Link>
                  <span className="muted cell__sub">{d.order.customerName}</span>
                </td>
              )}
              <td className="num">{d.lineNumber}</td>
              <td>
                <span className="code">{d.item.itemId}</span>
                <span className="muted cell__sub">{d.item.description}</span>
              </td>
              <td className="num">
                {d.quantity.toLocaleString()} EA
                {(d.status === 'SHORT' || (d.pickedQuantity > 0 && d.pickedQuantity < d.quantity)) && (
                  <span className="muted cell__sub">{d.pickedQuantity.toLocaleString()} picked</span>
                )}
              </td>
              <td>
                <Link className="code" to={`/inventory/by-location?location=${d.fromLocation.id}`}>
                  {d.fromLocation.locationId}
                </Link>
              </td>
              <td>
                {d.fromContainer ? (
                  <Link className="code" to={`/inventory/by-container?container=${d.fromContainer.id}`}>
                    {d.fromContainer.lpnId}
                  </Link>
                ) : (
                  <span className="muted">—</span>
                )}
              </td>
              <td className="code">{d.serialNumber ?? d.lotNumber ?? <span className="muted">—</span>}</td>
              <td>
                <span className={`badge badge--task-${statusClass(d.status)}`}>{taskStatusLabel(d.status)}</span>
                {d.pickedBy && <span className="muted cell__sub">{d.pickedBy}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Badge saying how the task is picked. */
export function PickMethodBadge({ method }: { method: PickMethod }) {
  return <span className={`badge badge--sm badge--method-${method.toLowerCase()}`}>{pickMethodLabel(method)}</span>;
}

/** "3 details · 2 lines · 45 EA" */
export function taskSummary(t: { detailCount: number; lineCount: number; totalQuantity: number }): string {
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;
  return `${plural(t.detailCount, 'detail')} · ${plural(t.lineCount, 'line')} · ${t.totalQuantity.toLocaleString()} EA`;
}
