package com.example.travscraper.repo;

import com.example.travscraper.entity.StartListHorse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StartListHorseRepo extends JpaRepository <StartListHorse, Long> {
}
