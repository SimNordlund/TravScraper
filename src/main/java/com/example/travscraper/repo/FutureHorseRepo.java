package com.example.travscraper.repo;

import com.example.travscraper.entity.FutureHorse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface FutureHorseRepo extends JpaRepository<FutureHorse, Long> {
    Optional<FutureHorse> findByDateAndTrackAndLapAndNumberOfHorse(LocalDate date, String track, String lap, String numberOfHorse);
}
