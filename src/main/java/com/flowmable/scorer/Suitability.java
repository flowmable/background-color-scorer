package com.flowmable.scorer;

/**
 * Three-tier classification for background color suitability.
 */
public enum Suitability {
    /** Score >= 65. Safe to auto-include in product listing. */
    GOOD,
    /** Score 40–64. Show to seller with a warning. */
    BORDERLINE,
    /** Score < 40. Auto-exclude or require explicit override. */
    BAD
}
