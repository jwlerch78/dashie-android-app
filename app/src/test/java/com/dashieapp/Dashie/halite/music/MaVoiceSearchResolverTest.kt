package com.dashieapp.Dashie.halite.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the voice music resolution policy — the two field bugs from
 * 2026-07-18 ("play jazz" played a rap track titled *Jazz*; "play songs by the
 * beatles" played one Beatles track then continued with the previous artist)
 * are both encoded here as regression cases.
 */
class MaVoiceSearchResolverTest {

    private val genres = listOf("Jazz", "Blues", "Hip Hop", "Classical", "Alternative Rock")

    // ── continuation policy ──────────────────────────────────────────────

    @Test
    fun `explicit artist album playlist do not continue into radio`() {
        assertFalse(MaVoiceSearchResolver.continuationForGroup("artists"))
        assertFalse(MaVoiceSearchResolver.continuationForGroup("albums"))
        assertFalse(MaVoiceSearchResolver.continuationForGroup("playlists"))
    }

    @Test
    fun `a single track continues`() {
        assertTrue(MaVoiceSearchResolver.continuationForGroup("tracks"))
    }

    @Test
    fun `a genre does not continue`() {
        // It resolves to a station/playlist, which is already self-sustaining.
        assertFalse(MaVoiceSearchResolver.continuationForGroup(MaVoiceSearchResolver.GROUP_GENRE))
    }

    @Test
    fun `uri continuation matches the group policy`() {
        assertTrue(MaVoiceSearchResolver.continuationForUri("library://track/42"))
        assertFalse(MaVoiceSearchResolver.continuationForUri("library://artist/7"))
        assertFalse(MaVoiceSearchResolver.continuationForUri("library://album/9"))
        assertFalse(MaVoiceSearchResolver.continuationForUri("spotify://playlist/37i9"))
    }

    // ── priority order ───────────────────────────────────────────────────

    @Test
    fun `songs by X prefers the artist`() {
        assertEquals("artists", MaVoiceSearchResolver.priorityOrder("songs by the beatles").first())
    }

    @Test
    fun `classifier media type hint wins over phrasing`() {
        // The Kotlin intent path strips "songs by", so only the hint survives —
        // dropping it made this request fall to tracks-first (field bug).
        assertEquals(
            "artists",
            MaVoiceSearchResolver.priorityOrder("the beatles", mediaTypeHint = "artist").first()
        )
    }

    @Test
    fun `bare query still defaults to tracks first`() {
        assertEquals("tracks", MaVoiceSearchResolver.priorityOrder("yesterday").first())
    }

    // ── genre matching ───────────────────────────────────────────────────

    @Test
    fun `bare genre matches`() {
        assertEquals("Jazz", MaVoiceSearchResolver.matchGenre("play jazz", genres))
        assertEquals("Jazz", MaVoiceSearchResolver.matchGenre("play some jazz", genres))
        assertEquals("Blues", MaVoiceSearchResolver.matchGenre("play some blues music", genres))
        assertEquals("Hip Hop", MaVoiceSearchResolver.matchGenre("play hip hop", genres))
    }

    @Test
    fun `genre match is case insensitive and punctuation tolerant`() {
        assertEquals("Jazz", MaVoiceSearchResolver.matchGenre("play JAZZ?", genres))
    }

    @Test
    fun `qualified requests are never treated as a genre`() {
        // Queen's *Jazz* album and "songs by" must not be hijacked by the genre path.
        assertNull(MaVoiceSearchResolver.matchGenre("play the jazz album", genres))
        assertNull(MaVoiceSearchResolver.matchGenre("play songs by jazz", genres))
        assertNull(MaVoiceSearchResolver.matchGenre("play my jazz playlist", genres))
        assertNull(MaVoiceSearchResolver.matchGenre("jazz", genres, mediaTypeHint = "artist"))
    }

    @Test
    fun `unknown genre falls through to search`() {
        assertNull(MaVoiceSearchResolver.matchGenre("play polka", genres))
        assertNull(MaVoiceSearchResolver.matchGenre("play jazz", emptyList()))
    }

    @Test
    fun `long queries are not genre matched`() {
        assertNull(MaVoiceSearchResolver.matchGenre("play a whole lot of smooth jazz", genres))
    }

    // ── query cleaning ───────────────────────────────────────────────────

    @Test
    fun `clean strips lead-ins and trailing punctuation`() {
        assertEquals("bob marley", MaVoiceSearchResolver.cleanQuery("songs by bob marley"))
        assertEquals("jazz", MaVoiceSearchResolver.cleanQuery("play some jazz"))
        assertEquals("jazz music", MaVoiceSearchResolver.cleanQuery("jazz music?"))
    }
}
