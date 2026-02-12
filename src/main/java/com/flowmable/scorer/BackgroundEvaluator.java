package com.flowmable.scorer;

import java.util.List;
import java.util.Map;

/**
 * Phase 2: Per-background-color evaluation.
 * <p>
 * V3 SCORING MODEL (Bounded Energy Layering)
 * <p>
 * Layer 1: Perceptual Energy (Physics) - Deterministic, Determinant of StdDev.
 * Layer 2: Aesthetic Energy (Coherence) - Bounded, Normalized to Physics.
 * Layer 3: Commercial Energy (Bias) - Scaled, Orthogonal.
 * Layer 4: Variance Governance - Distribution-aware budgeting.
 */
public class BackgroundEvaluator {

    private final ScoringThresholds thresholds;

    public BackgroundEvaluator() {
        this(ScoringThresholds.DEFAULT);
    }

    public BackgroundEvaluator(ScoringThresholds thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * Data Contract: PURE PHYSICS.
     * Contains only deterministic physical measurements.
     */
    public record RawScoringData(
            double rawContrastScore, // Weighted sum of P10, Mean, Min (with fragility)
            double p10DeltaE,
            double minClusterDeltaE,
            double weightedMeanDeltaE,
            double fragility,
            double tonalPenalty,
            double vibrationPenalty,
            double[] bgLab // Cached for Phase 2
    ) {
        public double netRawScore() {
            return rawContrastScore + tonalPenalty + vibrationPenalty;
        }
    }

    // --- PHASE 1: RAW EVALUATION (PURE) ---

    public RawScoringData evaluateRaw(DesignAnalysisResult design, String hexColor) {
        int bgRgb = Integer.parseInt(hexColor.replaceFirst("#", ""), 16);
        int bgR = (bgRgb >> 16) & 0xFF;
        int bgG = (bgRgb >> 8) & 0xFF;
        int bgB = bgRgb & 0xFF;
        double[] bgLabD = ColorSpaceUtils.srgbToLab(bgR, bgG, bgB);
        float[] bgLabF = new float[]{(float) bgLabD[0], (float) bgLabD[1], (float) bgLabD[2]};

        // 0. Degenerate Input Check (Handled in Scorer or returning 0)
        if (design.foregroundPixelCount() == 0) {
            return new RawScoringData(0, 0, 0, 0, 0, 0, 0, bgLabD);
        }

        // 1. Cluster Deltas (Min & Mean)
        List<DominantColor> colors = design.dominantColors();
        double minClusterDeltaE = Double.MAX_VALUE;
        double weightedMeanDeltaE = 0;
        double totalWeight = 0;

        for (DominantColor dc : colors) {
            double de = ColorSpaceUtils.ciede2000(dc.labL(), dc.labA(), dc.labB(), bgLabD[0], bgLabD[1], bgLabD[2]);
            if (de < minClusterDeltaE) minClusterDeltaE = de;
            weightedMeanDeltaE += de * dc.weight();
            totalWeight += dc.weight();
        }
        if (totalWeight > 0) weightedMeanDeltaE /= totalWeight;
        if (minClusterDeltaE == Double.MAX_VALUE) minClusterDeltaE = 0;

        // 2. P10 Pixel Contrast
        double p10DeltaE = calculateP10DeltaE(design, bgLabF, minClusterDeltaE);

        // 3. Fragility & Physics Weights (V3: Inverted Fragility)
        // Design Resistance: Darkness (55%), Structure (15%), Solidity (30%)
        double rDarkness = 1.0 - design.nearWhiteRatio();
        double rStructure = design.edgeDensity();
        double rSolidity = 1.0 - design.transparencyRatio();
        double designResistance = (0.55 * rDarkness) + (0.15 * rStructure) + (0.30 * rSolidity);
        designResistance = Math.max(0.0, Math.min(1.0, designResistance));
        double fragility = Math.pow(1.0 - designResistance, 2.2);

        // V3 Change: Fragile designs BOOST P10.
        // Cap fragility boost at 1.6x
        double fragilityBoost = 1.0 + (0.6 * fragility);

        // V3 Weights: 0.45 Mean + 0.30 P10 + 0.20 Min
        double termMean = 0.45 * weightedMeanDeltaE;
        double termP10 = 0.30 * (p10DeltaE * fragilityBoost);
        double termMin = 0.20 * minClusterDeltaE;

        double rawContrast = termMean + termP10 + termMin;

        // 4. Penalties (Tonal & Vibration)
        double tonalPenalty = 0;
        double vibrationPenalty = 0;

        double bgHue = hueAngle(bgLabD[1], bgLabD[2]);
        double minHueDist = 360.0;
        for (DominantColor dc : colors) {
             double dcHue = hueAngle(dc.labA(), dc.labB());
             double dist = Math.abs(bgHue - dcHue);
             if (dist > 180) dist = 360 - dist;
             if (dist < minHueDist) minHueDist = dist;
        }

        // Tonal Penalty
        if (minHueDist < 15.0 
                && minClusterDeltaE < 25.0 
                && p10DeltaE < (thresholds.tailVetoFloor() * thresholds.tonalTriggerRatio())) {
            tonalPenalty = -8.0;
        }

        // Vibration Penalty (BgChroma > FgP75 * Ratio)
        double bgChroma = Math.sqrt(bgLabD[1] * bgLabD[1] + bgLabD[2] * bgLabD[2]);
        // Complementary Hue & Equiluminant
        if (minHueDist >= 160.0 && minHueDist <= 200.0) {
            double lumDiff = Math.abs(bgLabD[0] - design.foregroundMeanL());
            if (lumDiff < 30.0) {
                 if (bgChroma > (design.foregroundP75Chroma() * thresholds.vibrationChromaRatio())
                     && design.foregroundP75Chroma() > 15.0) {
                     vibrationPenalty = -5.0;
                 }
            }
        }
        
        // Dominance Compression (Low coverage)
        double coverage = (double) design.foregroundPixelCount() / design.totalPixelCount();
        if (coverage < 0.15) {
             rawContrast *= 0.85; 
        }

        return new RawScoringData(
                rawContrast, 
                p10DeltaE, 
                minClusterDeltaE, 
                weightedMeanDeltaE, 
                fragility, 
                tonalPenalty, 
                vibrationPenalty,
                bgLabD
        );
    }

    // --- PHASE 2: FINAL EVALUATION (BUDGETED) ---

    public BackgroundEvaluationResult evaluateFinal(
            RawScoringData raw, 
            DesignAnalysisResult design, 
            String hexColor, 
            double rewardBudget, 
            double aestheticScale
    ) {
        if (design.foregroundPixelCount() == 0) {
             return new BackgroundEvaluationResult(hexColor, 0, 0, 0, 0, 0, 1.0, 0, 0, 0, 0, Suitability.BAD, "DEGENERATE");
        }

        double[] bgLabD = raw.bgLab;
        double rawScore = raw.netRawScore(); // Physics - Penalties

        // --- AESTHETIC LAYER (Refinements) ---

        // 1. Harmony Reward (Positive)
        // Normalized by Contrast Confidence (Don't reward chaos)
        // Tight Sigma = 25.0
        double harmonyReward = 0.0;
        double bgHue = hueAngle(bgLabD[1], bgLabD[2]);
        double minHueDist = 360.0; 
        for (DominantColor dc : design.dominantColors()) {
             double dcHue = hueAngle(dc.labA(), dc.labB());
             double dist = Math.abs(bgHue - dcHue);
             if (dist > 180) dist = 360 - dist;
             if (dist < minHueDist) minHueDist = dist;
        }

        if (raw.tonalPenalty == 0 && raw.vibrationPenalty == 0) {
            double hueFactor = Math.exp(-Math.pow(minHueDist / thresholds.harmonySigma(), 2));
            // Context Awareness: Scale by contrast confidence (Raw / 60)
            double contrastConfidence = Math.min(1.0, raw.rawContrastScore / 60.0); 
            harmonyReward = 4.0 * hueFactor * contrastConfidence;
        }

        // 2. Outline Boost (Directional Polarity)
        // Uses Refactored DesignAnalyzer metric: whiteBlackEdgeRatio
        double outlineBoost = 0.0;
        if (isNearBlack(bgLabD)) {
            // "Edge Polarity" = EdgeDensity * WhiteBlackAdjacencyRatio
            // Boost = Scaled P10? No, boost is aesthetic reinforcement.
            // Using logic: WhiteBlackEdgeRatio * 10, capped at 3.5.
            outlineBoost = (design.whiteBlackEdgeRatio() * 10.0); 
            outlineBoost = Math.min(3.5, outlineBoost);
        }

        // 3. Flatness Risk (Context-Aware Neutral Dampener)
        // Target: Low Chroma AND Mid-High Luminance
        // Context: Only if Design Contrast (P10) is weak relative to background (i.e. low P10)
        double neutralDampener = 0.0;
        double bgChroma = Math.sqrt(bgLabD[1]*bgLabD[1] + bgLabD[2]*bgLabD[2]);
        double bgL = bgLabD[0];
        
        double chromaRisk = Math.exp(-Math.pow(bgChroma / 12.0, 2));
        double lRisk = Math.exp(-Math.pow((bgL - 60.0) / 30.0, 2)); // Centered at 60
        double flatnessRisk = chromaRisk * lRisk;
        
        // Protect High Contrast Designs: If P10 is high (>50), dampener is 0.
        double normP10 = Math.min(1.0, raw.p10DeltaE / 50.0);
        neutralDampener = -thresholds.flatnessPenaltyScale() * flatnessRisk * (1.0 - normP10);

        // --- COMMERCIAL LAYER ---

        // Market Weight (Scaled)
        double marketBonus;
        if (thresholds.marketWeights().containsKey(hexColor)) {
            marketBonus = thresholds.marketWeights().get(hexColor);
        } else {
            marketBonus = computeDynamicMarketWeight(bgLabD) * 2.0; // V3: 2.0x Scaling
        }

        // Double Counting Guard: If Harmony > 2.0, reduce Market Bonus
        if (harmonyReward > 2.0) {
            marketBonus *= 0.5;
        }

        // --- BUDGET APPLICATION ---
        
        double positiveAdditions = harmonyReward + outlineBoost + Math.max(0, marketBonus);
        
        // Cap total positives by budget
        if (positiveAdditions > rewardBudget) {
            double scaling = rewardBudget / positiveAdditions;
            harmonyReward *= scaling;
            outlineBoost *= scaling;
            if (marketBonus > 0) marketBonus *= scaling;
        }

        // Combine Final Score
        double aestheticTotal = (harmonyReward + outlineBoost + neutralDampener) * aestheticScale;
        
        double finalScore = rawScore + aestheticTotal + marketBonus;
        finalScore = clamp(finalScore, 0.0, 100.0);

        // --- CLASSIFICATION ---
        Suitability suitability;
        boolean tailStrong = raw.p10DeltaE >= thresholds.tailVetoFloor();

        if (finalScore >= thresholds.goodFloor()) {
            if (tailStrong) suitability = Suitability.GOOD;
            else suitability = Suitability.BORDERLINE;
        } else if (finalScore >= thresholds.borderlineFloor()) {
            if (tailStrong) suitability = Suitability.BORDERLINE;
            else suitability = Suitability.BAD;
        } else {
            suitability = Suitability.BAD;
        }

        return new BackgroundEvaluationResult(
                hexColor,
                raw.p10DeltaE,
                raw.minClusterDeltaE,
                0.0,
                rawScore, // 'Technical Score' maps to Raw Physics
                0.0,
                1.0,
                aestheticTotal, // 'Appeal' maps to Aesthetics
                neutralDampener, // 'Ink' maps to Dampener (Observable)
                marketBonus,
                finalScore,
                suitability,
                null
        );
    }
    
    // Legacy Adapter for Single-Pass usage (Default Budget)
    public BackgroundEvaluationResult evaluate(DesignAnalysisResult design, String hexColor) {
        RawScoringData raw = evaluateRaw(design, hexColor);
        // Default Budget: 6.0
        return evaluateFinal(raw, design, hexColor, 6.0, 1.0);
    }

    // --- UTILS ---

    private double calculateP10DeltaE(DesignAnalysisResult design, float[] bgLab, double minClusterDeltaE) {
        float[] pixels = design.foregroundPixelsLab();
        if (pixels == null || pixels.length == 0) return minClusterDeltaE;

        int count = pixels.length / 3;
        double[] deltaEs = new double[count];
        
        for (int i = 0; i < count; i++) {
            deltaEs[i] = ColorSpaceUtils.ciede2000Float(
                    pixels[i * 3], pixels[i * 3 + 1], pixels[i * 3 + 2],
                    bgLab[0], bgLab[1], bgLab[2]
            );
        }

        int k = (int) (count * 0.10); 
        if (k >= count) k = count - 1;
        double rawP10 = quickSelect(deltaEs, 0, count - 1, k);

        if (count < 200) {
            double blend = count / 200.0;
            return blend * rawP10 + (1.0 - blend) * minClusterDeltaE;
        }
        
        return rawP10;
    }

    private double quickSelect(double[] arr, int left, int right, int k) {
        if (left == right) return arr[left];
        int pivotIndex = left + (right - left) / 2;
        pivotIndex = partition(arr, left, right, pivotIndex);
        if (k == pivotIndex) return arr[k];
        else if (k < pivotIndex) return quickSelect(arr, left, pivotIndex - 1, k);
        else return quickSelect(arr, pivotIndex + 1, right, k);
    }
    
    private int partition(double[] arr, int left, int right, int pivotIndex) {
        double pivotValue = arr[pivotIndex];
        swap(arr, pivotIndex, right);
        int storeIndex = left;
        for (int i = left; i < right; i++) {
            if (arr[i] < pivotValue) {
                swap(arr, storeIndex, i);
                storeIndex++;
            }
        }
        swap(arr, storeIndex, right);
        return storeIndex;
    }
    
    private void swap(double[] arr, int i, int j) {
        double temp = arr[i];
        arr[i] = arr[j];
        arr[j] = temp;
    }

    private double hueAngle(double a, double b) {
        double h = Math.toDegrees(Math.atan2(b, a));
        if (h < 0) h += 360.0;
        return h;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    
    private boolean isNearBlack(double[] lab) {
        return lab[0] < 15.0; 
    }

    private double computeDynamicMarketWeight(double[] bgLab) {
        double L = bgLab[0];
        double a = bgLab[1];
        double b = bgLab[2];
        double chroma = Math.sqrt(a*a + b*b);
        double hue = Math.toDegrees(Math.atan2(b, a));
        if (hue < 0) hue += 360.0;

        // V3 Market Weight
        double neutralComponent = Math.exp(-Math.pow(chroma / 18.0, 2));
        double midToneComponent = Math.exp(-Math.pow((L - 50.0) / 25.0, 2));
        double vibrancyComponent = Math.pow(chroma / 100.0, 2);
        
        if (hue >= 340 || hue <= 30) {
            vibrancyComponent *= 0.6; 
        }

        double hueComponent = 0.0;
        if (hue >= 200 && hue <= 260) hueComponent = 0.3;
        else if (hue >= 30 && hue <= 70) hueComponent = 0.2;
        else if (hue >= 300 && hue < 340) hueComponent = -0.2;

        double versatilityComponent = 1.0 - (chroma / 100.0);

        double marketWeight =
                0.7 * neutralComponent
              + 0.2 * midToneComponent
              - 1.4 * vibrancyComponent
              + 0.4 * hueComponent
              + 0.1 * versatilityComponent
              - 0.35;

        return Math.max(-2.0, Math.min(2.0, marketWeight));
    }
}
