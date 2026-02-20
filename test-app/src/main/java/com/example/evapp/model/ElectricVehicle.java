package com.example.evapp.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "electric_vehicles")
public class ElectricVehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand", nullable = false, length = 100)
    @NotBlank(message = "Marka bos olamaz")
    private String brand;

    @Column(name = "model", nullable = false, length = 100)
    @NotBlank(message = "Model bos olamaz")
    private String model;

    @Column(name = "year")
    @Min(value = 2000, message = "Yil 2000'den kucuk olamaz")
    @Max(value = 2030, message = "Yil 2030'dan buyuk olamaz")
    private int year;

    @Column(name = "battery_capacity_kwh")
    @DecimalMin(value = "1.0", message = "Batarya kapasitesi 1 kWh'den kucuk olamaz")
    private double batteryCapacityKwh;

    @Column(name = "range_km")
    @Min(value = 1, message = "Menzil 1 km'den kucuk olamaz")
    private int rangeKm;

    @Column(name = "charge_port_type", length = 50)
    private String chargePortType;

    @Column(name = "owner_name", length = 200)
    private String ownerName;

    @Column(name = "license_plate", unique = true, length = 20)
    private String licensePlate;

    @Column(name = "current_charge_percent")
    @Min(0) @Max(100)
    private int currentChargePercent;

    @Column(name = "is_charging")
    private boolean charging;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Constructors
    public ElectricVehicle() {}

    public ElectricVehicle(String brand, String model, int year,
                           double batteryCapacityKwh, int rangeKm,
                           String chargePortType, String ownerName, String licensePlate) {
        this.brand = brand;
        this.model = model;
        this.year = year;
        this.batteryCapacityKwh = batteryCapacityKwh;
        this.rangeKm = rangeKm;
        this.chargePortType = chargePortType;
        this.ownerName = ownerName;
        this.licensePlate = licensePlate;
        this.currentChargePercent = 100;
        this.charging = false;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public double getBatteryCapacityKwh() { return batteryCapacityKwh; }
    public void setBatteryCapacityKwh(double batteryCapacityKwh) { this.batteryCapacityKwh = batteryCapacityKwh; }

    public int getRangeKm() { return rangeKm; }
    public void setRangeKm(int rangeKm) { this.rangeKm = rangeKm; }

    public String getChargePortType() { return chargePortType; }
    public void setChargePortType(String chargePortType) { this.chargePortType = chargePortType; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public String getLicensePlate() { return licensePlate; }
    public void setLicensePlate(String licensePlate) { this.licensePlate = licensePlate; }

    public int getCurrentChargePercent() { return currentChargePercent; }
    public void setCurrentChargePercent(int currentChargePercent) { this.currentChargePercent = currentChargePercent; }

    public boolean isCharging() { return charging; }
    public void setCharging(boolean charging) { this.charging = charging; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
