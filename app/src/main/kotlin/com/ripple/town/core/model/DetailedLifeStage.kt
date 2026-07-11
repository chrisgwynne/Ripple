package com.ripple.town.core.model

/**
 * Ten-stage breakdown of a resident's developmental phase. Used by CaregiverSystem and
 * DecisionSystem for fine-grained gating (school, work, caregiving) while the existing
 * four-value [LifeStage] enum remains untouched for all other simulation systems.
 *
 * Bridged from age via [Resident.detailedLifeStageAt].
 */
enum class DetailedLifeStage(val label: String) {
    NEWBORN("Newborn"),          // age 0
    INFANT("Infant"),            // age 1–2
    TODDLER("Toddler"),          // age 3–4
    CHILD("Child"),              // age 5–11
    TEENAGER("Teenager"),        // age 12–17
    YOUNG_ADULT("Young adult"),  // age 18–25
    ADULT("Adult"),              // age 26–39
    MIDDLE_AGE("Middle-aged"),   // age 40–59
    SENIOR("Senior"),            // age 60–74
    ELDERLY("Elderly");          // age 75+

    /** Requires a designated caregiver to be present; cannot be left alone. */
    val needsCaregiver: Boolean
        get() = this == NEWBORN || this == INFANT || this == TODDLER

    /** Attends school on weekday mornings (ages 5–17). */
    val isSchoolAge: Boolean
        get() = this == CHILD || this == TEENAGER

    /** Old enough to hold paid employment.  Note: TEENAGER covers 12–17 but work is only
     *  legal from age 16; DecisionSystem uses [Resident.ageAt] >= 16 as the actual gate. */
    val canWork: Boolean
        get() = ordinal >= TEENAGER.ordinal
}
