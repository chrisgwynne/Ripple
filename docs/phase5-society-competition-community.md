# Ripple — Phase 5: Society, Competition & Community Emergence

*Grounded in the codebase state as of July 2026. All sections build on confirmed existing foundations.*

---

## Overview

The simulation already models family dynasties, business rivalries, community groups, district character, and seasonal events. Phase 5 makes them **matter and be visible**: social power becomes a traceable arc, competition creates observable winners and losers, and communities emerge as genuine civic actors rather than background noise.

Three themes, 18 sections.

---

## Theme 1: SOCIETY

### S1 — Family dynasty panel (UI visibility)
**Gap:** `FamilyLegacy` data (mayorships, wealth arc, reputation type, milestones) exists in `WorldState.familyLegacies` but is entirely invisible to the player.  
**Implementation:** Add a "Families" tab or section in `TownSheets.kt` showing the top 3 families by legacy score. Each row: surname, reputation type label (POLITICAL_DYNASTY, BUSINESS_EMPIRE, etc.), generations present, notable achievement. Tap to open a family detail sheet listing all living members, past mayors/councillors, businesses owned, and key milestones.  
**Files:** `WorldSnapshot.kt` (FamilyLegacyUi data class), `TownSheets.kt` (FamilyRow + FamilyDetailSheet)

### S2 — Dynasty power effects (sim feedback)
**Gap:** POLITICAL_DYNASTY and BUSINESS_EMPIRE reputation types are labels with no downstream effect.  
**Implementation:** In `ElectionSystem.kt`, a candidate from a POLITICAL_DYNASTY family gets +5 campaign boost (their surname carries weight). In `EconomySystem.kt`, a business founded by a BUSINESS_EMPIRE family gets +0.05 formation viability bonus. In `PoliticsSystem.kt`, CRIMINAL families get a -10 recruitment penalty (voters remember). Cap: these modifiers apply only if the family has ≥2 living members.  
**Files:** `ElectionSystem.kt`, `EconomySystem.kt`, `PoliticsSystem.kt`

### S3 — Social mobility arc (visible tracking)
**Gap:** Residents have wealth, but no "trajectory" label — the player can't tell a rising family from a falling one.  
**Implementation:** Add `wealthTrend: WealthTrend` to `FamilyLegacy` — RISING (current > 1.2× starting), FALLING (current < 0.6× peak), STABLE. Surface this on the family panel as a small trend arrow. Also surface on the resident profile: "A rising family" or "A family in decline."  
**Files:** `FamilyLegacy.kt`, `LegacySystem.kt` (update monthly), `WorldSnapshot.kt`, `TownSheets.kt`

### S4 — Gentrification causal effects (sim mechanic)
**Gap:** GENTRIFYING district character is a classification with no downstream rent or migration pressure.  
**Implementation:** In `VacancySystem.kt` or a new `GentrificationSystem.kt`, when a district is GENTRIFYING: (a) building `value` increases by 0.5%/day for all buildings in that district, (b) residents with wealth below the district median face a 0.3% daily emigration chance (they can't afford rising rents), (c) wealthy migrants have a 0.4% daily immigration chance to GENTRIFYING districts. Emit `EventType.DISPLACEMENT` when a low-wealth resident leaves a gentrifying district.  
**Files:** `VacancySystem.kt` (or new `GentrificationSystem.kt`), `LifecycleSystem.kt`, `WorldEvent.kt`

### S5 — District social character effects (sim mechanic)
**Gap:** DECLINING districts only affect vacancy decay rate. PROSPEROUS districts do nothing beyond visual.  
**Implementation:** In `NeedsSystem.kt`, add a district-character modifier to daily need decay: PROSPEROUS/GENTRIFYING districts reduce stress by 0.1/day for residents there; DECLINING/HIGH_CRIME increase stress by 0.15/day. In `HealthSystem.kt`, HIGH_CRIME districts increase onset probability by 10%. These are small per-day effects that compound over years, creating visible divergence.  
**Files:** `NeedsSystem.kt`, `HealthSystem.kt`

### S6 — Dynasty heir and self-made identity facets
**Gap:** Residents have identity facets (ENTREPRENEUR, ELDER, etc.) but no facet for being born into or escaping a legacy.  
**Implementation:** In `IdentitySystem.onLifeEvent()`, when a resident achieves a goal that their parent also achieved (same occupation, same role), add `DYNASTY_HEIR` identity facet. When a resident achieves something their family never did (first business owner in a working-class family, first councillor), add `SELF_MADE` identity facet. Both facets affect belief drift: DYNASTY_HEIR = slight conservatism (+0.02 INSTITUTIONAL_TRUST drift), SELF_MADE = slight optimism (+0.02 ECONOMIC_OPTIMISM).  
**Files:** `IdentitySystem.kt`, `IdentityFacet.kt` (add 2 new types)

---

## Theme 2: COMPETITION

### C1 — Business rivalry visible in UI
**Gap:** `BusinessRivalrySystem` tracks pairwise rivalry standing between businesses, but this is never shown to the player.  
**Implementation:** In `WorldSnapshot.kt`, add `rival: String? = null` to `BusinessUi` (the name of the business's strongest rival, if any). In `SnapshotBuilder`, populate from `state.rivalries` finding the highest-standing rivalry involving this business. In `TownSheets.kt` business detail section, show: "In rivalry with: [The Crown Pub] — ongoing for 3 years."  
**Files:** `WorldSnapshot.kt`, `TownSheets.kt`

### C2 — Price war events
**Gap:** When two rivals both reduce prices in the same week, there's no event or narrative.  
**Implementation:** In `PriceDriftSystem.kt`, when processing daily price changes, check if two businesses in rivalry both cut prices in the same 7-day window. If so, emit `EventType.PRICE_WAR` with both as sourceResidentId / involvedResidentIds. The newspaper picks this up. Add `PRICE_WAR` to `EventType` enum.  
**Files:** `PriceDriftSystem.kt`, `WorldEvent.kt`

### C3 — Sector monopoly anomaly
**Gap:** `AnomalyDetector` has 10 checks but none for market concentration.  
**Implementation:** Add check 11: `SECTOR_MONOPOLY` — one business captures >65% of all catchment demand in its sector. Anomaly description: "The [bakery name] holds near-complete dominance in [sector]. Competition has collapsed." This feeds the newspaper and town character.  
**Files:** `AnomalyDetector.kt`

### C4 — Political dynasty rivalry events
**Gap:** When two families both have POLITICAL_DYNASTY reputation, there's no narrative consequence.  
**Implementation:** In `ElectionSystem.kt`, when two candidates from different POLITICAL_DYNASTY families run against each other, emit a `DYNASTY_RIVALRY` event with both families named. The newspaper covers it as "a clash of the old families." The event adds -5 trust between members of opposing families.  
**Files:** `ElectionSystem.kt`, `WorldEvent.kt`

### C5 — Community group membership rivalry
**Gap:** Community groups recruit independently; two groups with overlapping hobbies never compete.  
**Implementation:** In `CommunitySystem.kt`, when two groups share a hobby type, mark them as rivals. Each month, a resident with that hobby who is eligible for both groups makes a preference choice (based on which group's members they have higher affection for). A group that loses a potential member to a rival emits a quiet status flag. Cap this at groups with ≥3 shared potential members.  
**Files:** `CommunitySystem.kt`

### C6 — Business empire legacy milestone
**Gap:** `FamilyLegacy` tracks businesses owned but emits no milestone when a family closes multiple businesses.  
**Implementation:** In `LegacySystem.kt`, when a family's `businessesClosed` count crosses 3, emit `EventType.FAMILY_MILESTONE` with description "The [Family] name has seen three businesses fail" → shifts reputation toward FALLEN if prosperity is also low. When `businessesOwned` crosses 5 (ever), emit "The [Family] business empire spans generations" → reputation toward BUSINESS_EMPIRE.  
**Files:** `LegacySystem.kt`, `WorldEvent.kt`

---

## Theme 3: COMMUNITY EMERGENCE

### CE1 — Community group impact on town sentiment
**Gap:** Community groups boost members' social/purpose needs but have no effect on the broader town.  
**Implementation:** In `TownSentimentSystem.kt` or `CommunitySystem.kt`, large groups (≥5 members, reputation ≥60) directly shift the relevant town culture dimension monthly: a SPORTS group above threshold raises town SAFE/TIGHT_KNIT by 0.5; a FAITH group raises TIGHT_KNIT; a CHARITY group lowers suffering-linked sentiment scores. Effect is tiny but cumulative.  
**Files:** `CommunitySystem.kt`, `TownCultureSystem.kt`

### CE2 — Community-organised events
**Gap:** Harvest Fair and Winter Market fire as sim effects but aren't tied to community groups or visible as "organised by" someone.  
**Implementation:** In `SeasonalEventSystem.kt`, link annual events to the active community groups. The Harvest Fair is "organised" by the largest SPORTS or CHARITY group. The Winter Market by the largest general group. Emit an event naming the organising group and its leader. In the newspaper, headline: "[Group name] leads this year's Harvest Fair." Groups that successfully organise an event gain +10 reputation.  
**Files:** `SeasonalEventSystem.kt`, `CommunitySystem.kt`

### CE3 — Community petitions amplified by group membership
**Gap:** Petitions gather signatures but group membership doesn't amplify their pressure.  
**Implementation:** In `PetitionSystem.kt`, when a petition gains signatures, check how many signatories are members of the same community group. Each group cohort of ≥3 co-signing members adds +0.15 pressure (they move as a bloc). Cap at +1.0 extra pressure total from group cohesion. Emit a note in the petition event: "The [group name] signed as a bloc."  
**Files:** `PetitionSystem.kt`

### CE4 — Community crisis response
**Gap:** When a building is damaged (flood, fire) residents don't organise a collective response.  
**Implementation:** In `IncidentSystem.kt` or a new hook in `ConsequenceEngine.kt`, when a WEATHER_DAMAGE or ARSON_ATTEMPT event fires near a CHARITY or NEIGHBOURHOOD_WATCH group's members, the group "responds": all members move toward the building (activity HELP_NEIGHBOUR), each contributing +5 building condition over 3 days. The leader emits a `COMMUNITY_AID` event. This surfaces in the newspaper as "Local group rallies to help."  
**Files:** `IncidentSystem.kt`, `CommunitySystem.kt`, `WorldEvent.kt`

### CE5 — Collective community memory
**Gap:** Residents have individual memories, but groups have no shared memory of witnessed events.  
**Implementation:** Add `sharedMemories: MutableList<Long>` (event IDs) to `CommunityGroup`. In `CommunitySystem.updateMonthly()`, if a significant event (severity ≥0.6) occurred within the district containing ≥3 group members, add the event to `sharedMemories` (cap at 10). Surface this in a community group detail view: "This group witnessed the Great Flood of Year 7." Groups with a shared memory of the same traumatic event have +10 cohesion.  
**Files:** `CommunitySystem.kt`, community group model

### CE6 — Community group detail view in UI
**Gap:** Community groups are invisible in the UI — no way to see which groups exist, who's in them, or what they've done.  
**Implementation:** In `WorldSnapshot.kt`, add `CommunityGroupUi` (name, type, memberCount, reputation, leader name, sharedMemoryCount). Add `val communityGroups: List<CommunityGroupUi>` to `WorldUi`. In `TownSheets.kt`, add a "Communities" section in the "At a glance" tab showing active groups as rows. Tapping a group opens a detail sheet: members list (first 5), shared memories, recent activities, rivalries.  
**Files:** `WorldSnapshot.kt`, `TownSheets.kt`

---

## Implementation order
Implement in three waves of parallelism:
- **Wave 1 (surface existing data):** S1 (family panel), C1 (rivalry in UI), CE6 (community group UI)
- **Wave 2 (sim additions):** S2 (dynasty effects), S4 (gentrification), CE1 (group→sentiment), C2 (price war), C3 (monopoly anomaly), CE3 (petition amplification), CE4 (crisis response)
- **Wave 3 (accumulation):** S3 (mobility arc), S5 (district needs), S6 (identity facets), C4 (dynasty rivalry), C5 (group rivalry), C6 (empire milestone), CE2 (events linked to groups), CE5 (collective memory)

---

*Compile after each wave. This brief is grounded in confirmed existing model fields — no new model invention required for Wave 1, minimal for Wave 2, moderate for Wave 3.*
