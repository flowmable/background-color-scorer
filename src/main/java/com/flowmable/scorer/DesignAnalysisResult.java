package com.flowmable.scorer;

import java.util.List;

/**
 * Immutable result of one-time per-design image analysis.
 * <p>
 * Contains all extracted features needed for per-background-color scoring.
 * This object is cacheable and serializable.
 *
 * @param dominantColors       Dominant colors sorted by weight desc (max 8)
 * @param luminanceHistogram   16-bin normalized histogram (sums to 1.0)
 * @param meanLuminance        Mean relative luminance of foreground [0, 1]
 * @param luminanceSpread      Standard deviation of foreground luminance
 * @param edgeDensity          Fraction of interior foreground pixels that are edges [0, 1]
 * @param transparencyRatio    Fraction of total pixels that are transparent [0, 1]
 * @param nearWhiteRatio       Fraction of foreground pixels that are near-white [0, 1]
 * @param nearBlackRatio       Fraction of foreground pixels that are near-black [0, 1]
 * @param foregroundPixelCount Number of foreground (opaque) pixels
 * @param totalPixelCount      Total pixel count in downsampled image
 */
public record DesignAnalysisResult(
        List<DominantColor> dominantColors,
        double[] luminanceHistogram,
        double meanLuminance,
        double luminanceSpread,
        double edgeDensity,
        double transparencyRatio,
    float[] foregroundPixelsLab, // Flattened [L, a, b, L, a, b...] (max 10k pixels)
    double foregroundMeanL,
    double foregroundP75Chroma,
        double nearWhiteRatio,
        double nearBlackRatio,
        int foregroundPixelCount,
        int totalPixelCount,
        double legibilityLuminanceP25,
        double legibilityLuminanceP50,
        double legibilityLuminanceP75,
        double legibilityAreaRatio,
        double whiteBlackEdgeRatio
) {}
