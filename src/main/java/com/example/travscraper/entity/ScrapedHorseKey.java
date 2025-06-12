package com.example.travscraper.entity;

import java.io.Serializable;
import java.time.LocalDate;

public record ScrapedHorseKey(
        LocalDate date,
        String track,
        String lap,
        String numberOfHorse) implements Serializable {
}
