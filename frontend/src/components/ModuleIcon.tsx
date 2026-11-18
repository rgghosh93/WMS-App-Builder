import {
  ArrowDownToLine,
  ArrowUpFromLine,
  BarChart3,
  Boxes,
  Database,
  Layers,
  LayoutGrid,
  ListChecks,
  Plug,
  Receipt,
  RefreshCw,
  ScanBarcode,
  Settings,
  Truck,
  Undo2,
  Users,
  Warehouse,
  type LucideIcon,
} from 'lucide-react';

const ICONS: Record<string, LucideIcon> = {
  database: Database,
  inbound: ArrowDownToLine,
  putaway: Layers,
  inventory: Boxes,
  outbound: ArrowUpFromLine,
  picking: ScanBarcode,
  shipping: Truck,
  replenishment: RefreshCw,
  tasks: ListChecks,
  returns: Undo2,
  labor: Users,
  yard: Warehouse,
  billing: Receipt,
  reporting: BarChart3,
  integration: Plug,
  admin: Settings,
};

export function ModuleIcon({ icon, size = 18 }: { icon: string; size?: number }) {
  const Icon = ICONS[icon] ?? LayoutGrid;
  return <Icon size={size} aria-hidden="true" strokeWidth={1.75} />;
}
