package com.smarthome.ml;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MlTrainingRunRepository extends JpaRepository<MlTrainingRun, UUID> {

	List<MlTrainingRun> findByDeviceUuidAndModelTypeOrderByTrainedAtDesc(UUID deviceUuid, String modelType, Pageable page);

	/** The newest run that produced a model which became the current one. */
	Optional<MlTrainingRun> findFirstByDeviceUuidAndModelTypeAndBecameCurrentTrueOrderByTrainedAtDesc(UUID deviceUuid, String modelType);

	long countByDeviceUuidAndModelType(UUID deviceUuid, String modelType);

	@Modifying
	@Query("delete from MlTrainingRun r where r.deviceUuid = :device")
	int deleteAllForDevice(@Param("device") UUID device);
}
