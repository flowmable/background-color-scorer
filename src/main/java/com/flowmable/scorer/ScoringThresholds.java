package com.flowmable.scorer;

/**
 * Configurable scoring thresholds for classification and safety overrides.
 *
 * @param goodFloor              Minimum final score for GOOD (default: 65)
 * @param borderlineFloor        Minimum final score for BORDERLINE (default: 40)
 * @param collisionVetoFloor     Critical collision weight to force BAD (default: 0.60)
 * @param contrastVetoFloor      Min ΔE below which to cap at BORDERLINE (default: 3.0)
 * @param lowContrastScoreFloor  Contrast score below which to cap at BORDERLINE (default: 45.0)
 * @param partialCollisionFloor  Collision weight above which to cap at BORDERLINE (default: 0.10)
 */
public record ScoringThresholds(
        double goodFloor,
        double borderlineFloor,
        double collisionVetoFloor,
        double contrastVetoFloor,
        double lowContrastScoreFloor,
        double partialCollisionFloor
) {
    public static final ScoringThresholds DEFAULT =
            new ScoringThresholds(80.0, 55.0, 0.60, 3.0, 45.0, 0.10);
}
