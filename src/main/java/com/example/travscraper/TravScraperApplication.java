package com.example.travscraper;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TravScraperApplication implements CommandLineRunner {

    private final AtgScraperService service;

    public TravScraperApplication(AtgScraperService service) {
        this.service = service;
    }

    public static void main(String[] args) {
        SpringApplication.run(TravScraperApplication.class, args);
    }

    @Override
    public void run(String... args) {
        service.scrape();
    }
}





