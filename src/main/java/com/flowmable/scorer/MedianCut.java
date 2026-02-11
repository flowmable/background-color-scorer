package com.flowmable.scorer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Median-cut color quantization algorithm.
 * <p>
 * Deterministic: identical input always produces identical output.
 * Tie-breaking uses left-child-before-right-child order in the cut tree.
 */
public final class MedianCut {

    private MedianCut() {}

    /**
     * Quantize a list of RGB pixels into k representative dominant colors.
     *
     * @param pixels           List of {r, g, b} arrays (foreground pixels only)
     * @param k                Target number of buckets (must be a power of 2, e.g. 8)
     * @param totalForeground  Total foreground pixel count (for weight calculation)
     * @return List of DominantColor sorted by weight descending
     */
    public static List<DominantColor> quantize(List<int[]> pixels, int k, int totalForeground) {
        if (pixels.isEmpty()) {
            return List.of();
        }

        List<List<int[]>> buckets = new ArrayList<>();
        buckets.add(new ArrayList<>(pixels));

        // Split until we have k buckets (k should be power of 2, so log2(k) splits)
        while (buckets.size() < k) {
            List<List<int[]>> newBuckets = new ArrayList<>();
            for (List<int[]> bucket : buckets) {
                if (bucket.size() <= 1 || newBuckets.size() + (buckets.size() - buckets.indexOf(bucket)) >= k) {
                    // Can't split further or we have enough
                    newBuckets.add(bucket);
                } else {
                    splitBucket(bucket, newBuckets);
                }
            }
            if (newBuckets.size() == buckets.size()) {
                break; // No more splits possible
            }
            buckets = newBuckets;
        }

        // Convert buckets to DominantColors
        List<DominantColor> result = new ArrayList<>();
        for (List<int[]> bucket : buckets) {
            if (bucket.isEmpty()) continue;

            long rSum = 0, gSum = 0, bSum = 0;
            for (int[] px : bucket) {
                rSum += px[0];
                gSum += px[1];
                bSum += px[2];
            }
            int n = bucket.size();
            int avgR = (int) Math.round((double) rSum / n);
            int avgG = (int) Math.round((double) gSum / n);
            int avgB = (int) Math.round((double) bSum / n);
            double weight = (double) n / totalForeground;

            result.add(DominantColor.of(
                    Math.min(255, Math.max(0, avgR)),
                    Math.min(255, Math.max(0, avgG)),
                    Math.min(255, Math.max(0, avgB)),
                    weight
            ));
        }

        // Sort by weight descending (deterministic: strict total order)
        result.sort(Comparator.comparingDouble(DominantColor::weight).reversed());
        return result;
    }

    private static void splitBucket(List<int[]> bucket, List<List<int[]>> output) {
        // Find the channel with the largest range
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        for (int[] px : bucket) {
            if (px[0] < minR) minR = px[0];
            if (px[0] > maxR) maxR = px[0];
            if (px[1] < minG) minG = px[1];
            if (px[1] > maxG) maxG = px[1];
            if (px[2] < minB) minB = px[2];
            if (px[2] > maxB) maxB = px[2];
        }

        int rangeR = maxR - minR;
        int rangeG = maxG - minG;
        int rangeB = maxB - minB;

        // Don't split if all channels have zero range (uniform color)
        if (rangeR == 0 && rangeG == 0 && rangeB == 0) {
            output.add(bucket);
            return;
        }

        // Pick channel with largest range; tie-break: R > G > B
        final int channel;
        if (rangeR >= rangeG && rangeR >= rangeB) {
            channel = 0;
        } else if (rangeG >= rangeB) {
            channel = 1;
        } else {
            channel = 2;
        }

        // Sort by the chosen channel
        bucket.sort(Comparator.comparingInt(px -> px[channel]));

        // Split at median
        int mid = bucket.size() / 2;
        output.add(new ArrayList<>(bucket.subList(0, mid)));
        output.add(new ArrayList<>(bucket.subList(mid, bucket.size())));
    }
}
