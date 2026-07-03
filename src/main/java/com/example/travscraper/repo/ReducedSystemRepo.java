package com.example.travscraper.repo;

import com.example.travscraper.entity.ReducedSystem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReducedSystemRepo extends JpaRepository <ReducedSystem, Long> {
    List<ReducedSystem> findByStartDatumAndBanKodAndStreckTypAndLopp(
            String startDatum, String banKod, String streckTyp, Integer lopp);
}
