package com.smarthome.ml;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MlModelRepository extends JpaRepository<MlModel, UUID> {

	Optional<MlModel> findByDeviceUuidAndModelType(UUID deviceUuid, String modelType);

	@Modifying
	@Query("delete from MlModel m where m.deviceUuid = :device")
	int deleteAllForDevice(@Param("device") UUID device);
}
