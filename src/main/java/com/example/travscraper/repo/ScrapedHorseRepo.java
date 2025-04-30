package com.example.travscraper.repo;

import com.example.travscraper.entity.ScrapedHorse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScrapedHorseRepo extends JpaRepository<ScrapedHorse, Long> {
}
