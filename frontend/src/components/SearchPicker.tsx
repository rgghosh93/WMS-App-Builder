import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { X } from 'lucide-react';

interface SearchPickerProps<T> {
  id: string;
  /** The chosen option, or null. */
  value: T | null;
  onChange: (value: T | null) => void;
  /** Finds options for what was typed (up to a handful). */
  search: (text: string, signal: AbortSignal) => Promise<T[]>;
  optionKey: (option: T) => string;
  /** Text shown in the box once an option is chosen. */
  optionText: (option: T) => string;
  /** How an option looks in the list; defaults to its text. */
  renderOption?: (option: T) => ReactNode;
  invalid?: boolean;
  placeholder?: string;
  clearLabel?: string;
  emptyText?: string;
  /** Upper-case what is typed (for codes). */
  uppercase?: boolean;
}

/** A type-ahead box: searches as you type; arrow keys and Enter (or a click) pick an option. */
export function SearchPicker<T>({
  id,
  value,
  onChange,
  search,
  optionKey,
  optionText,
  renderOption,
  invalid,
  placeholder,
  clearLabel = 'Clear',
  emptyText = 'No matches',
  uppercase = false,
}: SearchPickerProps<T>) {
  const listId = useId();
  const [text, setText] = useState(value ? optionText(value) : '');
  const [open, setOpen] = useState(false);
  const [options, setOptions] = useState<T[]>([]);
  const [active, setActive] = useState(0);
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const searchRef = useRef(search);
  searchRef.current = search;

  const valueText = value ? optionText(value) : '';
  useEffect(() => {
    setText(valueText);
  }, [valueText]);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    const handle = setTimeout(() => {
      setLoading(true);
      searchRef.current(text.trim(), controller.signal)
        .then((found) => {
          setOptions(found);
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

  const choose = (option: T) => {
    onChange(option);
    setText(optionText(option));
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
          className={uppercase ? 'input--code' : undefined}
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={open && options[active] ? `${listId}-${optionKey(options[active])}` : undefined}
          aria-invalid={invalid}
          autoComplete="off"
          placeholder={placeholder}
          value={text}
          onChange={(e) => {
            setText(uppercase ? e.target.value.toUpperCase() : e.target.value);
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
            aria-label={clearLabel}
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
          {options.map((option, index) => (
            <li
              key={optionKey(option)}
              id={`${listId}-${optionKey(option)}`}
              role="option"
              aria-selected={index === active}
              className={`picker__option${index === active ? ' picker__option--active' : ''}`}
              onMouseDown={(e) => {
                e.preventDefault();
                choose(option);
              }}
              onMouseEnter={() => setActive(index)}
            >
              {renderOption ? renderOption(option) : optionText(option)}
            </li>
          ))}
          {!loading && options.length === 0 && <li className="picker__empty">{emptyText}</li>}
        </ul>
      )}
    </div>
  );
}
