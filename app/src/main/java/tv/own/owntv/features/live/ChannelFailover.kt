package tv.own.owntv.features.live

import tv.own.owntv.core.database.entity.ChannelEntity
import java.text.Normalizer

/**
 * Cross-playlist failover: when a live stream is interrupted, hop to the same channel on the
 * same playlist (another entry for it, if one exists) or on the next playlist.
 *
 * Candidates are found by fuzzy channel-name matching ([rankFailoverCandidates]) and tried in
 * order until one plays. Pure Kotlin, no Android dependencies — unit tested.
 */
data class RankedFailoverCandidate(val channel: ChannelEntity, val score: Double)

/** Tokens that carry no identity for matching ("BBC One HD" and "BBC ONE FHD" are the same). */
private val FAILOVER_STOPWORDS = setOf(
    "hd", "fhd", "qhd", "uhd", "sd", "hdr", "h265", "hevc", "h264",
    "1080p", "720p", "2160p", "1080", "720", "2160", "4k", "8k",
    "live", "tv", "channel", "plus",
)

/** Minimum score for a channel to be considered the same channel ([failoverScore]). */
const val FAILOVER_MIN_SCORE = 0.5

/** Maximum streams tried per interruption (the interrupted one excluded). */
const val MAX_FAILOVER_ATTEMPTS = 8

/** Lowercase, de-accent, drop punctuation/quality tags — the form names are compared in. */
fun normalizeChannelName(raw: String): String {
    val deAccented = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
    return deAccented.lowercase()
        .replace("&", " and ")
        .replace("[^a-z0-9]+".toRegex(), " ")
        .split(" ")
        .filter { it.isNotEmpty() && it !in FAILOVER_STOPWORDS }
        .joinToString(" ")
}

/** Significant tokens of a normalized name, for the LIKE pre-filter and the token overlap. */
fun failoverTokens(normalized: String): List<String> =
    normalized.split(" ").filter { it.length >= 3 }

/** Classic edit distance, for the character-level half of [failoverScore]. */
fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in a.indices) {
        curr[0] = i + 1
        for (j in b.indices) {
            curr[j + 1] = minOf(prev[j + 1] + 1, curr[j] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
        }
        val tmp = prev; prev = curr; curr = tmp
    }
    return prev[b.length]
}

/**
 * Similarity of two already-[normalizeChannelName]d names, 0..1 (1 = identical).
 * Token overlap (Jaccard) carries the weight — word order and "UK |" prefixes vary between
 * playlists — with character similarity as the tiebreak half.
 */
fun failoverScore(aNorm: String, bNorm: String): Double {
    if (aNorm == bNorm) return 1.0
    if (aNorm.isEmpty() || bNorm.isEmpty()) return 0.0
    val aTokens = aNorm.split(" ").filter { it.isNotEmpty() }.toSet()
    val bTokens = bNorm.split(" ").filter { it.isNotEmpty() }.toSet()
    val overlap = if (aTokens.isEmpty() || bTokens.isEmpty()) 0.0 else {
        val inter = aTokens.intersect(bTokens).size.toDouble()
        inter / aTokens.union(bTokens).size.toDouble()
    }
    val aFlat = aNorm.replace(" ", "")
    val bFlat = bNorm.replace(" ", "")
    val charSim = 1.0 - levenshtein(aFlat, bFlat).toDouble() / maxOf(aFlat.length, bFlat.length)
    return 0.6 * overlap + 0.4 * charSim
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
    val originalNorm = normalizeChannelName(original.name)
    val orderIndex = sourceOrder.withIndex().associate { (i, id) -> id to i }
    return candidates
        .asSequence()
        .filter { it.id != original.id && it.streamUrl != original.streamUrl }
        .distinctBy { it.id }
        .map { ch ->
            RankedFailoverCandidate(ch, failoverScore(originalNorm, normalizeChannelName(ch.name)))
        }
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
