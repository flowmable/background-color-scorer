# Background Color Scorer - Technical System Document

## 1. PROJECT OVERVIEW

### Problem Statement
Print-on-Demand (POD) sellers often struggle to determine which t-shirt background colors work best for a specific design. A poor choice (e.g., black text on a black shirt, or low-contrast combinations) results in "invisible" designs, customer returns, and wasted inventory. Manual verification of every design against every possible shirt color is time-consuming and error-prone.

### Intended User
*   **POD Platform Developers**: Integrating automated quality checks into upload workflows.
*   **Sellers/Merchants**: Using a CLI tool to get instant recommendations for their designs.

### Core Business Goals
1.  **Eliminate "Invisible" Prints**: Automatically detect and reject low-contrast combinations.
2.  **Automate Decision Making**: Categorize background colors into **BEST**, **ACCEPTABLE**, and **AVOID** tiers without human intervention.
3.  **Human-Aligned Scoring**: Ensure the algorithm's judgment matches human aesthetic and legibility perception (e.g., penalizing "vibrating" colors, rewarding high contrast).

### Inputs
*   **Design Image**: A raster image (PNG/JPG) with transparency (alpha channel). This represents the artwork to be printed.
*   **Candidate Background Colors**: A list of hex color codes representing available t-shirt fabrics (e.g., "#000000" for Black, "#FFFFFF" for White).

### Outputs
*   **Scored Recommendations**: A ranked list of background colors.
*   **Suitability Class**: PROMOTED (BEST), PASSED (ACCEPTABLE), or REJECTED (AVOID) for each color.
*   **Detailed Metrics**: Contrast scores, collision penalties, print risk assessments, and legibility failure reasons.

---

## 2. SYSTEM ARCHITECTURE

The system follows a **Layered Architecture** designed for batch processing and separation of concerns between image analysis (expensive) and scoring (cheap).

### High-Level Architecture
```mermaid
graph TD
    User[User / CLI] -->|Input Image| Driver[ScorerDriver (Orchestrator)]
    Driver -->|1. Analyze (Once)| Analyzer[DesignAnalyzer]
    Analyzer -->|Downsample & Process| Result[DesignAnalysisResult (Record)]
    
    Driver -->|2. Score Loop| Evaluator[BackgroundEvaluator]
    Result -.->|Input| Evaluator
    Config[ScoringThresholds] -.->|Config| Evaluator
    BG[Background Colors List] -.->|Iterate| Evaluator
    
    Evaluator -->|3. Output| EvalResult[BackgroundEvaluationResult]
    Driver -->|4. Rank & Report| Console[Console Output]
```

### Architectural Pattern
*   **Pipeline / Phase-Based**:
    *   **Phase 1 (Analysis)**: computationally expensive, runs once per design image. Independent of background colors.
    *   **Phase 2 (Evaluation)**: computationally cheap, runs N times (once per background color). Pure mathematical function.
    *   **Phase 3 (Recommendation)**: Business logic layer that aggregates scores and applies sorting/tiering.

### Module Breakdown
*   **Analysis Module** (`DesignAnalyzer`, `MedianCut`, `ColorSpaceUtils`): Responsible for understanding the image content (colors, transparency, edges).
*   **Evaluation Module** (`BackgroundEvaluator`, `ScoringThresholds`): Responsible for the physics of color interaction (contrast, vibration, visibility).
*   **Orchestration Module** (`ScorerDriver`, `ValidationDriver`): Responsible for file I/O, looping, and reporting.

### Dependency Flow
`ScorerDriver` -> `BackgroundColorScorer` (Wrapper) -> `DesignAnalyzer` & `BackgroundEvaluator` -> `DesignAnalysisResult` (Data Carrier).
`ColorSpaceUtils` is a shared utility used by both Analysis and Evaluation modules.

---

## 3. TECHNOLOGY STACK

*   **Language**: Java 17+ (Uses Records, sealed classes).
*   **Build System**: Maven (`pom.xml`).
*   **Core Dependencies**: None. Pure Java Standard Library (`java.awt`, `javax.imageio`).
*   **Testing**: JUnit 5 (`junit-jupiter`).
*   **Frameworks**: None (Lightweight, standalone CLI).

---

## 4. EXECUTION FLOW (DETAILED)

### Step 1: Application Startup
*   **Entry Point**: `ScorerDriver.main(String[] args)`.
*   **Initialization**:
    1.  Loads default `ScoringThresholds`.
    2.  Instantiates `BackgroundColorScorer` (a facade for Analyzer and Evaluator).
    3.  Scans for input files:
        *   Checks command-line arguments for file paths.
        *   If none, scans `src/main/resources/designs/` for `.png` or `.jpg` files.

### Step 2: Design Analysis (Phase 1)
For each found design file:
1.  **Image Loading**: `ImageIO.read()` loads the raster image.
2.  **Analysis Call**: `DesignAnalyzer.analyze(image)` is invoked.
3.  **Downsampling**: Image is resized to max 256x256 (preserving aspect ratio) to normalize performance.
4.  **Pixel Iteration**:
    *   Iterates every pixel of the downsampled image.
    *   Builds **Foreground Mask**: Pixels with Alpha >= 128 are treated as "ink".
    *   Collects **Foreground Pixels**: Stores RGB values for quantization.
    *   Computes **Global Stats**: Transparency ratio, Near-White ratio (L*>70, C<30), Near-Black ratio (L*<15, C<30).
5.  **Color Quantization**:
    *   Uses **Median Cut Algorithm** (`MedianCut.quantize`) to reduce thousands of colors to **8 Dominant Colors**.
    *   Each dominant color has a `weight` (frequency in foreground).
6.  **Feature Extraction**:
    *   **Edge Density**: Runs Sobel operator on the 256px image to find internal edges (structural complexity).
    *   **Legibility**: Runs a separate pass on a 1024px version of the image to detect high-frequency details (text) using Sobel gradients and computes P50 luminance of these details.
7.  **Result**: Returns an immutable `DesignAnalysisResult` record containing all extracted data.

### Step 3: Background Evaluation (Phase 2)
The driver iterates through a hardcoded list of 35 standard POD background colors (`BACKGROUND_COLORS` list in `ScorerDriver`).
For each background color:
1.  **Evaluation Call**: `BackgroundEvaluator.evaluate(analysisResult, hexColor)`.
2.  **Color Conversion**: Hex string converted to sRGB and then CIELAB (`ColorSpaceUtils`).
3.  **Scoring Logic** (See "Business Logic" section for formulas):
    *   Calculates **Contrast**, **Collision**, and **Print Risk**.
    *   Computes **Base Score**.
    *   Calculates **Design Resistance** and **Fragility**.
    *   Applies **Penalties** (Conditional Penalty based on fragility).
    *   Applies **Legibility Gate** (Penalty if text contrast < 4.5:1).
    *   Applies **Visual Appeal** (Heuristic bonuses).
4.  **Classification**: Maps final score to `Suitability.GOOD`, `BORDERLINE`, or `BAD` based on thresholds.
5.  **Result**: Returns `BackgroundEvaluationResult`.

### Step 4: Ranking & Reporting (Phase 3)
1.  **Collection**: All `BackgroundEvaluationResult` objects for a design are collected.
2.  **Sorting**: Results are sorted by `finalScore` descending.
3.  **Tiering**:
    *   **BEST**: Top 20% percentile AND Legibility Contrast >= 4.5.
    *   **ACCEPTABLE**: Middle ~50%.
    *   **AVOID**: Bottom 30% OR Legibility Contrast < 3.0.
4.  **Output**: Prints a formatted text report to the console with icons (✅, ⚠️, ❌) and scores.

---

## 5. COMPONENT DEEP DIVE

### `com.flowmable.scorer.ScorerDriver`
*   **Responsibility**: CLI Application entry point. Handles I/O and high-level orchestration.
*   **Key Data**: `BACKGROUND_COLORS` (List<NamedColor>) - The "database" of available shirts.
*   **Logic**: Implements the "Recommendation Layer" (percentile ranking).

### `com.flowmable.scorer.DesignAnalyzer`
*   **Responsibility**: Extract features from the raw image. Pure image processing.
*   **Key Methods**:
    *   `analyze(BufferedImage)`: Main workflow.
    *   `downsample(BufferedImage, int)`: Resizing logic using `Graphics2D`.
    *   `calculateLegibilityMetrics()`: Dedicated pass for high-frequency text detection.
*   **State**: Stateless utility (singleton usage pattern).

### `com.flowmable.scorer.BackgroundEvaluator`
*   **Responsibility**: The "Brain" of the operation. Contains all scoring physics.
*   **Key Methods**:
    *   `evaluate(DesignAnalysisResult, String)`: Runs the full scoring pipeline for one pair.
    *   `calculateVisualAppeal(...)`: Applies subjective aesthetic rules (e.g., "Navy looks good").

### `com.flowmable.scorer.DesignAnalysisResult` (Record)
*   **Responsibility**: Data Transfer Object (DTO) between Phase 1 and Phase 2.
*   **Fields**: `dominantColors`, `luminanceHistogram`, `edgeDensity`, `transparencyRatio`, `legibilityLuminanceP50`, etc.
*   **Immutability**: Guaranteed by Java Record.

### `com.flowmable.scorer.ColorSpaceUtils`
*   **Responsibility**: Math.
*   **Key Methods**:
    *   `srgbToLab(r,g,b)`: Standard conversion D65.
    *   `ciede2000(L1,a1,b1, L2,a2,b2)`: Implementation of the industry-standard color difference formula (Sharma 2005).

### `com.flowmable.scorer.MedianCut`
*   **Responsibility**: Reducing thousands of pixel colors to 8 representative colors.
*   **Logic**: Recursively splits color space buckets along the widest axis (R, G, or B) at the median point.

---

## 6. DATA FLOW

1.  **Raw Bytes** (File) -> `ImageIO` -> **BufferedImage** (Heap).
2.  **BufferedImage** -> `DesignAnalyzer` -> **DesignAnalysisResult** (Record).
    *   *Transformation*: Pixels aggregated into `DominantColor` objects (RGB + Weight).
    *   *Transformation*: Edge detection reduces image to `double edgeDensity`.
3.  **DesignAnalysisResult** + **Hex String** -> `BackgroundEvaluator`.
4.  **Evaluator Internal State**:
    *   `Hex` -> `double[] Lab`.
    *   `DominantColors` -> `double[] DeltaEs`.
5.  **BackgroundEvaluationResult** (Record): Contains final `double` scores and `Suitability` enum.
6.  **Driver**: Reads `finalScore` and `legibilityContrast` -> Console Text.

---

## 7. BUSINESS LOGIC EXPLANATION

### A. Dominant Color Extraction (Median Cut)
The system does not average the entire image. It identifies the top 8 "clusters" of color. This ensures that even small distinct details (like red text on a blue shirt) are preserved as distinct entities if they are statistically significant.

### B. Contrast Scoring (CIEDE2000)
*   **Formula**: `RawContrast = 0.7 * WeightedMeanΔE + 0.3 * MinSignificantΔE`.
*   **Why**: Weighted mean captures the "overall" look. Min significant ΔE ensures that the *least* visible part of the design (e.g., the text) still has enough contrast.
*   **Normalization**: Clamped to 0-100 range.

### C. Design Resistance & Fragility (The Core Innovation)
*   **Concept**: Some designs are "tough" (bold, solid, dark) and work on almost anything. Some are "fragile" (thin, transparent, pastel) and need specific backgrounds.
*   **Design Resistance (`R`)**: Calculated from **Darkness** (55%), **Structure/Edges** (15%), and **Solidity/Alpha** (30%).
*   **Fragility**: `(1 - R)^2.2`. Fragility is non-linear. A slightly weak design is okay, but a very weak design becomes exponentially more fragile.
*   **Conditional Penalty**: `Penalty = BaseScore * Fragility * BackgroundWeakness`.
    *   This logic means: If the design is robust, it doesn't matter if the background is "weak" (low score). If the design is fragile, the background *must* be perfect, otherwise the score is heavily penalized.

### D. Legibility Gate
*   **Detection**: Uses Sobel edge detection to find "high frequency" areas (likely text).
*   **Metric**: Calculates the P50 luminance of these edge pixels.
*   **Check**: Compares Text P50 vs Background Luminance.
*   **Rule**: If Contrast Ratio < 3.0, apply a massive penalty (multiplicative factor 0.55x - 0.80x). This forces the color into the AVOID or borderline tier.

### E. Visual Appeal
*   **Harmony**: +2% score if background hue matches a dominant design hue (Monochromatic look).
*   **Neutral Dark**: +3% for Black/Navy/Charcoal (Sales data shows these sell best).
*   **Risk**: -3% for Near-White backgrounds if the design is also Near-White (looks washed out).

---

## 8. CONFIGURATION

*   **File**: `ScoringThresholds.java` (In-code configuration).
*   **Params**:
    *   `goodFloor (80.0)`: Score needed for BEST.
    *   `borderlineFloor (55.0)`: Score needed for ACCEPTABLE.
    *   `contrastVetoFloor (3.0)`: Minimum deltaE.
*   **Extensibility**: The `BackgroundEvaluator` constructor accepts a `ScoringThresholds` object, allowing runtime injection of different strictness levels (e.g., "Strict Mode" vs "Lenient Mode").

---

## 9. ERROR HANDLING & EDGE CASES

### Degenerate Inputs
*   **No Foreground**: If an image is 100% transparent, `evaluate` returns `Suitability.BAD` with score 0 and reason "DEGENERATE".
*   **Single Pixel**: Handled correctly by Median Cut.

### Handling "Invisible" Designs
*   **White on White**: Caught by Print Risk (White-on-light) and low DeltaE contrast.
*   **Black on Black**: Caught by Print Risk (Black-on-dark) and low DeltaE contrast.

### Legibility Failures
*   If the system detects text but the contrast is too low, it explicitly populates `overrideReason` in the result with "Legibility < 3.0".

---

## 10. PERFORMANCE CONSIDERATIONS

*   **Analysis Phase**: The heaviest part.
    *   **Optimization**: Image is **downsampled** to 256x256 immediately. This bounds the pixel loop to 65,536 iterations regardless of input size (4k, 8k, etc).
    *   **Caching**: `DesignAnalysisResult` is designed to be cached. If running a web server, calculate this once per upload and store it.
*   **Evaluation Phase**: Extremely fast (< 1ms).
    *   Math-only operations on 8 dominant colors.
    *   Can scores 50+ background colors in microseconds.
*   **Concurrency**: `DesignAnalyzer` is stateless and thread-safe. `BackgroundEvaluator` is immutable and thread-safe. Can be parallelized easily.

---

## 11. EXTENSIBILITY DESIGN

*   **Adding New Backgrounds**: Simply add to the `BACKGROUND_COLORS` list in `ScorerDriver` or pass a new list to the evaluator.
*   **Tuning the Algorithm**:
    *   Adjust `ScoringThresholds` for global sensitivity.
    *   Adjust weights in `BackgroundEvaluator` (CONTRAST_WEIGHT, etc.) to prioritize different factors.
*   **New Features**:
    *   To add "Vintage Mode": Subclass `BackgroundEvaluator` and override `calculateVisualAppeal` to reward low-saturation pairings.

---

## 12. KNOWN LIMITATIONS

*   **Gradient Backgrounds**: The system assumes the background is a solid color (single Hex code). Tie-dye or heathered shirts are approximated by a single average color, which might be inaccurate.
*   **Text Detection**: The "High Frequency" detection for legibility is a heuristic (Sobel). It might mistake complex textures (fur, grass) for text and apply legibility penalties unnecessarily.
*   **Hardcoded Colors**: The list of 35 colors is hardcoded in the Driver. In a real app, this should come from a database.

---

## 13. COMPLETE END-TO-END WALKTHROUGH

**Scenario**: User inputs a design `vintage_logo.png` (Cream text #FDF5E6, some transparency).

1.  **Driver** starts. Reads `vintage_logo.png`.
2.  **Analyzer** resizes to 256x256.
    *   Finds it's 80% transparent.
    *   Finds Dominant Color 1: #FDF5E6 (Cream) with weight 0.15.
    *   Detects high edge density (lots of text lines).
    *   `DesignAnalysisResult` created.
3.  **Evaluator** Loop:
    *   **Case 1: White Background (#FFFFFF)**
        *   DeltaE between Cream and White is ~2.0 (Very low).
        *   Contrast Score ~ 10/100.
        *   Print Risk: High (White-on-light).
        *   **Result**: Score 15.0 -> **BAD**.
    *   **Case 2: Black Background (#000000)**
        *   DeltaE between Cream and Black is ~90.0 (High).
        *   Contrast Score ~ 95/100.
        *   Visual Appeal: Bonus for Black (+0.03).
        *   **Result**: Score 98.0 -> **GOOD**.
    *   **Case 3: Pink Background (#FFC0CB)**
        *   DeltaE ~ 25.0 (Moderate).
        *   Contrast Score ~ 50.0.
        *   **Fragility Check**: Design is "text-like" (Fragile). `Fragility` factor is high.
        *   Background Weakness is moderate.
        *   Conditional Penalty triggers: Score drops from 50 -> 35.
        *   **Result**: Score 35.0 -> **BAD/AVOID**.
4.  **Driver** sorts results.
5.  **Output**:
    *   ✅ BEST: Black
    *   ❌ AVOID: White, Pink.

---

## 14. RECONSTRUCTION GUIDE

If you need to rebuild this from scratch:

**Step 1: Set up the Project**
*   Create a Maven project.
*   No external libraries needed for core logic.
*   Copy `ColorSpaceUtils.java` (Open source CIEDE2000 implementations exist, or implement from Sharma 2005 paper).

**Step 2: Implement Analysis (The "Eye")**
*   Create `MedianCut` class to handle color quantization.
*   Create `DesignAnalyzer` to load images, run Median Cut, and calculate stats (transparency, edge density).
*   *Verification*: running it on a clear PNG should return the correct dominant colors.

**Step 3: Implement Scoring (The "Brain")**
*   Create `BackgroundEvaluator`.
*   Implement `srgbToLab` and `ciede2000`.
*   Implement the scoring formula:
    *   `Base = 0.4*Contrast + 0.35*Collision + 0.25*Risk`.
    *   `Penalty = Base * Fragility * Weakness`.
    *   `Final = Base - Penalty`.

**Step 4: Create the Harness**
*   Create `ScorerDriver`.
*   Add the list of standard T-shirt hex codes.
*   Connect the Analyzer output to the Evaluator input.
