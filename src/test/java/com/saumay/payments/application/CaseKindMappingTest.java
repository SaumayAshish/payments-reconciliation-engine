package com.saumay.payments.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.matching.ExceptionKind;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Case intake maps the engine's exception kinds to case kinds by name, so the two enums must stay aligned. */
class CaseKindMappingTest {

    @Test
    void everyEngineKindHasACaseKindOfTheSameName() {
        for (ExceptionKind kind : ExceptionKind.values()) {
            assertDoesNotThrow(() -> CaseKind.valueOf(kind.name()), kind.toString());
        }
    }

    @Test
    void duplicateSuspectIsTheOnlyCaseKindTheEngineDoesNotYetProduce() {
        Set<CaseKind> unproduced = EnumSet.allOf(CaseKind.class);
        for (ExceptionKind kind : ExceptionKind.values()) {
            unproduced.remove(CaseKind.valueOf(kind.name()));
        }
        assertEquals(Set.of(CaseKind.DUPLICATE_SUSPECT), unproduced);
    }
}