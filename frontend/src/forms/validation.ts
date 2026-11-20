/** Message for a required positive decimal with at most `decimals` places, or null when valid. */
export function positiveNumberError(raw: string, decimals: number): string | null {
  const value = raw.trim();
  if (value === '') return 'Required';
  const n = Number(value);
  if (Number.isNaN(n)) return 'Enter a number';
  if (n <= 0) return 'Must be greater than 0';
  const places = decimals === 1 ? '1 decimal place' : `${decimals} decimal places`;
  if (!decimalPattern(decimals).test(value)) return `Up to ${places}`;
  return null;
}

/** Message for a required whole number greater than 0, or null when valid. */
export function positiveIntegerError(raw: string): string | null {
  const value = raw.trim();
  if (value === '') return 'Required';
  if (!/^\d+$/.test(value) || Number(value) <= 0) return 'Whole number, greater than 0';
  return null;
}

/** Digits, optionally followed by a dot and 1..decimals digits. */
function decimalPattern(decimals: number): RegExp {
  return new RegExp('^[0-9]+([.][0-9]{1,' + decimals + '})?$');
}

/** '' → null, otherwise the number. */
export function optionalNumber(raw: string): number | null {
  return raw.trim() === '' ? null : Number(raw);
}
