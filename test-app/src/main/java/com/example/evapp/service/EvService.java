package com.example.evapp.service;

import com.example.evapp.dto.EvRequest;
import com.example.evapp.dto.EvResponse;
import com.example.evapp.model.ElectricVehicle;
import com.example.evapp.repository.EvRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class EvService {

    private final EvRepository evRepository;

    public EvService(EvRepository evRepository) {
        this.evRepository = evRepository;
    }

    @Transactional(readOnly = true)
    public List<EvResponse> getAllVehicles() {
        return evRepository.findAll()
                .stream()
                .map(EvResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EvResponse getVehicleById(Long id) {
        ElectricVehicle ev = evRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Arac bulunamadi: ID=" + id));
        return EvResponse.from(ev);
    }

    @Transactional(readOnly = true)
    public EvResponse getVehicleByLicensePlate(String licensePlate) {
        ElectricVehicle ev = evRepository.findByLicensePlate(licensePlate)
                .orElseThrow(() -> new RuntimeException("Arac bulunamadi: Plaka=" + licensePlate));
        return EvResponse.from(ev);
    }

    @Transactional(readOnly = true)
    public List<EvResponse> getVehiclesByBrand(String brand) {
        return evRepository.findByBrandIgnoreCase(brand)
                .stream()
                .map(EvResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EvResponse> getChargingVehicles() {
        return evRepository.findByChargingTrue()
                .stream()
                .map(EvResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EvResponse> getLowBatteryVehicles(int threshold) {
        return evRepository.findByCurrentChargePercentLessThan(threshold)
                .stream()
                .map(EvResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EvResponse> getVehiclesByMinRange(int minRange) {
        return evRepository.findByMinRange(minRange)
                .stream()
                .map(EvResponse::from)
                .collect(Collectors.toList());
    }

    public EvResponse createVehicle(EvRequest request) {
        if (evRepository.existsByLicensePlate(request.getLicensePlate())) {
            throw new IllegalArgumentException("Bu plaka zaten kayitli: " + request.getLicensePlate());
        }

        ElectricVehicle ev = new ElectricVehicle(
                request.getBrand(),
                request.getModel(),
                request.getYear(),
                request.getBatteryCapacityKwh(),
                request.getRangeKm(),
                request.getChargePortType(),
                request.getOwnerName(),
                request.getLicensePlate()
        );

        ElectricVehicle saved = evRepository.save(ev);
        return EvResponse.from(saved);
    }

    public EvResponse updateVehicle(Long id, EvRequest request) {
        ElectricVehicle ev = evRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Arac bulunamadi: ID=" + id));

        ev.setBrand(request.getBrand());
        ev.setModel(request.getModel());
        ev.setYear(request.getYear());
        ev.setBatteryCapacityKwh(request.getBatteryCapacityKwh());
        ev.setRangeKm(request.getRangeKm());
        ev.setChargePortType(request.getChargePortType());
        ev.setOwnerName(request.getOwnerName());
        ev.setLicensePlate(request.getLicensePlate());

        ElectricVehicle saved = evRepository.save(ev);
        return EvResponse.from(saved);
    }

    public EvResponse startCharging(Long id) {
        ElectricVehicle ev = evRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Arac bulunamadi: ID=" + id));
        ev.setCharging(true);
        return EvResponse.from(evRepository.save(ev));
    }

    public EvResponse stopCharging(Long id) {
        ElectricVehicle ev = evRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Arac bulunamadi: ID=" + id));
        ev.setCharging(false);
        ev.setCurrentChargePercent(100);
        return EvResponse.from(evRepository.save(ev));
    }

    public EvResponse updateChargeLevel(Long id, int chargePercent) {
        ElectricVehicle ev = evRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Arac bulunamadi: ID=" + id));

        if (chargePercent < 0 || chargePercent > 100) {
            throw new IllegalArgumentException("Sarj yuzdesi 0-100 arasinda olmali");
        }

        ev.setCurrentChargePercent(chargePercent);
        return EvResponse.from(evRepository.save(ev));
    }

    public void deleteVehicle(Long id) {
        if (!evRepository.existsById(id)) {
            throw new RuntimeException("Arac bulunamadi: ID=" + id);
        }
        evRepository.deleteById(id);
    }
}
