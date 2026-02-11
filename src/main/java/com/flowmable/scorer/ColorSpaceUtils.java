package com.flowmable.scorer;

/**
 * Color space conversion utilities and perceptual distance metrics.
 * <p>
 * Provides sRGB → CIELAB conversion (D65 illuminant) and full CIEDE2000
 * color difference computation per Sharma, Wu, Dalal (2005).
 */
public final class ColorSpaceUtils {

    private ColorSpaceUtils() {}

    // D65 white point
    private static final double XN = 0.95047;
    private static final double YN = 1.00000;
    private static final double ZN = 1.08883;

    /**
     * Convert sRGB (0–255 per channel) to CIELAB [L*, a*, b*].
     */
    public static double[] srgbToLab(int r, int g, int b) {
        // 1. sRGB → linear RGB
        double rl = gammaExpand(r / 255.0);
        double gl = gammaExpand(g / 255.0);
        double bl = gammaExpand(b / 255.0);

        // 2. Linear RGB → XYZ (D65 illuminant)
        double x = 0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl;
        double y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl;
        double z = 0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl;

        // 3. XYZ → Lab
        double fx = labF(x / XN);
        double fy = labF(y / YN);
        double fz = labF(z / ZN);

        double L = 116.0 * fy - 16.0;
        double a = 500.0 * (fx - fy);
        double bStar = 200.0 * (fy - fz);
        return new double[]{L, a, bStar};
    }

    /**
     * Compute relative luminance from sRGB (0–255).
     * Returns value in [0.0, 1.0].
     */
    public static double relativeLuminance(int r, int g, int b) {
        return 0.2126 * gammaExpand(r / 255.0)
             + 0.7152 * gammaExpand(g / 255.0)
             + 0.0722 * gammaExpand(b / 255.0);
    }

    private static double gammaExpand(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        return t > 0.008856 ? Math.cbrt(t) : (903.3 * t + 16.0) / 116.0;
    }

    /**
     * CIEDE2000 color difference.
     * Full implementation per Sharma, Wu, Dalal (2005).
     *
     * @return ΔE₀₀ ≥ 0
     */
    public static double ciede2000(double L1, double a1, double b1,
                                   double L2, double a2, double b2) {
        double Lb = (L1 + L2) / 2.0;
        double C1 = Math.sqrt(a1 * a1 + b1 * b1);
        double C2 = Math.sqrt(a2 * a2 + b2 * b2);
        double Cb = (C1 + C2) / 2.0;

        double Cb7 = Math.pow(Cb, 7);
        double G = 0.5 * (1.0 - Math.sqrt(Cb7 / (Cb7 + Math.pow(25.0, 7))));

        double a1p = a1 * (1.0 + G);
        double a2p = a2 * (1.0 + G);

        double C1p = Math.sqrt(a1p * a1p + b1 * b1);
        double C2p = Math.sqrt(a2p * a2p + b2 * b2);
        double Cbp = (C1p + C2p) / 2.0;
        double dCp = C2p - C1p;

        double h1p = hueAngle(b1, a1p);
        double h2p = hueAngle(b2, a2p);

        double dhp;
        if (C1p == 0 || C2p == 0) {
            dhp = 0;
        } else if (Math.abs(h2p - h1p) <= Math.PI) {
            dhp = h2p - h1p;
        } else if (h2p - h1p > Math.PI) {
            dhp = h2p - h1p - 2.0 * Math.PI;
        } else {
            dhp = h2p - h1p + 2.0 * Math.PI;
        }

        double dHp = 2.0 * Math.sqrt(C1p * C2p) * Math.sin(dhp / 2.0);

        double Hbp;
        if (C1p == 0 || C2p == 0) {
            Hbp = h1p + h2p;
        } else if (Math.abs(h1p - h2p) <= Math.PI) {
            Hbp = (h1p + h2p) / 2.0;
        } else if (h1p + h2p < 2.0 * Math.PI) {
            Hbp = (h1p + h2p + 2.0 * Math.PI) / 2.0;
        } else {
            Hbp = (h1p + h2p - 2.0 * Math.PI) / 2.0;
        }

        double T = 1.0
                - 0.17 * Math.cos(Hbp - Math.toRadians(30))
                + 0.24 * Math.cos(2.0 * Hbp)
                + 0.32 * Math.cos(3.0 * Hbp + Math.toRadians(6))
                - 0.20 * Math.cos(4.0 * Hbp - Math.toRadians(63));

        double Lb50sq = (Lb - 50.0) * (Lb - 50.0);
        double SL = 1.0 + 0.015 * Lb50sq / Math.sqrt(20.0 + Lb50sq);
        double SC = 1.0 + 0.045 * Cbp;
        double SH = 1.0 + 0.015 * Cbp * T;

        double dTheta = Math.toRadians(30)
                * Math.exp(-((Hbp - Math.toRadians(275)) / Math.toRadians(25))
                * ((Hbp - Math.toRadians(275)) / Math.toRadians(25)));

        double Cbp7 = Math.pow(Cbp, 7);
        double RC = 2.0 * Math.sqrt(Cbp7 / (Cbp7 + Math.pow(25.0, 7)));
        double RT = -Math.sin(2.0 * dTheta) * RC;

        double dL = L2 - L1;
        double kL = 1.0, kC = 1.0, kH = 1.0;

        double lTerm = dL / (kL * SL);
        double cTerm = dCp / (kC * SC);
        double hTerm = dHp / (kH * SH);

        return Math.sqrt(
                lTerm * lTerm
              + cTerm * cTerm
              + hTerm * hTerm
              + RT * cTerm * hTerm
        );
    }

    private static double hueAngle(double b, double ap) {
        double h = Math.atan2(b, ap);
        if (h < 0) h += 2.0 * Math.PI;
        return h;
    }
}
