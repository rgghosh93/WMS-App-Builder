import { Link } from 'react-router-dom';
import { WEIGHT_UOMS } from '../../api/measure';
import type { Carton } from '../../api/packing';

/** A shipping carton and what is in it. */
export function CartonCard({ carton, showOrder = false, linked = false }: { carton: Carton; showOrder?: boolean; linked?: boolean }) {
  const unit = WEIGHT_UOMS.find((u) => u.value === carton.weightUom)?.label ?? '';
  return (
    <section className="carton" aria-label={`Carton ${carton.container.lpnId}`}>
      <div className="carton__header">
        {linked ? (
          <Link className="code carton__lpn" to={`/inventory/by-container?container=${carton.container.id}`}>
            {carton.container.lpnId}
          </Link>
        ) : (
          <span className="code carton__lpn">{carton.container.lpnId}</span>
        )}
        <span className={`badge badge--task-${carton.status === 'OPEN' ? 'in-progress' : 'completed'}`}>
          {carton.status === 'OPEN' ? 'Open' : 'Closed'}
        </span>
        <span className="muted">
          {carton.containerType} · {carton.totalQuantity.toLocaleString()} EA
          {carton.weight !== null && ` · ${carton.weight} ${unit}`}
          {showOrder && ` · ${carton.order.orderNumber}`}
        </span>
      </div>
      {carton.contents.length === 0 ? (
        <p className="muted">Empty</p>
      ) : (
        <ul className="carton__contents">
          {carton.contents.map((l) => (
            <li key={l.detailId}>
              <span className="num">{l.quantity.toLocaleString()} EA</span>
              <span>
                <span className="code">{l.item.itemId}</span> <span className="muted">{l.item.description}</span>
                {(l.serialNumber ?? l.lotNumber) && <span className="muted"> · {l.serialNumber ?? l.lotNumber}</span>}
              </span>
            </li>
          ))}
        </ul>
      )}
      {carton.closedBy && <p className="muted carton__by">Packed by {carton.closedBy}</p>}
    </section>
  );
}
