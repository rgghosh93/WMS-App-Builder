import { getAsn, type AsnDetail, type AsnReceipt } from '../../api/asns';
import { Barcode } from '../../components/Barcode';
import { formatDateTime, formatDay } from '../outbound/OrdersPage';
import { PrintPage } from '../shipping/PrintPages';

/** One label per LPN received on an ASN. */
export function LpnLabelsPage() {
  return (
    <PrintPage load={getAsn} title="LPN labels">
      {(asn: AsnDetail) => {
        const lpns = new Map<string, AsnReceipt[]>();
        for (const r of asn.receipts.filter((receipt) => !receipt.reversedAt)) {
          lpns.set(r.lpn.lpnId, [...(lpns.get(r.lpn.lpnId) ?? []), r]);
        }
        if (lpns.size === 0) return <p className="muted">Nothing has been received on {asn.asnNumber} yet.</p>;
        return [...lpns].map(([lpnId, receipts]) => {
          const contents = new Map<string, { receipt: AsnReceipt; quantity: number }>();
          for (const r of receipts) {
            const key = [r.itemId, r.lotNumber, r.expiryDate].join('|');
            contents.set(key, { receipt: r, quantity: (contents.get(key)?.quantity ?? 0) + r.quantity });
          }
          return (
            <article key={lpnId} className="doc-label" aria-label={`Label ${lpnId}`}>
              <div className="doc-label__row">
                <div>
                  <span className="doc-label__tag">ASN</span>
                  <strong>{asn.asnNumber}</strong>
                </div>
                <div>
                  <span className="doc-label__tag">Vendor</span>
                  <strong>{asn.vendorName}</strong>
                </div>
                <div>
                  <span className="doc-label__tag">Received</span>
                  <strong>{formatDateTime(receipts[receipts.length - 1].receivedAt)}</strong>
                </div>
              </div>
              <div className="doc-label__to">
                <span className="doc-label__tag">Contents</span>
                {[...contents.values()].slice(0, 4).map(({ receipt, quantity }, i) => (
                  <span key={i}>
                    <span className="doc-label__name">{receipt.itemId}</span> {quantity.toLocaleString()} EA
                    {receipt.lotNumber && ` · lot ${receipt.lotNumber}`}
                    {receipt.expiryDate && ` · expires ${formatDay(receipt.expiryDate)}`}
                  </span>
                ))}
                {contents.size > 4 && <span>and {contents.size - 4} more</span>}
              </div>
              <Barcode value={lpnId} height={110} />
            </article>
          );
        });
      }}
    </PrintPage>
  );
}
