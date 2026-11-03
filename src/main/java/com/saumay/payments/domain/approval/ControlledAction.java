package com.saumay.payments.domain.approval;

import com.saumay.payments.domain.cases.ResolutionType;

/** The resolutions that can need a second approver. */
public enum ControlledAction {
    WRITE_OFF(ResolutionType.WRITE_OFF),
    ADJUSTMENT(ResolutionType.ADJUSTMENT);

    private final ResolutionType resolution;

    ControlledAction(ResolutionType resolution) {
        this.resolution = resolution;
    }

    /** The resolution type recorded on the case when this action completes. */
    public ResolutionType resolution() {
        return resolution;
    }
}