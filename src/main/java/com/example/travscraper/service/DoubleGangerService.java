package com.example.travscraper.service;

public class DoubleGangerService {

    //SELECT
    //    r.namn,
    //    r.datum,
    //    COUNT(*) AS antal
    //FROM resultat r
    //GROUP BY
    //    r.namn,
    //    r.datum
    //HAVING COUNT(*) > 1;
    //---------------------------
    //SELECT *
    //FROM resultat r
    //WHERE EXISTS (
    //    SELECT 1
    //    FROM resultat r2
    //    WHERE r2.namn = r.namn
    //      AND r2.datum = r.datum
    //      AND r2.id <> r.id
    //);
}
