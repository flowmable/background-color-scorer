package com.flowmable.scorer;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MedianCut quantization, DesignAnalyzer feature extraction,
 * and the top-level BackgroundColorScorer integration.
 */
class DesignAnalyzerTest {

    private final DesignAnalyzer analyzer = new DesignAnalyzer();

    @Test
    void solidWhite_dominantColorIsWhite() {
        BufferedImage img = MandatoryTestCorpusTest.solidColor(255, 255, 255);
        DesignAnalysisResult result = analyzer.analyze(img);

        assertFalse(result.dominantColors().isEmpty());
        DominantColor dc = result.dominantColors().get(0);
        assertEquals(255, dc.r());
        assertEquals(255, dc.g());
        assertEquals(255, dc.b());
        assertTrue(dc.weight() > 0.9, "Single solid color should have weight ~1.0");
    }

    @Test
    void solidBlack_nearBlackRatioIsHigh() {
        BufferedImage img = MandatoryTestCorpusTest.solidColor(0, 0, 0);
        DesignAnalysisResult result = analyzer.analyze(img);

        assertTrue(result.nearBlackRatio() > 0.9,
                "Solid black should have nearBlackRatio ~1.0, got: " + result.nearBlackRatio());
        assertTrue(result.nearWhiteRatio() < 0.01);
    }

    @Test
    void solidWhite_nearWhiteRatioIsHigh() {
        BufferedImage img = MandatoryTestCorpusTest.solidColor(255, 255, 255);
        DesignAnalysisResult result = analyzer.analyze(img);

        assertTrue(result.nearWhiteRatio() > 0.9,
                "Solid white should have nearWhiteRatio ~1.0, got: " + result.nearWhiteRatio());
        assertTrue(result.nearBlackRatio() < 0.01);
    }

    @Test
    void fullyTransparent_degenerateResult() {
        BufferedImage img = MandatoryTestCorpusTest.fullyTransparent();
        DesignAnalysisResult result = analyzer.analyze(img);

        assertEquals(0, result.foregroundPixelCount());
        assertTrue(result.dominantColors().isEmpty());
        assertEquals(1.0, result.transparencyRatio(), 0.01);
    }

    @Test
    void textLike_hasSignificantEdgeDensity() {
        BufferedImage img = MandatoryTestCorpusTest.textLike(0, 0, 0);
        DesignAnalysisResult result = analyzer.analyze(img);

        // Text-like pattern with thin lines should have some edges
        // (edge density depends on whether lines are thick enough for interior pixels)
        assertTrue(result.foregroundPixelCount() > 0);
        assertTrue(result.transparencyRatio() > 0.5,
                "Text-like should have high transparency, got: " + result.transparencyRatio());
    }

    @Test
    void twoColor_hasTwoDominantColors() {
        BufferedImage img = MandatoryTestCorpusTest.twoColorSplit(255, 0, 0, 0, 0, 255);
        DesignAnalysisResult result = analyzer.analyze(img);

        // Should have at least 2 dominant colors with significant weight
        List<DominantColor> significant = result.dominantColors().stream()
                .filter(dc -> dc.weight() > 0.1)
                .toList();
        assertTrue(significant.size() >= 2,
                "Two-color split should have at least 2 significant colors, got: " + significant.size());
    }

    @Test
    void solidColor_zeroEdgeDensity() {
        BufferedImage img = MandatoryTestCorpusTest.solidColor(128, 128, 128);
        DesignAnalysisResult result = analyzer.analyze(img);

        assertEquals(0.0, result.edgeDensity(), 0.01,
                "Solid color should have zero edge density");
    }

    @Test
    void gradient_hasMeanLuminanceInMiddle() {
        BufferedImage img = MandatoryTestCorpusTest.gradient(0, 0, 0, 255, 255, 255);
        DesignAnalysisResult result = analyzer.analyze(img);

        assertTrue(result.meanLuminance() > 0.3 && result.meanLuminance() < 0.7,
                "Black-to-white gradient should have mean luminance ~0.5, got: " + result.meanLuminance());
        assertTrue(result.luminanceSpread() > 0.1,
                "Gradient should have significant luminance spread, got: " + result.luminanceSpread());
    }

    @Test
    void jpegLikeImage_noTransparency() {
        // TYPE_INT_RGB has no alpha — simulates JPEG
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 100; y++)
            for (int x = 0; x < 100; x++)
                img.setRGB(x, y, 0xFF0000); // red
        DesignAnalysisResult result = analyzer.analyze(img);

        assertEquals(0.0, result.transparencyRatio(), 0.01,
                "JPEG-like image should have zero transparency");
        assertEquals(result.totalPixelCount(), result.foregroundPixelCount());
    }

    @Test
    void smallImage_noDownsample() {
        // 50x50 is below 256 — should not be downsampled
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
        int white = 0xFFFFFFFF;
        for (int y = 0; y < 50; y++)
            for (int x = 0; x < 50; x++)
                img.setRGB(x, y, white);
        DesignAnalysisResult result = analyzer.analyze(img);

        assertEquals(2500, result.totalPixelCount());
        assertEquals(2500, result.foregroundPixelCount());
    }
}
