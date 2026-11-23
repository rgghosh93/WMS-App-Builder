import { useState } from 'react';
import { ApiError, errorMessage } from '../../../api/http';
import { sentenceCase } from '../../../components/Field';

export type Errors = Partial<Record<string, string>>;

/** Sending a form: what is wrong with it, by field and altogether, and whether it is on its way. */
export function useSubmit() {
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  /** Sends if nothing is wrong already. */
  const submit = async (found: Errors, send: () => Promise<void>) => {
    setErrors(found);
    setFormError(null);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    try {
      await send();
    } catch (error) {
      if (error instanceof ApiError) setErrors(Object.fromEntries(Object.entries(error.fieldErrors).map(([f, m]) => [f, sentenceCase(m)])));
      setFormError(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  return { errors, formError, saving, submit };
}
