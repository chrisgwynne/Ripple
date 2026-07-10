package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * A practical subset of the brief's belief-topic list — deliberately not mapped to any real-world
 * political party or ideology, and deliberately not the full brief taxonomy. Picked for the
 * clearest tie to systems that already exist and already fire real, causally-traceable events:
 * crime/policing ([TRUST_IN_POLICE]), employment/the town's finances ([ECONOMIC_OPTIMISM],
 * [TRUST_IN_GOVERNMENT]), the brief's "conservatism vs openness" axis ([SOCIAL_OPENNESS]),
 * weather/seasonal damage ([ENVIRONMENTAL_CONCERN]), petitions/community response
 * ([COMMUNITY_LOYALTY]), personal achievement/risk-taking ([RISK_TOLERANCE],
 * [INDIVIDUALISM_VS_COLLECTIVISM]), and civic institutions broadly ([INSTITUTIONAL_TRUST]) —
 * see `docs/simulation-rules.md` "Beliefs" for the full topic-to-trigger mapping. Nine topics, not
 * the brief's full set, matching this session's established "practical subset, not exhaustive
 * brief coverage" convention (see [EmotionType]'s own doc comment for the same reasoning applied
 * to emotions).
 */
enum class BeliefTopic(val label: String) {
    TRUST_IN_GOVERNMENT("Trust in local government"),
    TRUST_IN_POLICE("Trust in the constable"),
    ECONOMIC_OPTIMISM("Economic optimism"),
    SOCIAL_OPENNESS("Openness to change"),
    ENVIRONMENTAL_CONCERN("Environmental concern"),
    COMMUNITY_LOYALTY("Community loyalty"),
    INDIVIDUALISM_VS_COLLECTIVISM("Self-reliance vs. community"),
    RISK_TOLERANCE("Risk tolerance"),
    INSTITUTIONAL_TRUST("Trust in institutions generally")
}

/**
 * One resident's standing view on a [BeliefTopic] — formed and drifted by
 * `BeliefSystem`, never written to directly by any other simulation code, same convention as
 * [PersonalityModifiers]/[Resident.applyPersonalityDrift]. [position] is signed
 * (`-1.0` = strongly negative/distrustful/pessimistic end of the axis, `+1.0` = strongly
 * positive/trusting/optimistic end — see each [BeliefTopic]'s doc for which pole is which);
 * [confidence] is how strongly held the view is (0 = barely an opinion, 1 = firmly settled) and
 * independently gates how fast [position] can move — a low-confidence, newly-inherited belief
 * drifts faster from real experience than a long-held, high-confidence one (mirrored in
 * `BeliefSystem`'s drift formula, not stored here). [emotionalAttachment] (0..1) is how much this
 * belief is tied up with feeling rather than just opinion — reserved for a future
 * conversation/persuasion system to read (how hard someone pushes back when the belief is
 * challenged), not yet consumed by anything in this pass. [sourceEventIds] traces the real events
 * that shaped the current position, newest-relevant first, capped the same bounded way
 * [Resident.knownFacts] is. [lastUpdatedAt] is sim minutes, matching every other timestamp field
 * on [Resident].
 */
@Serializable
data class Belief(
    val topic: BeliefTopic,
    var position: Double,             // -1.0..1.0
    var confidence: Double,           // 0.0..1.0
    var emotionalAttachment: Double = 0.0,   // 0.0..1.0
    val sourceEventIds: MutableList<Long> = mutableListOf(),
    var lastUpdatedAt: Long
) {
    fun clamp() {
        position = position.coerceIn(-1.0, 1.0)
        confidence = confidence.coerceIn(0.0, 1.0)
        emotionalAttachment = emotionalAttachment.coerceIn(0.0, 1.0)
    }
}
