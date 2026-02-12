package com.flowmable.scorer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generator for Technical Documentation Data Tables.
 * <p>
 * Computes:
 * - Distribution Diagnostics
 * - Component Contribution Analysis
 * - Design Metadata Extraction
 */
public class DocumentationGenerator {

    // Same color list as ScorerDriver
    private static final List<ScorerDriver.NamedColor> BACKGROUND_COLORS = List.of(
            new ScorerDriver.NamedColor("White", "#ffffff"),
            new ScorerDriver.NamedColor("Brick", "#915C5C"),
            new ScorerDriver.NamedColor("Ivory", "#FFF7E7"),
            new ScorerDriver.NamedColor("Mustard", "#D0AE6E"),
            new ScorerDriver.NamedColor("Yam", "#C9814F"),
            new ScorerDriver.NamedColor("Espresso", "#846b5b"),
            new ScorerDriver.NamedColor("Butter", "#F5E1A4"),
            new ScorerDriver.NamedColor("Pepper", "#5F605B"),
            new ScorerDriver.NamedColor("Grey", "#7A7F79"),
            new ScorerDriver.NamedColor("Bay", "#C3CFC1"),
            new ScorerDriver.NamedColor("Moss", "#747F66"),
            new ScorerDriver.NamedColor("Island Reef", "#A2D8C2"),
            new ScorerDriver.NamedColor("Chalky Mint", "#A7D9D4"),
            new ScorerDriver.NamedColor("Light Green", "#738874"),
            new ScorerDriver.NamedColor("Blue Spruce", "#536758"),
            new ScorerDriver.NamedColor("Lagoon Blue", "#89E4ED"),
            new ScorerDriver.NamedColor("Sapphire", "#03b2d3"),
            new ScorerDriver.NamedColor("Chambray", "#D9EDF5"),
            new ScorerDriver.NamedColor("Flo Blue", "#7682C2"),
            new ScorerDriver.NamedColor("Blue Jean", "#788CA1"),
            new ScorerDriver.NamedColor("Graphite", "#373231"),
            new ScorerDriver.NamedColor("Black", "#000000"),
            new ScorerDriver.NamedColor("Navy", "#263040"),
            new ScorerDriver.NamedColor("Violet", "#A88FD7"),
            new ScorerDriver.NamedColor("Neon Violet", "#e8ace3"),
            new ScorerDriver.NamedColor("Orchid", "#CBB3CC"),
            new ScorerDriver.NamedColor("Blossom", "#F8D1E2"),
            new ScorerDriver.NamedColor("Neon Pink", "#f57caf"),
            new ScorerDriver.NamedColor("Crunchberry", "#EB7CA2"),
            new ScorerDriver.NamedColor("Berry", "#775568"),
            new ScorerDriver.NamedColor("Watermelon", "#DA807B"),
            new ScorerDriver.NamedColor("Chili", "#853F44"),
            new ScorerDriver.NamedColor("Crimson", "#B66A74"),
            new ScorerDriver.NamedColor("Red", "#A80D27")
    );

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Documentation Data Generation...");
        
        Path designDir = Paths.get("src/main/resources/designs");
        if (!Files.exists(designDir)) {
            System.err.println("Design directory not found: " + designDir.toAbsolutePath());
            return;
        }

        List<Path> designFiles = Files.list(designDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".png"))
                .sorted(Comparator.comparing(Path::getFileName))
                .collect(Collectors.toList());

        BackgroundColorScorer scorer = new BackgroundColorScorer(ScoringThresholds.DEFAULT);
        
        // 1. Distribution Diagnostics
        System.out.println("\n# 4️⃣ Distribution Diagnostics");
        generateDistributionDiagnostics(scorer, designFiles);
        
        // 2 & 3. Component Analysis & Design Metadata
        System.out.println("\n# 5️⃣ Component Contribution Analysis & 6️⃣ Design Metadata");
        
        for (Path designFile : designFiles) {
            String designName = designFile.getFileName().toString();
            System.out.println("\n## Design: " + designName);
            
            BufferedImage img = ImageIO.read(designFile.toFile());
            if (img == null) continue;
            
            DesignAnalysisResult analysis = scorer.analyzeDesign(img);
            
            // Metadata
            generateDesignMetadata(scorer, analysis, img);
            
            // Component Analysis for selected colors
            List<String> targetColors = List.of("Black", "White", "Navy", "Grey", "Sapphire", "Neon Pink");
            generateComponentAnalysis(scorer, analysis, targetColors);
        }
    }

    private static void generateDistributionDiagnostics(BackgroundColorScorer scorer, List<Path> designFiles) throws Exception {
        List<Double> allScores = new ArrayList<>();
        List<Double> marketWeights = new ArrayList<>();
        int promoted = 0;
        int passed = 0;
        int rejected = 0;
        
        // Map<Suitability, Integer> counts = new HashMap<>();

        for (Path designFile : designFiles) {
            BufferedImage img = ImageIO.read(designFile.toFile());
            if (img == null) continue;
            DesignAnalysisResult analysis = scorer.analyzeDesign(img);

            for (ScorerDriver.NamedColor nc : BACKGROUND_COLORS) {
                BackgroundEvaluationResult res = scorer.evaluateBackground(analysis, nc.hex());
                allScores.add(res.finalScore());
                marketWeights.add(res.marketBonus());
                
                switch (res.suitability()) {
                    case GOOD -> promoted++;
                    case BORDERLINE -> passed++;
                    case BAD -> rejected++;
                }
            }
        }
        
        DoubleSummaryStatistics scoreStats = allScores.stream().mapToDouble(Double::doubleValue).summaryStatistics();
        DoubleSummaryStatistics weightStats = marketWeights.stream().mapToDouble(Double::doubleValue).summaryStatistics();
        
        System.out.println("| Metric | Value |");
        System.out.println("| :--- | :--- |");
        System.out.printf("| Mean Final Score | %.2f |\n", scoreStats.getAverage());
        System.out.printf("| StdDev Final Score | %.2f |\n", calculateStdDev(allScores));
        System.out.printf("| Min / Max Score | %.2f / %.2f |\n", scoreStats.getMin(), scoreStats.getMax());
        System.out.printf("| Market Weight Mean | %.2f |\n", weightStats.getAverage());
        System.out.printf("| Market Weight StdDev | %.2f |\n", calculateStdDev(marketWeights));
        System.out.println("| | |");
        System.out.printf("| Promoted (GOOD) | %d (%.1f%%) |\n", promoted, (promoted * 100.0 / allScores.size()));
        System.out.printf("| Passed (BORDERLINE) | %d (%.1f%%) |\n", passed, (passed * 100.0 / allScores.size()));
        System.out.printf("| Rejected (BAD) | %d (%.1f%%) |\n", rejected, (rejected * 100.0 / allScores.size()));
    }

    private static void generateDesignMetadata(BackgroundColorScorer scorer, DesignAnalysisResult analysis, BufferedImage img) {
        // Recalculate pixel-level stats that might not be in analysis result
        // Analysis result has: nearWhiteRatio, meanLuminance, p75Chroma, edgeDensity.
        // We need: % dark pixels (< L* 20), % light pixels (> L* 80), % near-black, % near-white, dominant hue cluster.
        
        // We can use the sampled pixels in analysis.foregroundPixelsLab() to approximate distributions if we trust the sample.
        float[] pixels = analysis.foregroundPixelsLab();
        int sampleCount = pixels.length / 3;
        
        int dark20 = 0;
        int light80 = 0;
        int nearBlack = 0; // L < 15 && C < 30 (Based on DesignAnalyzer logic)
        int nearWhite = 0; // L > 70 && C < 30
        
        // Calculate average saturation (Chroma)
        double totalChroma = 0;
        
        for(int i=0; i<sampleCount; i++) {
            float L = pixels[i*3];
            float a = pixels[i*3+1];
            float b = pixels[i*3+2];
            double C = Math.sqrt(a*a + b*b);
            
            if (L < 20) dark20++;
            if (L > 80) light80++;
            
            if (L < 15 && C < 30) nearBlack++;
            // Note: DesignAnalyzer uses L > 70 for nearWhite
            if (L > 70 && C < 30) nearWhite++;
            
            totalChroma += C;
        }

        double pctDark20 = (double) dark20 / sampleCount * 100.0;
        double pctLight80 = (double) light80 / sampleCount * 100.0;
        double pctNearBlack = (double) nearBlack / sampleCount * 100.0; // Approximation of full scan
        double pctNearWhite = analysis.nearWhiteRatio() * 100.0; // Use precise ratio from analysis
        double avgChroma = totalChroma / sampleCount;

        System.out.println("\n### Design Metadata");
        System.out.println("| Metric | Value |");
        System.out.println("| :--- | :--- |");
        System.out.printf("| %% Dark Pixels (< L* 20) | %.1f%% |\n", pctDark20);
        System.out.printf("| %% Light Pixels (> L* 80) | %.1f%% |\n", pctLight80);
        System.out.printf("| %% Near-Black Pixels | %.1f%% |\n", pctNearBlack);
        System.out.printf("| %% Near-White Pixels | %.1f%% |\n", pctNearWhite);
        System.out.printf("| Average Chroma | %.1f |\n", avgChroma);
        System.out.printf("| Outline Density | %.2f |\n", analysis.edgeDensity());
        System.out.printf("| Dominant Colors | %d |\n", analysis.dominantColors().size());
        
        // Dominant Hue Cluster (Avg Hue of Top Dominant Color)
        if (!analysis.dominantColors().isEmpty()) {
            DominantColor dc = analysis.dominantColors().get(0);
            double hue = Math.toDegrees(Math.atan2(dc.labB(), dc.labA()));
            if (hue < 0) hue += 360;
            System.out.printf("| Dominant Hue | %.1f° |\n", hue);
        }
    }

    private static void generateComponentAnalysis(BackgroundColorScorer scorer, DesignAnalysisResult analysis, List<String> targetColorNames) {
        System.out.println("\n### Component Contribution");
        System.out.println("| Color | Contrast Score | Harmony Score | P10 Delta | Ink Penalty | Market Weight | Raw Perc | Final Score | Class |");
        System.out.println("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |");

        // We need reflection to get internal breakdown if we want to be precise about "Harmony Score" (Tonal + Vibration)
        // BackgroundEvaluator.evaluate returns a result, but it aggregates Tonal+Vibration into 'baseScore' along with Contrast.
        // Wait, baseScore = perceptualScore = rawContrast + tonalPenalty + vibrationPenalty.
        // contrastScore = p10DeltaE.
        // We can infer rawContrast from formula if we expose components.
        // BUT, easier: Use reflection to call private methods or copy logic? 
        // Or just interpret the result:
        // P10 Delta = result.contrastScore()
        // Market = result.marketBonus()
        // Ink = result.inkPenalty()
        // Final = result.finalScore()
        
        // Missing: "Contrast Score" (The weighted term?), "Harmony Score" (Penalties), "Raw Perceptual Score" (The sum).
        // "Raw Perceptual Score" IS result.baseScore().
        // "Contrast Score" usually implies the positive contribution from contrast.
        // "Harmony Score" usually implies the negative penalties.
        
        // Let's rely on standard evaluate and do some manual math using assumptions or reflection if needed.
        // Or just duplicate the logic here for the report. 
        // Duplication is safer for "Exacting" documentation than inferring from aggregated values.
        
        // Instantiate private inner helper to get accurate breakdown
        // Actually, let's just use the public result and the known formula to reverse engineer?
        // baseScore = rawContrast + tonal + vibration.
        // We don't know tonal vs vibration split without re-calc.
        
        BackgroundEvaluator evaluator = new BackgroundEvaluator(ScoringThresholds.DEFAULT);

        for (String name : targetColorNames) {
            String hex = BACKGROUND_COLORS.stream().filter(c -> c.name().equals(name)).findFirst().map(ScorerDriver.NamedColor::hex).orElse(null);
            if (hex == null) continue;
            
            BackgroundEvaluationResult res = scorer.evaluateBackground(analysis, hex);
            
            // Re-calculate penalties to split them out
            double[] breakdown = recalculateBreakdown(evaluator, analysis, hex);
            double rawContrast = breakdown[0];
            double tonal = breakdown[1];
            double vib = breakdown[2];
            double harmony = tonal + vib;
            
            // "Contrast Score" in the table req likely means the Weighted Contrast Term (Mean + P10 + Min).
            // OR it means P10. 
            // The prompt asks for: "Contrast Score", "Harmony Score", "P10 Delta".
            // Since P10 Delta is listed separately, "Contrast Score" likely means the composite contrast metric (Weighted Mean/Min/P10).
            
            System.out.printf("| %s | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %s |\n",
                    name,
                    rawContrast,
                    harmony,
                    res.contrastScore(), // P10
                    res.inkPenalty(),
                    res.marketBonus(),
                    res.baseScore(), // Raw Perceptual
                    res.finalScore(),
                    res.suitability()
            );
        }
    }
    
    // Quick re-implementation of the penalty logic to isolate harmony terms
    private static double[] recalculateBreakdown(BackgroundEvaluator evaluator, DesignAnalysisResult design, String hex) {
        // [RawContrast, Tonal, Vibration]
        try {
            int bgRgb = Integer.parseInt(hex.replaceFirst("#", ""), 16);
            int bgR = (bgRgb >> 16) & 0xFF;
            int bgG = (bgRgb >> 8) & 0xFF;
            int bgB = bgRgb & 0xFF;
            double[] bgLabD = ColorSpaceUtils.srgbToLab(bgR, bgG, bgB);
            
            // 1. Cluster Deltas
            double minClusterDeltaE = Double.MAX_VALUE;
            double weightedMeanDeltaE = 0;
            double totalWeight = 0;
            for (DominantColor dc : design.dominantColors()) {
                double de = ColorSpaceUtils.ciede2000(dc.labL(), dc.labA(), dc.labB(), bgLabD[0], bgLabD[1], bgLabD[2]);
                if (de < minClusterDeltaE) minClusterDeltaE = de;
                weightedMeanDeltaE += de * dc.weight();
                totalWeight += dc.weight();
            }
            if (totalWeight > 0) weightedMeanDeltaE /= totalWeight;
            if (minClusterDeltaE == Double.MAX_VALUE) minClusterDeltaE = 0;
            
            // 2. P10 - access via private method or re-implement? 
            // Use reflection for P10
            Method p10Method = BackgroundEvaluator.class.getDeclaredMethod("calculateP10DeltaE", DesignAnalysisResult.class, float[].class, double.class);
            p10Method.setAccessible(true);
            float[] bgLabF = new float[]{(float) bgLabD[0], (float) bgLabD[1], (float) bgLabD[2]};
            double p10DeltaE = (double) p10Method.invoke(evaluator, design, bgLabF, minClusterDeltaE);

            // 3. Fragility
            double rDarkness = 1.0 - design.nearWhiteRatio();
            double rStructure = design.edgeDensity();
            double rSolidity = 1.0 - design.transparencyRatio();
            double designResistance = (0.55 * rDarkness) + (0.15 * rStructure) + (0.30 * rSolidity);
            designResistance = Math.max(0.0, Math.min(1.0, designResistance));
            double fragility = Math.pow(1.0 - designResistance, 2.2);
            
            double termMean = 0.45 * weightedMeanDeltaE;
            double termP10 = 0.35 * (p10DeltaE * (1.0 - fragility));
            double termMin = 0.20 * minClusterDeltaE;
            double rawContrast = termMean + termP10 + termMin;
            
            // Coverage Dampening
            double coverage = (double) design.foregroundPixelCount() / design.totalPixelCount();
            if (coverage < 0.15) {
                rawContrast *= 0.85;
            }

            // Penalties
            double tonalPenalty = 0;
            double vibrationPenalty = 0;
            
            // Re-implement hue logic
            Method hueMethod = BackgroundEvaluator.class.getDeclaredMethod("hueAngle", double.class, double.class);
            hueMethod.setAccessible(true);
            double bgHue = (double) hueMethod.invoke(evaluator, bgLabD[1], bgLabD[2]);
            
            double minHueDist = 360.0;
            for (DominantColor dc : design.dominantColors()) {
                 double dcHue = (double) hueMethod.invoke(evaluator, dc.labA(), dc.labB());
                 double dist = Math.abs(bgHue - dcHue);
                 if (dist > 180) dist = 360 - dist;
                 if (dist < minHueDist) minHueDist = dist;
            }
            
            ScoringThresholds thresholds = ScoringThresholds.DEFAULT;
            if (minHueDist < 15.0 && minClusterDeltaE < 25.0 && p10DeltaE < (thresholds.tailVetoFloor() * thresholds.tonalTriggerRatio())) {
                tonalPenalty = -8.0;
            }
            
            double bgChroma = Math.sqrt(bgLabD[1] * bgLabD[1] + bgLabD[2] * bgLabD[2]);
            if (minHueDist >= 160.0 && minHueDist <= 200.0) {
                double lumDiff = Math.abs(bgLabD[0] - design.foregroundMeanL());
                if (lumDiff < 30.0) {
                     if (bgChroma > (design.foregroundP75Chroma() * thresholds.vibrationChromaRatio()) && design.foregroundP75Chroma() > 15.0) {
                         vibrationPenalty = -5.0;
                     }
                }
            }
            
            return new double[] { rawContrast, tonalPenalty, vibrationPenalty };
            
        } catch (Exception e) {
            e.printStackTrace();
            return new double[]{0,0,0};
        }
    }

    private static double calculateStdDev(List<Double> values) {
        if (values.isEmpty()) return 0;
        double mean = values.stream().mapToDouble(d -> d).average().orElse(0);
        double sqDiff = values.stream().mapToDouble(d -> Math.pow(d - mean, 2)).sum();
        return Math.sqrt(sqDiff / values.size());
    }
}
