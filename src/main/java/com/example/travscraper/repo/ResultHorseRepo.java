package com.example.travscraper.repo;

import com.example.travscraper.entity.ResultHorse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ResultHorseRepo extends JpaRepository <ResultHorse, Long>  {
}
