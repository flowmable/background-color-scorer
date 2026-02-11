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
        double finalScore,
        Suitability suitability,
        String overrideReason
) {}
