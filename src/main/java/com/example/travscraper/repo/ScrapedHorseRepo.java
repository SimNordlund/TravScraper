package com.example.travscraper.repo;

import com.example.travscraper.entity.ScrapedHorse;
import com.example.travscraper.entity.ScrapedHorseKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface ScrapedHorseRepo extends JpaRepository<ScrapedHorse, ScrapedHorseKey> {
    Optional<ScrapedHorse> findByDateAndTrackAndLapAndNumberOfHorse(
            LocalDate date, String track, String lap, String numberOfHorse);
}
