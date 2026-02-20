package com.example.evapp.dto;

import com.example.evapp.model.ElectricVehicle;

import java.time.LocalDateTime;

public class EvResponse {

    private Long id;
    private String brand;
    private String model;
    private int year;
    private double batteryCapacityKwh;
    private int rangeKm;
    private String chargePortType;
    private String ownerName;
    private String licensePlate;
    private int currentChargePercent;
    private boolean isCharging;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Factory method
    public static EvResponse from(ElectricVehicle ev) {
        EvResponse dto = new EvResponse();
        dto.id = ev.getId();
        dto.brand = ev.getBrand();
        dto.model = ev.getModel();
        dto.year = ev.getYear();
        dto.batteryCapacityKwh = ev.getBatteryCapacityKwh();
        dto.rangeKm = ev.getRangeKm();
        dto.chargePortType = ev.getChargePortType();
        dto.ownerName = ev.getOwnerName();
        dto.licensePlate = ev.getLicensePlate();
        dto.currentChargePercent = ev.getCurrentChargePercent();
        dto.isCharging = ev.isCharging();
        dto.createdAt = ev.getCreatedAt();
        dto.updatedAt = ev.getUpdatedAt();
        return dto;
    }

    // Getters
    public Long getId() { return id; }
    public String getBrand() { return brand; }
    public String getModel() { return model; }
    public int getYear() { return year; }
    public double getBatteryCapacityKwh() { return batteryCapacityKwh; }
    public int getRangeKm() { return rangeKm; }
    public String getChargePortType() { return chargePortType; }
    public String getOwnerName() { return ownerName; }
    public String getLicensePlate() { return licensePlate; }
    public int getCurrentChargePercent() { return currentChargePercent; }
    public boolean isCharging() { return isCharging; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
