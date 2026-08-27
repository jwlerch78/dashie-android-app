package com.dashieapp.Dashie.halite.music

/**
 * Pure resolution policy for voice music searches.
 *
 * Extracted from [MaApiClient.searchAndPlay] so the decision logic (what the user
 * meant, and whether playback should keep going afterwards) is separable from the
 * MA transport — and so MaApiClient stops growing past its size budget.
 *
 * Everything here is a pure function of the utterance plus data the caller already
 * fetched. No I/O.
 */
object MaVoiceSearchResolver {

    /** Group key used for a genre resolution — not an MA search group, ours. */
    const val GROUP_GENRE = "genre"

    private val ARTIST_QUERY = Regex("(songs?|music|tracks?)\\s+by\\s+", RegexOption.IGNORE_CASE)
    private val LEADING_BY = Regex("^(songs?|music|tracks?)\\s+by\\s+", RegexOption.IGNORE_CASE)
    private val LEADING_PLAY = Regex("^play\\s+(some\\s+|me\\s+)?", RegexOption.IGNORE_CASE)
    private val TRAILING_PUNCT = Regex("[?!.,;:]+$")
    private val TRAILING_MUSIC = Regex("\\s+music$", RegexOption.IGNORE_CASE)

    /**
     * Strip the search prefix to get just the artist/album/track name.
     * "songs by bob marley" → "bob marley" for better search matching.
     *
     * Trailing sentence punctuation is stripped because the STT tacks it on
     * ("jazz music?" → "jazz music") and it reaches the MA search verbatim,
     * making it miss (field bug 2026-07-17).
     */
    fun cleanQuery(query: String): String = query
        .replace(LEADING_BY, "")
        .replace(LEADING_PLAY, "")
        .replace(TRAILING_PUNCT, "")
        .trim()

    fun isArtistQuery(query: String): Boolean = ARTIST_QUERY.containsMatchIn(query)
    fun isAlbumQuery(query: String): Boolean = query.contains("album", ignoreCase = true)
    fun isPlaylistQuery(query: String): Boolean = query.contains("playlist", ignoreCase = true)

    /**
     * Which MA search group to prefer.
     *
     * [mediaTypeHint] is the classifier's own verdict ("artist", "album",
     * "playlist") and WINS over phrasing when present. It matters because the
     * Kotlin intent path extracts the entity before we see it — "play songs by
     * the beatles" arrives here as just "the beatles", with the "by" that would
     * signal artist intent already stripped. Dropping the hint made that request
     * fall to tracks-first and match a track instead of the artist.
     */
    fun priorityOrder(query: String, mediaTypeHint: String? = null): List<String> = when {
        mediaTypeHint == "artist" -> listOf("artists", "albums", "tracks", "playlists")
        mediaTypeHint == "album" -> listOf("albums", "artists", "tracks", "playlists")
        mediaTypeHint == "playlist" -> listOf("playlists", "albums", "tracks", "artists")
        isArtistQuery(query) -> listOf("artists", "albums", "tracks", "playlists")
        isAlbumQuery(query) -> listOf("albums", "artists", "tracks", "playlists")
        isPlaylistQuery(query) -> listOf("playlists", "albums", "tracks", "artists")
        else -> listOf("tracks", "albums", "artists", "playlists")
    }

    /**
     * Whether "don't stop the music" should be enabled, derived from WHAT WE
     * RESOLVED rather than from the caller.
     *
     * An explicit artist/album/playlist request is self-contained and must NOT
     * drift into MA's radio generator: DSTM extrapolates from recent listening
     * HISTORY, not from the item just played, so "play songs by the beatles"
     * played one Beatles track and then continued with the previously-played
     * artist (field bug 2026-07-18). A single track, or a genre/mood request,
     * genuinely does want continuation.
     *
     * This mirrors the browse path (`radioMode = isTrack` in
     * MusicComponentWiring's play_media branch), which has always behaved
     * correctly — but derives from the resolved group, which we know exactly,
     * instead of sniffing the URI string.
     */
    fun continuationForGroup(groupKey: String): Boolean = when (groupKey) {
        "tracks" -> true
        // A genre resolves to a station or playlist (see MaGenrePlayer), which is
        // already self-sustaining — continuation would only let MA's history-seeded
        // radio take the queue over.
        GROUP_GENRE -> false
        else -> false // artists, albums, playlists: play the item, then stop
    }

    /**
     * Same policy as [continuationForGroup], for callers that only hold a URI
     * (a browse tap, or a voice command carrying an explicit `library://…` URI).
     *
     * This is the rule the browse path has always used inline; it lives here now
     * so browse and voice can't drift apart again — voice hardcoding this to
     * `true` while browse derived it is exactly what caused the 2026-07-18 bug.
     */
    fun continuationForUri(uri: String): Boolean =
        uri.contains("://track/") || uri.contains("/track/")

    /**
     * Match a bare genre utterance ("play jazz", "play some blues music") against
     * the library's genre names.
     *
     * Guarded deliberately tightly, because genre names collide with real titles —
     * Queen's *Jazz* album, the band Genesis. We only claim a genre when:
     *  - the utterance carries no artist/album/playlist qualifier, and
     *  - the whole cleaned query (optionally minus a trailing "music") is an
     *    EXACT case-insensitive match for a library genre, and
     *  - it's at most 3 words.
     *
     * Anything less certain falls through to the normal search, which is the
     * pre-existing behaviour.
     *
     * @return the library's canonical genre name, or null to fall through.
     */
    fun matchGenre(query: String, libraryGenres: List<String>, mediaTypeHint: String? = null): String? {
        // The classifier already named a media type — trust it over a genre guess.
        if (mediaTypeHint != null) return null
        if (isArtistQuery(query) || isAlbumQuery(query) || isPlaylistQuery(query)) return null
        if (libraryGenres.isEmpty()) return null

        val cleaned = cleanQuery(query)
        if (cleaned.isEmpty()) return null

        val candidates = listOf(cleaned, cleaned.replace(TRAILING_MUSIC, "").trim())
            .filter { it.isNotEmpty() && it.split(Regex("\\s+")).size <= 3 }
            .distinct()

        for (candidate in candidates) {
            val hit = libraryGenres.firstOrNull { it.equals(candidate, ignoreCase = true) }
            if (hit != null) return hit
        }
        return null
    }
}
