package com.smarthome.report;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, UUID> {

	List<Report> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId, Pageable page);

	Optional<Report> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

	long countByOwnerUserId(UUID ownerUserId);
}
