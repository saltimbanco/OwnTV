package tv.own.owntv.features.live

import tv.own.owntv.core.database.entity.ChannelEntity
import java.text.Normalizer

/**
 * Cross-playlist failover: when a live stream is interrupted, hop to the same channel on the
 * same playlist (another entry for it, if one exists) or on the next playlist.
 *
 * Candidates are found by fuzzy channel-name matching ([matchScore]) and tried in order until one
 * plays (see [LiveViewModel]'s failover watcher). The matcher is ported from the mobile app's
 * `ServerFailover.kt`: a provider may shorten or decorate a name ("ERT 1 HD" for "ERT1") but may
 * not invent a word the target never had, so "ERT 1" is never "ERT Sports 1".
 */
data class RankedFailoverCandidate(val channel: ChannelEntity, val score: Double)

/** Minimum [matchScore] for a candidate to be considered the same channel. */
const val FAILOVER_MIN_SCORE = 0.45

/** Maximum streams tried per interruption (the interrupted one excluded). */
const val MAX_FAILOVER_ATTEMPTS = 8

/** Tokens that carry no identity for a search probe ("BBC One HD" and "BBC ONE FHD" match alike). */
private val FAILOVER_PROBE_STOPWORDS = setOf(
    "hd", "fhd", "qhd", "uhd", "sd", "hdr", "h265", "hevc", "h264",
    "1080p", "720p", "2160p", "1080", "720", "2160", "4k", "8k",
    "live", "tv", "channel", "plus",
)

/** Lowercase, de-accent, drop punctuation and quality tags — the form search probes are built in. */
fun normalizeChannelName(raw: String): String {
    val deAccented = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
    return deAccented.lowercase()
        .replace("&", " and ")
        .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
        .split(" ")
        .filter { it.isNotEmpty() && it !in FAILOVER_PROBE_STOPWORDS }
        .joinToString(" ")
}

/** Significant tokens of a normalized name, for the DAO search pre-filter. */
fun failoverTokens(normalized: String): List<String> =
    normalized.split(" ").filter { it.length >= 3 }

// ---- The scorer (ported from the mobile app's ServerFailover.kt) -------------------------------

/** Compact identity key: lowercased, letters/digits only (`"NOVA Sports-Prime"` → `"novasportsprime"`). */
fun normalizeChannelKey(name: String): String =
    name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

/** Significant search tokens of a channel name (`"Nova Sports Prime HD"` → {nova, sports, prime, hd}). */
fun channelTokens(name: String): Set<String> =
    name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

/** Words that mark picture quality or region, not which channel it is. */
private val NAME_DECORATIONS =
    setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h264", "h265",
        "gr", "uk", "us", "de", "fr", "it", "es", "pt", "nl", "tr")

private val RESOLUTION_TOKEN = Regex("\\d{3,4}[pi]")

/** [channelTokens] with the quality/region decorations removed. */
private fun significantTokens(name: String): Set<String> =
    channelTokens(name).filterNot { it in NAME_DECORATIONS || RESOLUTION_TOKEN.matches(it) }.toSet()

/** The same word: equal, or a plural/abbreviation pair — `sport` ≈ `sports`, `spor` ≈ `sport`. */
private fun tokensMatch(a: String, b: String): Boolean {
    if (a == b) return true
    val shorter = if (a.length < b.length) a else b
    val longer = if (a.length < b.length) b else a
    return shorter.length >= 4 && longer.length - shorter.length <= 3 && longer.startsWith(shorter)
}

/**
 * How likely [candidateName] is the same channel as [targetName]. The asymmetry that matters:
 * a provider may shorten or decorate a name (`"nova sport prime"`, `"ERT 1 HD"` for `"ERT1"`)
 * but may not invent words the target never had (`"ERT 1"` is not `"ERT Sports 1"`,
 * `"ASports 1"` is not `"Sports 1"` — a shared fragment is not a shared name). So the candidate
 * is scored on how much of the target it covers, and anything it adds disqualifies it.
 * Quality/region decorations (`" HD"`, `"GR"`, `" 1080p"`) are stripped from both sides first,
 * and plurals/abbreviations count as the same word.
 */
fun matchScore(targetName: String, candidateName: String): Double {
    val target = normalizeChannelKey(targetName)
    val candidate = normalizeChannelKey(candidateName)
    if (target.isEmpty() || candidate.isEmpty()) return 0.0
    if (target == candidate) return 1.0
    val targetTokens = significantTokens(targetName)
    val candidateTokens = significantTokens(candidateName)
    // Same name once the decorations (`" HD"`, `"GR"`, `" 1080p"`) are also removed:
    // `"ERT1"` ≡ `"ERT 1 HD"`, `"Nova Sports Prime HD"` ≡ `"Nova Sports Prime"`.
    val targetBare = targetTokens.joinToString("")
    if (targetBare.isNotEmpty() && targetBare == candidateTokens.joinToString("")) return 0.9
    if (targetTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0
    val extra = candidateTokens.filter { cTok -> targetTokens.none { tokensMatch(it, cTok) } }
    if (extra.isNotEmpty()) return 0.0
    val covered = targetTokens.count { tTok -> candidateTokens.any { tokensMatch(tTok, it) } }
    return if (covered == 0) 0.0 else covered.toDouble() / targetTokens.size * 0.9
}

/**
 * Order [candidates] for hopping after [original] was interrupted.
 *
 * Same-playlist siblings come first (another entry for the channel, if it exists), then other
 * playlists in profile order ([sourceOrder]). Within each band, best fuzzy match first. Entries
 * below [FAILOVER_MIN_SCORE] are dropped, as is the interrupted stream itself (same id or same
 * URL — retrying the identical URL is the engine's own retry, not a hop). Matching is purely
 * name-based: provider ids are not comparable across playlists.
 */
fun rankFailoverCandidates(
    original: ChannelEntity,
    candidates: List<ChannelEntity>,
    sourceOrder: List<Long>,
    limit: Int = MAX_FAILOVER_ATTEMPTS,
    minScore: Double = FAILOVER_MIN_SCORE,
): List<RankedFailoverCandidate> {
    val orderIndex = sourceOrder.withIndex().associate { (i, id) -> id to i }
    return candidates
        .asSequence()
        .filter { it.id != original.id && it.streamUrl != original.streamUrl }
        .distinctBy { it.id }
        .map { ch -> RankedFailoverCandidate(ch, matchScore(original.name, ch.name)) }
        .filter { it.score >= minScore }
        .sortedWith(
            compareBy<RankedFailoverCandidate>(
                { if (it.channel.sourceId == original.sourceId) 0 else 1 },
                { orderIndex[it.channel.sourceId] ?: Int.MAX_VALUE },
                { -it.score },
            ),
        )
        .take(limit)
        .toList()
}
