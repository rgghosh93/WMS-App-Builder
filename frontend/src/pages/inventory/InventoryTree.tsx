import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Box, ChevronRight, Container as PalletIcon, ExternalLink, Package } from 'lucide-react';
import { containerIds, inventoryStatusLabel, packBreakdown, type ContainerNode, type InventoryLine } from '../../api/inventory';
import { containerStatusLabel } from '../../api/containers';
import { categoryLabel, type ContainerCategory } from '../../api/containerTypes';

interface ContainerTreeProps {
  nodes: ContainerNode[];
  baseCode: string;
  /** Accessible name for the tree, e.g. "Containers in DC1-RS-01-01-A-01". */
  label: string;
  /** Heading shown above the tree, with expand/collapse controls. */
  title?: string;
  /** Show a link on each LPN to open it in the container view. */
  linkContainers?: boolean;
  /** Don't offer a link for this container (it is the one already shown). */
  currentContainerId?: string;
}

/** Containers as an expandable tree: each LPN with its own stock and the LPNs inside it. */
export function ContainerTree({ nodes, baseCode, label, title, linkContainers = false, currentContainerId }: ContainerTreeProps) {
  const allIds = useMemo(() => containerIds(nodes), [nodes]);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(allIds));

  // New data (another location or container): start with everything open again.
  useEffect(() => setExpanded(new Set(allIds)), [allIds]);

  const toggle = (id: string) =>
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <>
      {(title || allIds.length > 1) && (
        <div className="contents__section-head">
          {title ? (
            <h3>
              <Box size={16} aria-hidden="true" /> {title}
            </h3>
          ) : (
            <span />
          )}
          {allIds.length > 1 && (
            <div className="button-row">
              <button type="button" className="link-button link-button--small" onClick={() => setExpanded(new Set(allIds))}>
                Expand all
              </button>
              <button type="button" className="link-button link-button--small" onClick={() => setExpanded(new Set())}>
                Collapse all
              </button>
            </div>
          )}
        </div>
      )}
      <ul className="tree" role="tree" aria-label={label}>
        {nodes.map((node) => (
          <TreeNode
            key={node.id}
            node={node}
            depth={1}
            expanded={expanded}
            onToggle={toggle}
            baseCode={baseCode}
            linkContainers={linkContainers}
            currentContainerId={currentContainerId}
          />
        ))}
      </ul>
    </>
  );
}

interface TreeNodeProps {
  node: ContainerNode;
  depth: number;
  expanded: Set<string>;
  onToggle: (id: string) => void;
  baseCode: string;
  linkContainers: boolean;
  currentContainerId?: string;
}

function TreeNode({ node, depth, expanded, onToggle, baseCode, linkContainers, currentContainerId }: TreeNodeProps) {
  const isOpen = expanded.has(node.id);
  const hasContents = node.lines.length > 0 || node.children.length > 0;
  const bodyId = `lpn-${node.id}`;
  const nested = node.children.length;

  return (
    <li className="tree__node" role="treeitem" aria-level={depth} aria-expanded={hasContents ? isOpen : undefined}>
      <div className="tree__row">
        <button
          type="button"
          className="tree__head"
          aria-controls={hasContents ? bodyId : undefined}
          onClick={() => hasContents && onToggle(node.id)}
          disabled={!hasContents}
        >
          <ChevronRight
            size={16}
            aria-hidden="true"
            className={`tree__chevron${isOpen ? ' tree__chevron--open' : ''}${hasContents ? '' : ' tree__chevron--hidden'}`}
          />
          <CategoryIcon category={node.containerType.category} />
          <span className="tree__lpn code">{node.lpnId}</span>
          <span className="tree__type">
            {categoryLabel(node.containerType.category)} · {node.containerType.containerTypeId}
          </span>
          <span className={`badge badge--${node.status.toLowerCase().replace('_', '-')}`}>{containerStatusLabel(node.status)}</span>
          {node.permanent && <span className="tag">Permanent</span>}
          <span className="tree__totals">
            {hasContents ? (
              <>
                {node.totalQuantity.toLocaleString()} {baseCode}
                {node.itemCount > 0 && ` · ${node.itemCount} item${node.itemCount === 1 ? '' : 's'}`}
                {nested > 0 && ` · ${nested} LPN${nested === 1 ? '' : 's'} inside`}
              </>
            ) : (
              <span className="muted">Empty</span>
            )}
          </span>
        </button>
        {linkContainers && node.id !== currentContainerId && (
          <Link
            className="icon-button tree__open"
            to={`/inventory/by-container?container=${node.id}`}
            aria-label={`Open ${node.lpnId} in the container view`}
            title="Open in the container view"
          >
            <ExternalLink size={14} aria-hidden="true" />
          </Link>
        )}
      </div>

      {hasContents && isOpen && (
        <div className="tree__body" id={bodyId}>
          {node.lines.length > 0 && <LinesTable lines={node.lines} baseCode={baseCode} label={`Stock in ${node.lpnId}`} />}
          {node.children.length > 0 && (
            <ul className="tree tree--nested" role="group">
              {node.children.map((child) => (
                <TreeNode
                  key={child.id}
                  node={child}
                  depth={depth + 1}
                  expanded={expanded}
                  onToggle={onToggle}
                  baseCode={baseCode}
                  linkContainers={linkContainers}
                  currentContainerId={currentContainerId}
                />
              ))}
            </ul>
          )}
        </div>
      )}
    </li>
  );
}

export function CategoryIcon({ category }: { category: ContainerCategory }) {
  const Icon = category === 'PALLET' || category === 'CAGE' ? PalletIcon : category === 'CARTON' || category === 'CASE' ? Package : Box;
  return <Icon size={16} aria-hidden="true" className="tree__icon" />;
}

/** Stock lines: item, quantity (with pack breakdown) and attributes. Lot/serial/expiry columns show only when used. */
export function LinesTable({ lines, baseCode, label }: { lines: InventoryLine[]; baseCode: string; label: string }) {
  const showLot = lines.some((l) => l.lotNumber);
  const showSerial = lines.some((l) => l.serialNumber);
  const showExpiry = lines.some((l) => l.expiryDate);
  const showAllocated = lines.some((l) => l.allocatedQuantity > 0);

  return (
    <div className="table-wrap table-wrap--flat">
      <table className="table table--compact" aria-label={label}>
        <thead>
          <tr>
            <th scope="col">Item</th>
            <th scope="col" className="num">
              Quantity
            </th>
            {showAllocated && (
              <th scope="col" className="num">
                Allocated
              </th>
            )}
            {showLot && <th scope="col">Lot</th>}
            {showSerial && <th scope="col">Serial</th>}
            {showExpiry && <th scope="col">Expiry</th>}
            <th scope="col">Status</th>
            <th scope="col">Received</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => {
            const breakdown = packBreakdown(line.quantity, line.item.packs, baseCode);
            return (
              <tr key={line.id}>
                <td>
                  <span className="code">{line.item.itemId}</span>
                  <span className="muted cell__sub">{line.item.description}</span>
                </td>
                <td className="num">
                  {line.quantity.toLocaleString()} {baseCode}
                  {breakdown && <span className="muted cell__sub">{breakdown}</span>}
                </td>
                {showAllocated && (
                  <td className="num">
                    {line.allocatedQuantity > 0 ? (
                      <span className="allocated" title="Reserved for orders">
                        {line.allocatedQuantity.toLocaleString()} {baseCode}
                      </span>
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                )}
                {showLot &&<td className="code">{line.lotNumber ?? '—'}</td>}
                {showSerial && <td className="code">{line.serialNumber ?? '—'}</td>}
                {showExpiry && <td>{line.expiryDate ? formatDate(line.expiryDate) : '—'}</td>}
                <td>
                  <span className={`badge badge--inv-${line.status.toLowerCase().replace('_', '-')}`}>{inventoryStatusLabel(line.status)}</span>
                </td>
                <td className="muted">{formatDate(line.receivedAt)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function formatDate(value: string): string {
  // Expiry dates are plain dates (no time zone); show them as written.
  const date = value.length === 10 ? new Date(`${value}T00:00:00`) : new Date(value);
  return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}
