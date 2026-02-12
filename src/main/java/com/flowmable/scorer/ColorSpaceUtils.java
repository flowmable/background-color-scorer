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
     * Calculates CIEDE2000 color difference between two Lab colors (float precision).
     */
    public static float ciede2000Float(float L1, float a1, float b1, float L2, float a2, float b2) {
        return (float) ciede2000(L1, a1, b1, L2, a2, b2);
    }

    /**
     * Calculates standard CIEDE2000 color difference.
     * Reference implementation: http://www.ece.rochester.edu/~gsharma/ciede2000/
     */
    public static double ciede2000(double L1, double a1, double b1, double L2, double a2, double b2) {
        double C1 = Math.sqrt(a1 * a1 + b1 * b1);
        double C2 = Math.sqrt(a2 * a2 + b2 * b2);
        double C_bar = (C1 + C2) / 2.0;

        double G = 0.5 * (1.0 - Math.sqrt(Math.pow(C_bar, 7) / (Math.pow(C_bar, 7) + 6103515625.0))); // 25^7

        double a1_prime = (1.0 + G) * a1;
        double a2_prime = (1.0 + G) * a2;

        double C1_prime = Math.sqrt(a1_prime * a1_prime + b1 * b1);
        double C2_prime = Math.sqrt(a2_prime * a2_prime + b2 * b2);

        double h1_prime = (b1 == 0 && a1_prime == 0) ? 0.0 : Math.toDegrees(Math.atan2(b1, a1_prime));
        if (h1_prime < 0) h1_prime += 360.0;

        double h2_prime = (b2 == 0 && a2_prime == 0) ? 0.0 : Math.toDegrees(Math.atan2(b2, a2_prime));
        if (h2_prime < 0) h2_prime += 360.0;

        double dL_prime = L2 - L1;
        double dC_prime = C2_prime - C1_prime;
        
        double dh_prime = 0.0;
        if (C1_prime * C2_prime != 0) {
            double diff = h2_prime - h1_prime;
            if (Math.abs(diff) <= 180) {
                dh_prime = diff;
            } else if (diff > 180) {
                dh_prime = diff - 360;
            } else {
                dh_prime = diff + 360;
            }
        }
        
        double dH_prime = 2.0 * Math.sqrt(C1_prime * C2_prime) * Math.sin(Math.toRadians(dh_prime / 2.0));

        double L_bar_prime = (L1 + L2) / 2.0; 
        double C_bar_prime = (C1_prime + C2_prime) / 2.0;
        
        double h_bar_prime = h1_prime + h2_prime;
        if (C1_prime * C2_prime != 0) {
            double diff = Math.abs(h1_prime - h2_prime);
            if (diff > 180) {
                if ((h1_prime + h2_prime) < 360) h_bar_prime += 360;
                else h_bar_prime -= 360;
            }
            h_bar_prime /= 2.0;
        } else {
            h_bar_prime = (h1_prime + h2_prime); 
        }
        
        double T = 1.0 - 0.17 * Math.cos(Math.toRadians(h_bar_prime - 30))
                       + 0.24 * Math.cos(Math.toRadians(2 * h_bar_prime))
                       + 0.32 * Math.cos(Math.toRadians(3 * h_bar_prime + 6))
                       - 0.20 * Math.cos(Math.toRadians(4 * h_bar_prime - 63));
                       
        double dTheta = 30 * Math.exp(-Math.pow((h_bar_prime - 275) / 25, 2));
        double RC = 2.0 * Math.sqrt(Math.pow(C_bar_prime, 7) / (Math.pow(C_bar_prime, 7) + 6103515625.0));
        double SL = 1.0 + (0.015 * Math.pow(L_bar_prime - 50, 2)) / Math.sqrt(20 + Math.pow(L_bar_prime - 50, 2));
        double SC = 1.0 + 0.045 * C_bar_prime;
        double SH = 1.0 + 0.015 * C_bar_prime * T;
        double RT = -Math.sin(Math.toRadians(2 * dTheta)) * RC;
        
        double kL = 1.0;
        double kC = 1.0;
        double kH = 1.0;
        
        double term1 = dL_prime / (kL * SL);
        double term2 = dC_prime / (kC * SC);
        double term3 = dH_prime / (kH * SH);
        
        return Math.sqrt(term1*term1 + term2*term2 + term3*term3 + RT * term2 * term3);
    }
}
