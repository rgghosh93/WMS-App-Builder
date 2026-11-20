import { Layers } from 'lucide-react';
import type { LocationDetail } from '../../api/inventory';
import { ContainerTree, LinesTable } from './InventoryTree';

interface LocationContentsProps {
  detail: LocationDetail;
  /** Code of the base unit, e.g. EA. */
  baseCode: string;
}

/** Everything in one location: loose stock first, then each container and what is inside it. */
export function LocationContents({ detail, baseCode }: LocationContentsProps) {
  const empty = detail.lines.length === 0 && detail.containers.length === 0;

  return (
    <section className="contents" aria-labelledby="contents-title">
      <header className="contents__header">
        <div>
          <h2 id="contents-title" className="code">
            {detail.location.locationId}
          </h2>
          <p className="muted">
            {[detail.location.area, detail.location.zone && `Zone ${detail.location.zone}`].filter(Boolean).join(' · ')}
          </p>
        </div>
        <dl className="summary summary--small">
          <div>
            <dt>Quantity</dt>
            <dd>
              {detail.totalQuantity.toLocaleString()} <span className="summary__unit">{baseCode}</span>
            </dd>
          </div>
          <div>
            <dt>Items</dt>
            <dd>{detail.itemCount}</dd>
          </div>
          <div>
            <dt>LPNs</dt>
            <dd>{detail.containerCount}</dd>
          </div>
        </dl>
      </header>

      {empty && <p className="contents__empty">This location is empty.</p>}

      {detail.lines.length > 0 && (
        <div className="contents__section">
          <h3>
            <Layers size={16} aria-hidden="true" /> Loose in the location
          </h3>
          <LinesTable lines={detail.lines} baseCode={baseCode} label={`Loose stock in ${detail.location.locationId}`} />
        </div>
      )}

      {detail.containers.length > 0 && (
        <div className="contents__section">
          <ContainerTree
            nodes={detail.containers}
            baseCode={baseCode}
            title="Containers"
            label={`Containers in ${detail.location.locationId}`}
            linkContainers
          />
        </div>
      )}
    </section>
  );
}
