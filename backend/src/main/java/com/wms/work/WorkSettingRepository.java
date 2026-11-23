package com.wms.work;

import org.springframework.data.jpa.repository.JpaRepository;

interface WorkSettingRepository extends JpaRepository<WorkSetting, WorkKind> {
}
