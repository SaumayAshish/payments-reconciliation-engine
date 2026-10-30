package com.saumay.payments.api;

import com.saumay.payments.application.CaseIntakeService;
import com.saumay.payments.application.RunRejectedException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opens the work items for the exceptions of a completed run (FRD-06). Safe to call again: a case exists once per
 * natural key, so a repeat creates nothing new. Phase 7 replaces this explicit call with an event consumer.
 */
@RestController
@RequestMapping("/api/v1/reconciliations/runs")
public class CaseIntakeController {

    private final CaseIntakeService intake;

    public CaseIntakeController(CaseIntakeService intake) {
        this.intake = intake;
    }

    @PostMapping("/{id}/exceptions")
    public IntakeResponse open(@PathVariable("id") long id) {
        try {
            return IntakeResponse.from(intake.openCases(id));
        } catch (RunRejectedException e) {
            throw ReconciliationController.translate(e);
        }
    }
}