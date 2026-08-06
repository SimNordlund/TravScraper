package com.example.travscraper.service;

import com.example.travscraper.entity.ResultHorse;
import com.example.travscraper.entity.Tid99Warning;
import com.example.travscraper.repo.ResultHorseRepo;
import com.example.travscraper.repo.Tid99WarningRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class Tid99WarningService {

    private static final int BATCH_SIZE = 500;

    private final ResultHorseRepo resultHorseRepo;
    private final Tid99WarningRepo tid99WarningRepo;

    @Transactional
    public void refreshTid99Warnings() {
        log.info("Tid99Warning: starting refresh");

        List<ResultHorse> flagged = resultHorseRepo.findHorsesWithPlacementAndTid99();
        log.info("Tid99Warning: found {} horses with placement and tid=99", flagged.size());

        log.info("Tid99Warning: deleting old rows from tid_99_warning...");
        tid99WarningRepo.deleteAllInBatch();
        log.info("Tid99Warning: delete done");

        if (flagged.isEmpty()) {
            log.info("Tid99Warning: nothing to save, finished");
            return;
        }

        List<Tid99Warning> buffer = new ArrayList<>(Math.min(BATCH_SIZE, flagged.size()));
        int saved = 0;

        for (ResultHorse result : flagged) {
            buffer.add(Tid99Warning.builder()
                    .datum(result.getDatum())
                    .bankod(result.getBankod())
                    .lopp(result.getLopp())
                    .nr(result.getNr())
                    .namn(result.getNamn())
                    .placering(result.getPlacering())
                    .tid(result.getTid())
                    .build());

            if (buffer.size() >= BATCH_SIZE) {
                tid99WarningRepo.saveAll(buffer);
                saved += buffer.size();
                log.info("Tid99Warning: saved {} / {}", saved, flagged.size());
                buffer.clear();
            }
        }

        if (!buffer.isEmpty()) {
            tid99WarningRepo.saveAll(buffer);
            saved += buffer.size();
        }

        log.info("Tid99Warning: finished, saved {} rows to tid_99_warning", saved);
    }
}