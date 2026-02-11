package com.flowmable.scorer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CLI driver that scores design images and produces a ranked recommendation report.
 * <p>
 * Implements the Recommendation Layer (Layer C) of the spec:
 * - Group backgrounds into BEST, ACCEPTABLE, AVOID.
 * - Sort by scoreAfterAppeal.
 * - Format output for human review.
 */
public class ScorerDriver {

    // ──────────────────────────────────────────────────────────────
    // ▼▼▼  EDIT THESE HEX CODES TO TEST DIFFERENT BACKGROUNDS  ▼▼▼
    // ──────────────────────────────────────────────────────────────
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
    // ──────────────────────────────────────────────────────────────

    private static final String DESIGNS_DIR = "designs";

    public static void main(String[] args) throws Exception {
        // Use custom thresholds if needed, but logic is mainly in Evaluator now.
        ScoringThresholds thresholds = ScoringThresholds.DEFAULT;
        BackgroundColorScorer scorer = new BackgroundColorScorer(thresholds);

        List<Path> designFiles = findDesignFiles(args);

        if (designFiles.isEmpty()) {
            System.out.println("No designs found. Place files in src/main/resources/designs/");
            return;
        }

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  BACKGROUND RECOMMENDER — Human-Aligned Report");
        System.out.println("═══════════════════════════════════════════════════════════");

        for (Path file : designFiles) {
            processDesign(scorer, file);
        }
    }

    private static void processDesign(BackgroundColorScorer scorer, Path file) {
        String name = file.getFileName().toString();
        System.out.printf("\nDesign: %s\n", name);

        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
            if (image == null) return;
        } catch (IOException e) {
            System.out.println("  Error reading file.");
            return;
        }

        DesignAnalysisResult analysis = scorer.analyzeDesign(image);

        // Evaluate all backgrounds
        List<ScoredBackground> results = new ArrayList<>();
        for (NamedColor bg : BACKGROUND_COLORS) {
            BackgroundEvaluationResult res = scorer.evaluateBackground(analysis, bg.hex());
            results.add(new ScoredBackground(bg, res));
        }

        // Sort by Final Score (descending)
        results.sort(Comparator.comparingDouble((ScoredBackground sb) -> sb.result.finalScore()).reversed());

        int N = results.size();
        List<ScoredBackground> best = new ArrayList<>();
        List<ScoredBackground> acceptable = new ArrayList<>();
        List<ScoredBackground> avoid = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            ScoredBackground sb = results.get(i);
            double score = sb.result.finalScore();
            double contrast = sb.result.legibilityContrast();

            // Tier Assignment Logic
            boolean isLegibilityFail = contrast > 0 && contrast < 3.0; // contrast 0 means skipped/irrelevant

            if (isLegibilityFail) {
                avoid.add(sb);
            } else {
                // Determine percentile rank (0.0 = top, 1.0 = bottom)
                double rankPercentile = (double) i / N;

                if (rankPercentile < 0.20 && contrast >= 4.5) {
                    best.add(sb);
                } else if ((rankPercentile < 0.70 || (score >= 55.0 && contrast >= 3.0)) && contrast >= 3.0) {
                     // Spec: ACCEPTABLE: Middle 50% OR (failed BEST & LC>=3.0) AND LC>=3.0
                     // We use a simplified check: if not BEST, and not AVOID (bottom 30% or fail), then ACCEPTABLE.
                     // AVOID is Bottom 30% OR LC < 3.0.
                     // So if rank >= 0.70, it's AVOID unless specific overrides?
                     // Spec says: AVOID: Bottom 30% OR ...
                     if (rankPercentile >= 0.70) {
                         avoid.add(sb);
                     } else {
                         acceptable.add(sb);
                     }
                } else {
                    avoid.add(sb);
                }
            }
        }

        // Print Report
        printTier("BEST", best);
        printTier("ACCEPTABLE", acceptable);
        printTier("AVOID", avoid);
        System.out.println();
    }

    private static void printTier(String title, List<ScoredBackground> items) {
        String icon = switch (title) {
            case "BEST" -> "✅";
            case "ACCEPTABLE" -> "⚠️";
            case "AVOID" -> "❌";
            default -> "";
        };
        System.out.printf("\n%s %s:\n", icon, title);
        if (items.isEmpty()) {
            System.out.println("  (None)");
            return;
        }
        for (ScoredBackground sb : items) {
             // Format: - Black (score: 82.1, legibility: 8.2, appeal: +0.03)
             System.out.printf("  - %-12s (score: %4.1f, legibility: %4.1f, appeal: %+5.2f)\n",
                     sb.bg.name,
                     sb.result.finalScore(),
                     sb.result.legibilityContrast(),
                     sb.result.visualAppealScore());
        }
    }

    /**
     * Find design files: from CLI args, or from the resources/designs directory.
     */
    private static List<Path> findDesignFiles(String[] args) throws Exception {
        if (args.length > 0) {
            return java.util.Arrays.stream(args)
                    .map(Path::of)
                    .filter(Files::isRegularFile)
                    .toList();
        }
        Path resourcesDir = Path.of("src", "main", "resources", DESIGNS_DIR);
        if (Files.isDirectory(resourcesDir)) {
            try (var stream = Files.list(resourcesDir)) {
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

    record ScoredBackground(NamedColor bg, BackgroundEvaluationResult result) {}
}
