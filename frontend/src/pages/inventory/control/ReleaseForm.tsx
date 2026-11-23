import { useState, type FormEvent } from 'react';
import { releaseHolds, type HoldResult, type Stock } from '../../../api/control';
import { Drawer } from '../../../components/Drawer';
import { ReasonFields } from './ReasonFields';
import { stockPlace } from './StockPicker';
import { useSubmit } from './useSubmit';

/** Makes held stock good to use again. */
export function ReleaseForm({ stock, onClose, onDone }: { stock: Stock[]; onClose: () => void; onDone: (result: HoldResult) => void }) {
  const [reasonId, setReasonId] = useState('');
  const [note, setNote] = useState('');
  const { errors, formError, saving, submit } = useSubmit();

  const send = (event: FormEvent) => {
    event.preventDefault();
    void submit(reasonId ? {} : { reasonId: 'Choose a reason' }, async () =>
      onDone(await releaseHolds(stock.map((s) => s.id), reasonId, note.trim())),
    );
  };

  return (
    <Drawer
      title="Release"
      onClose={onClose}
      footer={
        <div className="button-row button-row--end">
          <button type="button" className="button" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" form="release-form" className="button button--primary" disabled={saving}>
            {saving ? 'Releasing…' : `Release ${stock.reduce((sum, s) => sum + s.quantity, 0).toLocaleString()} EA`}
          </button>
        </div>
      }
    >
      <form id="release-form" className="form" onSubmit={send} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <ul className="order-list">
          {stock.map((s) => (
            <li key={s.id}>
              <span className="code">{s.item.itemId}</span> {s.quantity.toLocaleString()} EA <span className="muted">{stockPlace(s)}</span>
            </li>
          ))}
        </ul>
        <p className="muted">It can be allocated again at once. Orders that are short don’t get it until they are allocated again.</p>
        <ReasonFields id="release" kind="HOLD" reasonId={reasonId} note={note} error={errors.reasonId} onReason={setReasonId} onNote={setNote} />
      </form>
    </Drawer>
  );
}
