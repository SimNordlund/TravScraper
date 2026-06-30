package com.example.travscraper.repo;

import com.example.travscraper.entity.ReducedSystem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReducedSystemRepo extends JpaRepository <ReducedSystem, Long> {
}
