package com.flowmable.scorer;

import java.util.List;

/**
 * Phase 2: Per-background-color evaluation.
 * <p>
 * Computes contrast, collision, and print risk scores, then combines
 * them into a weighted final score with classification and safety overrides.
 */
public class BackgroundEvaluator {

    private static final double CONTRAST_WEIGHT = 0.40;
    private static final double COLLISION_WEIGHT = 0.35;
    private static final double PRINT_RISK_WEIGHT = 0.25;

    /** Minimum weight for a dominant color to participate in scoring (2% floor). */
    private static final double MIN_COVERAGE_WEIGHT = 0.02;



    private final ScoringThresholds thresholds;

    public BackgroundEvaluator() {
        this(ScoringThresholds.DEFAULT);
    }

    public BackgroundEvaluator(ScoringThresholds thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * Evaluate a single background color against a design analysis result.
     */
    public BackgroundEvaluationResult evaluate(DesignAnalysisResult design, String hexColor) {
        int bgRgb = Integer.parseInt(hexColor.replaceFirst("#", ""), 16);
        int bgR = (bgRgb >> 16) & 0xFF;
        int bgG = (bgRgb >> 8) & 0xFF;
        int bgB = bgRgb & 0xFF;
        double[] bgLab = ColorSpaceUtils.srgbToLab(bgR, bgG, bgB);
        double bgLuminance = ColorSpaceUtils.relativeLuminance(bgR, bgG, bgB);

        // Filter scoring colors by 2% coverage floor
        List<DominantColor> scoringColors = design.dominantColors().stream()
                .filter(dc -> dc.weight() >= MIN_COVERAGE_WEIGHT)
                .toList();

        // Safety override S3: degenerate design
        if (scoringColors.isEmpty()) {
            return new BackgroundEvaluationResult(
                    hexColor, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0,
                    Suitability.BAD,
                    "DEGENERATE: no significant foreground content"
            );
        }

        // Re-normalize weights
        double totalWeight = scoringColors.stream().mapToDouble(DominantColor::weight).sum();

        // --- Contrast Score (GAP 1: Explicit Formula) ---
        double[] deltaEs = new double[scoringColors.size()];
        double[] normWeights = new double[scoringColors.size()];
        for (int i = 0; i < scoringColors.size(); i++) {
            DominantColor dc = scoringColors.get(i);
            deltaEs[i] = ColorSpaceUtils.ciede2000(
                    dc.labL(), dc.labA(), dc.labB(),
                    bgLab[0], bgLab[1], bgLab[2]
            );
            normWeights[i] = dc.weight() / totalWeight;
        }

        // Step 3: Weighted mean ΔE
        double weightedMeanDeltaE = 0;
        for (int i = 0; i < scoringColors.size(); i++) {
            weightedMeanDeltaE += normWeights[i] * deltaEs[i];
        }

        // Step 4: Worst-case (minimum) ΔE for significant colors
        double minDeltaE = Double.MAX_VALUE;
        for (int i = 0; i < scoringColors.size(); i++) {
            if (normWeights[i] >= 0.05) {
                minDeltaE = Math.min(minDeltaE, deltaEs[i]);
            }
        }
        if (minDeltaE == Double.MAX_VALUE) {
            // All colors are small; use overall min
            for (double de : deltaEs) {
                minDeltaE = Math.min(minDeltaE, de);
            }
        }

        // Step 5: Composite raw contrast
        double rawContrast = 0.7 * weightedMeanDeltaE + 0.3 * minDeltaE;

        // Step 6: Normalize to 0–100
        double normalizedContrast = clamp(rawContrast * 2.0, 0, 100);

        // Step 7: Edge density penalty
        double edgePenalty = 0;
        if (normalizedContrast < 50) {
            edgePenalty = (50 - normalizedContrast) * design.edgeDensity() * 0.3;
        }
        double contrastScore = clamp(normalizedContrast - edgePenalty, 0, 100);

        // --- Collision Score (GAP 2: Formal Weighting) ---
        double collisionWeight = 0;
        double criticalCollisionWeight = 0;
        for (int i = 0; i < scoringColors.size(); i++) {
            double deltaE = deltaEs[i];
            double collisionFactor;
            if (deltaE < 5.0) {
                collisionFactor = 1.0;
                criticalCollisionWeight += normWeights[i];
            } else if (deltaE < 12.0) {
                collisionFactor = (12.0 - deltaE) / 7.0;
            } else {
                collisionFactor = 0.0;
            }
            collisionWeight += normWeights[i] * collisionFactor;
        }
        double collisionScore = clamp((1.0 - collisionWeight) * 100, 0, 100);

        // --- Print Risk Score ---
        double printRisk = 0;

        // Risk 1: White-on-light
        if (bgLab[0] > 80) {
            printRisk += design.nearWhiteRatio() * 40;
        }
        // Risk 2: Black-on-dark
        if (bgLab[0] < 25) {
            printRisk += design.nearBlackRatio() * 40;
        }
        // Risk 3: High transparency + marginal contrast
        if (design.transparencyRatio() > 0.7 && normalizedContrast < 60) {
            printRisk += 15;
        }
        // Risk 4: Luminance collision
        double lumDiff = Math.abs(design.meanLuminance() - bgLuminance);
        if (lumDiff < 0.15) {
            printRisk += 20 * (1.0 - lumDiff / 0.15);
        }

        double printRiskScore = clamp(100 - printRisk, 0, 100);

        // --- 1. Compute Base Score ---
        double baseScore =
                contrastScore * CONTRAST_WEIGHT
              + collisionScore * COLLISION_WEIGHT
              + printRiskScore * PRINT_RISK_WEIGHT;

        // --- 2. Compute Resistance ---
        // Design Resistance Contract:
        // - Purely design-dependent
        // - Clamped 0-1
        // - Monotonic
        // Components: Darkness (55%), Structure (15%), Solidity (30%)
        double rDarkness = 1.0 - design.nearWhiteRatio();
        double rStructure = design.edgeDensity(); 
        double rSolidity = 1.0 - design.transparencyRatio();

        double designResistance = (0.55 * rDarkness) + (0.15 * rStructure) + (0.30 * rSolidity);
        
        // Explicitly clamp resistance to [0,1]
        designResistance = Math.max(0.0, Math.min(1.0, designResistance));

        // --- 3. Compute Fragility (New Model) ---
        // fragility = pow(1.0 - resistance, 2.2)
        // Robust designs -> fragility near 0
        // Fragile designs -> fragility -> 1
        double vulnerability = 1.0 - designResistance;
        double fragility = Math.pow(vulnerability, 2.2);

        // --- 4. Compute Background Weakness ---
        // backgroundWeakness = 1.0 - (baseScore / 100.0)
        double backgroundWeakness = 1.0 - (baseScore / 100.0);

        // --- 5. Apply Conditional Penalty ---
        // penalty = baseScore * fragility * backgroundWeakness
        double penalty = baseScore * fragility * backgroundWeakness;
        // 'finalScore' in old logic is our 'technicalScore' or 'baseScore' for the new recommendation logic
        double technicalScore = clamp(baseScore - penalty, 0.0, 100.0);

        // --- 6. Legibility Strength (Graded Penalty) ---
        double legibilityPenaltyFactor = 1.0;
        double legibilityContrast = 0.0;

        // Only apply if we detected significant High-Frequency content (0.5% area)
        if (design.legibilityAreaRatio() >= 0.005 && design.legibilityLuminanceP50() >= 0) {
            double p50 = design.legibilityLuminanceP50();
            double L1 = Math.max(p50, bgLuminance);
            double L2 = Math.min(p50, bgLuminance);
            legibilityContrast = (L1 + 0.05) / (L2 + 0.05);

            System.out.printf("  Legibility: P50=%.2f (Contrast %.2f)\n", p50, legibilityContrast);

            if (legibilityContrast < 3.0) {
                // lerp(0.55 -> 0.80)
                double t = legibilityContrast / 3.0; // 0.0 to 1.0
                legibilityPenaltyFactor = 0.55 + (0.25 * t);
                System.out.printf("  Link: Penalty %.2fx (Contrast < 3.0)\n", legibilityPenaltyFactor);
            } else if (legibilityContrast < 4.5) {
                // lerp(0.80 -> 0.95)
                double t = (legibilityContrast - 3.0) / 1.5; // 0.0 to 1.0
                legibilityPenaltyFactor = 0.80 + (0.15 * t);
                System.out.printf("  Link: Penalty %.2fx (Contrast < 4.5)\n", legibilityPenaltyFactor);
            }
        } else {
             System.out.println("  Legibility: Skipped (Not enough HF content)");
        }

        double scoreAfterLegibility = technicalScore * legibilityPenaltyFactor;

        // --- 7. Visual Appeal Adjustment (Human Bias) ---
        double appealFactor = calculateVisualAppeal(design, bgLab, bgRgb);
        double scoreAfterAppeal = scoreAfterLegibility * (1.0 + appealFactor);

        double finalScoreVal = clamp(scoreAfterAppeal, 0.0, 100.0);

        // Determine Suitability (Legacy / Driver-dependent, but we set a default here)
        Suitability suitability = Suitability.BAD;
        if (finalScoreVal >= thresholds.goodFloor()) suitability = Suitability.GOOD;
        else if (finalScoreVal >= thresholds.borderlineFloor()) suitability = Suitability.BORDERLINE;

        // Override logic (just for reporting legibility fails in valid struct)
        String overrideReason = null;
        if (legibilityContrast > 0 && legibilityContrast < 3.0) {
             overrideReason = String.format("Legibility %.1f < 3.0", legibilityContrast);
        }

        System.out.printf("SUMMARY: bg=%s | base=%.1f | pen=%.2f | appeal=%+.2f | final=%.1f | class=%s\n",
                hexColor, technicalScore, legibilityPenaltyFactor, appealFactor, finalScoreVal, suitability);
        System.out.println("--------------------------------------------------");

        return new BackgroundEvaluationResult(
                hexColor,
                contrastScore,
                collisionScore,
                printRiskScore,
                technicalScore,
                legibilityContrast,
                legibilityPenaltyFactor,
                appealFactor,
                finalScoreVal,
                suitability,
                overrideReason
        );
    }

    private double calculateVisualAppeal(DesignAnalysisResult design, double[] bgLab, int bgRgb) {
        double L = bgLab[0];
        double a = bgLab[1];
        double b = bgLab[2];
        double C = Math.sqrt(a * a + b * b);
        double hDegrees = Math.toDegrees(Math.atan2(b, a));
        if (hDegrees < 0) hDegrees += 360.0;

        double appeal = 0.0;

        // 1. Neutral Dark Bonus (+0.03)
        // Black (L < 5)
        boolean isBlack = L < 5.0;
        // Charcoal/Pepper (L < 40, C < 10)
        boolean isDarkNeutral = L < 40.0 && C < 10.0;
        // Navy (H in [260, 280], L < 30)
        boolean isNavy = (hDegrees >= 260 && hDegrees <= 280) && L < 30.0;

        if (isBlack || isDarkNeutral || isNavy) {
            appeal += 0.03;
        }

        // 2. Near-White Aesthetic Risk (-0.03) (Constraint: Use Lab L* > 90 explicitly)
        // If design.nearWhiteRatio() > 0.15 AND bgLab[0] > 90
        if (design.nearWhiteRatio() > 0.15 && L > 90.0) {
            appeal -= 0.03;
        }

        // 3. High Saturation Risk (-0.02)
        // If Background Chroma C* > 50
        if (C > 50.0) {
            appeal -= 0.02;
        }

        // 4. Harmony Bonus (+0.02)
        // If Background Hue is within +/- 20 degrees of any DominantColor hue (weight > 0.10)
        boolean hasHarmony = false;
        if (C > 5.0) { // Only check hue if background has color
            for (DominantColor dc : design.dominantColors()) {
                if (dc.weight() > 0.10) {
                    double dcC = Math.sqrt(dc.labA() * dc.labA() + dc.labB() * dc.labB());
                    if (dcC > 5.0) {
                        double dcH = Math.toDegrees(Math.atan2(dc.labB(), dc.labA()));
                        if (dcH < 0) dcH += 360.0;
                        
                        double diff = Math.abs(hDegrees - dcH);
                        if (diff > 180.0) diff = 360.0 - diff;
                        
                        if (diff <= 20.0) {
                            hasHarmony = true;
                            break;
                        }
                    }
                }
            }
        }
        if (hasHarmony) {
            appeal += 0.02;
        }

        return clamp(appeal, -0.05, 0.05);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
