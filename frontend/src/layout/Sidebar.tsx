import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { ChevronRight, Home } from 'lucide-react';
import { filterModules } from '../api/navigation';
import { useNavigation } from '../navigation/NavigationContext';
import { ModuleIcon } from '../components/ModuleIcon';
import { SearchBox } from '../components/SearchBox';

/** Directory of every WMS page, grouped by module. */
export function Sidebar({ open, onNavigate }: { open: boolean; onNavigate: () => void }) {
  const { state } = useNavigation();
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const toggle = (id: string) =>
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const searching = query.trim().length > 0;
  const modules = state.status === 'ready' ? filterModules(state.modules, query) : [];

  return (
    <nav className={`sidebar${open ? ' sidebar--open' : ''}`} aria-label="Page directory">
      <NavLink to="/" end className="sidebar__home" onClick={onNavigate}>
        <Home size={18} aria-hidden="true" strokeWidth={1.75} />
        Home
      </NavLink>

      <div className="sidebar__search">
        <SearchBox value={query} onChange={setQuery} placeholder="Find a page" label="Find a page in the directory" />
      </div>

      {state.status === 'loading' && <p className="sidebar__note">Loading pages…</p>}
      {state.status === 'error' && <p className="sidebar__note">Directory unavailable</p>}
      {state.status === 'ready' && modules.length === 0 && <p className="sidebar__note">No pages match “{query}”</p>}

      <ul className="sidebar__modules">
        {modules.map((module) => {
          const isOpen = searching || expanded.has(module.id);
          const groupId = `sidebar-group-${module.id}`;
          return (
            <li key={module.id}>
              <button
                type="button"
                className="sidebar__module"
                aria-expanded={isOpen}
                aria-controls={groupId}
                onClick={() => toggle(module.id)}
              >
                <ModuleIcon icon={module.icon} />
                <span className="sidebar__module-name">{module.name}</span>
                <ChevronRight
                  size={16}
                  aria-hidden="true"
                  className={`sidebar__chevron${isOpen ? ' sidebar__chevron--open' : ''}`}
                />
              </button>
              {isOpen && (
                <ul className="sidebar__pages" id={groupId}>
                  {module.pages.map((page) => (
                    <li key={page.id}>
                      {page.status === 'AVAILABLE' ? (
                        <NavLink to={page.route} className="sidebar__page" onClick={onNavigate}>
                          {page.title}
                        </NavLink>
                      ) : (
                        <span className="sidebar__page sidebar__page--planned" aria-disabled="true" title="Not built yet">
                          {page.title}
                          <span className="tag">Soon</span>
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
