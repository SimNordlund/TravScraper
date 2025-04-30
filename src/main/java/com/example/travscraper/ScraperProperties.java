package com.example.travscraper;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "scraper")
public class ScraperProperties {

    /** Tracks to scrape – names must match the URL segment */
    private List<String> tracks = List.of(
            "romme", "mantorp", "farjestad", "skelleftea", "eskilstuna",
            "jagersro", "bollnas", "solvalla", "bergaker");

    /** Inclusive start date (yyyy-MM-dd) */
    private LocalDate startDate = LocalDate.now().minusDays(1);

    /** Inclusive end date (yyyy-MM-dd) */
    private LocalDate endDate = LocalDate.now();

    // getters & setters
    public List<String> getTracks() { return tracks; }
    public void setTracks(List<String> tracks) { this.tracks = tracks; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
}
