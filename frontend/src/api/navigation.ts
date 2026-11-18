export type PageStatus = 'AVAILABLE' | 'PLANNED';

export interface PageDefinition {
  id: string;
  title: string;
  description: string;
  route: string;
  status: PageStatus;
}

export interface ModuleDefinition {
  id: string;
  name: string;
  description: string;
  icon: string;
  pages: PageDefinition[];
}

export async function fetchModules(signal?: AbortSignal): Promise<ModuleDefinition[]> {
  const response = await fetch('/api/navigation/modules', { signal });
  if (!response.ok) {
    throw new Error(`Could not load the page directory (HTTP ${response.status})`);
  }
  return response.json();
}

/**
 * Keeps modules whose name matches the query (with all their pages), and for
 * other modules only the pages whose title or description matches.
 * Modules left with no pages are dropped.
 */
export function filterModules(modules: ModuleDefinition[], query: string): ModuleDefinition[] {
  const q = query.trim().toLowerCase();
  if (!q) return modules;
  return modules
    .map((module) => {
      if (module.name.toLowerCase().includes(q)) return module;
      const pages = module.pages.filter(
        (page) => page.title.toLowerCase().includes(q) || page.description.toLowerCase().includes(q),
      );
      return { ...module, pages };
    })
    .filter((module) => module.pages.length > 0);
}
