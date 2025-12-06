package com.example.travscraper.repo;

import com.example.travscraper.entity.HorseWarning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HorseWarningRepo extends JpaRepository<HorseWarning, Long> {
}
