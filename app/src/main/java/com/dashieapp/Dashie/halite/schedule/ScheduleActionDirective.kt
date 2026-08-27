package com.dashieapp.Dashie.halite.schedule

import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * Handles the brain's `schedule_action` client tool (WS5-a AI callbacks):
 * "tell me at 9:30 each night if the garage door is open" → brain extracts
 * {time, recurrence, prompt, label} → this creates the device-owned action
 * and returns the spoken ack. [manager] is wired statically from
 * VoiceComponentWiring (same pattern as ScheduledActionReceiver.onFire) so
 * VoicePipelineCoordinator's dispatch stays a 4-line branch.
 *
 * Query shape (contract with js/ai/tools/tool-schemas.js — registry row in
 * .reference/JS_KOTLIN_CONTRACTS.md):
 *   { time: "HH:MM" (24h local), recurrence: "once"|"daily",
 *     prompt: "<what Dashie should check/do at fire time>",
 *     label?: "<short confirmation phrase>" }
 */
object ScheduleActionDirective {

    @Volatile
    var manager: ScheduledActionManager? = null

    /**
     * Static AI-turn injector, wired once in VoiceComponentWiring. Returns true
     * if the voice pipeline was available to accept the injection. Static (not a
     * manager instance field) so it survives ScheduledActionManager recreation,
     * and null until voice wiring runs — a fire shortly after a process restart
     * (memory kill on constrained tablets) gets `null → false` and the manager
     * retries until voice init wires it, instead of failing to a "couldn't run"
     * card. See ScheduledActionManager.fireAiTurn.
     */
    @Volatile
    var injectAiTurn: ((String) -> Boolean)? = null

    // Anti-recursion guard. A fire-time AI-turn injection replays the stored
    // prompt through the brain. If any brain re-classifies that replay as a
    // schedule_action (e.g. the stored prompt still carries the time clause),
    // it would create a NEW action — compounding every fire. beginInjectedTurn()
    // arms a short window; a schedule_action inside it is refused ONLY when it is
    // re-scheduling the very text we just replayed. Model-INDEPENDENT by
    // construction: it never trusts how the brain routed the replay.
    //
    // The window alone is NOT enough to identify the culprit: it's wall-clock, so a
    // *user* who asks to schedule something while an unrelated action happens to fire
    // (daily recurrences fire every night) had their request silently swallowed —
    // return "" means no card, no speech, no error. Hence the prompt match: the
    // recursion case is by definition the same work replaying, so anything else in
    // the window is a real user request and must pass through.
    @Volatile
    private var suppressCreateUntilMs = 0L

    /** The text the in-flight fire-time turn replayed — what a self-reschedule would echo. */
    @Volatile
    private var injectedPrompt: String? = null

    /** Call immediately before injecting a fire-time AI-turn prompt. */
    fun beginInjectedTurn(injectedText: String) {
        injectedPrompt = injectedText
        suppressCreateUntilMs = System.currentTimeMillis() + INJECT_GUARD_WINDOW_MS
    }

    /** Case/punctuation/whitespace-insensitive form for the self-reschedule compare. */
    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Is [newPrompt] the same work the fire-time turn just replayed (the replay
     * re-scheduling ITSELF), rather than an unrelated request the user made while the
     * window happened to be open? Containment in either direction, because the brain
     * typically re-extracts a sub-phrase of the injected text — a replay of
     * "tell me a joke in 5 minutes" comes back as prompt "tell me a joke".
     */
    private fun isSelfReschedule(newPrompt: String): Boolean {
        val injected = norm(injectedPrompt ?: return false)
        val candidate = norm(newPrompt)
        if (injected.isEmpty() || candidate.isEmpty()) return false
        return injected.contains(candidate) || candidate.contains(injected)
    }

    /** The most recently FIRED action's label + fire time — stamped by the manager just before
     *  dispatch (noteFiring) so the async card callbacks (JS-routed for HA commands) can title
     *  the card with WHAT was asked and WHEN it ran. One fire at a time in practice; a stale
     *  read would only mis-title a card, never mis-run an action. */
    data class FiredMeta(val label: String, val atMs: Long)
    @Volatile
    private var lastFired: FiredMeta? = null

    fun noteFiring(action: ScheduledAction) {
        lastFired = FiredMeta(action.label, System.currentTimeMillis())
    }

    private fun firedTitle(): String? = lastFired?.label?.takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase() }

    /** Static bridge so the (size-gated) coordinator can show a fired AI-turn's
     *  answer in the reminder card without new callback wiring.
     *
     *  NOT gated on the conversation — an AI-turn's card must appear WITH its speech, and the
     *  turn itself was already parked behind any live chat (VoiceComponentWiring.injectAiTurn).
     *  The scheduled-HA-command card is the one that needs gating — see [showScheduledHaCard]. */
    fun showAnnouncementCard(text: String?, isHaCommand: Boolean = false) {
        manager?.showAnnouncementCard(text, isHaCommand, firedTitle(), lastFired?.atMs ?: System.currentTimeMillis())
    }

    /**
     * The device's whole scheduled-action store, as a JSON array — read by reminder-sync's mirror
     * REPAIR pass (device → cloud).
     *
     * The mirror is fire-and-forget: if the upsert fails (no network, edge 5xx, app killed
     * mid-flight) the event is simply lost and nothing ever heals it — a fire during a network blip
     * left the cloud row 'scheduled' with a past fire_at FOREVER, so the console showed an action
     * that already ran as still pending. The repair pass pushes the device's CURRENT STATE rather
     * than replaying missed events, so it's self-correcting: a stale queued 'fired' can't clobber a
     * newer cancel.
     */
    fun allActionsJson(): String {
        val mgr = manager ?: return "[]"
        val arr = org.json.JSONArray()
        mgr.all().forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    /** Park presentation until the user isn't mid-conversation. Wired to the voice pipeline's
     *  idle gate in VoiceComponentWiring; returns false if there's no pipeline to gate on. */
    @Volatile
    var deferUntilIdle: ((String, () -> Unit) -> Boolean)? = null

    /** CREATION-time confirmation card ("Reminder · Today at 5:12 PM"), wired in
     *  VoiceComponentWiring to the voice indicator. Parity with full mode's creation text line
     *  (reminder-action-handler.js) — before this a brain-scheduled action was voice-only until
     *  it fired. Fire-time cards are separate (showAnnouncementCard / showScheduledHaCard). */
    @Volatile
    var showCreatedCard: ((message: String, detail: String) -> Unit)? = null

    /**
     * A scheduled HA command (kind='command') ran — show its HA-branded confirmation.
     *
     * The COMMAND already ran on time (the porch light goes off at 9:30 whether or not you're
     * mid-chat — an action must never wait on a conversation). Only the CARD is parked, so it
     * can't drop a modal over a live chat and dim it. Action and notification have different
     * timing requirements; this is the seam between them.
     */
    fun showScheduledHaCard(message: String?) {
        // Capture the fired meta NOW — the card may be parked behind a live chat for minutes,
        // and a later fire would otherwise re-title it.
        val title = firedTitle()
        val at = lastFired?.atMs ?: System.currentTimeMillis()
        val show = { manager?.showAnnouncementCard(message, isHaCommand = true, title = title, firedAtMs = at); Unit }
        if (deferUntilIdle?.invoke("scheduled-ha-card", show) != true) show()
    }

    /** A fired action FAILED — small dismissible corner card (see ScheduledErrorCard). */
    fun showErrorCard(text: String?, isHaCommand: Boolean = false) {
        manager?.showErrorCard(text, isHaCommand)
    }

    /** A scheduled HA command FAILED. Parked behind a live chat like its success twin. */
    fun showScheduledHaError(message: String?) {
        // Stamp the failure NOW, not when the card is finally drawn — the gate can park this
        // behind a conversation for minutes, and a card that says "just now" about something
        // that broke five minutes ago is worse than no timestamp.
        val at = System.currentTimeMillis()
        val show = { manager?.showErrorCard(message, isHaCommand = true, occurredAtMs = at); Unit }
        if (deferUntilIdle?.invoke("scheduled-ha-error", show) != true) show()
    }

    /**
     * Static HA-command injector (kind='command'), wired in VoiceComponentWiring
     * to `window.dashieRunScheduledHaCommand`. Runs the command DIRECTLY against
     * HA Assist at fire time — no classifier replay, no live-command card — and
     * the JS side calls back showScheduledHaCard for the HA-branded card. Returns
     * true if the pipeline/WebView was available (else the manager retries).
     */
    @Volatile
    var runHaCommand: ((String) -> Boolean)? = null

    fun handle(query: JSONObject): String {
        // The model occasionally omits `prompt` and puts the whole action in `label`
        // ("Turn on the string lights in one minute" → empty prompt, label carried it —
        // Samsung 2026-07-18 08:36, which dead-ended in the "couldn't work out" apology).
        // label is a faithful short restatement of the action, so it's a safe stand-in.
        val prompt = query.optString("prompt").trim()
            .ifEmpty { query.optString("label").trim() }

        // Anti-recursion: refuse ONLY the replay re-scheduling itself, never a user's
        // own request that merely overlapped the window (see the guard notes above).
        if (System.currentTimeMillis() < suppressCreateUntilMs && isSelfReschedule(prompt)) {
            suppressCreateUntilMs = 0L  // consume — one-shot
            injectedPrompt = null
            Log.w(TAG, "Refused schedule_action from a fire-time injection (anti-recursion guard): \"${prompt.take(60)}\"")
            return ""  // no re-schedule, no announcement
        }
        val mgr = manager
            ?: return "Sorry — scheduling isn't available on this device right now."

        if (prompt.isEmpty()) {
            return "Sorry — I couldn't work out what you want me to check."
        }
        val rawLabel = query.optString("label").trim()
        val label = rawLabel.ifEmpty { "check on that" }
        // Brain-tagged kind: 'notify' = a plain reminder — the device just speaks the text at
        // fire (native chime + card + TTS, NO brain call); 'command' = a smart-home control to
        // issue DIRECTLY at fire; anything else = a prompt to re-run through the AI at fire.
        // An old APK that predates 'notify' maps it to TYPE_AI_TURN via its else-branch — the
        // framed fire-time injection makes that announce correctly too (compat-safe).
        val actionType = when (query.optString("kind")) {
            "command" -> ScheduledAction.TYPE_HA_COMMAND
            "notify" -> ScheduledAction.TYPE_NOTIFY
            else -> ScheduledAction.TYPE_AI_TURN
        }
        // NOTIFY fires through executor.execute, which speaks/shows notifyText — the prompt IS
        // the announcement text there. AI/HA kinds keep it in prompt for the fire-time dispatch.
        val notifyText = if (actionType == ScheduledAction.TYPE_NOTIFY) prompt else ""
        val firePrompt = if (actionType == ScheduledAction.TYPE_NOTIFY) "" else prompt

        // Relative delay ("in 5 minutes") takes precedence over an absolute time;
        // relative actions are always one-shot.
        val delayMin = query.optDouble("delay_minutes", 0.0)
        if (delayMin > 0.0) {
            val fireAt = System.currentTimeMillis() + (delayMin * 60_000).toLong()
            mgr.createScheduledAction(
                fireAtEpochMs = fireAt,
                actionType = actionType,
                prompt = firePrompt,
                notifyText = notifyText,
                label = rawLabel,
            )
            Log.i(TAG, "Scheduled $actionType in ${delayMin}min: \"${prompt.take(60)}\"")
            showCreatedCard?.invoke(cardTitle(actionType, fireAt, recurring = false), prompt)
            // A notify is a REMINDER — "I'll remind you", not "I'll check the oven"
            // (Dashie isn't doing the checking; the user is).
            return if (actionType == ScheduledAction.TYPE_NOTIFY)
                "Okay — I'll remind you in ${spokenDelay(delayMin)}."
            else "Okay — in ${spokenDelay(delayMin)}, I'll $label."
        }

        val time = query.optString("time")
        val hm = Recurrence.parseHhMm(time)
            ?: return "Sorry — I couldn't work out when you wanted that."
        val recurrence = if (query.optString("recurrence") == ScheduledAction.RECUR_DAILY)
            ScheduledAction.RECUR_DAILY else null

        val fireAt = Recurrence.nextOccurrence(hm.first, hm.second)
        mgr.createScheduledAction(
            fireAtEpochMs = fireAt,
            actionType = actionType,
            prompt = firePrompt,
            notifyText = notifyText,
            recurrence = recurrence,
            label = rawLabel,
        )
        Log.i(TAG, "Scheduled $actionType @ $time recur=$recurrence: \"${prompt.take(60)}\"")
        showCreatedCard?.invoke(cardTitle(actionType, fireAt, recurring = recurrence != null), prompt)

        val spokenTime = spokenTime(hm.first, hm.second)
        return when {
            actionType == ScheduledAction.TYPE_NOTIFY && recurrence != null ->
                "Okay — I'll remind you every day at $spokenTime."
            actionType == ScheduledAction.TYPE_NOTIFY ->
                "Okay — I'll remind you at $spokenTime."
            recurrence != null -> "Okay — every day at $spokenTime, I'll $label."
            else -> "Okay — at $spokenTime, I'll $label."
        }
    }

    /** "Reminder · Today at 5:12 PM" / "Scheduled · Daily at 9:30 PM" — mirrors the full-mode
     *  creation line (reminder-action-handler.js `_fmtFireTime`). */
    private fun cardTitle(actionType: String, fireAtMs: Long, recurring: Boolean): String {
        val kind = if (actionType == ScheduledAction.TYPE_HA_COMMAND) "Scheduled" else "Reminder"
        return "$kind · ${if (recurring) "Daily at ${fmtTime(fireAtMs)}" else fmtFireTime(fireAtMs)}"
    }

    private fun fmtTime(ms: Long): String =
        java.text.SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(ms))

    private fun fmtFireTime(ms: Long): String {
        val dayStamp = java.text.SimpleDateFormat("yyyyDDD", Locale.US)
        val sameDay = dayStamp.format(java.util.Date(ms)) == dayStamp.format(java.util.Date())
        return if (sameDay) "Today at ${fmtTime(ms)}"
        else java.text.SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.US).format(java.util.Date(ms))
    }

    private fun spokenDelay(minutes: Double): String {
        val m = minutes.toInt()
        return when {
            minutes < 1.0 -> "${(minutes * 60).toInt()} seconds"
            m == 1 -> "a minute"
            m < 60 -> "$m minutes"
            m == 60 -> "an hour"
            m % 60 == 0 -> "${m / 60} hours"
            else -> "$m minutes"
        }
    }

    private fun spokenTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return if (minute == 0) "$h12 $amPm"
        else String.format(Locale.US, "%d:%02d %s", h12, minute, amPm)
    }

    private const val TAG = "ScheduleActionDirective"
    private const val INJECT_GUARD_WINDOW_MS = 15_000L
}
