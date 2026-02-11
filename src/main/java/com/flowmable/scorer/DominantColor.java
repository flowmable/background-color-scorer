package com.flowmable.scorer;

/**
 * A dominant color extracted from a design image via median-cut quantization.
 *
 * @param r     Red channel (0–255)
 * @param g     Green channel (0–255)
 * @param b     Blue channel (0–255)
 * @param weight Coverage weight: pixel count / total foreground pixels. Range [0, 1].
 * @param labL  CIELAB L* component
 * @param labA  CIELAB a* component
 * @param labB  CIELAB b* component
 */
public record DominantColor(
        int r, int g, int b,
        double weight,
        double labL, double labA, double labB
) {
    /**
     * Create a DominantColor from RGB and weight, auto-computing Lab values.
     */
    public static DominantColor of(int r, int g, int b, double weight) {
        double[] lab = ColorSpaceUtils.srgbToLab(r, g, b);
        return new DominantColor(r, g, b, weight, lab[0], lab[1], lab[2]);
    }
}
