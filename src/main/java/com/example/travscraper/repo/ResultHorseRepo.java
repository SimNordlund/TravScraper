package com.example.travscraper.repo;

import com.example.travscraper.entity.ResultHorse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResultHorseRepo extends JpaRepository<ResultHorse, Long> {

    Optional<ResultHorse> findByDatumAndBankodAndLoppAndNamn(Integer datum, String bankod, Integer lopp, String namn);

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
    List<HorseStartSummary> findHorsesWithStartsLessThan(@Param("minStarts") long minStarts);

    @Query(value = """
                select distinct
                    r1.datum as datum,
                    r1.bankod as track1,
                    r2.bankod as track2,
                    r1.namn as "horseName"
                from resultat r1
                join resultat r2
                  on r1.datum = r2.datum
                 and r1.namn = r2.namn
                 and r1.bankod < r2.bankod
                where r1.namn is not null
                  and trim(r1.namn) <> ''
                  and r1.bankod is not null
                  and r2.bankod is not null
            """, nativeQuery = true)
    List<DoubleGangerCandidate> findDoubleGangerCandidates();
}
