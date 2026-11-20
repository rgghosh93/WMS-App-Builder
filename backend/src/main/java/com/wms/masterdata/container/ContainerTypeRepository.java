package com.wms.masterdata.container;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ContainerTypeRepository
        extends JpaRepository<ContainerType, UUID>, JpaSpecificationExecutor<ContainerType> {

    Optional<ContainerType> findByContainerTypeId(String containerTypeId);
}
