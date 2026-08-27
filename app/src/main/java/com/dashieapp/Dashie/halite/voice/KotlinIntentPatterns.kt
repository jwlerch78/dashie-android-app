package com.dashieapp.Dashie.halite.voice

import android.util.Log

/**
 * Local intent classification for voice command interception.
 *
 * Mirrors patterns from the shared JS library:
 *   @dashieapp/intent-classifier (js/vendor/dashie-shared/intent-classifier/src/patterns.js)
 *
 * Used by HaliteVoiceController to intercept voice commands BEFORE they reach
 * the Home Assistant pipeline, enabling fast local handling of timers, music,
 * and volume commands.
 */
object KotlinIntentPatterns {
    private const val TAG = "KotlinIntentPatterns"

    data class InterceptResult(
        val category: String,     // "timer", "media", "volume", "playback_mode", "speaker"
        val command: String,      // e.g. "start_timer", "pause", "next", "volume_up", "shuffle_on", "mute"
        val ttsResponse: String,  // Text to speak
        val params: Map<String, Any> = emptyMap()
    )

    // ==================== TIMER PATTERNS ====================
    // Source: @dashieapp/intent-classifier patterns.js TIMER_*

    private val TIMER_START = listOf(
        Regex("""(?:set|start|create)\s+(?:\w+\s+)?(?:\d+|one|an|half)""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)[\s-]*(?:minute|second|hour|min|sec|hr)s?[\s-]+(?:\w+\s+)?(?:timer|countdown)""", RegexOption.IGNORE_CASE),
        // "timer for <duration>": accept word-fractions parseDuration understands
        // (half an hour / an hour / a minute and a half), not just digits — else
        // "set a timer for half an hour" misses the fast-path and falls through to
        // the cloud AI, which can't create a native timer.
        Regex("""timer\s+(?:for|of)\s+(?:\d+|an?|half|one)""", RegexOption.IGNORE_CASE)
    )
    private val TIMER_PAUSE = listOf(
        Regex("""paused?\s+(?:the|my)\s+.+?\s+timer""", RegexOption.IGNORE_CASE),  // "pause the pasta timer"
        Regex("""paused?\s+timer\s+\d+""", RegexOption.IGNORE_CASE),               // "pause timer 2"
        Regex("""paused?\s*(?:the|my)?\s*timer""", RegexOption.IGNORE_CASE)         // "pause the timer"
    )
    private val TIMER_RESUME = listOf(
        Regex("""(?:resumed?|continued?|unpaused?)\s+(?:the|my)\s+.+?\s+timer""", RegexOption.IGNORE_CASE),
        Regex("""(?:resumed?|continued?|unpaused?)\s+timer\s+\d+""", RegexOption.IGNORE_CASE),
        Regex("""(?:resumed?|continued?|unpaused?)\s*(?:the|my)?\s*timer""", RegexOption.IGNORE_CASE)
    )
    private val TIMER_CANCEL = listOf(
        Regex("""(?:cancell?ed|cancell?|stopped?|deleted?|cleared?)\s+(?:the|my)\s+.+?\s*timer""", RegexOption.IGNORE_CASE),
        Regex("""(?:cancell?ed|cancell?|stopped?|deleted?|cleared?)\s+timer\s+\d+""", RegexOption.IGNORE_CASE),
        Regex("""(?:cancell?ed|cancell?|stopped?|deleted?|cleared?)\s*(?:the|my)?\s*timer""", RegexOption.IGNORE_CASE),
        Regex("""(?:cancell?ed|cancell?|stopped?)\s+(?:it|that|this)$""", RegexOption.IGNORE_CASE)
    )
    private val TIMER_QUERY = listOf(
        Regex("""how\s+(?:much|long)\s+(?:time\s+)?(?:is\s+)?(?:left|remaining)""", RegexOption.IGNORE_CASE),
        Regex("""(?:what|how\s+much)\s+time\s+(?:is\s+)?(?:left|remaining)""", RegexOption.IGNORE_CASE),
        Regex("""check\s*(?:the|my)?\s*timer""", RegexOption.IGNORE_CASE),
        Regex("""(?:what's|whats|how's|hows)\s+(?:the|my)?\s*timer""", RegexOption.IGNORE_CASE)
    )
    private val TIMER_ADD = Regex("""add\s+(?:\d+|a|an)\s*(?:minute|second|hour)""", RegexOption.IGNORE_CASE)
    private val TIMER_ADD_GENERIC = Regex("""add\s+(?:more\s+)?time\s+(?:to|on)\s+(?:the|my)?\s*timer""", RegexOption.IGNORE_CASE)
    private val TIMER_SUBTRACT = Regex("""(?:subtract|remove|take\s+off)\s+(?:\d+|a|an)\s*(?:minute|second|hour)""", RegexOption.IGNORE_CASE)

    // ==================== MEDIA PATTERNS ====================
    // GENERATED DATA (classifier Phase B, 2026-07-18): the keyword lists come from
    // GeneratedIntentData ← the shared JS canonical (patterns.js MEDIA_*), so they can't
    // drift from the kiosk/full-mode JS classifiers again (they were ~11 phrasings apart).
    // The matching LOGIC below stays hand-written.

    private val MEDIA_PAUSE_KW = GeneratedIntentData.MEDIA_PAUSE_KEYWORDS
    private val MEDIA_PLAY_KW = GeneratedIntentData.MEDIA_PLAY_KEYWORDS
    // Matched at command position only (see matchesTransportCommand). Bare
    // ambiguous words ("skip", "next") are handled by the music-playing-gated
    // FLEXIBLE_NEXT matcher, so a sentence like "when do the hurricanes play
    // next" can't be classified as a skip.
    private val MEDIA_NEXT_KW = GeneratedIntentData.MEDIA_NEXT_KEYWORDS
    private val MEDIA_PREVIOUS_KW = GeneratedIntentData.MEDIA_PREVIOUS_KEYWORDS

    // Flexible media - short phrases that only match when music is playing
    private val FLEXIBLE_NEXT = GeneratedIntentData.MEDIA_FLEXIBLE_NEXT
    private val FLEXIBLE_STOP = GeneratedIntentData.MEDIA_FLEXIBLE_STOP
    private val FLEXIBLE_PREVIOUS = GeneratedIntentData.MEDIA_FLEXIBLE_PREVIOUS
    private val FLEXIBLE_EXCLUDE = GeneratedIntentData.MEDIA_FLEXIBLE_EXCLUDE_WORDS

    // Play search patterns (with "place" for STT misrecognition of "play")
    // Optional "on/in [speaker]" suffix captured as last group
    private val SPEAKER_SUFFIX = """(?:\s+(?:on|in)\s+(?:the\s+)?(.+?)(?:\s+speaker)?)?$"""
    private val PLAY_BY_ARTIST = Regex("""^(?:play|place)\s+(?:songs?|music|something)\s+by\s+(.+?)$SPEAKER_SUFFIX""", RegexOption.IGNORE_CASE)
    private val PLAY_SONG_BY_ARTIST = Regex("""^(?:play|place)\s+(.+?)\s+by\s+(.+?)$SPEAKER_SUFFIX""", RegexOption.IGNORE_CASE)
    private val PLAY_GENERIC = Regex("""^(?:play|place)\s+(.+?)$SPEAKER_SUFFIX""", RegexOption.IGNORE_CASE)
    private val PLAY_RESUME = Regex("""^(?:play|place)$""", RegexOption.IGNORE_CASE)

    // ==================== SHUFFLE/REPEAT PATTERNS ====================

    private val SHUFFLE_ON_KW = listOf(
        "shuffle on", "turn on shuffle", "enable shuffle", "shuffle mode on",
        "shuffle the music", "shuffle songs", "start shuffle", "shuffle please"
    )
    private val SHUFFLE_OFF_KW = listOf(
        "shuffle off", "turn off shuffle", "disable shuffle", "shuffle mode off",
        "stop shuffle", "no shuffle", "unshuffle"
    )
    private val REPEAT_ONE_KW = listOf(
        "repeat this song", "repeat this track", "loop this song", "loop this track",
        "repeat one", "repeat song", "loop song", "play this on repeat"
    )
    private val REPEAT_ALL_KW = listOf(
        "repeat on", "turn on repeat", "enable repeat", "repeat all",
        "loop on", "turn on loop", "repeat mode on", "loop all"
    )
    private val REPEAT_OFF_KW = listOf(
        "repeat off", "turn off repeat", "disable repeat", "repeat mode off",
        "loop off", "turn off loop", "stop repeat", "stop looping", "no repeat"
    )

    // ==================== MUTE/UNMUTE PATTERNS ====================

    private val MUTE_SPEAKER = Regex("""^mute\s+(?:the\s+)?(.+?)(?:\s+speaker)?$""", RegexOption.IGNORE_CASE)
    private val UNMUTE_SPEAKER = Regex("""^unmute\s+(?:the\s+)?(.+?)(?:\s+speaker)?$""", RegexOption.IGNORE_CASE)

    // ==================== VOLUME PATTERNS ====================
    // GENERATED DATA: system + music volume lists from GeneratedIntentData (the shared
    // JS canonical keeps them as two lists; JS routes music-volume through the media
    // classifier and system-volume separately). Kotlin has ONE volume classifier, so it
    // UNIONS them — a documented consumption difference in hand-written logic, not a
    // silently different list.
    private val VOLUME_UP_KW =
        (GeneratedIntentData.VOLUME_UP_KEYWORDS + GeneratedIntentData.MEDIA_VOLUME_UP_KEYWORDS).distinct()
    private val VOLUME_DOWN_KW =
        (GeneratedIntentData.VOLUME_DOWN_KEYWORDS + GeneratedIntentData.MEDIA_VOLUME_DOWN_KEYWORDS).distinct()
    private val VOLUME_SET = Regex("""(?:set\s+)?volume\s+(?:to\s+)?(\d+)""", RegexOption.IGNORE_CASE)

    // ==================== VIDEO FEED PATTERNS ====================

    private val VIDEO_FEED_SHOW_VERBS = listOf("show", "display", "open", "view", "pull up", "bring up")
    private val VIDEO_FEED_DISMISS_VERBS = listOf("hide", "dismiss", "close", "remove", "turn off")
    private val VIDEO_FEED_CONTEXT_WORDS = listOf("video feed", "video feeds", "camera", "cameras", "feed", "feeds")
    private val VIDEO_FEED_ALL_WORDS = listOf("all the", "all my", "every", "all")

    // ==================== ALARM DISMISS ====================
    // Broad dismiss words — only treated as "stop the alarm" WHILE an alarm is
    // ringing (otherwise bare "stop"/"cancel" would hijack music/cancel commands).
    private val ALARM_DISMISS = listOf(
        "stop", "stop it", "stop the alarm", "stop alarm",
        "ok", "okay", "dismiss", "cancel", "enough",
        "silence", "quiet", "turn it off"
    )

    // Explicit alarm phrases — these literally name the alarm, so they intercept
    // as a graceful no-op even when NO alarm is ringing. The wake word already
    // silences a ringing alarm, so a follow-up "stop the alarm" should act as if
    // it succeeded instead of falling through to HA ("I don't see a timer").
    private val ALARM_STOP_EXPLICIT = listOf(
        "stop the alarm", "stop alarm", "cancel the alarm", "cancel alarm",
        "turn off the alarm", "turn the alarm off", "dismiss the alarm",
        "silence the alarm"
    )

    // ==================== SCHEDULED ACTIONS (reminders) ====================
    // Phase 1: relative-time NOTIFY reminders intercepted on-device (the timer
    // fast-path shape) — "remind me to <text> in <duration>".
    // See .reference/build-plans/20260627_SCHEDULED_ACTIONS_PHASE1.md.

    private val SCHEDULE_TRIGGER = listOf(
        Regex("""\bremind\s+(?:me|us)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bset\s+(?:a|an)\s+reminder\b""", RegexOption.IGNORE_CASE),
    )

    // ==================== NUMBER NORMALIZATION ====================
    // The number-word VALUES are generated from the shared JS canonical
    // (GeneratedIntentData ← duration-data.js); the normalize LOGIC below stays
    // hand-written. Contract #6; compound-number behavior pinned by the golden vectors.

    private val WORD_NUMBERS = GeneratedIntentData.WORD_NUMBERS

    fun normalizeWordNumbers(text: String): String {
        var result = text.lowercase()

        // Strip polite prefixes that don't change intent (matches JS IntentClassifier)
        result = result
            .replace(Regex("""^(can you|could you|will you|would you|please|hey|ok|okay)\s+"""), "")
            .replace(Regex("""\s+(please)$"""), "")
            .trim()

        // Fix common STT mishearings
        result = result.replace(Regex("""\bset at\b"""), "set a")
        result = result.replace(Regex("""\bstart at\b"""), "start a")

        // Compound tens+ones FIRST ("twenty five" → 25), then bare tens — mirrors the JS
        // normalizeWordNumbers. Without this, "twenty five minutes" became "20 5 minutes" and
        // parseDuration matched "5 minute" → a 25-min timer silently became 5 min (golden-vector bug).
        val tens = WORD_NUMBERS.filterValues { it >= 20 }   // twenty..ninety
        val ones = WORD_NUMBERS.filterValues { it in 1..9 } // one..nine
        for ((tenWord, tenVal) in tens) {
            for ((oneWord, oneVal) in ones) {
                result = result.replace(
                    Regex("""\b${Regex.escape(tenWord)}[\s-]?${Regex.escape(oneWord)}\b""", RegexOption.IGNORE_CASE),
                    (tenVal + oneVal).toString()
                )
            }
            result = result.replace(Regex("""\b${Regex.escape(tenWord)}\b""", RegexOption.IGNORE_CASE), tenVal.toString())
        }

        // Remaining single words (0–19), longest first ("thirteen" before "three").
        for ((word, num) in WORD_NUMBERS.filterValues { it < 20 }.entries.sortedByDescending { it.key.length }) {
            result = result.replace(Regex("\\b${Regex.escape(word)}\\b"), num.toString())
        }
        return result
    }

    // ==================== DURATION PARSING ====================

    data class Duration(val hours: Int, val minutes: Int, val seconds: Int) {
        val totalSeconds: Int get() = hours * 3600 + minutes * 60 + seconds
    }

    fun parseDuration(text: String): Duration? {
        // ⚠️ DUPLICATED LOGIC — parseDuration is copy-pasted across 4 trees: this
        // Kotlin native copy, js/core/intent-classifier, js/vendor/dashie-shared,
        // and kiosk-overlay/js/vendor. A behavior change here MUST be mirrored in
        // all four or voice timers diverge by platform — the "half an hour" = 90min
        // bug survived in every copy for exactly this reason.
        // See .reference/_TECHNICAL_DEBT.md → "Timer & duration-parse duplication".
        val norm = text.lowercase()
        var hours = 0; var minutes = 0; var seconds = 0

        // Unit synonyms + fractional phrases come from GeneratedIntentData (the
        // generated SSOT). The branching LOGIC stays hand-written.
        val d = GeneratedIntentData
        val unitRe = { units: List<String> -> Regex("""(\d+)[\s-]*(?:${units.joinToString("|")})s?""") }

        // "half an hour" contains the substring "an hour" — guard so it doesn't
        // also trip the 1-hour branch and yield 90 minutes (see half-hour test).
        val isHalfHour = d.HALF_HOUR_PHRASES.any { norm.contains(it) }

        if (!isHalfHour && d.WHOLE_HOUR_PHRASES.any { norm.contains(it) }) hours = 1
        else if (!isHalfHour) unitRe(d.HOUR_UNITS).find(norm)?.let { hours = it.groupValues[1].toIntOrNull() ?: 0 }

        if (isHalfHour) minutes = d.HALF_HOUR_MINUTES
        else if (norm.contains(d.MINUTE_AND_HALF_PHRASE)) { minutes = d.MINUTE_AND_HALF_MINUTES; seconds = d.MINUTE_AND_HALF_SECONDS }
        else unitRe(d.MINUTE_UNITS).find(norm)?.let { minutes = it.groupValues[1].toIntOrNull() ?: 0 }

        unitRe(d.SECOND_UNITS).find(norm)?.let { seconds = it.groupValues[1].toIntOrNull() ?: 0 }

        val total = hours * 3600 + minutes * 60 + seconds
        return if (total > 0) Duration(hours, minutes, seconds) else null
    }

    fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return buildString {
            if (h > 0) append("$h hour${if (h > 1) "s" else ""} ")
            if (m > 0) append("$m minute${if (m > 1) "s" else ""} ")
            if (s > 0 && h == 0) append("$s second${if (s > 1) "s" else ""}")
        }.trim()
    }

    // ==================== TIMER REFERENCE PARSING ====================

    /**
     * Extract a timer reference (by name or slot number) from the text.
     * Returns a map with optional "timerName" and/or "timerSlot" keys.
     *
     * Examples:
     *   "pause the pasta timer"  → { timerName: "pasta" }
     *   "pause timer 2"          → { timerSlot: 2 }
     *   "add a minute to timer 1" → { timerSlot: 1 }
     *   "pause the timer"         → {} (no specific reference)
     */
    private fun parseTimerReference(normalized: String): Map<String, Any> {
        // Slot reference: "timer 2", "timer 1"
        Regex("""timer\s+(\d+)""").find(normalized)?.let {
            val slot = it.groupValues[1].toIntOrNull()
            if (slot != null) return mapOf("timerSlot" to slot)
        }

        // Name reference: "the pasta timer", "my egg timer"
        Regex("""(?:the|my)\s+(.+?)\s+timer""").find(normalized)?.let {
            val name = it.groupValues[1].trim()
            // Filter out duration-only phrases (e.g., "the 5 minute timer")
            if (name.isNotBlank() && !name.matches(Regex("""^(\d+\s*(?:minute|second|hour|min|sec|hr)s?)$""", RegexOption.IGNORE_CASE))) {
                return mapOf("timerName" to name)
            }
        }

        return emptyMap()
    }

    // ==================== MAIN CLASSIFIER ====================

    /**
     * Realtime "conversation mode" trigger phrases. Kept tight to avoid
     * accidental entry (build plan §3.1 / open decision #7). The pipeline only
     * consults this when conversation mode is enabled (a Live model is selected).
     */
    private val CONVERSATION_MODE_TRIGGERS = listOf(
        Regex("""\bconversation\s+mode\b""", RegexOption.IGNORE_CASE),
        Regex("""\blive\s+mode\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgo\s+live\b""", RegexOption.IGNORE_CASE),
        // "have a conversation", "let's have a conversation", "have a conversation with me"
        Regex("""\b(?:let'?s\s+)?have\s+a\s+conversation(?:\s+with\s+me)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\blet'?s\s+have\s+conversation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bstart\s+a\s+conversation\b""", RegexOption.IGNORE_CASE),
        Regex("""\blet'?s\s+(?:have\s+a\s+)?chat\b""", RegexOption.IGNORE_CASE),
        Regex("""\blet'?s\s+talk\b""", RegexOption.IGNORE_CASE),
    )

    /** True if the utterance is a request to enter realtime conversation mode. */
    fun isConversationModeTrigger(transcript: String): Boolean {
        val t = transcript.lowercase().trim().trimEnd('.', '?', '!', ',')
        return CONVERSATION_MODE_TRIGGERS.any { it.containsMatchIn(t) }
    }

    /**
     * Classify a voice transcript into a local command intent.
     *
     * @param transcript Raw STT text
     * @param musicPlaying Whether music is currently playing (enables flexible media matching)
     * @param alarmPlaying Whether a timer alarm is currently sounding
     * @param cachedTimerRemaining Cached remaining seconds from last timer update (for queries)
     * @return InterceptResult if a local command was matched, null to let HA handle it
     */
    fun classify(
        transcript: String,
        musicPlaying: Boolean = false,
        alarmPlaying: Boolean = false,
        cachedTimerRemaining: Int? = null
    ): InterceptResult? {
        val normalized = normalizeWordNumbers(
            transcript.lowercase().trim().trimEnd('.', '?', '!', ',')
        )

        // 1. Alarm dismiss (highest priority)
        if (alarmPlaying) {
            classifyAlarmDismiss(normalized)?.let { return it }
        }

        // 1b. Explicit "stop the alarm" intercepts even when nothing is ringing —
        // the wake word likely just silenced it, so acknowledge success (no-op)
        // instead of falling through to HA. Bare "stop"/"cancel" stay gated above.
        if (ALARM_STOP_EXPLICIT.any { normalized == it || normalized.startsWith(it) }) {
            return InterceptResult("timer", "stop_alarm", "")
        }

        // 1c. Scheduled actions / reminders ("remind me to … in <duration>")
        classifySchedule(normalized)?.let { return it }

        // 2. Timer commands
        classifyTimer(normalized, cachedTimerRemaining)?.let { return it }

        // 3. Mute/unmute speaker (before media, so "mute kitchen" doesn't fall through)
        classifySpeakerMute(normalized)?.let { return it }

        // 4. Media commands (explicit keywords, now with optional speaker targeting)
        classifyMedia(normalized)?.let { return it }

        // 5. Shuffle/repeat (only when music is playing)
        if (musicPlaying) {
            classifyPlaybackMode(normalized)?.let { return it }
        }

        // 6. Flexible media (only when music is playing)
        if (musicPlaying) {
            classifyFlexibleMedia(normalized)?.let { return it }
        }

        // 6b. Open external app by name (native-only — not in the JS shared patterns).
        //     Runs before video feed so "open netflix" launches the app rather
        //     than being read as a camera name. Logic lives in OpenAppClassifier.
        OpenAppClassifier.classify(normalized)?.let { return it }

        // 7. Video feed commands (show/hide cameras)
        classifyVideoFeed(normalized)?.let { return it }

        // 8. Volume commands
        classifyVolume(normalized)?.let { return it }

        return null
    }

    // ==================== INDIVIDUAL CLASSIFIERS ====================

    private fun classifyAlarmDismiss(normalized: String): InterceptResult? {
        val matches = ALARM_DISMISS.any { normalized == it || normalized.startsWith(it) }
        if (!matches) return null
        return InterceptResult("timer", "stop_alarm", "")
    }

    private fun classifyTimer(normalized: String, cachedRemaining: Int?): InterceptResult? {
        Log.d(TAG, "classifyTimer: normalized='$normalized', pause=${TIMER_PAUSE.any { it.containsMatchIn(normalized) }}, resume=${TIMER_RESUME.any { it.containsMatchIn(normalized) }}, cancel=${TIMER_CANCEL.any { it.containsMatchIn(normalized) }}, add=${TIMER_ADD.containsMatchIn(normalized)}, addGeneric=${TIMER_ADD_GENERIC.containsMatchIn(normalized)}")

        // Start timer
        if (TIMER_START.any { it.containsMatchIn(normalized) }) {
            val duration = parseDuration(normalized) ?: return null
            val desc = parseTimerDescription(normalized)
            val descStr = if (desc != null) " for $desc" else ""
            return InterceptResult(
                "timer", "start_timer",
                "Setting a ${formatDuration(duration.totalSeconds)} timer$descStr.",
                mapOf(
                    "durationSeconds" to duration.totalSeconds,
                    "description" to (desc ?: "")
                )
            )
        }

        // Extract timer reference (name or slot) for targeted commands
        val timerRef = parseTimerReference(normalized)

        // Pause timer
        if (TIMER_PAUSE.any { it.containsMatchIn(normalized) }) {
            return InterceptResult("timer", "pause_timer", "Timer paused.", timerRef)
        }

        // Resume timer
        if (TIMER_RESUME.any { it.containsMatchIn(normalized) }) {
            return InterceptResult("timer", "resume_timer", "Timer resumed.", timerRef)
        }

        // Cancel timer (check before flexible media "stop")
        if (TIMER_CANCEL.any { it.containsMatchIn(normalized) }) {
            return InterceptResult("timer", "cancel_timer", "Timer cancelled.", timerRef)
        }

        // Query time
        if (TIMER_QUERY.any { it.containsMatchIn(normalized) }) {
            val response = if (cachedRemaining != null && cachedRemaining > 0) {
                "${formatDuration(cachedRemaining)} remaining on your timer."
            } else {
                "You don't have any active timers."
            }
            return InterceptResult("timer", "query_time", response)
        }

        // Add time (with specific amount: "add 5 minutes")
        if (TIMER_ADD.containsMatchIn(normalized)) {
            val duration = parseDuration(normalized) ?: return null
            return InterceptResult(
                "timer", "add_time",
                "Added ${formatDuration(duration.totalSeconds)} to your timer.",
                timerRef + mapOf("addSeconds" to duration.totalSeconds)
            )
        }

        // Add time (generic: "add time to the timer" → default 1 minute)
        if (TIMER_ADD_GENERIC.containsMatchIn(normalized)) {
            return InterceptResult(
                "timer", "add_time",
                "Added 1 minute to your timer.",
                timerRef + mapOf("addSeconds" to 60)
            )
        }

        // Subtract time
        if (TIMER_SUBTRACT.containsMatchIn(normalized)) {
            val duration = parseDuration(normalized) ?: return null
            return InterceptResult(
                "timer", "subtract_time",
                "Removed ${formatDuration(duration.totalSeconds)} from your timer.",
                timerRef + mapOf("subtractSeconds" to duration.totalSeconds)
            )
        }

        return null
    }

    private fun classifySchedule(normalized: String): InterceptResult? {
        if (SCHEDULE_TRIGGER.none { it.containsMatchIn(normalized) }) return null

        // Phase 1 handles relative durations only ("in 30 seconds"); without a
        // parseable duration we defer (absolute "at 6am" is a later phase).
        val duration = parseDuration(normalized) ?: return null

        val reminderText = parseReminderText(normalized)
        val whenStr = formatDuration(duration.totalSeconds)
        val ttsResponse = if (reminderText != null) {
            "Okay, I'll remind you to $reminderText in $whenStr."
        } else {
            "Okay, I'll remind you in $whenStr."
        }
        return InterceptResult(
            "schedule", "create_reminder", ttsResponse,
            mapOf(
                "delaySeconds" to duration.totalSeconds,
                "reminderText" to (reminderText ?: ""),
                "vernacular" to "reminder"
            )
        )
    }

    /**
     * Pull the reminder subject out of a "remind me …" utterance. The transcript is
     * already word-number normalized ("thirty"→"30"), so the relative-time clause is
     * numeric.
     */
    private fun parseReminderText(normalized: String): String? {
        // "remind me to <text> in 30 seconds"
        Regex("""remind\s+(?:me|us)\s+to\s+(.+?)\s+in\s+\d""", RegexOption.IGNORE_CASE)
            .find(normalized)?.let { return it.groupValues[1].trim().ifBlank { null } }
        // "remind me in 30 seconds to <text>"
        Regex("""remind\s+(?:me|us)\s+in\s+.+?\s+to\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(normalized)?.let { return it.groupValues[1].trim().ifBlank { null } }
        // "remind me to <text>" — strip a trailing relative-time clause if present
        Regex("""remind\s+(?:me|us)\s+to\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(normalized)?.let {
                val t = it.groupValues[1].trim()
                    .replace(Regex("""\s+in\s+(?:\d+|an?|half).*$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                return t.ifBlank { null }
            }
        return null
    }

    private fun parseTimerDescription(normalized: String): String? {
        // "set a PASTA timer for 5 minutes"
        Regex("""(?:set|start|create)\s+(?:a|an)\s+(.+?)\s+timer\s+(?:for|of)""", RegexOption.IGNORE_CASE)
            .find(normalized)?.let {
                val desc = it.groupValues[1].trim()
                // Skip if it looks like a duration (e.g., "5 minute", "2-minute pasta" starts with duration)
                if (desc.isNotBlank()
                    && !desc.matches(Regex("""^(\d+|one|two|three|four|five|half|an?)[\s-]*(?:minute|second|hour|min|sec|hr)?s?$""", RegexOption.IGNORE_CASE))
                    && !desc.matches(Regex("""^\d+[\s-]*(?:minute|second|hour|min|sec|hr)s?\s+.+""", RegexOption.IGNORE_CASE))
                ) {
                    return desc
                }
            }
        // "set a 5 minute timer for PASTA"
        Regex("""(?:timer|countdown|minutes?|seconds?|hours?)\s+for\s+(.+?)$""", RegexOption.IGNORE_CASE)
            .find(normalized)?.let {
                val desc = it.groupValues[1].trim()
                if (desc.isNotBlank() && !desc.matches(Regex("""^\d+$"""))) return desc
            }
        // "set a 5 minute PASTA timer" — description between duration and "timer"
        Regex("""(?:minute|second|hour|min|sec|hr)s?\s+(.+?)\s+timer""", RegexOption.IGNORE_CASE)
            .find(normalized)?.let {
                val desc = it.groupValues[1].trim()
                if (desc.isNotBlank() && !desc.matches(Regex("""^(\d+|a|an)$""", RegexOption.IGNORE_CASE))) {
                    return desc
                }
            }
        return null
    }

    /**
     * Match a transport-control keyword only at COMMAND POSITION — the
     * normalized utterance equals the keyword or starts with it. Prevents a
     * keyword buried in a longer sentence ("when do the hurricanes play next",
     * "what's the next song") from being treated as a skip/previous command.
     * Mirrors matchesTransportCommand in the JS music-intents.js classifier.
     */
    private fun matchesTransportCommand(normalized: String, keywords: List<String>): Boolean {
        return keywords.any { normalized == it || normalized.startsWith("$it ") }
    }

    private fun classifyMedia(normalized: String): InterceptResult? {
        // Pause/stop music
        if (MEDIA_PAUSE_KW.any { normalized.contains(it) || normalized == it }) {
            return InterceptResult("media", "pause", "Pausing music.")
        }

        // Next track — command position only, so a keyword buried in a longer
        // sentence ("when do the hurricanes play next") isn't treated as a skip.
        if (matchesTransportCommand(normalized, MEDIA_NEXT_KW)) {
            return InterceptResult("media", "next", "Skipping to next track.")
        }

        // Previous track — command position only.
        if (matchesTransportCommand(normalized, MEDIA_PREVIOUS_KW)) {
            return InterceptResult("media", "previous", "Going to previous track.")
        }

        // Simple play/resume
        if (MEDIA_PLAY_KW.any { normalized.contains(it) || normalized == it }) {
            return InterceptResult("media", "play", "Resuming music.")
        }

        // Play resume (bare "play" or "place")
        if (PLAY_RESUME.matches(normalized)) {
            return InterceptResult("media", "play", "Resuming music.")
        }

        // Play by artist: "play songs by Taylor Swift [on kitchen]"
        PLAY_BY_ARTIST.find(normalized)?.let {
            val artist = it.groupValues[1].trim()
            val speaker = it.groupValues[2].trim().takeIf { s -> s.isNotEmpty() }
            val tts = if (speaker != null) "Playing $artist on $speaker." else "Playing $artist."
            val params = mutableMapOf<String, Any>("mediaId" to artist, "mediaType" to "artist")
            if (speaker != null) params["targetSpeaker"] = speaker
            return InterceptResult("media", "play_search", tts, params)
        }

        // Play song by artist: "play Shake It Off by Taylor Swift [on kitchen]"
        PLAY_SONG_BY_ARTIST.find(normalized)?.let {
            val song = it.groupValues[1].trim()
            val artist = it.groupValues[2].trim()
            val speaker = it.groupValues[3].trim().takeIf { s -> s.isNotEmpty() }
            val tts = if (speaker != null) "Playing $song by $artist on $speaker." else "Playing $song by $artist."
            val params = mutableMapOf<String, Any>("mediaId" to song, "artist" to artist)
            if (speaker != null) params["targetSpeaker"] = speaker
            return InterceptResult("media", "play_search", tts, params)
        }

        // Generic play: "play jazz [on kitchen]"
        PLAY_GENERIC.find(normalized)?.let {
            val query = it.groupValues[1].trim()
            val speaker = it.groupValues[2].trim().takeIf { s -> s.isNotEmpty() }
            if (query != "music" && query.isNotBlank()) {
                val tts = if (speaker != null) "Playing $query on $speaker." else "Playing $query."
                val params = mutableMapOf<String, Any>("mediaId" to query)
                if (speaker != null) params["targetSpeaker"] = speaker
                return InterceptResult("media", "play_search", tts, params)
            }
        }

        return null
    }

    private fun classifyFlexibleMedia(normalized: String): InterceptResult? {
        // Don't match if transcript contains non-music words
        if (FLEXIBLE_EXCLUDE.any { normalized.contains(it) }) return null

        val firstWord = normalized.split(Regex("""\s+""")).firstOrNull() ?: return null

        if (firstWord in FLEXIBLE_NEXT) {
            return InterceptResult("media", "next", "Skipping to next track.")
        }
        if (firstWord in FLEXIBLE_STOP) {
            return InterceptResult("media", "pause", "Pausing music.")
        }
        if (firstWord in FLEXIBLE_PREVIOUS) {
            return InterceptResult("media", "previous", "Going to previous track.")
        }

        return null
    }

    private fun classifyPlaybackMode(normalized: String): InterceptResult? {
        // Shuffle on
        if (SHUFFLE_ON_KW.any { normalized.contains(it) || normalized == it }) {
            return InterceptResult("playback_mode", "shuffle_on", "Shuffle on.")
        }
        // Shuffle off
        if (SHUFFLE_OFF_KW.any { normalized.contains(it) || normalized == it }) {
            return InterceptResult("playback_mode", "shuffle_off", "Shuffle off.")
        }
        // Repeat one (check before repeat all — "repeat this song" must not match "repeat on")
        if (REPEAT_ONE_KW.any { normalized.contains(it) || normalized == it }) {
            return InterceptResult("playback_mode", "repeat_one", "Repeating this song.")
        }
        // Repeat all
        if (REPEAT_ALL_KW.any { normalized.contains(it) || normalized == it }) {
            return InterceptResult("playback_mode", "repeat_all", "Repeat on.")
        }
        // Repeat off
        if (REPEAT_OFF_KW.any { normalized.contains(it) || normalized == it }) {
            return InterceptResult("playback_mode", "repeat_off", "Repeat off.")
        }
        return null
    }

    private fun classifySpeakerMute(normalized: String): InterceptResult? {
        MUTE_SPEAKER.find(normalized)?.let {
            val speaker = it.groupValues[1].trim()
            if (speaker.isNotBlank()) {
                return InterceptResult(
                    "speaker", "mute", "Muting $speaker.",
                    mapOf("targetSpeaker" to speaker)
                )
            }
        }
        UNMUTE_SPEAKER.find(normalized)?.let {
            val speaker = it.groupValues[1].trim()
            if (speaker.isNotBlank()) {
                return InterceptResult(
                    "speaker", "unmute", "Unmuting $speaker.",
                    mapOf("targetSpeaker" to speaker)
                )
            }
        }
        return null
    }

    private fun classifyVideoFeed(normalized: String): InterceptResult? {
        // Must contain a camera/feed context word
        val hasContext = VIDEO_FEED_CONTEXT_WORDS.any { normalized.contains(it) }
        if (!hasContext) return null

        val isShow = VIDEO_FEED_SHOW_VERBS.any { normalized.startsWith("$it ") }
        val isDismiss = VIDEO_FEED_DISMISS_VERBS.any { normalized.startsWith("$it ") }
        if (!isShow && !isDismiss) return null

        // Check for "all" modifier
        val isAll = VIDEO_FEED_ALL_WORDS.any { normalized.contains(it) }

        if (isAll) {
            return if (isDismiss) {
                InterceptResult("videofeed", "dismiss_all", "Hiding all cameras.")
            } else {
                InterceptResult("videofeed", "show_all", "Showing all cameras.")
            }
        }

        // Extract feed name by stripping verb, articles, and context words
        val feedName = extractVideoFeedName(normalized, isShow)

        return if (isDismiss) {
            InterceptResult(
                "videofeed", "dismiss", "Hiding camera.",
                if (feedName != null) mapOf("feedName" to feedName) else emptyMap()
            )
        } else {
            InterceptResult(
                "videofeed", "show", if (feedName != null) "Showing $feedName camera." else "Showing camera.",
                if (feedName != null) mapOf("feedName" to feedName) else emptyMap()
            )
        }
    }

    /**
     * Extract the camera/feed name from a normalized transcript.
     * Strips verbs, articles, and context words to isolate the name.
     * "show the pool camera" → "pool"
     * "pull up front door feed" → "front door"
     */
    private fun extractVideoFeedName(normalized: String, isShow: Boolean): String? {
        var name = normalized

        // Strip the leading verb
        val verbs = if (isShow) VIDEO_FEED_SHOW_VERBS else VIDEO_FEED_DISMISS_VERBS
        for (verb in verbs.sortedByDescending { it.length }) {
            if (name.startsWith("$verb ")) {
                name = name.removePrefix("$verb ").trim()
                break
            }
        }

        // Strip articles
        name = name.replace(Regex("""^(the|my|our|me the|me my|me)\s+"""), "")

        // Strip context words (longest first)
        for (word in VIDEO_FEED_CONTEXT_WORDS.sortedByDescending { it.length }) {
            name = name.replace(Regex("""\b${Regex.escape(word)}\b"""), "")
        }

        return name.trim().takeIf { it.isNotEmpty() }
    }

    private fun classifyVolume(normalized: String): InterceptResult? {
        // Set volume (most specific)
        VOLUME_SET.find(normalized)?.let {
            val level = it.groupValues[1].toIntOrNull() ?: return null
            return InterceptResult(
                "volume", "volume_set", "Volume set to $level.",
                mapOf("level" to level)
            )
        }

        // Volume up
        if (VOLUME_UP_KW.any { normalized.contains(it) || normalized == it }) {
            return InterceptResult("volume", "volume_up", "Turning it up.")
        }

        // Volume down
        if (VOLUME_DOWN_KW.any { normalized.contains(it) || normalized == it }) {
            return InterceptResult("volume", "volume_down", "Turning it down.")
        }

        return null
    }
}
