package tv.own.owntv.features.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * Pins the name-matching bar behind the cross-playlist hop — ported from the mobile app's
 * ServerFailoverTest: a provider's shortened or decorated variant of a name is the same channel;
 * a name with words the target never had is not. The ranking tests pin the hop order on top.
 */
class ChannelFailoverTest {

    private fun channel(id: Long, sourceId: Long, name: String, url: String = "http://x/$id.ts") =
        ChannelEntity(id = id, sourceId = sourceId, name = name, streamUrl = url)

    private fun matches(target: String, candidate: String) =
        matchScore(target, candidate) >= FAILOVER_MIN_SCORE

    /** The search-probe normalizer: case, punctuation and quality tags folded away. */
    @Test
    fun `normalizing drops case, quality tags and punctuation`() {
        assertEquals("sky sports main event", normalizeChannelName("SKY Sports | Main Event HD"))
        assertEquals("sky sports main event", normalizeChannelName("Sky Sports Main Event FHD"))
        assertEquals("bbc one", normalizeChannelName("BBC One 1080p"))
    }

    @Test
    fun `same channel with different spacing and case is an exact match`() {
        assertEquals(1.0, matchScore("ert1", "ERT 1"), 0.0)
        assertEquals(1.0, matchScore("Nova Sports-Prime", "novasportsprime"), 0.0)
    }

    @Test
    fun `shortened or plural variants are the same channel`() {
        assertTrue(matches("nova sports prime hd", "Nova Sport Prime"))
        assertTrue(matches("nova sports prime", "Nova Sports"))
        assertTrue(matches("nova sport", "Nova Sports"))
    }

    @Test
    fun `quality and region decorations do not split a name`() {
        assertTrue(matches("ert1", "ERT1 HD"))
        assertTrue(matches("nova sports", "NOVA SPORTS GR"))
        assertTrue(matches("nova sports", "Nova Sports 1080p"))
        assertTrue(matches("nova sports prime hd", "Nova Sports Prime 4K"))
    }

    @Test
    fun `a word the target never had is a different channel`() {
        assertEquals(0.0, matchScore("ert 1", "ERT Sports 1"), 0.0)
        assertEquals(0.0, matchScore("ert1", "ERT Sports 1"), 0.0)
        assertEquals(0.0, matchScore("nova sports prime hd", "Nova Sports News"), 0.0)
        assertEquals(0.0, matchScore("nova sports 1", "Nova Sports 2"), 0.0)
    }

    @Test
    fun `a shared fragment inside a longer word is not a name match`() {
        assertEquals(0.0, matchScore("sports 1", "ASports 1"), 0.0)
        assertEquals(0.0, matchScore("nova sports prime hd", "asports"), 0.0)
    }

    @Test
    fun `a candidate covering under half the name is too loose`() {
        assertFalse(matches("nova sports prime", "Nova"))
        assertFalse(matches("ERT", "ERT1"))
    }

    @Test
    fun `same channel across playlists outranks lookalikes`() {
        val original = channel(1, 10, "Sky Sports Main Event HD")
        val same = channel(2, 20, "SKY SPORTS MAIN EVENT FHD")
        // A different channel on the same playlist invents a word ("news") and is not eligible.
        val other = channel(3, 20, "Sky Sports News HD")
        val ranked = rankFailoverCandidates(original, listOf(other, same), listOf(10, 20))
        assertEquals(listOf(2L), ranked.map { it.channel.id })
    }

    @Test
    fun `every other playlist once, its best match, in profile order`() {
        val original = channel(1, 10, "BBC One HD")
        val sibling = channel(2, 10, "BBC ONE") // the interrupted playlist: skipped
        val lastLoose = channel(3, 30, "BBC One")
        val lastExact = channel(4, 30, "BBC ONE HD")
        val firstOnly = channel(5, 20, "BBC One FHD")
        val ranked = rankFailoverCandidates(
            original,
            listOf(lastLoose, sibling, lastExact, firstOnly),
            listOf(10, 20, 30),
        )
        assertEquals(listOf(5L, 4L), ranked.map { it.channel.id })
    }

    @Test
    fun `no cap on how many playlists are tried`() {
        val original = channel(1, 10, "ERT 1")
        val others = (1..12).map { n -> channel(100L + n, 10L + n, "ERT1 HD") }
        val ranked = rankFailoverCandidates(original, others.reversed(), (10L..22L).toList())
        assertEquals(others.map { it.id }, ranked.map { it.channel.id })
    }

    @Test
    fun `interrupted stream itself and identical urls are skipped`() {
        val original = channel(1, 10, "BBC One HD", url = "http://x/same.ts")
        val self = channel(1, 10, "BBC One HD")
        val sameUrl = channel(9, 20, "BBC One HD", url = "http://x/same.ts")
        val ranked = rankFailoverCandidates(original, listOf(self, sameUrl), listOf(10, 20))
        assertTrue(ranked.isEmpty())
    }
}
