package com.example.travscraper.service;

import com.example.travscraper.entity.DoubleGangers;
import com.example.travscraper.repo.DoubleGangerCandidate;
import com.example.travscraper.repo.DoubleGangerRepo;
import com.example.travscraper.repo.ResultHorseRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DoubleGangerService {

    private static final int BATCH_SIZE = 500;
    private final ResultHorseRepo resultHorseRepo;
    private final DoubleGangerRepo doubleGangerRepo;

    @Transactional
    public void refreshDoubleGangers() {
        log.info("DoubleGanger: starting refresh");

        List<DoubleGangerCandidate> candidates = resultHorseRepo.findDoubleGangerCandidates();
        log.info("DoubleGanger: found {} horse/date/track pairs", candidates.size());

        log.info("DoubleGanger: deleting old rows from double_gangers...");
        doubleGangerRepo.deleteAllInBatch();
        log.info("DoubleGanger: delete done");

        if (candidates.isEmpty()) {
            log.info("DoubleGanger: nothing to save, finished");
            return;
        }

        List<DoubleGangers> buffer = new ArrayList<>(Math.min(BATCH_SIZE, candidates.size()));
        int saved = 0;

        for (DoubleGangerCandidate candidate : candidates) {
            buffer.add(DoubleGangers.builder()
                    .date(candidate.getDatum() == null ? 0 : candidate.getDatum())
                    .track1(candidate.getTrack1())
                    .track2(candidate.getTrack2())
                    .horseName(candidate.getHorseName())
                    .build());

            if (buffer.size() >= BATCH_SIZE) {
                doubleGangerRepo.saveAll(buffer);
                saved += buffer.size();
                log.info("DoubleGanger XD: saved {} / {}", saved, candidates.size());
                buffer.clear();
            }
        }

        if (!buffer.isEmpty()) {
            doubleGangerRepo.saveAll(buffer);
            saved += buffer.size();
        }

        log.info("DoubleGanger XD: finished, saved {} rows to double_gangers", saved);
    }
}
