package com.flowmable.scorer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class DebugAnalyzer {

    // Text Region: x=1600, y=750, w=3200, h=500
    private static final int TEXT_X = 1600;
    private static final int TEXT_Y = 750;
    private static final int TEXT_W = 3200;
    private static final int TEXT_H = 500;

    // Backgrounds
    private static final Map<String, String> BACKGROUNDS = new LinkedHashMap<>();
    static {
        BACKGROUNDS.put("Black", "#000000");
        BACKGROUNDS.put("Pepper", "#5F605B");
        BACKGROUNDS.put("Chambray", "#D9EDF5");
        BACKGROUNDS.put("Blossom", "#F8D1E2");
    }

    public static void main(String[] args) throws Exception {
        Path designPath = Path.of("src/main/resources/designs/design (4).png");
        System.out.println("Processing " + designPath);
        BufferedImage image = ImageIO.read(designPath.toFile());

        // 1. Run Standard Analysis
        System.out.println("\n=== 1. Standard Analysis ===");
        ScoringThresholds thresholds = ScoringThresholds.DEFAULT;
        BackgroundColorScorer scorer = new BackgroundColorScorer(thresholds);
        DesignAnalysisResult analysis = scorer.analyzeDesign(image);
        BackgroundEvaluator evaluator = new BackgroundEvaluator(thresholds);

        // 2. Component/Scale Analysis
        System.out.println("\n=== 2. Scale Analysis ===");
        List<Component> components = findComponents(image);
        Map<String, List<Component>> buckets = new LinkedHashMap<>();
        buckets.put("Small", new ArrayList<>());
        buckets.put("Medium", new ArrayList<>());
        buckets.put("Large", new ArrayList<>());

        for (Component c : components) {
            if (c.pixelCount < 2000) buckets.get("Small").add(c);
            else if (c.pixelCount < 50000) buckets.get("Medium").add(c);
            else buckets.get("Large").add(c);
        }
        
        for (String bucket : buckets.keySet()) {
            int count = buckets.get(bucket).size();
            long pixels = buckets.get(bucket).stream().mapToLong(c -> c.pixelCount).sum();
            System.out.printf("Bucket %s: %d components, %d pixels%n", bucket, count, pixels);
        }


        // 3. Per-Background Analysis
        System.out.println("\n=== 3. Per-Background Detailed Metrics ===");
        
        for (Map.Entry<String, String> entry : BACKGROUNDS.entrySet()) {
            String bgName = entry.getKey();
            String bgHex = entry.getValue();
            System.out.println("\n--------------------------------------------------");
            System.out.printf("BACKGROUND: %s (%s)%n", bgName, bgHex);
            
            // A. Standard Scorer Output
            System.out.println("--- Scorer Output ---");
            BackgroundEvaluationResult result = evaluator.evaluate(analysis, bgHex); // This prints debug info to stdout inside logic!
            System.out.println("Captured Result: " + result);

            // B. Text Local Analysis
            System.out.println("--- Text Local Analysis ---");
            analyzeTextRegion(image, bgHex);

            // C. Scale Contrast Contribution
            System.out.println("--- Scale Contrast Contribution ---");
            analyzeScaleContrast(image, buckets, bgHex);
        }
    }

    private static void analyzeTextRegion(BufferedImage image, String bgHex) {
        int bgRgb = Integer.parseInt(bgHex.replaceFirst("#", ""), 16);
        double[] bgLab = ColorSpaceUtils.srgbToLab((bgRgb >> 16) & 0xFF, (bgRgb >> 8) & 0xFF, bgRgb & 0xFF);
        double bgLum = ColorSpaceUtils.relativeLuminance((bgRgb >> 16) & 0xFF, (bgRgb >> 8) & 0xFF, bgRgb & 0xFF);

        long sumLum = 0; // scaled by 1000
        long count = 0;
        
        // Iterate text box
        for (int y = TEXT_Y; y < TEXT_Y + TEXT_H; y++) {
            for (int x = TEXT_X; x < TEXT_X + TEXT_W; x++) {
                if (x >= image.getWidth() || y >= image.getHeight()) continue;
                int argb = image.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;
                if (alpha > 50) { // Text pixels
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    double lum = ColorSpaceUtils.relativeLuminance(r, g, b);
                    sumLum += (long)(lum * 10000);
                    count++;
                }
            }
        }
        
        if (count == 0) {
            System.out.println("No text pixels found in region!");
            return;
        }

        double avgTextLum = (sumLum / (double)count) / 10000.0;
        
        // WCAG Contrast
        double L1 = Math.max(avgTextLum, bgLum);
        double L2 = Math.min(avgTextLum, bgLum);
        double ratio = (L1 + 0.05) / (L2 + 0.05);
        double deltaLum = Math.abs(avgTextLum - bgLum);

        System.out.printf("Text Avg Lum: %.4f%n", avgTextLum);
        System.out.printf("Bg Lum:       %.4f%n", bgLum);
        System.out.printf("Local Contrast Ratio: %.2f:1%n", ratio);
        System.out.printf("Lum Difference:       %.4f%n", deltaLum);
    }

    private static void analyzeScaleContrast(BufferedImage image, Map<String, List<Component>> buckets, String bgHex) {
        int bgRgb = Integer.parseInt(bgHex.replaceFirst("#", ""), 16);
        double[] bgLab = ColorSpaceUtils.srgbToLab((bgRgb >> 16) & 0xFF, (bgRgb >> 8) & 0xFF, bgRgb & 0xFF);

        double totalWeightedDeltaE = 0;
        Map<String, Double> bucketContribution = new LinkedHashMap<>();

        // We estimate contrast score contribution by summing DeltaE of pixels
        // This is an approximation of the actual dominant color clustering logic, 
        // effectively treating every pixel as a dominant color of weight 1/total.
        
        for (String key : buckets.keySet()) {
            double sumDeltaE = 0;
            long pixelCount = 0;
            
            for (Component c : buckets.get(key)) {
                 // For speed, sample center pixel of component or average? 
                 // Better: iterate pixels of component.
                 // To save complexity, I'll validly sample 5 points per component
                 List<Point> samples = c.getSamples(5);
                 for (Point p : samples) {
                     int argb = image.getRGB(p.x, p.y);
                     int r = (argb >> 16) & 0xFF;
                     int g = (argb >> 8) & 0xFF;
                     int b = argb & 0xFF;
                     double[] lab = ColorSpaceUtils.srgbToLab(r, g, b);
                     double de = ColorSpaceUtils.ciede2000(lab[0], lab[1], lab[2], bgLab[0], bgLab[1], bgLab[2]);
                     sumDeltaE += de;
                 }
                 pixelCount += samples.size();
            }
            
            double avgDeltaE = (pixelCount > 0) ? sumDeltaE / pixelCount : 0;
            bucketContribution.put(key, avgDeltaE);
            System.out.printf("Bucket %s: Avg DeltaE = %.2f%n", key, avgDeltaE);
        }
    }


    // --- Component Logic Helper ---
    static class Point { int x, y; Point(int x, int y) { this.x = x; this.y = y; } }
    static class Component {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int pixelCount = 0;
        List<Point> pixels = new ArrayList<>(); // Store pixels for sampling
        
        void add(int x, int y) {
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            pixelCount++;
            if (pixelCount % 100 == 0 || pixels.size() < 10) pixels.add(new Point(x,y)); // Sparse storage
        }
        
        List<Point> getSamples(int n) {
             if (pixels.size() <= n) return pixels;
             // return random or even spread? just return first n
             return pixels.subList(0, n);
        }
    }

    private static List<Component> findComponents(BufferedImage image) {
        // Simplified BFS
        int w = image.getWidth();
        int h = image.getHeight();
        boolean[][] visited = new boolean[w][h];
        List<Component> components = new ArrayList<>();
        // Only scan a grid to save time? No, need all.
        // We'll downsample scan for speed? No.
        
        for (int y = 0; y < h; y+=2) { // Skip lines for speed
            for (int x = 0; x < w; x+=2) {
                if (visited[x][y]) continue;
                int alpha = (image.getRGB(x, y) >> 24) & 0xFF;
                if (alpha < 50) continue; 

                Component comp = new Component();
                Queue<Point> q = new ArrayDeque<>();
                q.add(new Point(x, y));
                visited[x][y] = true;
                comp.add(x, y);

                int processed = 0;
                while (!q.isEmpty()) {
                    Point p = q.poll();
                    int[] dx = {2, -2, 0, 0};
                    int[] dy = {0, 0, 2, -2};
                    for (int i=0; i<4; i++) {
                        int nx = p.x + dx[i];
                        int ny = p.y + dy[i];
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && !visited[nx][ny]) {
                             if (((image.getRGB(nx, ny) >> 24) & 0xFF) > 50) {
                                 visited[nx][ny] = true;
                                 q.add(new Point(nx, ny));
                                 comp.add(nx, ny);
                             }
                        }
                    }
                }
                if (comp.pixelCount > 50) components.add(comp);
            }
        }
        return components;
    }
}
