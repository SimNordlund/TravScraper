package com.example.travscraper.repo;

import com.example.travscraper.entity.Tid99Warning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface Tid99WarningRepo extends JpaRepository<Tid99Warning, Long> {
}