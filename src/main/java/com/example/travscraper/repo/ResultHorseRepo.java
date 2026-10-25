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

    @Query("""
                select r
                from ResultHorse r
                where r.placering is not null
                  and r.placering not in (15, 99)
                  and r.tid = 99.0
                order by r.datum, r.bankod, r.lopp, r.nr, r.namn
            """)
    List<ResultHorse> findHorsesWithPlacementAndTid99();

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

    @Query(value = """
                with today_horses as (
                    select datum, bankod, lopp, nr, namn
                    from resultat
                    where datum = :datum
                      and namn is not null
                      and trim(namn) <> ''
                ), horse_history as (
                    select h.namn,
                           count(*) as starts,
                           bool_or(coalesce(h.tid = 99.0 and h.placering not in (15, 99), false))
                               as tid_99_warning
                    from resultat h
                    where h.namn in (select namn from today_horses)
                    group by h.namn
                ), horse_warnings as (
                    select h.namn, h.starts, h.tid_99_warning,
                           h.starts < :minStarts as horse_warning,
                           exists (
                               select 1 from double_gangers d where d.horse_name = h.namn
                           ) as double_ganger
                    from horse_history h
                )
                select t.datum, t.bankod, t.lopp, t.nr, t.namn,
                       w.double_ganger as "doubleGanger",
                       w.tid_99_warning as "tid99Warning",
                       w.horse_warning as "horseWarning",
                       w.starts
                from today_horses t
                join horse_warnings w on w.namn = t.namn
                where w.double_ganger or w.tid_99_warning or w.horse_warning
                order by t.datum, t.bankod, t.lopp, t.nr, t.namn
            """, nativeQuery = true)
    List<TodayHorseWarning> findHorsesWithWarningsOnDate(@Param("datum") Integer datum,
                                                       @Param("minStarts") long minStarts);
}
