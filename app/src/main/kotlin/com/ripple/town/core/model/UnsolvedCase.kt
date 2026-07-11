package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * A piece of physical or testimonial evidence tied to an [UnsolvedCase].
 * Each fragment has a [survivesUntil] timestamp — after that, the record is considered
 * lost (evidence degrades, papers are misfiled, buildings change hands).
 * Once `survivesUntil < currentTime` the fragment is gone; the [UnsolvedCase]
 * itself is never deleted, so the *fact that evidence existed and was lost* persists.
 */
@Serializable
data class EvidenceFragment(
    val id: Long,
    val description: String,
    val foundAt: Long,
    val survivesUntil: Long
)

/**
 * A crime or incident whose investigation has gone cold — either nobody was ever identified,
 * or the named suspect was demonstrably wrong and the real culprit was never found.
 *
 * Over time:
 * - [evidence] fragments pass their [EvidenceFragment.survivesUntil] timestamps and are
 *   effectively lost.
 * - [witnessIds] may include residents who have since died or left town.
 * - [theories] accumulate from rumours and resident speculation.
 * - [isHopeless] is set once all evidence is lost AND all witnesses are dead — at that
 *   point the truth is irrecoverable, and the case lives only in folk-memory and legend.
 *
 * Managed by [com.ripple.town.core.simulation.UnsolvedCaseSystem].
 */
@Serializable
data class UnsolvedCase(
    val id: Long,
    val originalEventId: Long,
    /** EventType.name — stored as string so the model has no compile dependency on the enum. */
    val eventTypeName: String,
    val description: String,
    val occurredAt: Long,
    val witnessIds: MutableList<Long> = mutableListOf(),
    val evidence: MutableList<EvidenceFragment> = mutableListOf(),
    /** Free-text theories formed by residents over time — may contradict each other. */
    val theories: MutableList<String> = mutableListOf(),
    /** True once all evidence is expired and no living in-town witnesses remain. */
    var isHopeless: Boolean = false
)
