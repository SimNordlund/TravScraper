package com.example.travscraper;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "scraper")
public class ScraperProperties {

    private List<String> tracks = List.of(
            "romme", "mantorp", "farjestad", "skelleftea", "eskilstuna",
            "jagersro", "bollnas", "solvalla", "bergaker", "orebro");

    private LocalDate startDate = LocalDate.now().minusDays(0);
    private LocalDate endDate = LocalDate.now().minusDays(0);

    
    public List<String> getTracks() { return tracks; }
    public void setTracks(List<String> tracks) { this.tracks = tracks; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
}
