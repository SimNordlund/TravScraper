package com.example.travscraper.repo;

import com.example.travscraper.entity.ResultHorse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional; //Changed!

@Repository
public interface ResultHorseRepo extends JpaRepository<ResultHorse, Long> {

    Optional<ResultHorse> findByDatumAndBankodAndLoppAndNr( //Changed!
                                                            Integer datum, String bankod, Integer lopp, Integer nr //Changed!
    ); //Changed!

    @Query("""
        select 
            r.namn as name,
            max(r.datum) as lastDate,
            count(r) as starts
        from ResultHorse r
        where r.namn is not null
        group by r.namn
        having count(r) < :minStarts
    """)
    List<HorseStartSummary> findHorsesWithStartsLessThan(@Param("minStarts") long minStarts); //Changed!
}
