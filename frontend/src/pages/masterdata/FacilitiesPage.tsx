import { useState } from 'react';
import { listFacilities, saveFacility, type Facility } from '../../api/facilities';
import { SimpleRecords } from './SimpleRecords';

/** The buildings the warehouse is run in. */
export function FacilitiesPage() {
  const [address, setAddress] = useState('');
  return (
    <SimpleRecords<Facility>
      title="Facilities"
      noun="facility"
      nouns="facilities"
      permission="FACILITIES"
      intro="Buildings the warehouse is run in. Each has its own locations, stock, orders and work; items, carriers and other master data are shared. You work in one at a time, chosen at the top of the screen. Facilities are never deleted, only made inactive."
      load={listFacilities}
      onOpen={(facility) => setAddress(facility?.address ?? '')}
      save={(id, input) => saveFacility(id, { ...input, address: address.trim() || null })}
      extraColumn={{ label: 'Address', value: (f) => f.address }}
      extraField={{ id: 'record-address', label: 'Address', maxLength: 400, value: address, onChange: setAddress }}
    />
  );
}
