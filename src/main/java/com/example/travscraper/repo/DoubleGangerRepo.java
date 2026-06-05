package com.example.travscraper.repo;

import com.example.travscraper.entity.DoubleGangers;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DoubleGangerRepo extends JpaRepository<DoubleGangers, Long> {
}
