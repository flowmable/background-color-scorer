package com.flowmable.scorer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level entry point for the background color scoring pipeline.
 * <p>
 * Usage:
 * <pre>
 * var scorer = new BackgroundColorScorer();
 * var results = scorer.score(
 *     Path.of("design.png"),
 *     List.of("#FFFFFF", "#000000", "#2C3E50")
 * );
 * results.forEach((hex, result) ->
 *     System.out.printf("%s: %s (%.0f)%n", hex, result.suitability(), result.finalScore())
 * );
 * </pre>
 */
public class BackgroundColorScorer {

    private final DesignAnalyzer analyzer;
    private final BackgroundEvaluator evaluator;

    public BackgroundColorScorer() {
        this(ScoringThresholds.DEFAULT);
    }

    public BackgroundColorScorer(ScoringThresholds thresholds) {
        this.analyzer = new DesignAnalyzer();
        this.evaluator = new BackgroundEvaluator(thresholds);
    }

    /**
     * Full evaluation pipeline.
     *
     * @param imageFile        Path to PNG/JPEG design file
     * @param backgroundColors Hex codes, e.g. ["#FFFFFF", "#000000"]
     * @return Map of hex → evaluation result (preserves input order)
     * @throws IOException If the image cannot be read
     */
    public Map<String, BackgroundEvaluationResult> score(
            Path imageFile, List<String> backgroundColors) throws IOException {

        BufferedImage image = ImageIO.read(imageFile.toFile());
        if (image == null) {
            throw new IOException("Failed to decode image: " + imageFile);
        }
        return score(image, backgroundColors);
    }

    /**
     * Full evaluation pipeline from an in-memory image.
     *
     * @param image            The design image
     * @param backgroundColors Hex codes
     * @return Map of hex → evaluation result (preserves input order)
     */
    public Map<String, BackgroundEvaluationResult> score(
            BufferedImage image, List<String> backgroundColors) {

        DesignAnalysisResult analysis = analyzer.analyze(image);

        Map<String, BackgroundEvaluationResult> results = new LinkedHashMap<>();
        for (String hex : backgroundColors) {
            results.put(hex, evaluator.evaluate(analysis, hex));
        }
        return results;
    }

    /**
     * Analyze a design image without evaluating against any background.
     * Useful for caching the analysis result.
     */
    public DesignAnalysisResult analyzeDesign(BufferedImage image) {
        return analyzer.analyze(image);
    }

    /**
     * Evaluate a pre-analyzed design against a single background color.
     */
    public BackgroundEvaluationResult evaluateBackground(
            DesignAnalysisResult analysis, String hexColor) {
        return evaluator.evaluate(analysis, hexColor);
    }
}
