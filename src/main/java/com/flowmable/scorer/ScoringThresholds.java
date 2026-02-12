package com.flowmable.scorer;

import java.util.Collections;
import java.util.Map;

/**
 * Configurable scoring thresholds for classification and safety overrides.
 * <p>
 * VERSION: 3.0
 * Any modification to constants requires SCORING_MODEL_VERSION increment.
 *
 * @param goodFloor              Minimum final score for GOOD (PROMOTED)
 * @param borderlineFloor        Minimum final score for BORDERLINE (PASSED)
 * @param tailVetoFloor          Minimum P10 contrast to avoid REJECTED
 * @param tonalTriggerRatio      Multiplier for P10 to trigger Tonal Penalty
 * @param vibrationChromaRatio   Multiplier for P75 to trigger Vibration Penalty
 * @param flatnessPenaltyScale   Scale for Flatness Risk (Neutral Dampener)
 * @param harmonySigma           Gaussian width for Harmony Reward
 * @param rawBaselineStdDev      Baseline Raw StdDev for Budgeting
 * @param aestheticInfluenceMin  Minimum Aesthetic Influence Ratio
 * @param aestheticInfluenceMax  Maximum Aesthetic Influence Ratio
 * @param perDesignVarianceGuard Per-design variance guard multiplier
 * @param stabilityTolBase       Base stability tolerance
 * @param stabilityTolScale      Proportional stability tolerance scale
 * @param marketWeights          Map of commercial weights per background ID
 */
public record ScoringThresholds(
        double goodFloor,
        double borderlineFloor,
        double tailVetoFloor,
        double tonalTriggerRatio,
        double vibrationChromaRatio,
        double flatnessPenaltyScale,
        double harmonySigma,
        double rawBaselineStdDev,
        double aestheticInfluenceMin,
        double aestheticInfluenceMax,
        double perDesignVarianceGuard,
        double stabilityTolBase,
        double stabilityTolScale,
        Map<String, Double> marketWeights
) {
    public static final String SCORING_MODEL_VERSION = "3.0";

    // V3 Defaults ("Production-Grade")
    public static final ScoringThresholds DEFAULT = new ScoringThresholds(
            34.0, // goodFloor
            26.0, // borderlineFloor
            8.0,  // tailVetoFloor
            1.8,  // tonalTriggerRatio
            1.2,  // vibrationChromaRatio
            1.5,  // flatnessPenaltyScale (-1.5 max)
            25.0, // harmonySigma (Tightened from 35)
            7.42, // rawBaselineStdDev (Calibrated from V2)
            1.15, // aestheticInfluenceMin
            1.30, // aestheticInfluenceMax
            1.4,  // perDesignVarianceGuard (Raw * 1.4)
            2.0,  // stabilityTolBase
            0.02, // stabilityTolScale
            Collections.emptyMap() // marketWeights
    );
}
