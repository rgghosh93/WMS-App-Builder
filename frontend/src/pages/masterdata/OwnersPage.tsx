import { listOwners, saveOwner, type Owner } from '../../api/owners';
import { SimpleRecords } from './SimpleRecords';

/** Whose stock it is. */
export function OwnersPage() {
  return (
    <SimpleRecords<Owner>
      title="Owners"
      noun="owner"
      nouns="owners"
      permission="MASTER_DATA"
      intro="Whose stock it is: your own company, and the clients you keep stock for. Every item, order and ASN has one, and one owner's stock never goes to another's orders. Two owners can have items with the same ID. Owners are never deleted, only made inactive."
      load={listOwners}
      save={saveOwner}
    />
  );
}
