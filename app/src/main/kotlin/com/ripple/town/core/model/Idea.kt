package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * Rough emotional register of an [IdeaTemplate] — flavour used to shape how it's talked about
 * and, lightly, who's drawn to it, distinct from [IdeaTemplate.baseAppealTraits] which drives
 * the actual receptivity math. Deliberately a 3-value enum, not a continuous score: this is
 * texture, not another tunable dimension.
 */
enum class IdeaTone { POSITIVE, NEUTRAL, NEGATIVE }

/**
 * A hand-authored, curated idea a resident can come to hold, advocate for, or reject — the
 * genuinely new thing [com.ripple.town.core.simulation.RumourSystem] doesn't cover: an idea
 * isn't tied to one factual event, has no single "true" version, and can spread, mutate and
 * die purely as a social phenomenon (belief strength, advocacy, town-wide adoption) independent
 * of any specific happening. Contrast with [Resident.ideaSeeds] — a much narrower, pre-existing
 * mechanic (a single string hint consumed once by [com.ripple.town.core.simulation.GoalSystem]
 * to help decide whether a resident starts a business) that this system does not replace.
 *
 * Residents never invent templates themselves; [IdeaLibrary] is the complete, fixed set for a
 * town. [complexity] (0..1) slows adoption for a harder-to-grasp idea (see
 * [com.ripple.town.core.simulation.IdeaDiffusionSystem.transferChance]); [baseAppealTraits] are
 * the [Personality] traits (via [Resident.effectivePersonality]) that make a resident more
 * naturally receptive, averaged across whichever traits are listed.
 */
@Serializable
data class IdeaTemplate(
    val id: String,
    val label: String,
    val tone: IdeaTone,
    val complexity: Double,
    val baseAppealTraits: List<String>
)

/**
 * One resident's personal, evolving relationship with a single [IdeaTemplate] — a list entry
 * on [Resident.activeIdeas], not a shared/global record, since two residents holding "the same"
 * idea can genuinely disagree about how strongly they believe it or whether it's worth
 * repeating. All four 0..100 fields are independent, deliberately not derived from one another:
 *
 * - [awareness] — has merely heard of it (can be high with low belief: "I know people are
 *   saying X, I'm not convinced").
 * - [interest] — how much they care either way; decays fastest, the first thing lost from
 *   neglect.
 * - [beliefStrength] — how much they've personally bought in; crossing
 *   [com.ripple.town.core.simulation.IdeaDiffusionSystem.ADOPTION_THRESHOLD] fires
 *   [EventType.IDEA_ADOPTED].
 * - [advocacyStrength] — how likely they are to actively bring it up/push it on others;
 *   distinct from belief since a quiet believer and a loud evangelist can hold the same
 *   [beliefStrength].
 *
 * [distorted] flags a resident whose copy mutated on transfer from the "purest" form the idea's
 * originator held — lightweight texture (no separate content/text mutation system), see
 * [com.ripple.town.core.simulation.IdeaDiffusionSystem.maybeTransfer].
 */
@Serializable
data class ResidentIdeaState(
    val templateId: String,
    var awareness: Double,
    var interest: Double,
    var beliefStrength: Double,
    var advocacyStrength: Double,
    val firstHeardAt: Long,
    var lastReinforcedAt: Long,
    var distorted: Boolean = false
)

/**
 * The complete, fixed, hand-authored set of ideas a Ripple town can develop — deliberately
 * small and curated (not residents inventing arbitrary ideas) and grounded in the fictional
 * small-town setting. Each entry's [IdeaTemplate.baseAppealTraits] names fields of [Personality]
 * (matched by name in [com.ripple.town.core.simulation.IdeaDiffusionSystem.traitAffinity], not a
 * typed enum, to avoid a second parallel trait taxonomy alongside [Personality]'s own).
 */
object IdeaLibrary {
    val COMMUNITY_GARDEN = IdeaTemplate(
        id = "community_garden",
        label = "start a community garden",
        tone = IdeaTone.POSITIVE,
        complexity = 0.3,
        baseAppealTraits = listOf("kindness", "curiosity")
    )
    val BOYCOTT_STRUGGLING_BUSINESS = IdeaTemplate(
        id = "boycott_struggling_business",
        label = "boycott a business that's let the town down",
        tone = IdeaTone.NEGATIVE,
        complexity = 0.4,
        baseAppealTraits = listOf("honesty", "impulsiveness")
    )
    val HEALTHIER_EATING = IdeaTemplate(
        id = "healthier_eating",
        label = "take up healthier eating habits",
        tone = IdeaTone.POSITIVE,
        complexity = 0.2,
        baseAppealTraits = listOf("discipline", "ambition")
    )
    val NEIGHBOURHOOD_WATCH = IdeaTemplate(
        id = "neighbourhood_watch",
        label = "set up a neighbourhood watch",
        tone = IdeaTone.NEUTRAL,
        complexity = 0.4,
        baseAppealTraits = listOf("courage", "discipline")
    )
    val SUPPORT_COUNCIL_SPENDING = IdeaTemplate(
        id = "support_council_spending",
        label = "support increased council spending",
        tone = IdeaTone.NEUTRAL,
        complexity = 0.6,
        baseAppealTraits = listOf("empathy", "ambition")
    )
    val NEW_LOCAL_TRADITION = IdeaTemplate(
        id = "new_local_tradition",
        label = "start a new local tradition or festival",
        tone = IdeaTone.POSITIVE,
        complexity = 0.35,
        baseAppealTraits = listOf("curiosity", "sociability")
    )
    val DISTRUST_RISING_CRIME = IdeaTemplate(
        id = "distrust_rising_crime",
        label = "distrust that the town is getting less safe",
        tone = IdeaTone.NEGATIVE,
        complexity = 0.3,
        baseAppealTraits = listOf("courage", "honesty")
    )
    val NEW_BUSINESS_CONCEPT = IdeaTemplate(
        id = "new_business_concept",
        label = "a promising new business concept",
        tone = IdeaTone.POSITIVE,
        complexity = 0.5,
        baseAppealTraits = listOf("ambition", "curiosity")
    )
    val SHARE_TOOLS_AND_LABOUR = IdeaTemplate(
        id = "share_tools_and_labour",
        label = "share tools and labour between neighbours",
        tone = IdeaTone.POSITIVE,
        complexity = 0.25,
        baseAppealTraits = listOf("kindness", "empathy")
    )
    val SIMPLER_LIVING = IdeaTemplate(
        id = "simpler_living",
        label = "live a bit simpler, want less",
        tone = IdeaTone.NEUTRAL,
        complexity = 0.45,
        baseAppealTraits = listOf("patience", "discipline")
    )

    val ALL: List<IdeaTemplate> = listOf(
        COMMUNITY_GARDEN, BOYCOTT_STRUGGLING_BUSINESS, HEALTHIER_EATING, NEIGHBOURHOOD_WATCH,
        SUPPORT_COUNCIL_SPENDING, NEW_LOCAL_TRADITION, DISTRUST_RISING_CRIME, NEW_BUSINESS_CONCEPT,
        SHARE_TOOLS_AND_LABOUR, SIMPLER_LIVING
    )

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String): IdeaTemplate? = byId[id]
}
