import { Field } from '../../components/Field';
import type { ShipTo } from '../../api/shipping';

export type AddressValues = Record<keyof ShipTo, string>;

export const EMPTY_ADDRESS: AddressValues = { name: '', line1: '', line2: '', city: '', state: '', postalCode: '', country: 'US' };

export function addressValues(shipTo: ShipTo | null): AddressValues {
  return shipTo ? { ...shipTo, line2: shipTo.line2 ?? '', state: shipTo.state ?? '' } : EMPTY_ADDRESS;
}

/** Nothing entered (the country is filled in to start with, so it doesn't count). */
export function isBlankAddress(v: AddressValues): boolean {
  return !v.name.trim() && !v.line1.trim() && !v.line2.trim() && !v.city.trim() && !v.state.trim() && !v.postalCode.trim();
}

export function addressErrors(v: AddressValues): Partial<Record<keyof ShipTo, string>> {
  const found: Partial<Record<keyof ShipTo, string>> = {};
  if (!v.name.trim()) found.name = 'Enter a name';
  if (!v.line1.trim()) found.line1 = 'Enter a street address';
  if (!v.city.trim()) found.city = 'Enter a city';
  if (!v.postalCode.trim()) found.postalCode = 'Enter a postal code';
  if (!/^[A-Za-z]{2}$/.test(v.country.trim())) found.country = 'Use a 2-letter country code';
  return found;
}

export function toShipTo(v: AddressValues): ShipTo {
  return {
    name: v.name.trim(),
    line1: v.line1.trim(),
    line2: v.line2.trim() || null,
    city: v.city.trim(),
    state: v.state.trim() || null,
    postalCode: v.postalCode.trim(),
    country: v.country.trim().toUpperCase(),
  };
}

interface AddressFieldsProps {
  values: AddressValues;
  errors: Partial<Record<keyof ShipTo, string>>;
  onChange: (field: keyof ShipTo, value: string) => void;
}

export function AddressFields({ values, errors, onChange }: AddressFieldsProps) {
  const input = (field: keyof ShipTo, label: string, maxLength: number, autoComplete: string) => (
    <Field id={`address-${field}`} label={label} error={errors[field]}>
      <input
        id={`address-${field}`}
        value={values[field]}
        maxLength={maxLength}
        autoComplete={autoComplete}
        aria-invalid={Boolean(errors[field])}
        onChange={(e) => onChange(field, e.target.value)}
      />
    </Field>
  );
  return (
    <>
      {input('name', 'Name', 120, 'off')}
      <div className="grid grid--2">
        {input('line1', 'Street address', 120, 'off')}
        {input('line2', 'Suite, unit (optional)', 120, 'off')}
      </div>
      <div className="grid grid--4">
        {input('city', 'City', 80, 'off')}
        {input('state', 'State', 40, 'off')}
        {input('postalCode', 'Postal code', 20, 'off')}
        {input('country', 'Country', 2, 'off')}
      </div>
    </>
  );
}
