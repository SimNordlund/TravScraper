package com.example.travscraper.repo;

import com.example.travscraper.entity.ResultHorse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResultHorseRepo extends JpaRepository<ResultHorse, Long> {

    @Query("""
        select 
            r.namn as name,
            max(r.datum) as lastDate,
            count(r) as starts
        from ResultHorse r
        group by r.namn
        having count(r) < :minStarts
    """)
    List<HorseStartSummary> findHorsesWithStartsLessThan(@Param("minStarts") long minStarts);
}
