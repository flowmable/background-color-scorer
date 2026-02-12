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
 * <p>
 * V2 UPDATE:
 * - Computes P75 Chroma on full foreground set.
 * - Implements 5x5 Gaussian Smoothed Sobel for noise-robust sampling.
 * - Implements Proportional Top-K + Stratified Grid Sampling.
 * - Stores flattened Lab pixels for deterministic evaluation.
 */
public class DesignAnalyzer {

    private static final int TARGET_SIZE = 256;
    private static final int DOMINANT_COLOR_COUNT = 8;
    private static final int LUMINANCE_BINS = 16;
    private static final int ALPHA_THRESHOLD = 128;

    // Near-white: L* > 70, Chroma < 30
    private static final double NEAR_WHITE_L_THRESHOLD = 70.0;
    private static final double NEAR_BLACK_L_THRESHOLD = 15.0;
    private static final double CHROMA_THRESHOLD = 30.0;

    private static final int MAX_SAMPLED_PIXELS = 10_000;
    private static final int GRID_ROWS = 10;
    private static final int GRID_COLS = 10;

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
        boolean[] isNearWhite = new boolean[totalPixels];
        boolean[] isNearBlack = new boolean[totalPixels];
        int fgCount = 0;
        int transparentCount = 0;
        int nearWhiteCount = 0;
        int nearBlackCount = 0;
        List<int[]> fgPixelsRgb = new ArrayList<>();
        // Store Lab for all foreground pixels for P75 calculation and sampling
        // Stored as [L, a, b]
        List<float[]> fullFgLab = new ArrayList<>();

        double sumL = 0;

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
                    fgPixelsRgb.add(new int[]{r, g, b});

                    float lum = (float) (0.2126 * r / 255.0 + 0.7152 * g / 255.0 + 0.0722 * b / 255.0);
                    luminance[idx] = lum;

                    double[] labDouble = ColorSpaceUtils.srgbToLab(r, g, b);
                    float[] lab = new float[]{(float) labDouble[0], (float) labDouble[1], (float) labDouble[2]};
                    fullFgLab.add(lab);
                    
                    sumL += lab[0];

                    double chroma = Math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
                    if (lab[0] > NEAR_WHITE_L_THRESHOLD && chroma < CHROMA_THRESHOLD) {
                        nearWhiteCount++;
                        isNearWhite[idx] = true;
                    }
                    if (lab[0] < NEAR_BLACK_L_THRESHOLD && chroma < CHROMA_THRESHOLD) {
                        nearBlackCount++;
                        isNearBlack[idx] = true;
                    }
                } else {
                    transparentCount++;
                    luminance[idx] = 0f; // Zero for transparent in blur/sobel
                }
            }
        }

        // Handle degenerate case: no foreground
        if (fgCount == 0) {
            return new DesignAnalysisResult(
                    List.of(), new double[LUMINANCE_BINS], 0.0, 0.0, 0.0,
                    (double) transparentCount / totalPixels,
                    new float[0], 0.0, 0.0, // Corrected order matches below
                    0.0, 0.0,
                    0, totalPixels, -1.0, -1.0, -1.0, 0.0, 0.0
            );
        }

        double meanLum = sumL / fgCount;

        // Calculate P75 Chroma on FULL Set
        double p75Chroma = calculateP75Chroma(fullFgLab);

        // 3. Dominant colors via median cut
        List<DominantColor> dominantColors = MedianCut.quantize(fgPixelsRgb, DOMINANT_COLOR_COUNT, fgCount);

        // 4. Luminance histogram & Spread
        double[] histogram = new double[LUMINANCE_BINS];
        double lumSqSum = 0;
        double meanRelLum = 0;
        for (int i = 0; i < totalPixels; i++) {
            if (foreground[i]) {
                float lum = luminance[i];
                int bin = Math.min((int) (lum * LUMINANCE_BINS), LUMINANCE_BINS - 1);
                histogram[bin]++;
                lumSqSum += lum * lum;
                meanRelLum += lum;
            }
        }
        for (int i = 0; i < LUMINANCE_BINS; i++) {
            histogram[i] /= fgCount;
        }
        meanRelLum /= fgCount;
        
        double variance = lumSqSum / fgCount - meanRelLum * meanRelLum;
        double lumSpread = variance > 0 ? Math.sqrt(variance) : 0.0;

        // 5. Edge density via simple Sobel (Legacy Metric)
        int edgePixels = 0;
        int whiteBlackEdgeCount = 0;
        int interiorPixels = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = y * w + x;
                if (!foreground[idx]) continue;
                // Simple 8-neighbor check
                boolean allFg = true;
                for (int dy = -1; dy <= 1 && allFg; dy++) {
                    for (int dx = -1; dx <= 1 && allFg; dx++) {
                        if (!foreground[(y + dy) * w + (x + dx)]) allFg = false;
                    }
                }
                if (!allFg) continue;
                interiorPixels++;
                // Standard Sobel
                float gx = luminance[(y - 1) * w + (x + 1)] + 2 * luminance[y * w + (x + 1)] + luminance[(y + 1) * w + (x + 1)]
                        - luminance[(y - 1) * w + (x - 1)] - 2 * luminance[y * w + (x - 1)] - luminance[(y + 1) * w + (x - 1)];
                float gy = luminance[(y + 1) * w + (x - 1)] + 2 * luminance[(y + 1) * w + x] + luminance[(y + 1) * w + (x + 1)]
                        - luminance[(y - 1) * w + (x - 1)] - 2 * luminance[(y - 1) * w + x] - luminance[(y + 1) * w + (x + 1)];
                float grad = (float) Math.sqrt(gx * gx + gy * gy);
                if (grad > 0.1f) {
                    edgePixels++;
                    // Check for White-Black Adjacency in 3x3
                    boolean hasWhite = false;
                    boolean hasBlack = false;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nIdx = (y + dy) * w + (x + dx);
                            if (isNearWhite[nIdx]) hasWhite = true;
                            if (isNearBlack[nIdx]) hasBlack = true;
                        }
                    }
                    if (hasWhite && hasBlack) {
                        whiteBlackEdgeCount++;
                    }
                }
            }
        }
        double edgeDensity = interiorPixels > 0 ? (double) edgePixels / interiorPixels : 0.0;
        double whiteBlackEdgeRatio = edgePixels > 0 ? (double) whiteBlackEdgeCount / edgePixels : 0.0;

        // 6. Legibility Analysis
        LegibilityStats legibility = calculateLegibilityMetrics(original);

        // 7. V2 Sampling Logic
        float[] sampledLab = sampleForeground(luminance, foreground, fullFgLab, w, h, fgCount);

        // 8. Package
        return new DesignAnalysisResult(
                dominantColors,
                histogram,
                meanRelLum, // meanLuminance [0, 1]
                lumSpread,
                edgeDensity,
                (double) transparentCount / totalPixels, // transparencyRatio
                sampledLab, // foregroundPixelsLab
                meanLum,    // foregroundMeanL [0, 100]
                p75Chroma,  // foregroundP75Chroma
                (double) nearWhiteCount / fgCount, // nearWhiteRatio
                (double) nearBlackCount / fgCount, // nearBlackRatio
                fgCount,
                totalPixels,
                legibility.p25,
                legibility.p50,
                legibility.p75,
                legibility.areaRatio,
                whiteBlackEdgeRatio
        );
    }
    
    private double calculateP75Chroma(List<float[]> labPixels) {
        if (labPixels.isEmpty()) return 0.0;
        int n = labPixels.size();
        double[] chromas = new double[n];
        for (int i = 0; i < n; i++) {
            float[] lab = labPixels.get(i);
            chromas[i] = Math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
        }
        // Use Arrays.sort for stability and to avoid StackOverflowError with custom QuickSelect
        java.util.Arrays.sort(chromas);
        int k = (int) (n * 0.75);
        if (k >= n) k = n - 1;
        return chromas[k];
    }

    /**
     * Executes V2 Sampling Strategy:
     * 1. 5x5 Gaussian Smoothing on Luminance
     * 2. Sobel Magnitude Calculation
     * 3. Top-K Edge Pixel Selection (Proportional)
     * 4. Stratified Grid Sampling for remainder
     */
    private float[] sampleForeground(float[] rawLuminance, boolean[] foreground, List<float[]> fullLab, int w, int h, int count) {
        if (count <= MAX_SAMPLED_PIXELS) {
            float[] result = new float[count * 3];
            for (int i = 0; i < count; i++) {
                float[] lab = fullLab.get(i);
                result[i * 3] = lab[0];
                result[i * 3 + 1] = lab[1];
                result[i * 3 + 2] = lab[2];
            }
            return result;
        }

        // 1. Gaussian Blur (5x5)
        float[] blurred = applyGaussian5x5(rawLuminance, w, h);

        // 2. Sobel Magnitude
        float[] sobel = new float[w * h];
        List<Integer> validIndices = new ArrayList<>(count);
        int[] indexToLabIndex = new int[w * h]; 
        java.util.Arrays.fill(indexToLabIndex, -1);
        
        int labIdx = 0;
        for (int i = 0; i < w * h; i++) {
            if (foreground[i]) {
                indexToLabIndex[i] = labIdx++;
                validIndices.add(i);
            }
        }

        for (int i : validIndices) {
            int x = i % w;
            int y = i / w;
            if (x < 2 || x >= w - 2 || y < 2 || y >= h - 2) {
                sobel[i] = 0;
                continue;
            }
            // Standard Sobel on blurred
            float gx = blurred[(y - 1) * w + (x + 1)] + 2 * blurred[y * w + (x + 1)] + blurred[(y + 1) * w + (x + 1)]
                     - blurred[(y - 1) * w + (x - 1)] - 2 * blurred[y * w + (x - 1)] - blurred[(y + 1) * w + (x - 1)];
            float gy = blurred[(y + 1) * w + (x - 1)] + 2 * blurred[(y + 1) * w + x] + blurred[(y + 1) * w + (x + 1)]
                     - blurred[(y - 1) * w + (x - 1)] - 2 * blurred[(y - 1) * w + x] - blurred[(y + 1) * w + (x + 1)];
            sobel[i] = (float) Math.sqrt(gx * gx + gy * gy);
        }

        // 3. Top-K Selection
        int topKCount = Math.min(Math.max((int) (count * 0.02), 100), 500);
        
        validIndices.sort((a, b) -> Float.compare(sobel[b], sobel[a])); // Descending

        boolean[] selected = new boolean[w * h];
        int selectedCount = 0;
        float[] resultBuffer = new float[MAX_SAMPLED_PIXELS * 3];
        
        for (int i = 0; i < topKCount && i < validIndices.size(); i++) {
            int idx = validIndices.get(i);
            selected[idx] = true;
            int lIdx = indexToLabIndex[idx];
            float[] lab = fullLab.get(lIdx);
            resultBuffer[selectedCount * 3] = lab[0];
            resultBuffer[selectedCount * 3 + 1] = lab[1];
            resultBuffer[selectedCount * 3 + 2] = lab[2];
            selectedCount++;
        }

        // 4. Stratified Grid Sampling for Remainder
        int needed = MAX_SAMPLED_PIXELS - selectedCount;
        if (needed > 0) {
            int cellW = Math.max(1, w / GRID_COLS);
            int cellH = Math.max(1, h / GRID_ROWS);
            int totalCells = GRID_ROWS * GRID_COLS; 
            
            List<List<Integer>> gridPixels = new ArrayList<>(totalCells);
            for(int i=0; i<totalCells; i++) gridPixels.add(new ArrayList<>());
            
            for (int idx : validIndices) {
                if (!selected[idx]) {
                    int x = idx % w;
                    int y = idx / w;
                    int cx = Math.min(x / cellW, GRID_COLS - 1);
                    int cy = Math.min(y / cellH, GRID_ROWS - 1);
                    gridPixels.get(cy * GRID_COLS + cx).add(idx);
                }
            }
            
            int totalAvailable = 0;
            for(List<Integer> cell : gridPixels) totalAvailable += cell.size();
            
            int cellIdx = 0;
            while (selectedCount < MAX_SAMPLED_PIXELS && totalAvailable > 0) {
                 List<Integer> cell = gridPixels.get(cellIdx);
                 if (!cell.isEmpty()) {
                     int idx = cell.remove(0); 
                     int lIdx = indexToLabIndex[idx];
                     float[] lab = fullLab.get(lIdx);
                     resultBuffer[selectedCount * 3] = lab[0];
                     resultBuffer[selectedCount * 3 + 1] = lab[1];
                     resultBuffer[selectedCount * 3 + 2] = lab[2];
                     selectedCount++;
                     totalAvailable--;
                 }
                 cellIdx = (cellIdx + 1) % totalCells;
            }
        }

        if (selectedCount < MAX_SAMPLED_PIXELS) {
            float[] trimmed = new float[selectedCount * 3];
            System.arraycopy(resultBuffer, 0, trimmed, 0, selectedCount * 3);
            return trimmed;
        }

        return resultBuffer;
    }

    private float[] applyGaussian5x5(float[] input, int w, int h) {
        // 5x5 Gaussian Kernel (Sum = 256)
        float[] kernel = {
             1,  4,  6,  4,  1,
             4, 16, 24, 16,  4,
             6, 24, 36, 24,  6,
             4, 16, 24, 16,  4,
             1,  4,  6,  4,  1
        };
        
        float[] output = new float[w * h];
        for (int y = 2; y < h - 2; y++) {
            for (int x = 2; x < w - 2; x++) {
                float sum = 0;
                for (int ky = -2; ky <= 2; ky++) {
                    for (int kx = -2; kx <= 2; kx++) {
                        float val = input[(y + ky) * w + (x + kx)];
                        sum += val * kernel[(ky + 2) * 5 + (kx + 2)];
                    }
                }
                output[y * w + x] = sum / 256.0f;
            }
        }
        return output;
    }

    private record LegibilityStats(double p25, double p50, double p75, double areaRatio) {}

    private LegibilityStats calculateLegibilityMetrics(BufferedImage original) {
        BufferedImage img = downsample(original, 1024);
        int w = img.getWidth();
        int h = img.getHeight();
        float[] luminance = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                luminance[y * w + x] = (float) (0.2126 * r / 255.0 + 0.7152 * g / 255.0 + 0.0722 * b / 255.0);
            }
        }

        List<Float> highFreqLuminosity = new ArrayList<>();
        double sumGrad = 0;
        double sumSqGrad = 0;
        int gradCount = 0;
        float[] gradients = new float[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                float gx = luminance[(y - 1) * w + (x + 1)] + 2 * luminance[y * w + (x + 1)] + luminance[(y + 1) * w + (x + 1)]
                        - luminance[(y - 1) * w + (x - 1)] - 2 * luminance[y * w + (x - 1)] - luminance[(y + 1) * w + (x - 1)];
                float gy = luminance[(y + 1) * w + (x - 1)] + 2 * luminance[(y + 1) * w + x] + luminance[(y + 1) * w + (x + 1)]
                        - luminance[(y - 1) * w + (x - 1)] - 2 * luminance[(y - 1) * w + x] - luminance[(y + 1) * w + (x + 1)];
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
        double threshold = Math.max(meanGrad + 2.0 * stdGrad, 0.08);

        for (int i = 0; i < gradients.length; i++) {
            if (gradients[i] > threshold) {
                int x = i % w;
                int y = i / w;
                int alpha = (img.getRGB(x, y) >> 24) & 0xFF;
                if (alpha > 128) {
                   highFreqLuminosity.add(luminance[i]);
                }
            }
        }

        if (highFreqLuminosity.size() < 100 || highFreqLuminosity.size() < (w * h * 0.0001)) {
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
            if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
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
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(src, 0, 0, nw, nh, null);
        g2.dispose();
        return dst;
    }
}
