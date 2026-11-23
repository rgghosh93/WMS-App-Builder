import { useEffect, useState } from 'react';
import type { ActiveStatus } from './containerTypes';
import { request } from './http';

export interface OwnerRef {
  id: string;
  code: string;
  name: string;
}

export interface Owner extends OwnerRef {
  status: ActiveStatus;
  version: number;
}

export interface OwnerInput {
  code: string;
  name: string;
  status: ActiveStatus;
  version?: number;
}

export function listOwners(signal?: AbortSignal): Promise<Owner[]> {
  return request('/api/owners', { signal });
}

export function saveOwner(id: string | null, input: OwnerInput): Promise<Owner> {
  return request(id ? `/api/owners/${id}` : '/api/owners', { method: id ? 'PUT' : 'POST', body: JSON.stringify(input) });
}

/**
 * The owners, for screens that tell them apart. With only one there is nothing to tell apart, so nothing about owners
 * is shown; nor if they can't be had, as everything else still works.
 */
export function useOwners(): { owners: Owner[]; several: boolean } {
  const [owners, setOwners] = useState<Owner[]>([]);
  useEffect(() => {
    const controller = new AbortController();
    listOwners(controller.signal).then(
      (found) => Array.isArray(found) && setOwners(found),
      () => undefined,
    );
    return () => controller.abort();
  }, []);
  return { owners, several: owners.length > 1 };
}

/** Owners something could belong to, when the server asks which. */
export function ownersAsked(fieldErrors: Record<string, string>): string[] {
  return fieldErrors.owner?.includes(',') ? fieldErrors.owner.split(',') : [];
}
