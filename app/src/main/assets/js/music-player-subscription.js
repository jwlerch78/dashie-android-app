(function() {
    const forceReinject = __DASHIE_FORCE__;

    // If force mode, clean up previous subscription and event listeners
    if (forceReinject && window._dashieMusicPlayerInjected) {
        console.log('[DashieMusic] Force re-inject - cleaning up previous subscription + listeners');
        // Tell unsubscribe to skip hideMusicPlayer (we'll re-show from current state)
        window._dashieMusicSkipHideOnCleanup = true;
        if (window._dashieMusicPlayerUnsubscribe) {
            window._dashieMusicPlayerUnsubscribe();
        }
        window._dashieMusicSkipHideOnCleanup = false;
        // Abort all previous event listeners via AbortController
        if (window._dashieMusicAbortController) {
            window._dashieMusicAbortController.abort();
        }
        window._dashieMusicPlayerInjected = false;
    }

    if (window._dashieMusicPlayerInjected) {
        console.log('[DashieMusic] Already injected, skipping');
        return;
    }
    window._dashieMusicPlayerInjected = true;

    // AbortController for clean listener removal on re-injection
    window._dashieMusicAbortController = new AbortController();
    const abortSignal = window._dashieMusicAbortController.signal;

    const defaultEntityId = '__DASHIE_ENTITY_ID__';
    let currentActiveEntity = defaultEntityId;
    // Persist manual override across re-injections so entity picker survives force re-inject
    let manualOverrideEntity = window._dashieMusicManualOverride || null;
    const knownEntities = {};  // entityId -> { state, friendlyName, lastPlayedAt }
    let duckedEntityId = null;  // Which entity was ducked for voice
    const TAG = '[DashieMusic]';

    console.log(TAG, '🎵 ==============================================');
    console.log(TAG, '🎵 Initializing music player subscription');
    console.log(TAG, '🎵 Default entity:', defaultEntityId);
    console.log(TAG, '🎵 Force mode:', forceReinject);
    console.log(TAG, '🎵 ==============================================');

    /** Get display-friendly name for a media player entity. */
    function displayName(friendlyName, entityId) {
        const lower = (friendlyName || '').toLowerCase();
        if (!friendlyName || lower === 'speaker' || lower === 'speakers' || friendlyName.startsWith('media_player.')) {
            return entityId.replace(/^media_player\./, '').replace(/_/g, ' ')
                .replace(/\b\w/g, c => c.toUpperCase());
        }
        return friendlyName;
    }

    /**
     * Get the Music Assistant counterpart entity ID.
     * MA creates two entities per device: base (HA) and _2 (MA-managed).
     * music_assistant.play_media only works with the MA entity (_2).
     */
    function getMaCounterpart(entityId) {
        return entityId.endsWith('_2')
            ? entityId.slice(0, -2)
            : entityId + '_2';
    }

    /**
     * Resolve the best entity for sending commands.
     * Prefers the _2 (MA-managed) counterpart when available because
     * base HA entities often don't support next/prev/play_media.
     * Falls back to currentActiveEntity if no _2 counterpart exists.
     */
    function getCommandEntity() {
        const entity = currentActiveEntity;
        // If already the _2 entity, use it
        if (entity.endsWith('_2')) return entity;
        // Check if the _2 counterpart exists and isn't unavailable
        const alt = entity + '_2';
        const hass = getHass();
        if (hass && hass.states && hass.states[alt]) {
            const altState = hass.states[alt].state;
            if (altState !== 'unavailable') {
                return alt;
            }
        }
        return entity;
    }

    /**
     * Resolve which entity the card should show based on priority:
     * 1. Manual override (picker selection) — always wins while set
     * 2. Default entity — if playing or paused
     * 3. Most recently started non-default entity — if playing or paused
     * 4. Prefer _2 (MA) counterpart when idle — needed for play_media
     */
    function resolveActiveEntity() {
        // 1. Manual override from picker — always wins
        if (manualOverrideEntity) {
            return manualOverrideEntity;
        }
        // 2. Default entity if playing or paused
        if (knownEntities[defaultEntityId]) {
            const s = knownEntities[defaultEntityId].state;
            if (s === 'playing' || s === 'paused') return defaultEntityId;
        }
        // 3. Most recently started non-default
        let best = null;
        let bestTime = 0;
        for (const eid in knownEntities) {
            const info = knownEntities[eid];
            if ((info.state === 'playing' || info.state === 'paused') && info.lastPlayedAt > bestTime) {
                best = eid;
                bestTime = info.lastPlayedAt;
            }
        }
        if (best) return best;
        // 4. Fall back — prefer _2 (MA) counterpart when available
        const defaultInfo = knownEntities[defaultEntityId];
        const alt = getMaCounterpart(defaultEntityId);
        const altInfo = knownEntities[alt];
        if (defaultInfo && defaultInfo.state === 'unavailable') {
            if (altInfo && altInfo.state !== 'unavailable') {
                console.log(TAG, '🎵 Default unavailable, falling back to:', alt);
                return alt;
            }
        }
        // If default is non-_2 and its _2 counterpart exists and is available,
        // prefer the _2 entity (MA-managed) — needed for music_assistant.play_media
        if (!defaultEntityId.endsWith('_2') && altInfo && altInfo.state !== 'unavailable') {
            console.log(TAG, '🎵 Preferring MA entity over base HA entity:', alt);
            return alt;
        }
        return defaultEntityId;
    }

    function updateKnownEntity(entityId, newState) {
        if (!newState) return;
        const wasPlaying = knownEntities[entityId]?.state === 'playing';
        knownEntities[entityId] = {
            state: newState.state || 'idle',
            friendlyName: displayName(newState.attributes?.friendly_name, entityId),
            lastPlayedAt: (newState.state === 'playing' && !wasPlaying)
                ? Date.now()
                : (knownEntities[entityId]?.lastPlayedAt || 0)
        };
    }

    // Track last state to avoid duplicate updates
    let lastStateJson = null;
    let positionUpdateInterval = null;
    let lastPositionUpdate = 0;

    // Track minimized state (persists across updates)
    let isMinimized = false;

    /**
     * Parse media_player state into MusicPlayerData format
     */
    function parseMediaState(state, entityId) {
        if (!state || !state.attributes) return null;
        entityId = entityId || currentActiveEntity;

        const attrs = state.attributes;
        const entityState = state.state;

        // Handle various states - some media players use different state names
        const isPlaying = entityState === 'playing';
        const isPaused = entityState === 'paused';
        // Also consider "standby" or "buffering" as having media
        const isBuffering = entityState === 'buffering';
        // Music Assistant goes idle after handing stream to native entity (ExoPlayer).
        // idle+media_title means there's displayable media data (show paused UI).
        const isIdleWithMedia = entityState === 'idle' && !!attrs.media_title;
        const hasMedia = isPlaying || isPaused || isBuffering || isIdleWithMedia;

        // Store media info when playing (for resume-from-idle fallback)
        if (isPlaying && attrs.media_title) {
            lastKnownMediaInfo = {
                title: attrs.media_title,
                artist: attrs.media_artist || '',
                contentId: attrs.media_content_id || null
            };
        }

        if (!hasMedia) return null;

        // Calculate current position based on position_updated_at
        let position = attrs.media_position || 0;
        if (isPlaying && attrs.media_position_updated_at) {
            const updatedAt = new Date(attrs.media_position_updated_at).getTime();
            const elapsed = (Date.now() - updatedAt) / 1000;
            // During active playing, extrapolate position from last HA update.
            // HA only updates media_position on state changes, not continuously,
            // so elapsed can grow large during normal playback.
            // Cap at duration to avoid overshooting, and reject negative values
            // (clock skew) or values beyond 2x duration (truly stale session).
            const duration = attrs.media_duration || 0;
            const maxElapsed = duration > 0 ? (duration - attrs.media_position + 5) : 600;
            if (elapsed >= 0 && elapsed < maxElapsed) {
                position = Math.floor(attrs.media_position + elapsed);
            }
        }

        // Clamp position to duration
        const duration = attrs.media_duration || 0;
        if (duration > 0 && position > duration) {
            position = duration;
        }

        // Cache volume when present so idle transitions don't reset to 0
        const rawVolume = attrs.volume_level;
        if (rawVolume != null) lastKnownVolume = rawVolume;
        const effectiveVolume = (rawVolume != null) ? rawVolume : (lastKnownVolume ?? 0);

        return {
            trackName: attrs.media_title || 'Unknown Track',
            artistName: attrs.media_artist || 'Unknown Artist',
            albumName: attrs.media_album_name || '',
            albumArtUrl: attrs.entity_picture ? buildAlbumArtUrl(attrs.entity_picture) : null,
            isPlaying: isPlaying,
            positionSeconds: Math.floor(position),
            durationSeconds: Math.floor(duration),
            volumeLevel: effectiveVolume,
            isVolumeMuted: !!attrs.is_volume_muted,
            isVolumeUnavailable: (rawVolume == null && lastKnownVolume == null),
            hasMedia: hasMedia,
            isMinimized: isMinimized,
            entityId: entityId,
            friendlyName: displayName(attrs.friendly_name, entityId)
        };
    }

    /**
     * Build full album art URL from HA entity_picture
     */
    function buildAlbumArtUrl(entityPicture) {
        if (!entityPicture) return null;
        // entity_picture is typically a relative URL like:
        // /api/media_player_proxy/media_player.xxx?token=abc
        // Construct full URL using current location
        if (entityPicture.startsWith('http')) return entityPicture;
        return window.location.origin + entityPicture;
    }

    /**
     * Send music player update to Kotlin
     */
    function updateMusicPlayer(data) {
        if (!window.DashieNative) return;

        const json = JSON.stringify(data);

        // Avoid duplicate updates (except for position changes during playback)
        if (json === lastStateJson && !data.isPlaying) {
            return;
        }
        lastStateJson = json;

        window.DashieNative.updateMusicPlayer(json);
    }

    /**
     * Hide music player via Kotlin
     */
    function hideMusicPlayer() {
        if (!window.DashieNative) return;

        // During force re-inject cleanup, skip hiding the player UI
        // (the re-inject will re-show from current state)
        if (window._dashieMusicSkipHideOnCleanup) {
            console.log(TAG, '🎵 hideMusicPlayer skipped (force re-inject cleanup)');
            return;
        }

        lastStateJson = null;
        window.DashieNative.hideMusicPlayer();

        // Stop position updates
        if (positionUpdateInterval) {
            clearInterval(positionUpdateInterval);
            positionUpdateInterval = null;
        }
    }

    /**
     * Handle state change for the media_player entity.
     * If the active entity is playing but has no media metadata (common with
     * native HA entities when MA wraps them as _2), try the counterpart
     * entity for metadata.
     */
    function onStateChange(state) {
        // Check for bounce detection (retry logic)
        const newState = state?.state || 'idle';
        if (checkForBounce(newState)) {
            return;  // Don't update UI while retrying
        }

        let data = parseMediaState(state);

        // If active entity is playing/paused but has no/bad media metadata,
        // try its _2 or non-_2 counterpart for metadata.
        // This handles MA where _2 has metadata but base entity doesn't.
        const needsMetadata = !data || (data && !state?.attributes?.media_title);
        if (needsMetadata && (newState === 'playing' || newState === 'paused')) {
            const hass = getHass();
            if (hass && hass.states) {
                const alt = currentActiveEntity.endsWith('_2')
                    ? currentActiveEntity.slice(0, -2)
                    : currentActiveEntity + '_2';
                const altState = hass.states[alt];
                if (altState && altState.attributes && altState.attributes.media_title) {
                    console.log(TAG, '🎵 Using metadata from counterpart:', alt);
                    // Merge: use the active entity's playback state with counterpart's metadata
                    const merged = Object.assign({}, altState, { state: newState });
                    data = parseMediaState(merged, currentActiveEntity);
                }
            }
        }

        if (data && data.hasMedia) {
            updateMusicPlayer(data);

            // Start position update interval for smooth progress bar
            // Use hasMedia (not isPlaying) so interval survives playing→idle
            // transitions during MA→ExoPlayer handoff after track skip
            if (data.hasMedia && !positionUpdateInterval) {
                positionUpdateInterval = setInterval(() => {
                    const hass = getHass();
                    if (hass && hass.states && hass.states[currentActiveEntity]) {
                        const activeState = hass.states[currentActiveEntity];
                        let currentData = parseMediaState(activeState, currentActiveEntity);
                        // Counterpart metadata fallback (same as onStateChange)
                        const activeEntityState = activeState.state;
                        if ((!currentData || !activeState.attributes?.media_title)
                            && (activeEntityState === 'playing' || activeEntityState === 'paused')) {
                            const alt = currentActiveEntity.endsWith('_2')
                                ? currentActiveEntity.slice(0, -2)
                                : currentActiveEntity + '_2';
                            const altState = hass.states[alt];
                            if (altState && altState.attributes && altState.attributes.media_title) {
                                const merged = Object.assign({}, altState, { state: activeEntityState });
                                currentData = parseMediaState(merged, currentActiveEntity);
                            }
                        }
                        if (currentData && currentData.hasMedia) {
                            updateMusicPlayer(currentData);
                        } else {
                            // Stop interval only when media is gone entirely
                            clearInterval(positionUpdateInterval);
                            positionUpdateInterval = null;
                        }
                    }
                }, 1000);
            } else if (!data.hasMedia && positionUpdateInterval) {
                clearInterval(positionUpdateInterval);
                positionUpdateInterval = null;
            }
        } else if (newState !== 'unavailable' && window._dashieMusicUserStopped !== true) {
            // Entity is connected but idle (no media playing).
            // Send an idle-connected update so Kotlin knows we're connected
            // and can show recently played, speaker selector, etc.
            const hass = getHass();
            const entityState = hass && hass.states ? hass.states[currentActiveEntity] : null;
            const attrs = entityState ? entityState.attributes : {};
            const rawVolume = attrs.volume_level;
            if (rawVolume != null) lastKnownVolume = rawVolume;
            const idleData = {
                trackName: '',
                artistName: '',
                albumName: '',
                albumArtUrl: null,
                isPlaying: false,
                positionSeconds: 0,
                durationSeconds: 0,
                volumeLevel: (rawVolume != null) ? rawVolume : (lastKnownVolume ?? 0),
                isVolumeMuted: !!attrs.is_volume_muted,
                isVolumeUnavailable: (rawVolume == null && lastKnownVolume == null),
                hasMedia: false,
                isMinimized: isMinimized,
                entityId: currentActiveEntity,
                friendlyName: displayName(attrs.friendly_name, currentActiveEntity)
            };
            console.log(TAG, '🎵 Entity connected but idle — sending idle state for:', currentActiveEntity);
            updateMusicPlayer(idleData);
        } else {
            console.log(TAG, '🎵 Entity unavailable or user-stopped, hiding player (state was:', state?.state, ')');
            hideMusicPlayer();
        }
    }

    /**
     * Get the hass object from the HA frontend
     */
    function getHass() {
        const ha = document.querySelector('home-assistant');
        return ha ? ha.hass : null;
    }

    /**
     * Subscribe to state changes via hass connection
     */
    function subscribeToStateChanges() {
        const hass = getHass();
        if (!hass || !hass.connection) {
            console.log(TAG, 'Waiting for hass connection...');
            setTimeout(subscribeToStateChanges, 1000);
            return;
        }

        // Wait for states to be populated (connection truly ready)
        if (!hass.states || Object.keys(hass.states).length === 0) {
            console.log(TAG, 'Waiting for hass states to populate...');
            setTimeout(subscribeToStateChanges, 500);
            return;
        }

        console.log(TAG, '🎵 Subscribing to state changes (default:', defaultEntityId,
            ') - hass has', Object.keys(hass.states).length, 'entities');

        // Validate default entity exists in HA states
        // HA loads entities progressively — some may not be in hass.states yet
        // even though the object is non-empty. Retry a few times before continuing.
        if (defaultEntityId && !hass.states[defaultEntityId]) {
            if (!window._dashieEntityRetries) window._dashieEntityRetries = 0;
            window._dashieEntityRetries++;
            if (window._dashieEntityRetries < 6) {
                console.log(TAG, '🎵 Default entity not in states yet, retry', window._dashieEntityRetries, '/ 5');
                setTimeout(subscribeToStateChanges, 2000);
                return;
            }
            // Don't show error UI — the subscription below will listen for ALL
            // media_player entities and auto-switch to whatever starts playing.
            // The entity may appear later (e.g. after MA finishes loading).
            console.warn(TAG, '🎵 Default entity not in states after retries:', defaultEntityId,
                '— continuing with subscription (will auto-detect any playing entity)');
        }
        window._dashieEntityRetries = 0;

        // Initialize known entities from current HA states
        for (const eid in hass.states) {
            if (eid.startsWith('media_player.')) {
                updateKnownEntity(eid, hass.states[eid]);
            }
        }

        // Check initial state (prefer default, then resolve active)
        const initialEntityId = resolveActiveEntity();
        currentActiveEntity = initialEntityId;
        const initialState = hass.states ? hass.states[initialEntityId] : null;
        console.log(TAG, '🎵 Initial active entity:', initialEntityId,
            'state:', initialState ? initialState.state : 'NOT FOUND', 'force:', forceReinject);
        if (initialState) {
            lastKnownState = initialState.state || 'idle';
        }

        // On force re-inject (sidebar "Show" button), show player if there's media
        // On normal startup, skip - don't auto-show from stale state
        if (forceReinject && initialState) {
            const isActivelyPlaying = initialState.state === 'playing' || initialState.state === 'paused';
            const isIdleWithMedia = initialState.state === 'idle' && initialState.attributes && initialState.attributes.media_title;

            if (isActivelyPlaying) {
                console.log(TAG, '🎵 Force re-inject: showing player (entity:', initialEntityId, 'state:', initialState.state, ')');
                window._dashieMusicUserStopped = false;
                onStateChange(initialState);
            } else if (isIdleWithMedia && !window._dashieMusicUserStopped) {
                console.log(TAG, '🎵 Force re-inject: showing player from idle state (paused view)');
                onStateChange(initialState);
            } else if (isIdleWithMedia && window._dashieMusicUserStopped) {
                console.log(TAG, '🎵 Force re-inject: skipping idle state (user previously stopped)');
            } else if (!window._dashieMusicUserStopped && initialState.state !== 'unavailable') {
                // Entity is connected but idle with no media — send idle-connected update
                // so Kotlin can show recently played and speaker selector
                console.log(TAG, '🎵 Force re-inject: entity connected but idle — sending idle state for:', initialEntityId, 'state:', initialState.state);
                onStateChange(initialState);
            }
        }

        // Subscribe to ALL state_changed events for media_player entities
        console.log(TAG, '🎵 Setting up multi-entity state_changed subscription...');
        hass.connection.subscribeEvents((event) => {
            const eventEntityId = event.data ? event.data.entity_id : null;
            if (!eventEntityId || !eventEntityId.startsWith('media_player.')) return;

            const newState = event.data.new_state;
            updateKnownEntity(eventEntityId, newState);

            // Resolve which entity should be active
            const resolved = resolveActiveEntity();
            const entitySwitched = resolved !== currentActiveEntity;

            if (entitySwitched) {
                console.log(TAG, '🎵 Active entity switched:', currentActiveEntity, '->', resolved);
                currentActiveEntity = resolved;
                lastKnownMediaInfo = null;
            }

            // Update UI if this event is for the active entity, or entity just switched
            if (eventEntityId === currentActiveEntity || entitySwitched) {
                hasReceivedRealStateChange = true;
                // Use event's new_state when it matches the active entity,
                // otherwise read from hass.states for the active entity
                const activeState = (eventEntityId === currentActiveEntity && newState)
                    ? newState
                    : hass.states[currentActiveEntity];
                if (activeState) {
                    lastKnownState = activeState.state || 'idle';
                    onStateChange(activeState);
                }
            }
        }, 'state_changed').then(unsub => {
            // Store unsubscribe function globally for cleanup on re-inject
            window._dashieMusicPlayerUnsubscribe = () => {
                console.log(TAG, '🎵 Unsubscribing from state changes');
                if (unsub) unsub();
                // Stop position interval
                if (positionUpdateInterval) {
                    clearInterval(positionUpdateInterval);
                    positionUpdateInterval = null;
                }
                // Hide player
                hideMusicPlayer();
            };
            console.log(TAG, '🎵 Subscription active, unsubscribe function stored');
        });

        console.log(TAG, '🎵 Subscription active');
    }

    /**
     * Call HA service for next track
     * Arms bounce detection to auto-play if Music Assistant goes idle
     */
    function callNext() {
        console.log(TAG, '🎵 callNext() invoked, current state:', lastKnownState);
        const hass = getHass();
        if (!hass) {
            console.warn(TAG, '🎵 Cannot call service - hass not available');
            return;
        }

        // Don't arm bounce detection for next/prev - track transitions
        // naturally go through brief idle states, and the retry would
        // send media_play instead of media_next_track.
        playCommandSentAt = null;
        retryCount = 0;
        isRetrying = false;
        sawPlayingAfterSkip = false;

        // Clear position interval so stale timestamps don't cause position jumps
        if (positionUpdateInterval) {
            clearInterval(positionUpdateInterval);
            positionUpdateInterval = null;
        }

        const cmdEntity = getCommandEntity();
        console.log(TAG, '🎵 Calling media_player.media_next_track for entity:', cmdEntity);

        hass.callService('media_player', 'media_next_track', {}, {
            entity_id: cmdEntity
        }).then(() => {
            console.log(TAG, '🎵 Next track service called');
            armAutoPlayAfterSkip();
        }).catch(err => {
            console.error(TAG, '🎵 Failed to call next track:', JSON.stringify(err), err.message || 'no message');
        });
    }

    /**
     * Call HA service for previous track
     */
    function callPrevious() {
        console.log(TAG, '🎵 callPrevious() invoked, current state:', lastKnownState);
        const hass = getHass();
        if (!hass) {
            console.warn(TAG, '🎵 Cannot call service - hass not available');
            return;
        }

        // Don't arm bounce detection for next/prev - track transitions
        // naturally go through brief idle states, and the retry would
        // send media_play instead of media_previous_track.
        playCommandSentAt = null;
        retryCount = 0;
        isRetrying = false;
        sawPlayingAfterSkip = false;

        // Clear position interval so stale timestamps don't cause position jumps
        if (positionUpdateInterval) {
            clearInterval(positionUpdateInterval);
            positionUpdateInterval = null;
        }

        const cmdEntity = getCommandEntity();
        console.log(TAG, '🎵 Calling media_player.media_previous_track for entity:', cmdEntity);

        hass.callService('media_player', 'media_previous_track', {}, {
            entity_id: cmdEntity
        }).then(() => {
            console.log(TAG, '🎵 Previous track service called');
            armAutoPlayAfterSkip();
        }).catch(err => {
            console.error(TAG, '🎵 Failed to call previous track:', JSON.stringify(err), err.message || 'no message');
        });
    }

    /**
     * After next/prev, wait 3s and send media_play if entity is still idle.
     * Handles Music Assistant not auto-playing after track skip on idle entity.
     */
    function armAutoPlayAfterSkip() {
        // No-op: ExoPlayer handles streaming reliably now.
        // MA entity going idle after skip is normal lifecycle (hands off to native entity).
        // Sending media_play would create a NEW stream that interrupts the working one.
        console.log(TAG, '🎵 armAutoPlayAfterSkip: skipped (ExoPlayer handles streaming)');
    }

    // ==================== Idle-Bounce Detection & Retry ====================
    // Music Assistant sometimes "bounces" - briefly goes to playing then back to idle.
    // We detect this and retry the play command automatically.

    const BOUNCE_CONFIG = {
        maxRetries: 2,
        retryDelayMs: 1500,
        idleBounceWindowMs: 4000  // Consider it a bounce if idle within 4s of play command
    };

    let playCommandSentAt = null;
    let retryCount = 0;
    let isRetrying = false;
    let lastKnownState = 'idle';
    let hasReceivedRealStateChange = false;  // Suppress UI until real state_changed event
    let lastKnownVolume = null;  // Cache volume across idle transitions
    let lastKnownMediaInfo = null;  // Track last playing track for resume-from-idle
    let sawPlayingAfterSkip = false;  // Track if entity reached playing after next/prev
    let voiceDuckedMusic = false;  // True if voice interaction ducked music that was playing
    let preDuckVolume = null;  // Volume level before voice ducking (0.0-1.0)
    const DUCK_VOLUME = 0.05;  // Very low volume during voice interaction

    /**
     * Detect if we just experienced an idle-bounce
     */
    function detectIdleBounce(oldState, newState) {
        if (!playCommandSentAt) return false;

        const timeSincePlayCommand = Date.now() - playCommandSentAt;

        // If we went from playing to idle within the bounce window
        if (oldState === 'playing' && newState === 'idle') {
            if (timeSincePlayCommand < BOUNCE_CONFIG.idleBounceWindowMs) {
                console.log(TAG, '🎵 [BOUNCE] Detected! playing→idle in', timeSincePlayCommand, 'ms');
                return true;
            }
        }

        // Also detect: we sent play/next/prev, but state went to idle
        if (newState === 'idle' && timeSincePlayCommand < BOUNCE_CONFIG.idleBounceWindowMs) {
            if (retryCount === 0 || isRetrying) {
                console.log(TAG, '🎵 [BOUNCE] Never reached stable playing state');
                return true;
            }
        }

        return false;
    }

    /**
     * Handle idle-bounce by retrying play command
     */
    function handleIdleBounce() {
        if (retryCount >= BOUNCE_CONFIG.maxRetries) {
            console.log(TAG, '🎵 [BOUNCE] Max retries reached, giving up');
            isRetrying = false;
            retryCount = 0;
            playCommandSentAt = null;
            return;
        }

        isRetrying = true;
        retryCount++;

        console.log(TAG, '🎵 [BOUNCE] Retry attempt', retryCount, '/', BOUNCE_CONFIG.maxRetries,
                    '- waiting', BOUNCE_CONFIG.retryDelayMs, 'ms...');

        setTimeout(() => {
            console.log(TAG, '🎵 [BOUNCE] Sending retry play command');
            sendPlayCommand();
        }, BOUNCE_CONFIG.retryDelayMs);
    }

    /**
     * Hook into state changes for bounce detection and user-stop suppression.
     * Returns true if the state change should be suppressed (don't update UI).
     */
    function checkForBounce(newState) {
        const oldState = lastKnownState;
        lastKnownState = newState;

        if (newState === 'playing') {
            // Suppress updates after user explicitly stopped
            if (window._dashieMusicUserStopped) {
                console.log(TAG, '🎵 Suppressing post-stop playing state update');
                return true;
            }
            sawPlayingAfterSkip = true;
        }

        return false;
    }

    /**
     * Send actual play command to HA
     */
    function sendPlayCommand() {
        playCommandSentAt = Date.now();

        const hass = getHass();
        if (!hass) {
            console.warn(TAG, 'Cannot call play - hass not available');
            return;
        }

        const cmdEntity = getCommandEntity();
        console.log(TAG, '🎵 Sending media_play command for:', cmdEntity);

        hass.callService('media_player', 'media_play', {}, {
            entity_id: cmdEntity
        }).then(() => {
            console.log(TAG, '🎵 Play command sent - watching for state change...');
        }).catch(err => {
            console.error(TAG, 'Play command failed:', err.message || err);
            playCommandSentAt = null;
        });
    }

    /**
     * Call play (with bounce detection).
     * If entity is idle and we have last known media info,
     * use play_media fallback since media_play fails on idle Music Assistant entities.
     */
    function callPlay() {
        // Check live HA state of both command entity and active entity
        // to avoid stale state causing play_media (restart) instead of media_play (resume)
        const hass = getHass();
        const cmdEntity = getCommandEntity();
        let effectiveState = lastKnownState;
        if (hass && hass.states) {
            // Prefer command entity state, fall back to active entity
            const cmdLive = hass.states[cmdEntity];
            const activeLive = hass.states[currentActiveEntity];
            if (cmdLive) effectiveState = cmdLive.state;
            else if (activeLive) effectiveState = activeLive.state;
            // Also check base entity if _2 is idle (might be paused on base)
            if (effectiveState === 'idle' || effectiveState === 'unavailable') {
                const base = cmdEntity.endsWith('_2') ? cmdEntity.slice(0, -2) : null;
                if (base && hass.states[base] && hass.states[base].state === 'paused') {
                    effectiveState = 'paused';
                }
            }
        }
        if (effectiveState !== lastKnownState) {
            console.log(TAG, '🎵 callPlay() - lastKnownState was', lastKnownState, 'but effective state is', effectiveState);
            lastKnownState = effectiveState;
        }
        console.log(TAG, '🎵 callPlay() - effective state:', effectiveState);
        // Clear user-stopped flag - this is an explicit play request
        window._dashieMusicUserStopped = false;
        window._dashieMusicStoppedAt = 0;
        retryCount = 0;
        isRetrying = false;

        // If entity is paused, always use media_play to resume from current position
        if (effectiveState === 'paused') {
            console.log(TAG, '🎵 Entity paused — sending media_play to resume');
            sendPlayCommand();
            return;
        }

        // When entity is idle, media_play is not supported — use play_media
        // to resume the last known track. Prefer contentId (e.g. library://track/4)
        // for exact match; fall back to text search only if contentId is unavailable.
        if (effectiveState === 'idle' && lastKnownMediaInfo) {
            if (lastKnownMediaInfo.contentId) {
                console.log(TAG, '🎵 Entity idle — resuming via contentId:', lastKnownMediaInfo.contentId);
                callPlayMedia(lastKnownMediaInfo.contentId, null, null);
            } else {
                console.log(TAG, '🎵 Entity idle — no contentId, falling back to search:', lastKnownMediaInfo.title);
                var searchQuery = lastKnownMediaInfo.title;
                if (lastKnownMediaInfo.artist) {
                    searchQuery = lastKnownMediaInfo.artist + ' ' + searchQuery;
                }
                callPlayMedia(searchQuery, lastKnownMediaInfo.artist, 'track');
            }
            return;
        }

        sendPlayCommand();
    }

    /**
     * Call pause
     */
    function callPause() {
        console.log(TAG, '🎵 callPause()');
        playCommandSentAt = null;  // Clear bounce detection on explicit pause

        const hass = getHass();
        if (!hass) {
            console.warn(TAG, 'Cannot call pause - hass not available');
            return;
        }

        const cmdEntity = getCommandEntity();
        hass.callService('media_player', 'media_pause', {}, {
            entity_id: cmdEntity
        }).then(() => {
            console.log(TAG, '🎵 Pause command sent to:', cmdEntity);
        }).catch(err => {
            console.error(TAG, 'Pause command failed:', err.message || err);
        });
    }

    /**
     * Toggle play/pause based on current state
     */
    function callPlayPause() {
        // Check live HA state of the command entity (the _2 MA entity if available)
        // since lastKnownState can be stale
        const hass = getHass();
        const cmdEntity = getCommandEntity();
        let effectiveState = lastKnownState;
        if (hass && hass.states) {
            const live = hass.states[cmdEntity];
            if (live) effectiveState = live.state;
            // Also check the base entity if command entity (_2) is idle
            if (effectiveState !== 'playing') {
                const base = cmdEntity.endsWith('_2') ? cmdEntity.slice(0, -2) : null;
                if (base && hass.states[base] && hass.states[base].state === 'playing') {
                    effectiveState = 'playing';
                }
            }
        }
        console.log(TAG, '🎵 callPlayPause() - effective state:', effectiveState, 'cmdEntity:', cmdEntity);
        if (effectiveState === 'playing') {
            callPause();
        } else {
            callPlay();
        }
    }

    /**
     * Listen for play command from Kotlin
     */
    window.addEventListener('music-player-play', () => {
        console.log(TAG, '🎵 Received play event from Kotlin');
        callPlay();
    }, { signal: abortSignal });

    /**
     * Listen for pause command from Kotlin
     */
    window.addEventListener('music-player-pause', () => {
        console.log(TAG, '🎵 Received pause event from Kotlin');
        callPause();
    }, { signal: abortSignal });

    /**
     * Listen for play/pause toggle from Kotlin
     */
    window.addEventListener('music-player-play-pause', () => {
        console.log(TAG, '🎵 Received play/pause event from Kotlin');
        callPlayPause();
    }, { signal: abortSignal });

    /**
     * Listen for next track command from Kotlin
     */
    window.addEventListener('music-player-next', () => {
        console.log(TAG, '🎵 Received next track event from Kotlin');
        callNext();
    }, { signal: abortSignal });

    /**
     * Listen for previous track command from Kotlin
     */
    window.addEventListener('music-player-previous', () => {
        console.log(TAG, '🎵 Received previous track event from Kotlin');
        callPrevious();
    }, { signal: abortSignal });

    /**
     * Listen for stop command from Kotlin (close button on player card).
     * Stops HA playback, resets session state so player stays hidden.
     */
    window.addEventListener('music-player-stop', () => {
        console.log(TAG, '🎵 Received stop event from Kotlin');
        const hass = getHass();
        if (hass) {
            const cmdEntity = getCommandEntity();
            hass.callService('media_player', 'media_stop', {}, {
                entity_id: cmdEntity
            }).catch(err => {
                console.error(TAG, '🎵 Stop command failed:', err.message || err);
            });
        }
        lastKnownMediaInfo = null;
        // Persist user-stopped flag on window so force re-inject respects it
        window._dashieMusicUserStopped = true;
        window._dashieMusicStoppedAt = Date.now();
        // Stop position update interval
        if (positionUpdateInterval) {
            clearInterval(positionUpdateInterval);
            positionUpdateInterval = null;
        }
        hideMusicPlayer();
    }, { signal: abortSignal });

    /**
     * Listen for minimize/expand toggle from Kotlin
     */
    window.addEventListener('music-player-toggle-minimize', () => {
        isMinimized = !isMinimized;
        console.log(TAG, '🎵 Toggle minimize - new state:', isMinimized);
    }, { signal: abortSignal });

    /**
     * Voice ducking: lower volume instead of pausing.
     * Keeps playback session active so next/prev commands work on non-idle entity.
     * Sent by Kotlin when wake word is detected.
     */
    window.addEventListener('music-player-voice-duck', () => {
        if (lastKnownState === 'playing') {
            const hass = getHass();
            if (!hass) return;
            // Read current volume from entity attributes
            const states = hass.states || {};
            const entity = states[currentActiveEntity];
            const currentVolume = entity && entity.attributes ? entity.attributes.volume_level : null;
            preDuckVolume = currentVolume || 0.5;
            duckedEntityId = currentActiveEntity;
            console.log(TAG, '🎵 Voice duck - lowering volume from', preDuckVolume, 'to', DUCK_VOLUME, 'on', duckedEntityId);
            voiceDuckedMusic = true;
            hass.callService('media_player', 'volume_set', { volume_level: DUCK_VOLUME }, {
                entity_id: duckedEntityId
            }).catch(err => {
                console.error(TAG, '🎵 Voice duck volume_set failed:', err.message || err);
            });
        } else {
            console.log(TAG, '🎵 Voice duck - music not playing, skipping');
            voiceDuckedMusic = false;
        }
    }, { signal: abortSignal });

    /**
     * Voice unduck: restore volume only if we ducked it.
     * Sent by Kotlin when non-music voice interaction completes.
     */
    window.addEventListener('music-player-voice-unduck', () => {
        if (voiceDuckedMusic && preDuckVolume !== null && duckedEntityId) {
            console.log(TAG, '🎵 Voice unduck - restoring volume to', preDuckVolume, 'on', duckedEntityId);
            voiceDuckedMusic = false;
            const hass = getHass();
            if (hass) {
                hass.callService('media_player', 'volume_set', { volume_level: preDuckVolume }, {
                    entity_id: duckedEntityId
                }).catch(err => {
                    console.error(TAG, '🎵 Voice unduck volume_set failed:', err.message || err);
                });
            }
            preDuckVolume = null;
            duckedEntityId = null;
        } else {
            console.log(TAG, '🎵 Voice unduck - music was not ducked, skipping');
        }
    }, { signal: abortSignal });

    /**
     * Listen for entity switch from Kotlin (picker selection)
     */
    window.addEventListener('music-player-switch-entity', (event) => {
        const entityId = event.detail?.entityId;
        if (!entityId) return;
        console.log(TAG, '🎵 Manual entity switch to:', entityId);
        manualOverrideEntity = entityId;
        window._dashieMusicManualOverride = entityId;
        currentActiveEntity = entityId;
        lastKnownMediaInfo = null;
        const hass = getHass();
        if (hass && hass.states && hass.states[entityId]) {
            lastKnownState = hass.states[entityId].state || 'idle';
            onStateChange(hass.states[entityId]);
        }
    }, { signal: abortSignal });

    // ==================== Volume control from Kotlin overlay ====================

    /**
     * Set entity volume from Kotlin overlay +/- buttons.
     */
    window.addEventListener('music-player-volume-set', (event) => {
        const detail = event.detail || {};
        const volumeLevel = detail.volumeLevel;
        if (volumeLevel == null) return;
        console.log(TAG, '🎵 Volume set to', volumeLevel);
        const hass = getHass();
        if (hass) {
            hass.callService('media_player', 'volume_set', { volume_level: volumeLevel }, {
                entity_id: currentActiveEntity
            }).catch(err => {
                console.error(TAG, '🎵 volume_set failed:', err.message || err);
            });
        }
    }, { signal: abortSignal });

    /**
     * Adjust volume by a relative delta (voice commands: "louder", "quieter").
     * Reads lastKnownVolume and applies delta as percentage (e.g., delta=10 → +0.1).
     */
    window.addEventListener('music-player-volume-delta', (event) => {
        const detail = event.detail || {};
        const delta = detail.delta;
        if (delta == null) return;
        const current = lastKnownVolume ?? 0.5;
        const newLevel = Math.max(0, Math.min(1, current + (delta / 100)));
        console.log(TAG, '🎵 Volume delta', delta, '→', newLevel.toFixed(2), '(was', current.toFixed(2), ')');
        const hass = getHass();
        if (hass) {
            hass.callService('media_player', 'volume_set', { volume_level: newLevel }, {
                entity_id: currentActiveEntity
            }).catch(err => {
                console.error(TAG, '🎵 volume_set (delta) failed:', err.message || err);
            });
        }
    }, { signal: abortSignal });

    // ==================== Music Assistant play_media ====================

    /**
     * Call Music Assistant play_media service with search query.
     *
     * @param {string} mediaId - The search query (artist name, song title, etc.)
     * @param {string} artist - Optional artist filter
     * @param {string} mediaType - Optional media type: artist, track, album, playlist, radio
     *                             If omitted, Music Assistant auto-detects.
     */
    function callPlayMedia(mediaId, artist, mediaType) {
        console.log(TAG, '🎵 callPlayMedia:', mediaId, artist || '(no artist filter)', 'type:', mediaType || '(auto-detect)');
        // Clear user-stopped flag - this is an explicit play request
        window._dashieMusicUserStopped = false;
        window._dashieMusicStoppedAt = 0;
        const hass = getHass();
        if (!hass) {
            console.warn(TAG, '🎵 Cannot call play_media - hass not available');
            return;
        }

        // Resolve the MA entity for music_assistant.play_media.
        // MA's play_media service only works with the MA-managed entity (_2 suffix).
        let targetEntity = currentActiveEntity;

        // Try to resolve to a valid media_player.* entity for targeting.
        // If currentActiveEntity is a base HA entity, try the _2 counterpart.
        if (targetEntity.startsWith('media_player.') && !targetEntity.endsWith('_2') && hass.states) {
            const maEntity = targetEntity + '_2';
            const maState = hass.states[maEntity];
            if (maState && maState.state !== 'unavailable') {
                console.log(TAG, '🎵 Switching to MA entity for play_media:', maEntity);
                targetEntity = maEntity;
                currentActiveEntity = maEntity;
            }
        }

        // If currentActiveEntity is not a media_player.* (e.g. Sendspin up* ID),
        // find the HA media_player entity whose active_queue matches our Sendspin ID
        if (!targetEntity.startsWith('media_player.') && hass.states) {
            var ssId = targetEntity;
            console.log(TAG, '🎵 Resolving Sendspin ID to media_player via active_queue:', ssId);
            for (var key in hass.states) {
                if (!key.startsWith('media_player.')) continue;
                var st = hass.states[key];
                if (st && st.attributes && st.attributes.active_queue === ssId) {
                    console.log(TAG, '🎵 Found matching entity:', key, '(active_queue matches', ssId, ')');
                    targetEntity = key;
                    currentActiveEntity = key;
                    break;
                }
            }
        }

        var hasValidEntity = targetEntity.startsWith('media_player.') &&
            hass.states && hass.states[targetEntity] && hass.states[targetEntity].state !== 'unavailable';

        // Last resort: find any available MA media_player.*_2 entity
        if (!hasValidEntity && hass.states) {
            console.log(TAG, '🎵 No entity via active_queue — finding any MA media_player.*_2');
            for (var key in hass.states) {
                if (key.startsWith('media_player.') && key.endsWith('_2')) {
                    var st = hass.states[key];
                    if (st && st.state !== 'unavailable' && st.attributes && st.attributes.mass_player_type) {
                        console.log(TAG, '🎵 Using MA entity:', key);
                        targetEntity = key;
                        currentActiveEntity = key;
                        hasValidEntity = true;
                        break;
                    }
                }
            }
        }

        if (!hasValidEntity) {
            console.warn(TAG, '🎵 No valid media_player entity found for play_media');
            if (window.DashieNative) {
                window.DashieNative.speak('Music player is not ready yet. Please try again in a moment.');
            }
            return;
        }

        const serviceData = { media_id: mediaId };
        if (artist) {
            serviceData.artist = artist;
        }
        if (mediaType) {
            serviceData.media_type = mediaType;
        }

        // Arm bounce detection for the play
        retryCount = 0;
        isRetrying = false;
        playCommandSentAt = Date.now();

        console.log(TAG, '🎵 Calling music_assistant.play_media with:', JSON.stringify(serviceData), 'entity:', targetEntity);

        hass.callService('music_assistant', 'play_media', serviceData, { entity_id: targetEntity }).then(() => {
            console.log(TAG, '🎵 play_media service called successfully for:', mediaId);
        }).catch(err => {
            console.error(TAG, '🎵 play_media failed:', err.message || err);
            playCommandSentAt = null;
        });
    }

    /**
     * Listen for play-media command from Kotlin (voice commands)
     */
    window.addEventListener('music-player-play-media', (event) => {
        const detail = event.detail || {};
        const mediaId = detail.mediaId;
        const artist = detail.artist;
        const mediaType = detail.mediaType;
        console.log(TAG, '🎵 Received play-media event:', mediaId, artist || '(no artist)', 'type:', mediaType || '(auto)');
        if (mediaId) {
            callPlayMedia(mediaId, artist, mediaType);
        } else {
            console.warn(TAG, '🎵 play-media event missing mediaId');
        }
    }, { signal: abortSignal });

    // Start subscription when HA loads
    function waitForHa() {
        const ha = document.querySelector('home-assistant');
        if (ha && ha.hass) {
            subscribeToStateChanges();
        } else {
            console.log(TAG, 'Waiting for home-assistant element...');
            setTimeout(waitForHa, 500);
        }
    }

    // Also handle location-changed event (HA navigation)
    // Only re-check state if we've already received a real state_changed event
    // (prevents showing player on initial HA navigation during startup)
    window.addEventListener('location-changed', () => {
        if (!hasReceivedRealStateChange) return;
        const hass = getHass();
        if (hass && hass.states && hass.states[currentActiveEntity]) {
            onStateChange(hass.states[currentActiveEntity]);
        }
    });

    // Start
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', waitForHa);
    } else {
        waitForHa();
    }

    console.log(TAG, '🎵 Music player subscription script loaded');
})();
