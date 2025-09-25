package com.example.travscraper.repo;

import com.example.travscraper.entity.StartListHorse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@Repository
public interface StartListHorseRepo extends JpaRepository <StartListHorse, Long> {

    @Query("select distinct s.banKod from StartListHorse s where s.startDatum = :date")
    List<String> findDistinctBanKoderOn(@Param("date") Integer date);

    @Query("select distinct s.banKod from StartListHorse s where s.startDatum between :from and :to")
    List<String> findDistinctBanKoderBetween(@Param("from") Integer from, @Param("to") Integer to);
}
