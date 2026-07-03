package com.example.travscraper;

import com.example.travscraper.service.DoubleGangerService;
import com.example.travscraper.service.HorseWarningService;
import com.example.travscraper.service.ReducedScraperService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TravScraperApplication implements CommandLineRunner {

    private final AtgScraperService service;
    private final HorseWarningService horseWarningService;
    private final DoubleGangerService doubleGangerService;
    private final ReducedScraperService reducedScraperService;

    public TravScraperApplication(AtgScraperService service, HorseWarningService horseWarningService, DoubleGangerService doubleGangerService, ReducedScraperService reducedScraperService) {
        this.service = service;
        this.horseWarningService = horseWarningService;
        this.doubleGangerService = doubleGangerService;
        this.reducedScraperService = reducedScraperService;
    }

    public static void main(String[] args) {
        SpringApplication.run(TravScraperApplication.class, args);
    }

    @Override
    public void run(String... args) {
        //doubleGangerService.refreshDoubleGangers();
        reducedScraperService.scrapeAllReducedGames();
        //service.scrapeFuture();
        //service.scrapeResultatPopupsOnly();
        //service.scrape();
        //service.scrapeForeign();
        horseWarningService.refreshWarnings(8);
    }
}
