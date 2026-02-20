package com.example.evapp.repository;

import com.example.evapp.model.ElectricVehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EvRepository extends JpaRepository<ElectricVehicle, Long> {

    List<ElectricVehicle> findByBrandIgnoreCase(String brand);

    List<ElectricVehicle> findByOwnerName(String ownerName);

    Optional<ElectricVehicle> findByLicensePlate(String licensePlate);

    List<ElectricVehicle> findByChargingTrue();

    List<ElectricVehicle> findByCurrentChargePercentLessThan(int threshold);

    @Query("SELECT ev FROM ElectricVehicle ev WHERE ev.brand = :brand AND ev.year = :year")
    List<ElectricVehicle> findByBrandAndYear(@Param("brand") String brand, @Param("year") int year);

    @Query("SELECT ev FROM ElectricVehicle ev WHERE ev.rangeKm >= :minRange ORDER BY ev.rangeKm DESC")
    List<ElectricVehicle> findByMinRange(@Param("minRange") int minRange);

    boolean existsByLicensePlate(String licensePlate);
}
