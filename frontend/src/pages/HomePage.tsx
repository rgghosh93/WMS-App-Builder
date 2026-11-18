import { useState } from 'react';
import { Link } from 'react-router-dom';
import { filterModules, type ModuleDefinition, type PageDefinition } from '../api/navigation';
import { useNavigation } from '../navigation/NavigationContext';
import { ModuleIcon } from '../components/ModuleIcon';
import { SearchBox } from '../components/SearchBox';

export function HomePage() {
  const { state, reload } = useNavigation();
  const [query, setQuery] = useState('');

  return (
    <div className="home">
      <header className="home__header">
        <div>
          <h1>Home</h1>
          <p className="muted">Every screen in the WMS, grouped by area.</p>
        </div>
        {state.status === 'ready' && <Summary modules={state.modules} />}
      </header>

      {state.status === 'loading' && <p className="muted">Loading pages…</p>}

      {state.status === 'error' && (
        <div className="notice" role="alert">
          <p>{state.message}</p>
          <button type="button" className="button" onClick={reload}>
            Try again
          </button>
        </div>
      )}

      {state.status === 'ready' && (
        <>
          <div className="home__search">
            <SearchBox value={query} onChange={setQuery} placeholder="Search pages" label="Search pages" />
          </div>
          <ModuleSections modules={filterModules(state.modules, query)} query={query} />
        </>
      )}
    </div>
  );
}

function Summary({ modules }: { modules: ModuleDefinition[] }) {
  const pages = modules.flatMap((m) => m.pages);
  const available = pages.filter((p) => p.status === 'AVAILABLE').length;
  return (
    <dl className="summary">
      <div>
        <dt>Areas</dt>
        <dd>{modules.length}</dd>
      </div>
      <div>
        <dt>Pages</dt>
        <dd>{pages.length}</dd>
      </div>
      <div>
        <dt>Available</dt>
        <dd>{available}</dd>
      </div>
    </dl>
  );
}

function ModuleSections({ modules, query }: { modules: ModuleDefinition[]; query: string }) {
  if (modules.length === 0) {
    return <p className="muted">No pages match “{query}”.</p>;
  }
  return (
    <div className="modules">
      {modules.map((module) => (
        <section key={module.id} className="module" aria-labelledby={`module-${module.id}`}>
          <header className="module__header">
            <span className="module__icon">
              <ModuleIcon icon={module.icon} size={20} />
            </span>
            <div>
              <h2 id={`module-${module.id}`}>{module.name}</h2>
              <p className="muted">{module.description}</p>
            </div>
          </header>
          <ul className="cards">
            {module.pages.map((page) => (
              <li key={page.id}>
                <PageCard page={page} />
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}

function PageCard({ page }: { page: PageDefinition }) {
  const body = (
    <>
      <span className="card__title">{page.title}</span>
      <span className="card__description">{page.description}</span>
      {page.status === 'PLANNED' && <span className="tag card__tag">Coming soon</span>}
    </>
  );

  if (page.status === 'AVAILABLE') {
    return (
      <Link to={page.route} className="card card--available">
        {body}
      </Link>
    );
  }
  return (
    <div className="card card--planned" aria-disabled="true">
      {body}
    </div>
  );
}
