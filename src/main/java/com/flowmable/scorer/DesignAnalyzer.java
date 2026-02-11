package com.flowmable.scorer;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1: One-time per-design image analysis.
 * <p>
 * Downsamples the image to max 256×256, extracts foreground mask,
 * computes dominant colors, luminance histogram, edge density,
 * transparency ratio, and near-white/near-black ratios.
 */
public class DesignAnalyzer {

    private static final int TARGET_SIZE = 256;
    private static final int DOMINANT_COLOR_COUNT = 8;
    private static final int LUMINANCE_BINS = 16;
    private static final int ALPHA_THRESHOLD = 128;

    // Near-white: L* > 70 (was 90, then 80) to capture Tan (L=75) and Wheat (L=89)
    // Chroma < 30 (was 10, then 18) to capture Cream (C=22), Tan (C=24), Pink (C=28)
    private static final double NEAR_WHITE_L_THRESHOLD = 70.0;
    // Near-black: L* < 15, chroma < 18
    private static final double NEAR_BLACK_L_THRESHOLD = 15.0;
    private static final double CHROMA_THRESHOLD = 30.0;

    /**
     * Analyze a design image and extract all scoring features.
     *
     * @param original The design image (PNG with alpha or JPEG)
     * @return Immutable analysis result containing all features
     */
    public DesignAnalysisResult analyze(BufferedImage original) {
        // 1. Downsample
        BufferedImage img = downsample(original, TARGET_SIZE);
        int w = img.getWidth();
        int h = img.getHeight();
        int totalPixels = w * h;

        // 2. Extract channels + build foreground mask
        boolean[] foreground = new boolean[totalPixels];
        float[] luminance = new float[totalPixels];
        int fgCount = 0;
        int transparentCount = 0;
        int nearWhiteCount = 0;
        int nearBlackCount = 0;
        List<int[]> fgPixels = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int argb = img.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                if (a >= ALPHA_THRESHOLD) {
                    foreground[idx] = true;
                    fgCount++;
                    fgPixels.add(new int[]{r, g, b});

                    float lum = (float) (0.2126 * r / 255.0 + 0.7152 * g / 255.0 + 0.0722 * b / 255.0);
                    luminance[idx] = lum;

                    // CIELAB for white/black detection
                    double[] lab = ColorSpaceUtils.srgbToLab(r, g, b);
                    double chroma = Math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
                    if (lab[0] > NEAR_WHITE_L_THRESHOLD && chroma < CHROMA_THRESHOLD) {
                        nearWhiteCount++;
                    }
                    if (lab[0] < NEAR_BLACK_L_THRESHOLD && chroma < CHROMA_THRESHOLD) {
                        nearBlackCount++;
                    }
                } else {
                    transparentCount++;
                    luminance[idx] = -1f; // sentinel for transparent
                }
            }
        }

        // Handle degenerate case: no foreground
        if (fgCount == 0) {
            return new DesignAnalysisResult(
                    List.of(),
                    new double[LUMINANCE_BINS],
                    0.0, 0.0, 0.0,
                    (double) transparentCount / totalPixels,
                    0.0, 0.0,
                    0, totalPixels,
                    -1.0, -1.0, -1.0, 0.0
            );
        }

        // 3. Dominant colors via median cut
        List<DominantColor> dominantColors = MedianCut.quantize(fgPixels, DOMINANT_COLOR_COUNT, fgCount);

        // 4. Luminance histogram
        double[] histogram = new double[LUMINANCE_BINS];
        double lumSum = 0;
        double lumSqSum = 0;
        for (int i = 0; i < totalPixels; i++) {
            if (foreground[i]) {
                float lum = luminance[i];
                int bin = Math.min((int) (lum * LUMINANCE_BINS), LUMINANCE_BINS - 1);
                histogram[bin]++;
                lumSum += lum;
                lumSqSum += lum * lum;
            }
        }
        for (int i = 0; i < LUMINANCE_BINS; i++) {
            histogram[i] /= fgCount;
        }
        double meanLum = lumSum / fgCount;
        double variance = lumSqSum / fgCount - meanLum * meanLum;
        double lumSpread = variance > 0 ? Math.sqrt(variance) : 0.0;

        // 5. Edge density via Sobel
        int edgePixels = 0;
        int interiorPixels = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = y * w + x;
                if (!foreground[idx]) continue;

                // Check all 8-neighbors are foreground
                boolean allFg = true;
                for (int dy = -1; dy <= 1 && allFg; dy++) {
                    for (int dx = -1; dx <= 1 && allFg; dx++) {
                        if (!foreground[(y + dy) * w + (x + dx)]) {
                            allFg = false;
                        }
                    }
                }
                if (!allFg) continue;

                interiorPixels++;

                // Sobel gradient
                float gx = luminance[(y - 1) * w + (x + 1)] + 2 * luminance[y * w + (x + 1)] + luminance[(y + 1) * w + (x + 1)]
                          - luminance[(y - 1) * w + (x - 1)] - 2 * luminance[y * w + (x - 1)] - luminance[(y + 1) * w + (x - 1)];
                float gy = luminance[(y + 1) * w + (x - 1)] + 2 * luminance[(y + 1) * w + x] + luminance[(y + 1) * w + (x + 1)]
                          - luminance[(y - 1) * w + (x - 1)] - 2 * luminance[(y - 1) * w + x] - luminance[(y - 1) * w + (x + 1)];
                float grad = (float) Math.sqrt(gx * gx + gy * gy);
                if (grad > 0.1f) {
                    edgePixels++;
                }
            }
        }
        double edgeDensity = interiorPixels > 0 ? (double) edgePixels / interiorPixels : 0.0;

        // 6. Legibility Analysis (High Frequency Detection)
        LegibilityStats legibility = calculateLegibilityMetrics(original);

        // 7. Package
        return new DesignAnalysisResult(
                dominantColors,
                histogram,
                meanLum,
                lumSpread,
                edgeDensity,
                (double) transparentCount / totalPixels,
                (double) nearWhiteCount / fgCount,
                (double) nearBlackCount / fgCount,
                fgCount,
                totalPixels,
                legibility.p25,
                legibility.p50,
                legibility.p75,
                legibility.areaRatio
        );
    }

    private record LegibilityStats(double p25, double p50, double p75, double areaRatio) {}

    private LegibilityStats calculateLegibilityMetrics(BufferedImage original) {
        // 1. Downsample to max 1024px for performance
        BufferedImage img = downsample(original, 1024);
        int w = img.getWidth();
        int h = img.getHeight();

        // 2. Compute Luminance & Gradient Magnitude
        float[] luminance = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                // Simple relative luminance
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                luminance[y * w + x] = (float) (0.2126 * r / 255.0 + 0.7152 * g / 255.0 + 0.0722 * b / 255.0);
            }
        }

        // 3. Sobel Edge Detection to find high-frequency pixels
        List<Float> highFreqLuminosity = new ArrayList<>();
        double sumGrad = 0;
        double sumSqGrad = 0;
        int gradCount = 0;

        // First pass: stats for dynamic threshold
        float[] gradients = new float[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                float gx = luminance[(y - 1) * w + (x + 1)] + 2 * luminance[y * w + (x + 1)] + luminance[(y + 1) * w + (x + 1)]
                        - luminance[(y - 1) * w + (x - 1)] - 2 * luminance[y * w + (x - 1)] - luminance[(y + 1) * w + (x - 1)];
                float gy = luminance[(y + 1) * w + (x - 1)] + 2 * luminance[(y + 1) * w + x] + luminance[(y + 1) * w + (x + 1)]
                        - luminance[(y - 1) * w + (x - 1)] - 2 * luminance[(y - 1) * w + x] - luminance[(y - 1) * w + (x + 1)];
                float grad = (float) Math.sqrt(gx * gx + gy * gy);
                gradients[y * w + x] = grad;

                sumGrad += grad;
                sumSqGrad += grad * grad;
                gradCount++;
            }
        }

        if (gradCount == 0) return new LegibilityStats(-1, -1, -1, 0);

        double meanGrad = sumGrad / gradCount;
        double stdGrad = Math.sqrt((sumSqGrad / gradCount) - (meanGrad * meanGrad));
        double threshold = Math.max(meanGrad + 2.0 * stdGrad, 2.0); // Threshold (relative to 0-1 range? Sobel output can be > 1.0)
        // Wait, luminance is 0-1. Sobel on 0-1.
        // gx, gy max is approx 4.0. grad max approx 5.6.
        // Let's use a reasonable absolute floor to avoid noise in flat images. 
        // 20/255 ~= 0.08. Let's say 0.15 is a safe floor for "real edge".
        
        // Revised Threshold Logic from plan:
        // "Dynamic threshold: max(Mean + 2*StdDev, 20.0)" -> 20.0 likely referred to 0-255 scale.
        // On 0.0-1.0 scale, 20.0/255.0 ~= 0.08. 
        threshold = Math.max(meanGrad + 2.0 * stdGrad, 0.08);

        for (int i = 0; i < gradients.length; i++) {
            if (gradients[i] > threshold) {
                // Ensure pixel is opaque enough to matter?
                // We're iterating the downsampled image which might have alpha.
                int x = i % w;
                int y = i / w;
                int alpha = (img.getRGB(x, y) >> 24) & 0xFF;
                if (alpha > 128) {
                   highFreqLuminosity.add(luminance[i]);
                }
            }
        }

        // 4. Compute Statistics
        if (highFreqLuminosity.size() < 100 || highFreqLuminosity.size() < (w * h * 0.0001)) {
             // Too few pixels to be robust text
             return new LegibilityStats(-1, -1, -1, 0);
        }

        highFreqLuminosity.sort(Float::compare);
        double p25 = highFreqLuminosity.get((int) (highFreqLuminosity.size() * 0.25));
        double p50 = highFreqLuminosity.get((int) (highFreqLuminosity.size() * 0.50));
        double p75 = highFreqLuminosity.get((int) (highFreqLuminosity.size() * 0.75));
        double areaRatio = (double) highFreqLuminosity.size() / (w * h);

        return new LegibilityStats(p25, p50, p75, areaRatio);
    }

    private BufferedImage downsample(BufferedImage src, int targetSize) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min((double) targetSize / w, (double) targetSize / h);
        if (scale >= 1.0) {
            // Ensure we have ARGB type for consistent alpha handling
            if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
                return src;
            }
            BufferedImage converted = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = converted.createGraphics();
            g2.drawImage(src, 0, 0, null);
            g2.dispose();
            return converted;
        }
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = dst.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(src, 0, 0, nw, nh, null);
        g2.dispose();
        return dst;
    }
}
