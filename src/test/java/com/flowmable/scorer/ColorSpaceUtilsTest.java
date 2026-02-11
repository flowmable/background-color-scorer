package com.flowmable.scorer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates sRGB→Lab conversion and CIEDE2000 against published reference values.
 */
class ColorSpaceUtilsTest {

    @Test
    void srgbToLab_pureWhite() {
        double[] lab = ColorSpaceUtils.srgbToLab(255, 255, 255);
        assertEquals(100.0, lab[0], 0.5);
        assertEquals(0.0, lab[1], 0.5);
        assertEquals(0.0, lab[2], 0.5);
    }

    @Test
    void srgbToLab_pureBlack() {
        double[] lab = ColorSpaceUtils.srgbToLab(0, 0, 0);
        assertEquals(0.0, lab[0], 0.5);
        assertEquals(0.0, lab[1], 0.5);
        assertEquals(0.0, lab[2], 0.5);
    }

    @Test
    void srgbToLab_pureRed() {
        double[] lab = ColorSpaceUtils.srgbToLab(255, 0, 0);
        assertEquals(53.2, lab[0], 1.0); // L*
        assertTrue(lab[1] > 70);          // a* positive (red)
        assertTrue(lab[2] > 50);          // b* positive
    }

    @Test
    void srgbToLab_pureGreen() {
        double[] lab = ColorSpaceUtils.srgbToLab(0, 255, 0);
        assertEquals(87.7, lab[0], 1.0); // L*
        assertTrue(lab[1] < -70);         // a* negative (green)
    }

    @Test
    void srgbToLab_midGray() {
        double[] lab = ColorSpaceUtils.srgbToLab(128, 128, 128);
        assertEquals(53.6, lab[0], 1.0); // L*
        assertEquals(0.0, lab[1], 1.0);   // a* near zero
        assertEquals(0.0, lab[2], 1.0);   // b* near zero
    }

    @Test
    void ciede2000_identical() {
        assertEquals(0.0, ColorSpaceUtils.ciede2000(50, 0, 0, 50, 0, 0), 0.001);
    }

    @Test
    void ciede2000_symmetry() {
        double forward = ColorSpaceUtils.ciede2000(50, 25, -10, 70, -15, 30);
        double reverse = ColorSpaceUtils.ciede2000(70, -15, 30, 50, 25, -10);
        assertEquals(forward, reverse, 0.01);
    }

    // Sharma et al. (2005) reference test pairs
    @Test
    void ciede2000_sharma_pair1() {
        double result = ColorSpaceUtils.ciede2000(50.0, 2.6772, -79.7751, 50.0, 0.0, -82.7485);
        assertEquals(2.0425, result, 0.01);
    }

    @Test
    void ciede2000_sharma_pair17() {
        // Pair 17: (50.0, 2.5, 0.0) vs (73.0, 25.0, -18.0)
        double result = ColorSpaceUtils.ciede2000(50.0, 2.5, 0.0, 73.0, 25.0, -18.0);
        assertTrue(result > 20 && result < 35,
                "Pair with large L* and chromatic difference should have ΔE 20-35, got: " + result);
    }

    @Test
    void ciede2000_blackVsWhite_large() {
        double[] black = ColorSpaceUtils.srgbToLab(0, 0, 0);
        double[] white = ColorSpaceUtils.srgbToLab(255, 255, 255);
        double deltaE = ColorSpaceUtils.ciede2000(
                black[0], black[1], black[2],
                white[0], white[1], white[2]
        );
        assertTrue(deltaE > 90, "Black vs White should have very large ΔE, got: " + deltaE);
    }

    @Test
    void ciede2000_similarColors_small() {
        double[] c1 = ColorSpaceUtils.srgbToLab(100, 100, 100);
        double[] c2 = ColorSpaceUtils.srgbToLab(102, 100, 100);
        double deltaE = ColorSpaceUtils.ciede2000(
                c1[0], c1[1], c1[2],
                c2[0], c2[1], c2[2]
        );
        assertTrue(deltaE < 2, "Very similar grays should have small ΔE, got: " + deltaE);
    }

    @Test
    void relativeLuminance_white() {
        double lum = ColorSpaceUtils.relativeLuminance(255, 255, 255);
        assertEquals(1.0, lum, 0.01);
    }

    @Test
    void relativeLuminance_black() {
        double lum = ColorSpaceUtils.relativeLuminance(0, 0, 0);
        assertEquals(0.0, lum, 0.001);
    }
}
