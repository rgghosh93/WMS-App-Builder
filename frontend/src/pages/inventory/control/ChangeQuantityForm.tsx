import { useState, type FormEvent } from 'react';
import { changeQuantity, type Adjustment, type Stock } from '../../../api/control';
import { Drawer } from '../../../components/Drawer';
import { Field } from '../../../components/Field';
import { ReasonFields } from './ReasonFields';
import { StockPicker, stockDetail } from './StockPicker';
import { useSubmit } from './useSubmit';

interface ChangeQuantityFormProps {
  limit: number | null;
  onClose: () => void;
  onDone: (adjustment: Adjustment) => void;
}

/** A new quantity for stock there is a record of. */
export function ChangeQuantityForm({ limit, onClose, onDone }: ChangeQuantityFormProps) {
  const [stock, setStock] = useState<Stock | null>(null);
  const [quantity, setQuantity] = useState('');
  const [reasonId, setReasonId] = useState('');
  const [note, setNote] = useState('');
  const { errors, formError, saving, submit } = useSubmit();

  const valid = /^[0-9]+$/.test(quantity.trim());
  const change = stock && valid ? Number(quantity) - stock.quantity : 0;

  const send = (event: FormEvent) => {
    event.preventDefault();
    void submit(
      {
        ...(stock ? {} : { inventoryId: 'Choose the stock to correct' }),
        ...(valid ? {} : { newQuantity: 'Enter how many there really are' }),
        ...(reasonId ? {} : { reasonId: 'Choose a reason' }),
      },
      async () => onDone(await changeQuantity({ inventoryId: stock!.id, expectedQuantity: stock!.quantity, newQuantity: Number(quantity), reasonId, note: note.trim() })),
    );
  };

  return (
    <Drawer
      title="Correct a quantity"
      onClose={onClose}
      footer={
        <div className="button-row button-row--end">
          <button type="button" className="button" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" form="change-form" className="button button--primary" disabled={saving}>
            {saving ? 'Saving…' : 'Correct it'}
          </button>
        </div>
      }
    >
      <form id="change-form" className="form" onSubmit={send} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <Field id="change-stock" label="Stock" error={errors.inventoryId}>
          <StockPicker id="change-stock" value={stock} invalid={Boolean(errors.inventoryId)} onChange={setStock} />
        </Field>
        {stock && (
          <p className="notice-info">
            {stock.item.description}. There are {stock.quantity.toLocaleString()} EA on record
            {stock.reserved > 0 && `, ${stock.reserved.toLocaleString()} of them reserved`}. {stockDetail(stock)}
          </p>
        )}
        <Field id="change-quantity" label="Really there (EA)" error={errors.newQuantity}>
          <input
            id="change-quantity"
            type="number"
            inputMode="numeric"
            min="0"
            step="1"
            value={quantity}
            aria-invalid={Boolean(errors.newQuantity)}
            onChange={(e) => setQuantity(e.target.value)}
          />
        </Field>
        {change !== 0 && (
          <p className="muted" role="status">
            {change > 0 ? `${change.toLocaleString()} EA more` : `${(-change).toLocaleString()} EA less`}
            {limit !== null && Math.abs(change) > limit && '. Someone else will have to approve this.'}
          </p>
        )}
        <ReasonFields id="change" kind="ADJUSTMENT" reasonId={reasonId} note={note} error={errors.reasonId} onReason={setReasonId} onNote={setNote} />
      </form>
    </Drawer>
  );
}
