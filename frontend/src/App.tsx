import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './layout/AppShell';
import { HomePage } from './pages/HomePage';
import { InventoryByContainerPage } from './pages/inventory/InventoryByContainerPage';
import { AllocationsPage } from './pages/outbound/AllocationsPage';
import { OrderDetailPage } from './pages/outbound/OrderDetailPage';
import { OrdersPage } from './pages/outbound/OrdersPage';
import { PickTaskDetailPage } from './pages/picking/PickTaskDetailPage';
import { PickTasksPage } from './pages/picking/PickTasksPage';
import { InventoryByLocationPage } from './pages/inventory/InventoryByLocationPage';
import { ContainersPage } from './pages/masterdata/ContainersPage';
import { ContainerTypesPage } from './pages/masterdata/ContainerTypesPage';
import { ItemsPage } from './pages/masterdata/ItemsPage';
import { LocationsPage } from './pages/masterdata/LocationsPage';
import { UomsPage } from './pages/masterdata/UomsPage';

export function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<HomePage />} />
        <Route path="master-data/locations" element={<LocationsPage />} />
        <Route path="master-data/items" element={<ItemsPage />} />
        <Route path="master-data/container-types" element={<ContainerTypesPage />} />
        <Route path="master-data/containers" element={<ContainersPage />} />
        <Route path="master-data/units-of-measure" element={<UomsPage />} />
        <Route path="inventory/by-location" element={<InventoryByLocationPage />} />
        <Route path="inventory/by-container" element={<InventoryByContainerPage />} />
        <Route path="outbound/orders" element={<OrdersPage />} />
        <Route path="outbound/orders/:id" element={<OrderDetailPage />} />
        <Route path="outbound/allocations" element={<AllocationsPage />} />
        <Route path="picking/tasks" element={<PickTasksPage />} />
        <Route path="picking/tasks/:id" element={<PickTaskDetailPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
