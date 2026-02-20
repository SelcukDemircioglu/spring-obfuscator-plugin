package com.example.evapp.config;

import com.example.evapp.model.ElectricVehicle;
import com.example.evapp.repository.EvRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initData(EvRepository evRepository) {
        return args -> {
            // Veri zaten varsa tekrar ekleme
            if (evRepository.count() > 0) {
                System.out.println("===========================================");
                System.out.println("  Veritabani mevcut. Veri yukleme atlandi.");
                System.out.println("  API: http://localhost:8080/api/v1/vehicles");
                System.out.println("===========================================");
                return;
            }

            // Tesla Model S
            ElectricVehicle tesla = new ElectricVehicle(
                    "Tesla", "Model S", 2023,
                    100.0, 650, "CCS2",
                    "Ahmet Yilmaz", "34 ABC 001");
            tesla.setCurrentChargePercent(85);
            evRepository.save(tesla);

            // BMW iX
            ElectricVehicle bmw = new ElectricVehicle(
                    "BMW", "iX", 2023,
                    111.5, 630, "CCS2",
                    "Ayse Kaya", "06 DEF 002");
            bmw.setCurrentChargePercent(60);
            bmw.setCharging(true);
            evRepository.save(bmw);

            // Hyundai IONIQ 5
            ElectricVehicle ioniq = new ElectricVehicle(
                    "Hyundai", "IONIQ 5", 2022,
                    77.4, 481, "CCS2",
                    "Mehmet Demir", "35 GHI 003");
            ioniq.setCurrentChargePercent(15);
            evRepository.save(ioniq);

            // Togg T10X
            ElectricVehicle togg = new ElectricVehicle(
                    "Togg", "T10X", 2023,
                    88.5, 523, "CCS2",
                    "Fatma Celik", "16 JKL 004");
            togg.setCurrentChargePercent(92);
            evRepository.save(togg);

            // Rivian R1T
            ElectricVehicle rivian = new ElectricVehicle(
                    "Rivian", "R1T", 2023,
                    135.0, 504, "CCS1",
                    "Ali Sahin", "34 MNO 005");
            rivian.setCurrentChargePercent(45);
            rivian.setCharging(true);
            evRepository.save(rivian);

            System.out.println("===========================================");
            System.out.println("  Ornek EV verileri yuklendi. (5 arac)");
            System.out.println("  API: http://localhost:8080/api/v1/vehicles");
            System.out.println("===========================================");
        };
    }
}
