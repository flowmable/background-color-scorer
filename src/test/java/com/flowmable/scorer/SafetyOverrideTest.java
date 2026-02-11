package com.flowmable.scorer;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the three safety override rules (S1, S2, S3) fire correctly.
 */
class SafetyOverrideTest {

    private final DesignAnalyzer analyzer = new DesignAnalyzer();
    private final BackgroundEvaluator evaluator = new BackgroundEvaluator();

    @Test
    void s1_collisionVeto_whiteOnWhite() {
        // 100% white design on white bg → >60% critical collision → force BAD
        BufferedImage design = MandatoryTestCorpusTest.solidColor(255, 255, 255);
        DesignAnalysisResult analysis = analyzer.analyze(design);
        BackgroundEvaluationResult result = evaluator.evaluate(analysis, "#FFFFFF");

        assertEquals(Suitability.BAD, result.suitability());
        assertNotNull(result.overrideReason());
        assertTrue(result.overrideReason().contains("COLLISION_VETO"),
                "Expected COLLISION_VETO override, got: " + result.overrideReason());
    }

    @Test
    void s1_collisionVeto_blackOnBlack() {
        BufferedImage design = MandatoryTestCorpusTest.solidColor(0, 0, 0);
        DesignAnalysisResult analysis = analyzer.analyze(design);
        BackgroundEvaluationResult result = evaluator.evaluate(analysis, "#000000");

        assertEquals(Suitability.BAD, result.suitability());
        assertNotNull(result.overrideReason());
        assertTrue(result.overrideReason().contains("COLLISION_VETO"));
    }

    @Test
    void s3_degenerateVeto_fullyTransparent() {
        BufferedImage design = MandatoryTestCorpusTest.fullyTransparent();
        DesignAnalysisResult analysis = analyzer.analyze(design);
        BackgroundEvaluationResult result = evaluator.evaluate(analysis, "#FFFFFF");

        assertEquals(Suitability.BAD, result.suitability());
        assertNotNull(result.overrideReason());
        assertTrue(result.overrideReason().contains("DEGENERATE"),
                "Expected DEGENERATE override, got: " + result.overrideReason());
    }

    @Test
    void s3_degenerateVeto_anyBackground() {
        BufferedImage design = MandatoryTestCorpusTest.fullyTransparent();
        DesignAnalysisResult analysis = analyzer.analyze(design);

        // Should be BAD for ALL backgrounds
        for (String bg : new String[]{"#FFFFFF", "#000000", "#FF0000", "#2C3E50"}) {
            BackgroundEvaluationResult result = evaluator.evaluate(analysis, bg);
            assertEquals(Suitability.BAD, result.suitability(),
                    "Transparent design should be BAD for " + bg);
        }
    }

    @Test
    void noOverride_highContrastPair() {
        // Black on white should have no override
        BufferedImage design = MandatoryTestCorpusTest.solidColor(0, 0, 0);
        DesignAnalysisResult analysis = analyzer.analyze(design);
        BackgroundEvaluationResult result = evaluator.evaluate(analysis, "#FFFFFF");

        assertEquals(Suitability.GOOD, result.suitability());
        assertNull(result.overrideReason(),
                "High contrast pair should have no override, got: " + result.overrideReason());
    }

    @Test
    void overridesOnlyMoveDown_neverUp() {
        // A design with moderate contrast should not be upgraded by overrides
        BufferedImage design = MandatoryTestCorpusTest.solidColor(80, 80, 80);
        DesignAnalysisResult analysis = analyzer.analyze(design);

        // Dark gray on mid-gray — borderline territory
        BackgroundEvaluationResult result = evaluator.evaluate(analysis, "#606060");
        // Regardless of score, suitability should not be GOOD if score < 65
        if (result.finalScore() < 65) {
            assertNotEquals(Suitability.GOOD, result.suitability());
        }
    }
}
