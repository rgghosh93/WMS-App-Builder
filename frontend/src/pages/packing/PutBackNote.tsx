import { useEffect, useState } from 'react';
import { listPutBack, type PutBack } from '../../api/control';

/** What stands at pack stations that no order has any more, and has to go back to storage. Not there when there is nothing. */
export function PutBackNote() {
  const [places, setPlaces] = useState<PutBack[]>([]);
  useEffect(() => {
    const controller = new AbortController();
    listPutBack(controller.signal).then(
      (found) => Array.isArray(found) && setPlaces(found),
      () => undefined,
    );
    return () => controller.abort();
  }, []);
  if (places.length === 0) return null;
  return (
    <section className="section" aria-labelledby="put-back-title">
      <h2 id="put-back-title">To put back</h2>
      <p className="muted">Stock at pack stations that no order has any more. Move it to storage on a mobile device, so that other orders can have it.</p>
      <div className="table-wrap">
        <table className="table" aria-label="To put back">
          <thead>
            <tr>
              <th scope="col">Station</th>
              <th scope="col">Container</th>
              <th scope="col">Items</th>
              <th scope="col" className="num">
                Quantity
              </th>
            </tr>
          </thead>
          <tbody>
            {places.map((p) => (
              <tr key={`${p.location} ${p.lpn ?? ''}`}>
                <th scope="row" className="code">
                  {p.location}
                </th>
                <td className="code">{p.lpn ?? <span className="muted">Loose</span>}</td>
                <td className="code">{itemsOf(p)}</td>
                <td className="num">
                  {p.quantity.toLocaleString()} EA
                  {p.held > 0 && <span className="muted cell__sub">{p.held.toLocaleString()} on hold or damaged</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export function itemsOf(place: PutBack): string {
  return place.items.slice(0, 3).join(', ') + (place.items.length > 3 ? ` +${place.items.length - 3} more` : '');
}
