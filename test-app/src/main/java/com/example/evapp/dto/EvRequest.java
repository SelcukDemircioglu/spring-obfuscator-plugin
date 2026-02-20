package com.example.evapp.dto;

import jakarta.validation.constraints.*;

public class EvRequest {

    @NotBlank(message = "Marka bos olamaz")
    private String brand;

    @NotBlank(message = "Model bos olamaz")
    private String model;

    @Min(value = 2000, message = "Yil 2000'den kucuk olamaz")
    @Max(value = 2030, message = "Yil 2030'dan buyuk olamaz")
    private int year;

    @DecimalMin(value = "1.0", message = "Batarya kapasitesi 1 kWh'den az olamaz")
    private double batteryCapacityKwh;

    @Min(value = 1, message = "Menzil en az 1 km olmali")
    private int rangeKm;

    private String chargePortType;

    private String ownerName;

    @NotBlank(message = "Plaka bos olamaz")
    private String licensePlate;

    // Getters and Setters
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
}
