(async function() {
    const TAG = '[Dashie-RecentlyPlayed]';
    try {
        // Debounce: skip if fetched within last 30s
        if (window._dashieRecentFetchAt && Date.now() - window._dashieRecentFetchAt < 30000) {
            console.log(TAG, 'Skipping fetch — debounce active');
            return;
        }

        const ha = document.querySelector('home-assistant');
        const hass = ha ? ha.hass : null;
        if (!hass || !hass.connection) {
            console.warn(TAG, 'hass or connection not available');
            return;
        }

        // 1. Discover Music Assistant config_entry_id
        console.log(TAG, 'Discovering MA config entry...');
        let configEntryId = null;
        try {
            const entries = await hass.connection.sendMessagePromise({ type: 'config_entries/get' });
            const maEntry = entries.find(function(e) { return e.domain === 'music_assistant'; });
            if (!maEntry) {
                console.log(TAG, 'Music Assistant integration not found');
                if (window.DashieNative) DashieNative.updateRecentlyPlayed('[]');
                return;
            }
            configEntryId = maEntry.entry_id;
            console.log(TAG, 'Found MA config entry:', configEntryId);
        } catch (err) {
            console.error(TAG, 'Failed to get config entries:', err.message || err);
            if (window.DashieNative) DashieNative.updateRecentlyPlayed('[]');
            return;
        }

        // 2. Fetch recently played albums and artists in parallel
        console.log(TAG, 'Fetching recently played albums + artists...');
        var albumItems = [];
        var artistItems = [];

        function unwrapResponse(raw) {
            if (!raw) return null;
            var resp = raw.response || raw;
            if (!resp || typeof resp !== 'object') return null;
            if (resp.items || resp.albums || resp.artists || Array.isArray(resp)) return resp;
            var keys = Object.keys(resp);
            for (var i = 0; i < keys.length; i++) {
                var val = resp[keys[i]];
                if (val && typeof val === 'object') return val;
            }
            return resp;
        }

        function extractItems(unwrapped, mediaType) {
            if (!unwrapped) return [];
            if (Array.isArray(unwrapped)) return unwrapped;
            return unwrapped[mediaType + 's'] || unwrapped.items || [];
        }

        try {
            var results = await Promise.all([
                hass.connection.sendMessagePromise({
                    type: 'call_service',
                    domain: 'music_assistant',
                    service: 'get_library',
                    service_data: {
                        media_type: 'album',
                        order_by: 'last_played_desc',
                        limit: 6,
                        config_entry_id: configEntryId
                    },
                    return_response: true
                }),
                hass.connection.sendMessagePromise({
                    type: 'call_service',
                    domain: 'music_assistant',
                    service: 'get_library',
                    service_data: {
                        media_type: 'artist',
                        order_by: 'last_played_desc',
                        limit: 4,
                        config_entry_id: configEntryId
                    },
                    return_response: true
                })
            ]);

            albumItems = extractItems(unwrapResponse(results[0]), 'album');
            artistItems = extractItems(unwrapResponse(results[1]), 'artist');
            console.log(TAG, 'Got', albumItems.length, 'albums,', artistItems.length, 'artists');
        } catch (err) {
            console.error(TAG, 'Failed to fetch library:', err.message || err);
            if (window.DashieNative) DashieNative.updateRecentlyPlayed('[]');
            return;
        }

        // 3. Resolve image URLs and build output items
        var origin = window.location.origin;
        var authToken = '';
        try { authToken = hass.auth.data.access_token || ''; } catch(e) {}

        function resolveImageUrl(item) {
            // Direct image URL (get_library format)
            if (item.image) {
                if (typeof item.image === 'string') {
                    // Relative URL → prepend origin for HA proxy paths
                    if (item.image.startsWith('/')) return origin + item.image;
                    return item.image;
                }
                // image might be an object with url/path — extract it
                if (item.image.url) {
                    if (item.image.url.startsWith('/')) return origin + item.image.url;
                    return item.image.url;
                }
                if (item.image.path) {
                    if (item.image.path.startsWith('http')) return item.image.path;
                    return origin + '/api/music_assistant/thumb?path=' + encodeURIComponent(item.image.path)
                        + '&provider=' + encodeURIComponent(item.image.provider || 'library')
                        + '&size=256';
                }
            }
            // Fallback: metadata.images array
            if (item.metadata && item.metadata.images && Array.isArray(item.metadata.images)) {
                var images = item.metadata.images;
                if (images.length === 0) return null;
                var img = images.find(function(i) { return i.type === 'thumb'; }) || images[0];
                if (!img || !img.path) return null;
                if (img.remotely_accessible) return img.path;
                return origin + '/api/music_assistant/thumb?path=' + encodeURIComponent(img.path)
                    + '&provider=' + encodeURIComponent(img.provider || 'library')
                    + '&size=256';
            }
            return null;
        }

        function getArtistName(item) {
            if (item.artists && item.artists.length > 0) {
                return item.artists[0].name || '';
            }
            return '';
        }

        function mapItem(item, mediaType) {
            return {
                name: item.name || '',
                artist: mediaType === 'album' ? getArtistName(item) : '',
                uri: item.uri || '',
                mediaType: mediaType,
                imageUrl: resolveImageUrl(item)
            };
        }

        // Detect currently playing album/artist to exclude from results
        var currentAlbumName = '';
        var currentArtistName = '';
        try {
            var states = hass.states;
            for (var entityId in states) {
                if (entityId.startsWith('media_player.') && states[entityId].state === 'playing') {
                    var attrs = states[entityId].attributes || {};
                    if (attrs.media_album_name) currentAlbumName = attrs.media_album_name;
                    if (attrs.media_artist) currentArtistName = attrs.media_artist;
                    break;
                }
            }
        } catch(e) {}
        if (currentAlbumName) console.log(TAG, 'Filtering current album:', currentAlbumName);

        // Interleave: album, artist, album, artist, album, ...
        var output = [];
        var ai = 0, bi = 0;
        while (output.length < 10 && (ai < albumItems.length || bi < artistItems.length)) {
            if (ai < albumItems.length) {
                var album = albumItems[ai];
                ai++;
                if (currentAlbumName && album.name === currentAlbumName) continue;
                output.push(mapItem(album, 'album'));
            }
            if (output.length < 10 && bi < artistItems.length) {
                var artist = artistItems[bi];
                bi++;
                if (currentArtistName && artist.name === currentArtistName) continue;
                output.push(mapItem(artist, 'artist'));
            }
        }

        var payload = {
            authToken: authToken,
            haBaseUrl: origin,
            items: output
        };

        console.log(TAG, 'Sending', output.length, 'items to Kotlin');
        window._dashieRecentFetchAt = Date.now();
        if (window.DashieNative) {
            DashieNative.updateRecentlyPlayed(JSON.stringify(payload));
        }
    } catch (err) {
        console.error(TAG, 'Unexpected error:', err.message || err);
        if (window.DashieNative) DashieNative.updateRecentlyPlayed('[]');
    }
})();
