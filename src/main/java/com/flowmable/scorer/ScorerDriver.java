package com.flowmable.scorer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CLI driver that scores design images and produces a ranked recommendation report.
 * <p>
 * V3 UPDATE:
 * - Adds Stability Validation (Spearman Rank Correlation, Promotion Drift).
 * - Adds Determinism Audit.
 */
public class ScorerDriver {

    public record NamedColor(String name, String hex) {}

    private static final List<NamedColor> BACKGROUND_COLORS = List.of(
            new NamedColor("White", "#ffffff"),
            new NamedColor("Brick", "#915C5C"),
            new NamedColor("Ivory", "#FFF7E7"),
            new NamedColor("Mustard", "#D0AE6E"),
            new NamedColor("Yam", "#C9814F"),
            new NamedColor("Espresso", "#846b5b"),
            new NamedColor("Butter", "#F5E1A4"),
            new NamedColor("Pepper", "#5F605B"),
            new NamedColor("Grey", "#7A7F79"),
            new NamedColor("Bay", "#C3CFC1"),
            new NamedColor("Moss", "#747F66"),
            new NamedColor("Island Reef", "#A2D8C2"),
            new NamedColor("Chalky Mint", "#A7D9D4"),
            new NamedColor("Light Green", "#738874"),
            new NamedColor("Blue Spruce", "#536758"),
            new NamedColor("Lagoon Blue", "#89E4ED"),
            new NamedColor("Sapphire", "#03b2d3"),
            new NamedColor("Chambray", "#D9EDF5"),
            new NamedColor("Flo Blue", "#7682C2"),
            new NamedColor("Blue Jean", "#788CA1"),
            new NamedColor("Graphite", "#373231"),
            new NamedColor("Black", "#000000"),
            new NamedColor("Navy", "#263040"),
            new NamedColor("Violet", "#A88FD7"),
            new NamedColor("Neon Violet", "#e8ace3"),
            new NamedColor("Orchid", "#CBB3CC"),
            new NamedColor("Blossom", "#F8D1E2"),
            new NamedColor("Neon Pink", "#f57caf"),
            new NamedColor("Crunchberry", "#EB7CA2"),
            new NamedColor("Berry", "#775568"),
            new NamedColor("Watermelon", "#DA807B"),
            new NamedColor("Chili", "#853F44"),
            new NamedColor("Crimson", "#B66A74"),
            new NamedColor("Red", "#A80D27")
    );

    private static final String DESIGNS_DIR = "designs";

    public static void main(String[] args) throws Exception {
        ScoringThresholds thresholds = ScoringThresholds.DEFAULT;
        BackgroundColorScorer scorer = new BackgroundColorScorer(thresholds);

        List<Path> designFiles = findDesignFiles(args);

        if (designFiles.isEmpty()) {
            System.out.println("No designs found. Place files in src/main/resources/designs/");
            return;
        }

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  BACKGROUND RECOMMENDER (V3 MODEL) — Benchmark Report");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("Model Version: " + ScoringThresholds.SCORING_MODEL_VERSION);
        System.out.println("=== Classification Gates ===");
        System.out.println("Promoted >= " + thresholds.goodFloor());
        System.out.println("Passed    >= " + thresholds.borderlineFloor());
        System.out.println("Baseline (Raw) = " + thresholds.rawBaselineStdDev());

        // 1. Audit Determinism (First File)
        auditDeterminism(scorer, designFiles.get(0));

        List<ScoredBackground> allBenchmarkResults = new ArrayList<>();
        List<Double> spearmanCorrelations = new ArrayList<>();

        for (Path file : designFiles) {
            List<ScoredBackground> designResults = processDesign(scorer, file);
            allBenchmarkResults.addAll(designResults);
            
            // Calculate Spearman for this design
            double rho = calculateSpearmanCorrelation(designResults);
            spearmanCorrelations.add(rho);
            System.out.printf("  [Stability] Spearman Rho (vs Raw): %.3f\n", rho);
        }
        
        if (!allBenchmarkResults.isEmpty()) {
             printWeightDistributionStats(allBenchmarkResults);
             printGlobalBenchmarkStats(allBenchmarkResults, spearmanCorrelations, thresholds);
        }
    }

    private static void auditDeterminism(BackgroundColorScorer scorer, Path file) throws IOException {
        System.out.println("\n[Audit] Checking Determinism on " + file.getFileName() + "...");
        BufferedImage img = ImageIO.read(file.toFile());
        List<String> hexes = BACKGROUND_COLORS.stream().map(NamedColor::hex).toList();
        
        var run1 = scorer.score(img, hexes);
        var run2 = scorer.score(img, hexes);
        
        boolean match = true;
        for (String hex : hexes) {
            if (run1.get(hex).finalScore() != run2.get(hex).finalScore()) {
                match = false;
                System.err.println("  ❌ MISMATCH for " + hex + ": " + run1.get(hex).finalScore() + " vs " + run2.get(hex).finalScore());
            }
        }
        
        if (match) System.out.println("  ✅ Determinism Check PASSED");
        else System.out.println("  ❌ Determinism Check FAILED");
    }

    private static List<ScoredBackground> processDesign(BackgroundColorScorer scorer, Path file) {
        String name = file.getFileName().toString();
        System.out.printf("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        System.out.printf("Design: %s\n", name);

        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
            if (image == null) return new ArrayList<>();
        } catch (IOException e) {
            System.out.println("  Error reading file.");
            return new ArrayList<>();
        }

        DesignAnalysisResult analysis = scorer.analyzeDesign(image);
        Map<String, BackgroundEvaluationResult> resultMap = scorer.score(image, BACKGROUND_COLORS.stream().map(NamedColor::hex).toList());
        
        List<ScoredBackground> results = new ArrayList<>();
        for(NamedColor bg : BACKGROUND_COLORS) {
            results.add(new ScoredBackground(bg, resultMap.get(bg.hex())));
        }

        // Sort by Final Score
        results.sort(Comparator.comparingDouble((ScoredBackground sb) -> sb.result.finalScore()).reversed());

        List<ScoredBackground> best = new ArrayList<>();
        List<ScoredBackground> acceptable = new ArrayList<>();
        List<ScoredBackground> avoid = new ArrayList<>();

        for (ScoredBackground sb : results) {
            switch (sb.result.suitability()) {
                case GOOD -> best.add(sb);
                case BORDERLINE -> acceptable.add(sb);
                case BAD -> avoid.add(sb);
            }
        }

        printTier("BEST (Promoted)", best);
        printTier("ACCEPTABLE (Passed)", acceptable);
        printTier("AVOID (Rejected)", avoid);
        
        System.out.println("\nDistribution:");
        System.out.println("Promoted: " + best.size());
        System.out.println("Passed: " + acceptable.size());
        System.out.println("Rejected: " + avoid.size());
        System.out.println();
        
        return results;
    }

    private static void printTier(String title, List<ScoredBackground> items) {
        String icon = switch (title) {
            case "BEST (Promoted)" -> "✅";
            case "ACCEPTABLE (Passed)" -> "⚠️";
            case "AVOID (Rejected)" -> "❌";
            default -> "";
        };
        System.out.printf("\n%s %s:\n", icon, title);
        if (items.isEmpty()) {
            System.out.println("  (None)");
            return;
        }
        for (ScoredBackground sb : items) {
             System.out.printf("  - %-12s (Final: %5.1f | P10: %4.1f | Perc: %4.1f | Ink: -%-4.2f | Mkt: %+5.2f)\n",
                     sb.bg.name,
                     sb.result.finalScore(),
                     sb.result.contrastScore(),
                     sb.result.baseScore(),
                     sb.result.inkPenalty(),
                     sb.result.marketBonus());
        }
    }

    private static void printWeightDistributionStats(List<ScoredBackground> results) {
         Map<String, Double> weights = results.stream()
                 .collect(Collectors.toMap(
                     sb -> sb.bg.hex, 
                     sb -> sb.result.marketBonus(), 
                     (existing, replacement) -> existing)); // Distinct keys

         DoubleSummaryStatistics stats = weights.values().stream()
                 .mapToDouble(Double::doubleValue)
                 .summaryStatistics();

         double stdDev = Math.sqrt(weights.values().stream()
                 .mapToDouble(v -> Math.pow(v - stats.getAverage(), 2))
                 .average().orElse(0.0));

         System.out.println("═══════════════════════════════════════════════════════════");
         System.out.printf("  MARKET WEIGHT DISTRIBUTION (Unique Hexes = %d)\n", weights.size());
         System.out.printf("  Min: %+5.2f | Max: %+5.2f | Mean: %+5.2f | StdDev: %.3f\n", 
                 stats.getMin(), stats.getMax(), stats.getAverage(), stdDev);
         System.out.println("═══════════════════════════════════════════════════════════");
    }

    private static List<Path> findDesignFiles(String[] args) throws Exception {
        if (args.length > 0) {
            return java.util.Arrays.stream(args)
                    .map(Path::of)
                    .filter(Files::isRegularFile)
                    .toList();
        }
        Path resourcesDir = Path.of("src", "main", "resources", DESIGNS_DIR);
        if (Files.isDirectory(resourcesDir)) {
            try (Stream<Path> stream = Files.list(resourcesDir)) {
                return stream
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                        })
                        .sorted()
                        .toList();
            }
        }
        return List.of();
    }

    private static void printGlobalBenchmarkStats(
            List<ScoredBackground> allResults, 
            List<Double> spearmanCorrelations,
            ScoringThresholds thresholds
    ) {
        if (allResults.isEmpty()) return;

        double sum = allResults.stream().mapToDouble(sb -> sb.result.finalScore()).sum();
        double mean = sum / allResults.size();
        double sqDiff = allResults.stream().mapToDouble(sb -> Math.pow(sb.result.finalScore() - mean, 2)).sum();
        double stdDev = Math.sqrt(sqDiff / allResults.size());

        long countGood = allResults.stream().filter(sb -> sb.result.suitability() == Suitability.GOOD).count();
        long countBorderline = allResults.stream().filter(sb -> sb.result.suitability() == Suitability.BORDERLINE).count();
        long countBad = allResults.stream().filter(sb -> sb.result.suitability() == Suitability.BAD).count();

        double pctGood = (double) countGood / allResults.size() * 100.0;
        double pctBorderline = (double) countBorderline / allResults.size() * 100.0;
        double pctBad = (double) countBad / allResults.size() * 100.0;
        
        // Promotion Drift Calculation
        long rawPromotedCount = allResults.stream()
                .filter(sb -> sb.result.baseScore() >= thresholds.goodFloor())
                .count();
        double rawPctGood = (double) rawPromotedCount / allResults.size() * 100.0;
        double drift = pctGood - rawPctGood;
        
        double avgSpearman = spearmanCorrelations.stream().mapToDouble(d -> d).average().orElse(0.0);

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.printf("  GLOBAL BENCHMARK AGGREGATE (N=%d)\n", allResults.size());
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.printf("  Final Score Mean : %.2f\n", mean);
        System.out.printf("  Final Score StdDev: %.2f (Target: 6-9, Raw: %.2f)\n", stdDev, thresholds.rawBaselineStdDev());
        System.out.println("  -----------------------------------------------------------");
        System.out.printf("  Promoted (Good)   : %d (%.1f%%) [Drift: %+.1f%%]\n", countGood, pctGood, drift);
        System.out.printf("  Passed (Borderline): %d (%.1f%%)\n", countBorderline, pctBorderline);
        System.out.printf("  Rejected (Bad)    : %d (%.1f%%)\n", countBad, pctBad);
        System.out.println("  -----------------------------------------------------------");
        System.out.printf("  Rank Stability (Rho): %.3f (Target > 0.85)\n", avgSpearman);
        System.out.println("═══════════════════════════════════════════════════════════");
    }
    
    // Spearman Rank Correlation Calculation
    private static double calculateSpearmanCorrelation(List<ScoredBackground> results) {
        int n = results.size();
        if (n <= 1) return 1.0;
        
        // 1. Get Ranks for Base Score
        List<ScoredBackground> sortedByBase = new ArrayList<>(results);
        sortedByBase.sort(Comparator.comparingDouble((ScoredBackground sb) -> sb.result.baseScore()).reversed());
        Map<String, Integer> baseRanks = new HashMap<>();
        for(int i=0; i<n; i++) baseRanks.put(sortedByBase.get(i).bg.hex, i+1);
        
        // 2. Get Ranks for Final Score
        List<ScoredBackground> sortedByFinal = new ArrayList<>(results);
        sortedByFinal.sort(Comparator.comparingDouble((ScoredBackground sb) -> sb.result.finalScore()).reversed());
        Map<String, Integer> finalRanks = new HashMap<>();
        for(int i=0; i<n; i++) finalRanks.put(sortedByFinal.get(i).bg.hex, i+1);
        
        // 3. Compute D^2
        double sumDSq = 0;
        for(ScoredBackground sb : results) {
            String hex = sb.bg.hex;
            int rb = baseRanks.get(hex);
            int rf = finalRanks.get(hex);
            sumDSq += Math.pow(rb - rf, 2);
        }
        
        // 4. Formula
        return 1.0 - (6.0 * sumDSq) / (n * (Math.pow(n, 2) - 1));
    }

    record ScoredBackground(NamedColor bg, BackgroundEvaluationResult result) {}
}
