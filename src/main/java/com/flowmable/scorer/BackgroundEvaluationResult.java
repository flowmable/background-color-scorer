package com.flowmable.scorer;

/**
 * Result of evaluating a single background color against a design.
 *
 * @param hexColor       The evaluated background hex color
 * @param contrastScore  Contrast score [0–100]
 * @param collisionScore Color collision score [0–100] (100 = no collision)
 * @param printRiskScore Print risk score [0–100] (100 = no risk)
 * @param finalScore     Weighted composite score [0–100]
 * @param suitability    Classification: GOOD, BORDERLINE, or BAD
 * @param overrideReason If a safety override was applied, explains why; null otherwise
 */
public record BackgroundEvaluationResult(
        String hexColor,
        double contrastScore,
        double collisionScore,
        double printRiskScore,
        double baseScore,
        double legibilityContrast, // Added field
        double legibilityPenalty,
        double visualAppealScore,
        double inkPenalty,     // New: Explicit Ink Bias (Positive value = penalty)
        double marketBonus,    // New: Explicit Market Weight (Positive or Negative)
        double finalScore,
        Suitability suitability,
        String overrideReason
) {
    // Secondary constructor for convenience, initializing some fields to default values
    public BackgroundEvaluationResult(String hexColor, double contrastScore, double collisionScore, double printRiskScore,
                                      double baseScore, double legibilityContrast, double legibilityPenalty,
                                      double visualAppealScore, double finalScore, Suitability suitability, String overrideReason) {
        this(hexColor, contrastScore, collisionScore, printRiskScore, baseScore, legibilityContrast, legibilityPenalty,
                visualAppealScore, 0.0, 0.0, finalScore, suitability, overrideReason);
    }

    // Another secondary constructor, assuming more defaults
    public BackgroundEvaluationResult(String hexColor, double score, Suitability suitability, String note) {
        this(hexColor,
                0.0, // contrastScore
                0.0, // collisionScore
                0.0, // printRiskScore
                0.0, // baseScore
                0.0, // legibilityContrast
                0.0, // legibilityPenalty
                0.0, // visualAppealScore
                0.0, // inkPenalty
                0.0, // marketBonus
                score,
                suitability,
                note
        );
    }
}
