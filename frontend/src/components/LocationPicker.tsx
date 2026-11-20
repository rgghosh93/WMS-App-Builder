import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { X } from 'lucide-react';
import { listLocations, type Location } from '../api/locations';
import type { LocationRef } from '../api/containers';

interface LocationPickerProps {
  id: string;
  value: LocationRef | null;
  onChange: (value: LocationRef | null) => void;
  invalid?: boolean;
  placeholder?: string;
}

const MAX_OPTIONS = 8;

/**
 * Type-ahead for choosing a location by its ID. Searches as you type and
 * shows up to eight matches; arrow keys and Enter pick one.
 */
export function LocationPicker({ id, value, onChange, invalid, placeholder = 'Search for a location ID' }: LocationPickerProps) {
  const listId = useId();
  const [text, setText] = useState(value?.locationId ?? '');
  const [open, setOpen] = useState(false);
  const [options, setOptions] = useState<Location[]>([]);
  const [active, setActive] = useState(0);
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    setText(value?.locationId ?? '');
  }, [value?.locationId]);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    const handle = setTimeout(() => {
      setLoading(true);
      listLocations({ q: text.trim(), type: '', page: 0, size: MAX_OPTIONS, sort: 'locationId', dir: 'asc' }, controller.signal)
        .then((page) => {
          setOptions(page.content);
          setActive(0);
        })
        .catch(() => {
          if (!controller.signal.aborted) setOptions([]);
        })
        .finally(() => {
          if (!controller.signal.aborted) setLoading(false);
        });
    }, 200);
    return () => {
      clearTimeout(handle);
      controller.abort();
    };
  }, [text, open]);

  const choose = (location: Location) => {
    onChange({ id: location.id, locationId: location.locationId });
    setText(location.locationId);
    setOpen(false);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setOpen(true);
      setActive((i) => Math.min(i + 1, options.length - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((i) => Math.max(i - 1, 0));
    } else if (event.key === 'Enter' && open && options[active]) {
      event.preventDefault();
      choose(options[active]);
    } else if (event.key === 'Escape' && open) {
      event.stopPropagation();
      setOpen(false);
    }
  };

  return (
    <div className="picker">
      <div className="picker__control">
        <input
          ref={inputRef}
          id={id}
          className="input--code"
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={open && options[active] ? `${listId}-${options[active].id}` : undefined}
          aria-invalid={invalid}
          autoComplete="off"
          placeholder={placeholder}
          value={text}
          onChange={(e) => {
            setText(e.target.value.toUpperCase());
            setOpen(true);
            if (value) onChange(null);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => {
            setOpen(false);
            if (!value) setText('');
          }}
          onKeyDown={onKeyDown}
        />
        {value && (
          <button
            type="button"
            className="icon-button picker__clear"
            aria-label="Clear location"
            onClick={() => {
              onChange(null);
              setText('');
              inputRef.current?.focus();
            }}
          >
            <X size={14} aria-hidden="true" />
          </button>
        )}
      </div>
      {open && (
        <ul className="picker__list" id={listId} role="listbox">
          {options.map((location, index) => (
            <li
              key={location.id}
              id={`${listId}-${location.id}`}
              role="option"
              aria-selected={index === active}
              className={`picker__option${index === active ? ' picker__option--active' : ''}`}
              onMouseDown={(e) => {
                e.preventDefault();
                choose(location);
              }}
              onMouseEnter={() => setActive(index)}
            >
              <span className="code">{location.locationId}</span>
            </li>
          ))}
          {!loading && options.length === 0 && <li className="picker__empty">No matching locations</li>}
        </ul>
      )}
    </div>
  );
}
