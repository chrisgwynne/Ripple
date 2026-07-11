package com.ripple.town.core.model

/**
 * Computed dynamically from a resident's current wealth — never stored directly on
 * [Resident]. Use [SocialClass.of] at read time. The thresholds represent the accumulated
 * lifetime savings that would place someone in each class in the sim's unit economy.
 */
enum class SocialClass(
    val label: String,
    val wealthFloor: Double,
    val shortLabel: String
) {
    DESTITUTE("Destitute", 0.0, "D"),
    WORKING_POOR("Working Poor", 150.0, "WP"),
    WORKING_CLASS("Working Class", 800.0, "WC"),
    LOWER_MIDDLE("Lower Middle Class", 3_000.0, "LM"),
    MIDDLE_CLASS("Middle Class", 10_000.0, "MC"),
    UPPER_MIDDLE("Upper Middle Class", 40_000.0, "UM"),
    WEALTHY("Wealthy", 120_000.0, "W"),
    ELITE("Elite", 400_000.0, "E");

    companion object {
        fun of(wealth: Double): SocialClass =
            values().reversed().firstOrNull { wealth >= it.wealthFloor } ?: DESTITUTE
    }
}
