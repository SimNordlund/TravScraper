package com.example.travscraper;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "scraper")
@Getter
@Setter
public class ScraperProperties {

    private List<String> tracks = List.of(
            "romme", "mantorp", "farjestad", "skelleftea", "eskilstuna",
            "jagersro", "bollnas", "solvalla", "bergaker", "orebro");

    private LocalDate startDate = LocalDate.now().minusDays(0);
    private LocalDate endDate = LocalDate.now().minusDays(0);
}
