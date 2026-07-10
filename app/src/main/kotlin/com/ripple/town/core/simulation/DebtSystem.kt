package com.ripple.town.core.simulation

import com.ripple.town.core.model.Household
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Resident

/**
 * Semantic classification of a resident's [Resident.debt], layered ON TOP of the raw `Double` —
 * never replacing it. `debt` stays the single source of truth for the actual arithmetic
 * (interest, repayment) in `EconomySystem.dailySettlement`, untouched by this file. [DebtState]
 * answers a different question: not "how much do they owe" but "how serious is that, for THIS
 * resident, right now" — the same raw number means something very different for a wealthy
 * resident with steady income than for one with none, dependants, and no savings cushion. See
 * `docs/simulation-rules.md` "Debt states" for the full write-up of each boundary and worked
 * examples.
 *
 * Deliberately six states, ordered worst-to-best-adjacent (`ordinal` is meaningful — used by
 * [DebtSystem.classify]'s callers to detect "worsened/improved by at least one tier"):
 * - [NONE] — no debt at all.
 * - [MANAGEABLE] — some debt, but small relative to means and easily serviced.
 * - [ELEVATED] — noticeable debt-to-wealth load; not urgent, but no longer trivial.
 * - [STRAINED] — the daily repayment/interest burden is heavy relative to what this resident can
 *   realistically absorb (thin savings cushion, unreliable/no income, or dependants stretching the
 *   same wealth further).
 * - [CRISIS] — at or beyond the existing [EconomySystem.DEBT_CRISIS_THRESHOLD] bar. This is
 *   deliberately the SAME threshold the pre-existing binary flag used — not a new number — so
 *   every already-tuned downstream consumer of "serious financial trouble" (`CrimeSystem`'s
 *   desperation gate, `PressureBridgeSystem`'s partner-strain bridge) keeps working unchanged.
 * - [INSOLVENT] — debt has grown well past crisis with no realistic path to clear it on this
 *   resident's own means: raw debt alone is enormous, or it dwarfs their total means (wealth +
 *   household savings) by a wide multiple. See [DebtSystem.INSOLVENT_DEBT_FLOOR] and
 *   [DebtSystem.INSOLVENT_MEANS_MULTIPLE] for the exact numbers.
 */
enum class DebtState(val label: String) {
    NONE("No debt"),
    MANAGEABLE("Manageable debt"),
    ELEVATED("Elevated debt"),
    STRAINED("Financially strained"),
    CRISIS("Debt crisis"),
    INSOLVENT("Insolvent")
}

/**
 * Pure classification of a resident's [DebtState] from existing signals — deterministic, no side
 * effects, no persisted state of its own. Safe to call from UI (`ResidentProfileScreen`,
 * `TownSheets`) or any simulation system at any time; nothing can drift out of sync because
 * nothing is cached. Mirrors the read-only-computation shape of `NeedsSystem.financialTarget`
 * and `Mood.fromScore` elsewhere in this codebase.
 *
 * Inputs deliberately limited to what THIS resident/household model can actually support (no
 * invented mortgage/student-loan categories — see the doc comment on [DebtState]):
 * - `resident.debt` vs `resident.wealth` — the core debt-to-means ratio.
 * - `resident.employmentId` — income-reliability proxy: an employed resident has a repayment
 *   source; an unemployed one is servicing debt purely out of a shrinking pot.
 * - `resident.childIds.size` — dependants: the same wealth has to stretch further.
 * - `household.savings`/`household.monthlyRent` — household cushion and fixed outgoings, when a
 *   household is known. A resident with no household (edge case — e.g. mid-move, or a background
 *   resident never assigned one) is classified on personal means alone.
 */
object DebtSystem {

    /** Below this, debt is trivial regardless of means — a coffee-money rounding error. */
    const val TRIVIAL_DEBT = 50.0

    /**
     * The debt-to-wealth ratio bands used once wealth is known to be positive. Expressed as
     * "how many multiples of this resident's own wealth is the debt" — the actual repayment
     * capacity question. Kept as named constants (not magic numbers inline) so the boundaries in
     * `docs/simulation-rules.md` and this file can never silently drift apart.
     */
    const val MANAGEABLE_RATIO = 0.5
    const val ELEVATED_RATIO = 1.5

    /**
     * Repayment-burden vs. income-reliability gate for [DebtState.STRAINED]: debt above
     * [ELEVATED_RATIO] of wealth is only bumped to STRAINED (rather than left at ELEVATED) when
     * at least one real hardship signal is also present — unemployed (no repayment source),
     * dependants stretching the household's wealth further, or a household savings cushion below
     * this floor. Prevents a wealthy, employed, childless resident with a merely large ratio (e.g.
     * high debt AND high wealth, still a healthy multiple apart) from being misclassified as
     * strained purely off the ratio — the whole point the brief calls out: same debt, different
     * classification depending on the person.
     */
    const val LOW_SAVINGS_CUSHION = 150.0
    const val DEPENDANT_STRAIN_THRESHOLD = 2

    /** Beyond this raw amount, debt is INSOLVENT regardless of wealth — no realistic personal
     *  means could plausibly clear it. Deliberately well above [EconomySystem.DEBT_CRISIS_THRESHOLD]
     *  (2,000.0): this is "crisis that has kept compounding for a long time unaddressed", not a
     *  second, lower crisis bar. */
    const val INSOLVENT_DEBT_FLOOR = 6_000.0

    /** Beyond [EconomySystem.DEBT_CRISIS_THRESHOLD], debt is also INSOLVENT once it exceeds this
     *  multiple of the resident's total realistic means (their own wealth plus their household's
     *  savings, when known) — the "no path to clear it on their own means" test from the brief,
     *  computed relative to means rather than as a second flat number alone. */
    const val INSOLVENT_MEANS_MULTIPLE = 4.0

    fun classify(resident: Resident, household: Household?): DebtState {
        val debt = resident.debt
        if (debt <= 0.0) return DebtState.NONE
        if (debt < TRIVIAL_DEBT) return DebtState.MANAGEABLE

        // Total realistic means: personal wealth plus a share of household savings, when a
        // household is known. Never negative-clamped away — a resident with zero wealth and no
        // household savings has zero means, which is exactly the signal we want to carry forward.
        val means = (resident.wealth + (household?.savings ?: 0.0)).coerceAtLeast(0.0)

        // Crisis/insolvent bar first — reuses the existing, already-tuned flat threshold rather
        // than inventing a new one (see class doc). Insolvency is crisis-or-worse debt that has
        // grown past what personal means could plausibly ever clear.
        if (debt > EconomySystem.DEBT_CRISIS_THRESHOLD) {
            val meansMultiple = if (means > 0.0) debt / means else Double.POSITIVE_INFINITY
            return if (debt >= INSOLVENT_DEBT_FLOOR || meansMultiple >= INSOLVENT_MEANS_MULTIPLE) {
                DebtState.INSOLVENT
            } else {
                DebtState.CRISIS
            }
        }

        // Below crisis: classify by debt-to-wealth ratio, using wealth alone (not total means) as
        // the denominator — wealth is what this resident can actually draw down day to day per
        // `EconomySystem.dailySettlement`'s own repayment formula, household savings are not
        // directly spent on personal debt anywhere in that loop.
        val ratio = if (resident.wealth > 0.0) debt / resident.wealth else Double.POSITIVE_INFINITY
        if (ratio <= MANAGEABLE_RATIO) return DebtState.MANAGEABLE
        if (ratio <= ELEVATED_RATIO) return DebtState.ELEVATED

        // Above ELEVATED_RATIO: STRAINED only if a real hardship signal accompanies the ratio —
        // otherwise stays ELEVATED (noticeable, not urgent). See LOW_SAVINGS_CUSHION doc comment.
        val noIncome = resident.employmentId == null
        val manyDependants = resident.childIds.size >= DEPENDANT_STRAIN_THRESHOLD
        val thinCushion = household != null && household.savings < LOW_SAVINGS_CUSHION
        return if (noIncome || manyDependants || thinCushion) DebtState.STRAINED else DebtState.ELEVATED
    }
}
