import { useState, type FormEvent } from 'react';
import { ApiError, errorMessage } from '../../api/http';
import { setShipTo, type ShipTo } from '../../api/shipping';
import { Drawer } from '../../components/Drawer';
import { sentenceCase } from '../../components/Field';
import { AddressFields, addressErrors, addressValues, toShipTo } from './AddressFields';

interface AddressFormProps {
  order: { id: string; orderNumber: string };
  shipTo: ShipTo | null;
  onClose: () => void;
  onSaved: (shipTo: ShipTo) => void;
}

/** Edits where an order goes. */
export function AddressForm({ order, shipTo, onClose, onSaved }: AddressFormProps) {
  const [values, setValues] = useState(() => addressValues(shipTo));
  const [errors, setErrors] = useState<Partial<Record<keyof ShipTo, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = addressErrors(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    try {
      onSaved(await setShipTo(order.id, toShipTo(values)));
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(Object.fromEntries(Object.entries(error.fieldErrors).map(([f, m]) => [f, sentenceCase(m)])));
      }
      setFormError(errorMessage(error));
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={`Ship ${order.orderNumber} to`}
      onClose={onClose}
      footer={
        <div className="button-row button-row--end">
          <button type="button" className="button" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" form="address-form" className="button button--primary" disabled={saving}>
            {saving ? 'Saving…' : 'Save address'}
          </button>
        </div>
      }
    >
      <form id="address-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <AddressFields
          values={values}
          errors={errors}
          onChange={(field, value) => {
            setValues((v) => ({ ...v, [field]: value }));
            setErrors((e) => ({ ...e, [field]: undefined }));
          }}
        />
      </form>
    </Drawer>
  );
}
