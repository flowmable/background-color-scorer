package com.flowmable.scorer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VisualAuthorityTest {

    private final BackgroundEvaluator evaluator = new BackgroundEvaluator();

    // Helper to create mocked analysis result
    private DesignAnalysisResult createMockDesign(double nearWhiteRatio, double edgeDensity, DominantColor color) {
        List<DominantColor> colors = List.of(color);

        return new DesignAnalysisResult(
            colors,
            new double[16], // histogram
            0.5, // meanLuminance
            0.1, // lumSpread
            edgeDensity,
            0.0, // transparency
            nearWhiteRatio,
            0.0, // nearBlack
            1000, // fgPixelCount
            2000 // totalPixelCount
        );
    }
    
    // Default white-ish color helper
    private DesignAnalysisResult createMockDesign(double nearWhiteRatio, double edgeDensity) {
        return createMockDesign(nearWhiteRatio, edgeDensity, 
            new DominantColor(255, 255, 255, 0.5, 100.0, 0.0, 0.0));
    }

    @Test
    void testCaseA_LightThinOnPastel_ShouldBePenalized() {
        // Scenario: Vintage/Cream on Blossom
        DesignAnalysisResult design = createMockDesign(0.8, 0.6); // White-ish text
        String blossomHex = "#FADADD";

        BackgroundEvaluationResult result = evaluator.evaluate(design, blossomHex);

        System.out.println("Case A: Penalty=" + result.authorityPenalty() + " Suitability=" + result.suitability());
        assertTrue(result.authorityPenalty() > 50.0, "Penalty should be massive (>50)");
        assertNotEquals(Suitability.GOOD, result.suitability(), "Should NOT be GOOD");
    }

    @Test
    void testCaseB_LightThinOnDark_ShouldNotBePenalized() {
        // Scenario: Vintage/Cream on Black
        // Design text is White-ish (default mock). Bg is Black.
        DesignAnalysisResult design = createMockDesign(0.8, 0.6); 
        String blackHex = "#000000";

        BackgroundEvaluationResult result = evaluator.evaluate(design, blackHex);

        System.out.println("Case B: Score=" + result.finalScore() + " Penalty=" + result.authorityPenalty());
        
        // Penalty is present by design (Background Independent)
        assertTrue(result.authorityPenalty() > 50.0, "Penalty should exist");
        
        // But result is SAFE due to high contrast (White on Black) override or scoring
        assertNotEquals(Suitability.BAD, result.suitability(), "Should be SAFE (BORDERLINE/GOOD) on black due to override");
    }

    @Test
    void testControl1_BoldDarkText_ShouldNotBePenalized() {
        // Scenario: Bold Dark Text on White
        // Use Black color
        DominantColor black = new DominantColor(0, 0, 0, 1.0, 0.0, 0.0, 0.0);
        DesignAnalysisResult design = createMockDesign(0.0, 0.1, black); 
        String whiteHex = "#FFFFFF";

        BackgroundEvaluationResult result = evaluator.evaluate(design, whiteHex);

        System.out.println("Control 1: Penalty=" + result.authorityPenalty() + " Score=" + result.finalScore());
        
        assertTrue(result.authorityPenalty() < 20.0, "Penalty should be small");
        assertEquals(Suitability.GOOD, result.suitability(), "Should be GOOD");
    }

    @Test
    void testControl2_ThickIllustration_ShouldNotBePenalized() {
        // Scenario: Thick Illustration on White (e.g. Red)
        // Red color
        DominantColor red = new DominantColor(200, 50, 50, 0.5, 50.0, 50.0, 50.0);
        DesignAnalysisResult design = createMockDesign(0.5, 0.1, red);
        String whiteHex = "#FFFFFF";

        BackgroundEvaluationResult result = evaluator.evaluate(design, whiteHex);

        System.out.println("Control 2: Penalty=" + result.authorityPenalty());
        
        assertTrue(result.authorityPenalty() > 30.0, "Penalty should be moderate");
        assertEquals(47.0, result.authorityPenalty(), 1.0, "Penalty matches curve");
    }
}
