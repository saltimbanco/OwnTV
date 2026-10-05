package tv.own.owntv.features.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/** Fuzzy same-channel matching behind the cross-playlist hop on stream interruption. */
class ChannelFailoverTest {

    private fun channel(id: Long, sourceId: Long, name: String, url: String = "http://x/$id.ts") =
        ChannelEntity(id = id, sourceId = sourceId, name = name, streamUrl = url)

    @Test
    fun `normalizing drops case, quality tags and punctuation`() {
        assertEquals("sky sports main event", normalizeChannelName("SKY Sports | Main Event HD"))
        assertEquals("sky sports main event", normalizeChannelName("Sky Sports Main Event FHD"))
        assertEquals("bbc one", normalizeChannelName("BBC One 1080p"))
    }

    @Test
    fun `identical names score one, unrelated names near zero`() {
        assertEquals(1.0, failoverScore("bbc one", "bbc one"), 0.0)
        assertTrue(failoverScore("bbc one", "sky sports") < 0.3)
    }

    @Test
    fun `same channel across playlists outranks lookalikes`() {
        val original = channel(1, 10, "Sky Sports Main Event HD")
        val same = channel(2, 20, "SKY SPORTS MAIN EVENT FHD")
        // A different channel on the same playlist scores below the threshold and is dropped.
        val other = channel(3, 20, "Sky Sports News HD")
        val ranked = rankFailoverCandidates(original, listOf(other, same), listOf(10, 20))
        assertEquals(listOf(2L), ranked.map { it.channel.id })
    }

    @Test
    fun `same playlist sibling comes before other playlists`() {
        val original = channel(1, 10, "BBC One HD")
        val sibling = channel(2, 10, "BBC ONE")
        val nextPlaylist = channel(3, 20, "BBC One HD")
        val ranked = rankFailoverCandidates(original, listOf(nextPlaylist, sibling), listOf(10, 20))
        assertEquals(listOf(2L, 3L), ranked.map { it.channel.id })
    }

    @Test
    fun `interrupted stream itself and identical urls are skipped`() {
        val original = channel(1, 10, "BBC One HD", url = "http://x/same.ts")
        val self = channel(1, 10, "BBC One HD")
        val sameUrl = channel(9, 20, "BBC One HD", url = "http://x/same.ts")
        val ranked = rankFailoverCandidates(original, listOf(self, sameUrl), listOf(10, 20))
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `better name match ranks first, ids take no part`() {
        val original = channel(1, 10, "BBC One HD")
        // Same name, different provider ids — still the top match on name alone.
        val exact = channel(2, 20, "BBC ONE")
        val partial = channel(3, 20, "BBC One Wales HD")
        val ranked = rankFailoverCandidates(original, listOf(partial, exact), listOf(10, 20))
        assertEquals(listOf(2L, 3L), ranked.map { it.channel.id })
    }
}
