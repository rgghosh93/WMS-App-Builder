import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './layout/AppShell';
import { RolesPage } from './pages/admin/RolesPage';
import { UsersPage } from './pages/admin/UsersPage';
import { HomePage } from './pages/HomePage';
import { AsnDetailPage } from './pages/inbound/AsnDetailPage';
import { AsnsPage } from './pages/inbound/AsnsPage';
import { AdjustmentsPage } from './pages/inventory/control/AdjustmentsPage';
import { CycleCountDetailPage } from './pages/inventory/control/CycleCountDetailPage';
import { CycleCountsPage } from './pages/inventory/control/CycleCountsPage';
import { HoldsPage } from './pages/inventory/control/HoldsPage';
import { InventoryByItemPage } from './pages/inventory/control/InventoryByItemPage';
import { ReasonCodesPage } from './pages/inventory/control/ReasonCodesPage';
import { InventoryMovesPage, StockHistoryPage } from './pages/inventory/control/StockHistoryPage';
import { LpnLabelsPage } from './pages/inbound/LpnLabelsPage';
import { InventoryByContainerPage } from './pages/inventory/InventoryByContainerPage';
import { AllocationsPage } from './pages/outbound/AllocationsPage';
import { OrderDetailPage } from './pages/outbound/OrderDetailPage';
import { OrdersPage } from './pages/outbound/OrdersPage';
import { PickTaskDetailPage } from './pages/picking/PickTaskDetailPage';
import { FacilitiesPage } from './pages/masterdata/FacilitiesPage';
import { OwnersPage } from './pages/masterdata/OwnersPage';
import { CarriersPage } from './pages/masterdata/CarriersPage';
import { ZoneDetailPage } from './pages/masterdata/ZoneDetailPage';
import { ZonesPage } from './pages/masterdata/ZonesPage';
import { ManifestDetailPage } from './pages/shipping/ManifestDetailPage';
import { ManifestsPage } from './pages/shipping/ManifestsPage';
import { LabelsPage, ManifestPrintPage, PackingSlipPage } from './pages/shipping/PrintPages';
import { ShipmentDetailPage } from './pages/shipping/ShipmentDetailPage';
import { ShipmentsPage } from './pages/shipping/ShipmentsPage';
import { PackStationApp } from './pages/pack/PackStationApp';
import { PackTaskDetailPage } from './pages/packing/PackTaskDetailPage';
import { PackTasksPage } from './pages/packing/PackTasksPage';
import { ReplenishmentRulesPage } from './pages/replenishment/ReplenishmentRulesPage';
import { ReplenishmentTasksPage } from './pages/replenishment/ReplenishmentTasksPage';
import { TaskQueuePage } from './pages/tasks/TaskQueuePage';
import { TaskSettingsPage } from './pages/tasks/TaskSettingsPage';
import { PutawayRulesPage } from './pages/putaway/PutawayRulesPage';
import { PutawayTaskDetailPage } from './pages/putaway/PutawayTaskDetailPage';
import { PutawayTasksPage } from './pages/putaway/PutawayTasksPage';
import { RfApp } from './pages/rf/RfApp';
import { PickTasksPage } from './pages/picking/PickTasksPage';
import { InventoryByLocationPage } from './pages/inventory/InventoryByLocationPage';
import { ContainersPage } from './pages/masterdata/ContainersPage';
import { ContainerTypesPage } from './pages/masterdata/ContainerTypesPage';
import { ItemsPage } from './pages/masterdata/ItemsPage';
import { LocationsPage } from './pages/masterdata/LocationsPage';
import { UomsPage } from './pages/masterdata/UomsPage';
import { DesktopGate } from './session/DesktopGate';

export function App() {
  return (
    <Routes>
      <Route path="rf/*" element={<RfApp />} />
      <Route path="pack" element={<PackStationApp />} />
      <Route element={<DesktopGate />}>
        <Route path="print/shipments/:id/labels" element={<LabelsPage />} />
        <Route path="print/shipments/:id/packing-slip" element={<PackingSlipPage />} />
        <Route path="print/manifests/:id" element={<ManifestPrintPage />} />
        <Route path="print/asns/:id/lpn-labels" element={<LpnLabelsPage />} />
        <Route element={<AppShell />}>
          <Route index element={<HomePage />} />
          <Route path="master-data/locations" element={<LocationsPage />} />
          <Route path="master-data/facilities" element={<FacilitiesPage />} />
          <Route path="master-data/owners" element={<OwnersPage />} />
          <Route path="master-data/zones" element={<ZonesPage />} />
          <Route path="master-data/zones/:id" element={<ZoneDetailPage />} />
          <Route path="master-data/items" element={<ItemsPage />} />
          <Route path="master-data/container-types" element={<ContainerTypesPage />} />
          <Route path="master-data/containers" element={<ContainersPage />} />
          <Route path="master-data/units-of-measure" element={<UomsPage />} />
          <Route path="inventory/by-location" element={<InventoryByLocationPage />} />
          <Route path="inventory/by-item" element={<InventoryByItemPage />} />
          <Route path="inventory/history" element={<StockHistoryPage />} />
          <Route path="inventory/moves" element={<InventoryMovesPage />} />
          <Route path="inventory/adjustments" element={<AdjustmentsPage />} />
          <Route path="inventory/holds" element={<HoldsPage />} />
          <Route path="inventory/counts" element={<CycleCountsPage />} />
          <Route path="inventory/counts/:id" element={<CycleCountDetailPage />} />
          <Route path="inventory/reasons" element={<ReasonCodesPage />} />
          <Route path="inventory/by-container" element={<InventoryByContainerPage />} />
          <Route path="inbound/asns" element={<AsnsPage />} />
          <Route path="inbound/asns/:id" element={<AsnDetailPage />} />
          <Route path="putaway/tasks" element={<PutawayTasksPage />} />
          <Route path="putaway/tasks/:id" element={<PutawayTaskDetailPage />} />
          <Route path="putaway/rules" element={<PutawayRulesPage />} />
          <Route path="tasks/queue" element={<TaskQueuePage />} />
          <Route path="tasks/settings" element={<TaskSettingsPage />} />
          <Route path="replenishment/tasks" element={<ReplenishmentTasksPage />} />
          <Route path="replenishment/rules" element={<ReplenishmentRulesPage />} />
          <Route path="outbound/orders" element={<OrdersPage />} />
          <Route path="outbound/orders/:id" element={<OrderDetailPage />} />
          <Route path="outbound/allocations" element={<AllocationsPage />} />
          <Route path="picking/tasks" element={<PickTasksPage />} />
          <Route path="picking/tasks/:id" element={<PickTaskDetailPage />} />
          <Route path="pack-ship/packing-tasks" element={<PackTasksPage />} />
          <Route path="pack-ship/packing-tasks/:id" element={<PackTaskDetailPage />} />
          <Route path="pack-ship/shipments" element={<ShipmentsPage />} />
          <Route path="pack-ship/shipments/:id" element={<ShipmentDetailPage />} />
          <Route path="pack-ship/manifests" element={<ManifestsPage />} />
          <Route path="pack-ship/manifests/:id" element={<ManifestDetailPage />} />
          <Route path="master-data/carriers" element={<CarriersPage />} />
          <Route path="admin/users" element={<UsersPage />} />
          <Route path="admin/roles" element={<RolesPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}
