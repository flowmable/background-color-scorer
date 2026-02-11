package com.flowmable.scorer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class ValidationDriver {

    private record NamedColor(String name, String hex) {}

    private static final List<NamedColor> TEST_BACKGROUNDS = List.of(
            new NamedColor("Black", "#000000"),
            new NamedColor("Pepper", "#5F605B"),
            new NamedColor("Chambray", "#D9EDF5"),
            new NamedColor("Blossom", "#F8D1E2")
    );

    public static void main(String[] args) throws Exception {
        Path designPath = Path.of("src/main/resources/designs/design (4).png");
        System.out.println("Validating Design: " + designPath);
        BufferedImage image = ImageIO.read(designPath.toFile());

        ScoringThresholds thresholds = ScoringThresholds.DEFAULT;
        BackgroundColorScorer scorer = new BackgroundColorScorer(thresholds);
        BackgroundEvaluator evaluator = new BackgroundEvaluator(thresholds);

        // Analyze
        long t0 = System.nanoTime();
        DesignAnalysisResult analysis = scorer.analyzeDesign(image);
        long tAnalysis = System.nanoTime() - t0;
        
        System.out.printf("Analysis Time: %d ms%n", tAnalysis / 1_000_000);
        System.out.printf("Legibility Stats: AreaRatio=%.4f%%, P25=%.2f, P50=%.2f, P75=%.2f%n", 
                analysis.legibilityAreaRatio()*100, analysis.legibilityLuminanceP25(), analysis.legibilityLuminanceP50(), analysis.legibilityLuminanceP75());

        System.out.println("\n| Background | Global Score | Legibility Contrast | Final Score | Class | Override |");
        System.out.println("| :--- | :--- | :--- | :--- | :--- | :--- |");

        for (NamedColor bg : TEST_BACKGROUNDS) {
            BackgroundEvaluationResult result = evaluator.evaluate(analysis, bg.hex());
            
            // Re-calculate local legibility contrast for display (it's internal to evaluate now, but I can replicate math)
            int bgRgb = Integer.parseInt(bg.hex().replaceFirst("#", ""), 16);
            double bgLum = ColorSpaceUtils.relativeLuminance((bgRgb >> 16) & 0xFF, (bgRgb >> 8) & 0xFF, bgRgb & 0xFF);
            double p50 = analysis.legibilityLuminanceP50();
            double L1 = Math.max(p50, bgLum);
            double L2 = Math.min(p50, bgLum);
            double ratio = (L1 + 0.05) / (L2 + 0.05);

            System.out.printf("| %-9s | %5.1f | %5.2f | %5.1f | %-10s | %s |%n",
                    bg.name(), 
                    result.finalScore(), // Wait, finalScore is post-penalty. Base score? No, let's just print final.
                    ratio,
                    result.finalScore(),
                    result.suitability(),
                    result.overrideReason() == null ? "-" : result.overrideReason()
            );
        }
    }
}
