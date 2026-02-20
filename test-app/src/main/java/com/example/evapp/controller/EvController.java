package com.example.evapp.controller;

import com.example.evapp.dto.EvRequest;
import com.example.evapp.dto.EvResponse;
import com.example.evapp.service.EvService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/vehicles")
public class EvController {

    private final EvService evService;

    public EvController(EvService evService) {
        this.evService = evService;
    }

    /**
     * GET /api/v1/vehicles
     * Tum araclari listele
     */
    @GetMapping
    public ResponseEntity<List<EvResponse>> getAllVehicles() {
        List<EvResponse> vehicles = evService.getAllVehicles();
        return ResponseEntity.ok(vehicles);
    }

    /**
     * GET /api/v1/vehicles/{id}
     * ID ile arac getir
     */
    @GetMapping("/{id}")
    public ResponseEntity<EvResponse> getVehicleById(@PathVariable Long id) {
        EvResponse vehicle = evService.getVehicleById(id);
        return ResponseEntity.ok(vehicle);
    }

    /**
     * GET /api/v1/vehicles/plate/{licensePlate}
     * Plaka ile arac getir
     */
    @GetMapping("/plate/{licensePlate}")
    public ResponseEntity<EvResponse> getVehicleByLicensePlate(@PathVariable String licensePlate) {
        EvResponse vehicle = evService.getVehicleByLicensePlate(licensePlate);
        return ResponseEntity.ok(vehicle);
    }

    /**
     * GET /api/v1/vehicles/brand/{brand}
     * Markaya gore arac listele
     */
    @GetMapping("/brand/{brand}")
    public ResponseEntity<List<EvResponse>> getVehiclesByBrand(@PathVariable String brand) {
        List<EvResponse> vehicles = evService.getVehiclesByBrand(brand);
        return ResponseEntity.ok(vehicles);
    }

    /**
     * GET /api/v1/vehicles/charging
     * Sarj edilen araclari listele
     */
    @GetMapping("/charging")
    public ResponseEntity<List<EvResponse>> getChargingVehicles() {
        List<EvResponse> vehicles = evService.getChargingVehicles();
        return ResponseEntity.ok(vehicles);
    }

    /**
     * GET /api/v1/vehicles/low-battery?threshold=20
     * Dusuk bataryali araclari listele
     */
    @GetMapping("/low-battery")
    public ResponseEntity<List<EvResponse>> getLowBatteryVehicles(
            @RequestParam(defaultValue = "20") int threshold) {
        List<EvResponse> vehicles = evService.getLowBatteryVehicles(threshold);
        return ResponseEntity.ok(vehicles);
    }

    /**
     * GET /api/v1/vehicles/range?min=300
     * Minimum menzile gore arac listele
     */
    @GetMapping("/range")
    public ResponseEntity<List<EvResponse>> getVehiclesByMinRange(
            @RequestParam(defaultValue = "300") int min) {
        List<EvResponse> vehicles = evService.getVehiclesByMinRange(min);
        return ResponseEntity.ok(vehicles);
    }

    /**
     * POST /api/v1/vehicles
     * Yeni arac kaydet
     */
    @PostMapping
    public ResponseEntity<EvResponse> createVehicle(@Valid @RequestBody EvRequest request) {
        EvResponse created = evService.createVehicle(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * PUT /api/v1/vehicles/{id}
     * Arac bilgilerini guncelle
     */
    @PutMapping("/{id}")
    public ResponseEntity<EvResponse> updateVehicle(
            @PathVariable Long id,
            @Valid @RequestBody EvRequest request) {
        EvResponse updated = evService.updateVehicle(id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * PATCH /api/v1/vehicles/{id}/charge/start
     * Sarj baslatma
     */
    @PatchMapping("/{id}/charge/start")
    public ResponseEntity<EvResponse> startCharging(@PathVariable Long id) {
        EvResponse updated = evService.startCharging(id);
        return ResponseEntity.ok(updated);
    }

    /**
     * PATCH /api/v1/vehicles/{id}/charge/stop
     * Sarj durdurma
     */
    @PatchMapping("/{id}/charge/stop")
    public ResponseEntity<EvResponse> stopCharging(@PathVariable Long id) {
        EvResponse updated = evService.stopCharging(id);
        return ResponseEntity.ok(updated);
    }

    /**
     * PATCH /api/v1/vehicles/{id}/charge/level
     * Sarj seviyesi guncelle
     */
    @PatchMapping("/{id}/charge/level")
    public ResponseEntity<EvResponse> updateChargeLevel(
            @PathVariable Long id,
            @RequestBody Map<String, Integer> body) {
        Integer level = body.get("chargePercent");
        if (level == null) {
            return ResponseEntity.badRequest().build();
        }
        EvResponse updated = evService.updateChargeLevel(id, level);
        return ResponseEntity.ok(updated);
    }

    /**
     * DELETE /api/v1/vehicles/{id}
     * Araci sil
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVehicle(@PathVariable Long id) {
        evService.deleteVehicle(id);
        return ResponseEntity.noContent().build();
    }
}
