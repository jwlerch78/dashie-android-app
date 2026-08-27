package com.dashieapp.Dashie.halite.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.TtsManager
import com.dashieapp.Dashie.halite.audio.AlertSoundService
import com.dashieapp.Dashie.halite.notify.FamilyAlertNotifier

/**
 * Facade for the scheduled-actions feature — the single entry point the voice pipeline,
 * the JS bridge, and wiring talk to. Owns the store, the AlarmManager scheduler, and the
 * executor.
 *
 * Phase 2 adds the cloud-mirror control surface:
 *  - [onLifecycle] fires on create/fire/cancel/update so JS can mirror to Supabase.
 *  - [cancel] / [update] let the console (via the JS bridge → Realtime) mutate a
 *    reminder; the device re-arms or cancels the AlarmManager alarm to match.
 *
 * [cancel] and [update] are **idempotent** (status + no-change guards) so the
 * console↔device echo (device emits → JS upserts → Realtime → device) terminates after
 * one round-trip instead of looping.
 */
class ScheduledActionManager(
    context: Context,
    alertSoundProvider: () -> AlertSoundService?,
    ttsProvider: () -> TtsManager?,
    overlayContainerProvider: () -> android.view.ViewGroup?
) {
    private val store = ScheduledActionStore(context)
    private val scheduler = ScheduledActionScheduler(context)
    private val executor = ScheduledActionExecutor(alertSoundProvider, ttsProvider, overlayContainerProvider)

    /** Wired in VoiceComponentWiring → JS mirror sync. event ∈ created|fired|cancelled|updated. */
    var onLifecycle: ((event: String, action: ScheduledAction) -> Unit)? = null

    /**
     * Fire an ad-hoc NOTIFY immediately (chime + alert view + TTS) without
     * persisting or arming an alarm — used by condition alerts, whose
     * lifecycle lives in ConditionAlertStore, not this store.
     */
    fun fireNow(action: ScheduledAction) = executor.execute(action)

    /** Show a fired AI-turn's answer (or a scheduled HA command's confirmation)
     *  in the reminder card. Called via ScheduleActionDirective. */
    fun showAnnouncementCard(text: String?, isHaCommand: Boolean = false, title: String? = null, firedAtMs: Long = System.currentTimeMillis()) =
        executor.showAnnouncementCard(text, isHaCommand, title, firedAtMs)

    /** A scheduled action failed — small dismissible card, bottom-right (not the modal).
     *  [occurredAtMs] is when it FAILED; the card may be shown later (parked behind a chat). */
    fun showErrorCard(text: String?, isHaCommand: Boolean = false, occurredAtMs: Long = System.currentTimeMillis()) =
        executor.showErrorCard(text, isHaCommand, occurredAtMs)

    /** Every action this device knows about — the DEVICE's truth, for the mirror repair pass.
     *  The cloud is a mirror, never the firing path, so when the two disagree the device wins. */
    fun all(): List<ScheduledAction> = store.all()

    private val main = Handler(Looper.getMainLooper())

    init {
        // Receive alarm fires while the process is alive.
        ScheduledActionReceiver.onFire = { id -> onFire(id) }
    }

    /**
     * Create a relative-time reminder.
     *
     * @param notifyText   what Dashie speaks when it fires
     * @param delaySeconds seconds from now until it fires
     * @param vernacular   "reminder" | "alarm" — controls phrase-out
     */
    fun createReminder(
        notifyText: String,
        delaySeconds: Int,
        vernacular: String = ScheduledAction.VERNACULAR_REMINDER
    ): ScheduledAction {
        val now = System.currentTimeMillis()
        val action = ScheduledAction(
            id = store.nextId(),
            createdAt = now,
            fireAtEpochMs = now + delaySeconds * 1000L,
            notifyText = notifyText,
            vernacular = vernacular
        )
        store.put(action)
        scheduler.register(action)
        Log.i(TAG, "Created ${action.id}: \"$notifyText\" in ${delaySeconds}s")
        onLifecycle?.invoke(EVENT_CREATED, action)
        return action
    }

    /**
     * Create an absolute-time action, optionally recurring, of either type:
     * TYPE_NOTIFY speaks [notifyText] at fire time; TYPE_AI_TURN injects
     * [prompt] into the voice pipeline ("at 9:30 each night, check whether
     * the garage door is open"). Created via the brain's schedule_action
     * client tool.
     */
    fun createScheduledAction(
        fireAtEpochMs: Long,
        actionType: String,
        prompt: String = "",
        notifyText: String = "",
        recurrence: String? = null,
        vernacular: String = ScheduledAction.VERNACULAR_REMINDER,
        label: String = ""
    ): ScheduledAction {
        val action = ScheduledAction(
            id = store.nextId(),
            createdAt = System.currentTimeMillis(),
            fireAtEpochMs = fireAtEpochMs,
            notifyText = notifyText,
            vernacular = vernacular,
            actionType = actionType,
            prompt = prompt,
            recurrence = recurrence,
            label = label
        )
        store.put(action)
        scheduler.register(action)
        Log.i(TAG, "Created ${action.id}: type=$actionType recur=$recurrence at=$fireAtEpochMs")
        onLifecycle?.invoke(EVENT_CREATED, action)
        return action
    }

    /** Cancel a scheduled action (from voice, or the console via the JS bridge). Idempotent. */
    fun cancel(id: String) {
        val action = store.get(id) ?: return
        if (action.status != ScheduledAction.Status.SCHEDULED) return  // already fired/cancelled → no-op
        scheduler.unregister(id)
        store.updateStatus(id, ScheduledAction.Status.CANCELLED)
        Log.i(TAG, "Cancelled $id")
        onLifecycle?.invoke(EVENT_CANCELLED, action.copy(status = ScheduledAction.Status.CANCELLED))
    }

    /**
     * Edit a scheduled action's text / fire time / vernacular and re-arm the alarm
     * (from the console via the JS bridge). Idempotent — a no-change call is a no-op,
     * which terminates the console↔device echo loop.
     */
    fun update(id: String, notifyText: String, fireAtEpochMs: Long, vernacular: String) {
        val existing = store.get(id) ?: return
        if (existing.status != ScheduledAction.Status.SCHEDULED) return  // only editable while scheduled
        if (existing.notifyText == notifyText &&
            existing.fireAtEpochMs == fireAtEpochMs &&
            existing.vernacular == vernacular
        ) return  // no change → break the echo loop
        val updated = existing.copy(
            notifyText = notifyText,
            fireAtEpochMs = fireAtEpochMs,
            vernacular = vernacular
        )
        store.put(updated)
        scheduler.unregister(id)     // cancel old alarm
        scheduler.register(updated)  // re-arm at the new fire time
        Log.i(TAG, "Updated $id → fireAt=$fireAtEpochMs \"$notifyText\"")
        onLifecycle?.invoke(EVENT_UPDATED, updated)
    }

    /** Re-arm every still-future scheduled action; fire any that elapsed while powered off. */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        store.active().forEach { action ->
            if (action.fireAtEpochMs > now) {
                scheduler.register(action)
            } else {
                Log.i(TAG, "Past-due on reschedule, firing now: ${action.id}")
                onFire(action.id)
            }
        }
    }

    /**
     * Inject a fired AI-turn's prompt, retrying while the voice pipeline comes
     * up. An alarm can fire mid-restart (memory-killed on constrained tablets),
     * before voice wiring sets [ScheduleActionDirective.injectAiTurn] — rather
     * than fail to a "couldn't run" card, retry for ~[AI_TURN_MAX_RETRIES]×
     * [AI_TURN_RETRY_MS], then fall back.
     */
    private fun fireAiTurn(prompt: String, attempt: Int) {
        val injected = ScheduleActionDirective.injectAiTurn?.invoke(prompt) ?: false
        if (injected) return
        if (attempt < AI_TURN_MAX_RETRIES) {
            Log.i(TAG, "AI-turn pipeline not ready (attempt ${attempt + 1}) — retry in ${AI_TURN_RETRY_MS}ms")
            main.postDelayed({ fireAiTurn(prompt, attempt + 1) }, AI_TURN_RETRY_MS)
        } else {
            Log.w(TAG, "AI-turn pipeline never ready — showing fallback for: $prompt")
            // A failure gets the small corner card, not the center-stage modal + chime + TTS
            // that execute() would fire — it's a "you should know", not a demand.
            executor.showErrorCard("I couldn't run your scheduled check: $prompt")
        }
    }

    /**
     * Issue a scheduled HA command DIRECTLY (kind='command'): no classifier
     * replay, no live-command card — the JS side runs it against HA Assist and
     * calls back showScheduledHaCard for the HA-branded reminder card. Same
     * retry-until-ready guard as AI turns (an alarm can fire mid-restart).
     */
    private fun fireHaCommand(prompt: String, attempt: Int) {
        val ran = ScheduleActionDirective.runHaCommand?.invoke(prompt) ?: false
        if (ran) return
        if (attempt < AI_TURN_MAX_RETRIES) {
            Log.i(TAG, "HA-command path not ready (attempt ${attempt + 1}) — retry in ${AI_TURN_RETRY_MS}ms")
            main.postDelayed({ fireHaCommand(prompt, attempt + 1) }, AI_TURN_RETRY_MS)
        } else {
            Log.w(TAG, "HA-command path never ready — showing fallback for: $prompt")
            executor.showErrorCard("I couldn't run your scheduled command: $prompt", isHaCommand = true)
        }
    }

    private fun onFire(id: String) {
        val action = store.get(id) ?: run {
            Log.w(TAG, "Fired action not in store: $id")
            return
        }
        if (action.status != ScheduledAction.Status.SCHEDULED) {
            Log.i(TAG, "Skipping non-scheduled $id (${action.status})")
            return
        }
        // Mark FIRED before executing so a redelivered alarm can't double-speak.
        store.updateStatus(id, ScheduledAction.Status.FIRED)

        // Stash the fired action's label + timestamp so the (async, possibly JS-routed)
        // card callbacks can title the card with WHAT was asked, not just the outcome.
        ScheduleActionDirective.noteFiring(action)
        when {
            action.actionType == ScheduledAction.TYPE_HA_COMMAND && action.prompt.isNotEmpty() ->
                fireHaCommand(action.prompt, attempt = 0)
            action.actionType == ScheduledAction.TYPE_AI_TURN && action.prompt.isNotEmpty() ->
                fireAiTurn(action.prompt, attempt = 0)
            else -> {
                executor.execute(action)
                // Notify the family's phones that a reminder/alarm went off. Only this
                // TYPE_NOTIFY branch — ha_command/ai_turn are silent automations, not alerts.
                notifyFamilyOfFire(action)
            }
        }
        // Recurring actions re-arm at the next wall-clock occurrence.
        val nextFire = Recurrence.nextAfter(action.fireAtEpochMs, action.recurrence)
        if (nextFire != null) {
            val next = action.copy(fireAtEpochMs = nextFire, status = ScheduledAction.Status.SCHEDULED)
            store.put(next)
            scheduler.register(next)
            Log.i(TAG, "Re-armed recurring ${action.id} for $nextFire")
            // A recurring action that re-arms is never "fired" as far as the mirror is concerned —
            // its row simply ADVANCES to the next occurrence. So emit ONLY the update.
            //
            // Emitting FIRED *and* UPDATED (the obvious first fix) is a RACE, not a sequence: each
            // event is an independent async upsert through the edge fn, keyed on the same
            // (device_id, local_id), and they can land in either order. Observed 2026-07-13 —
            // 'updated' completed at .693 and 'fired' at .815, so FIRED won and stamped the row
            // back to yesterday's time, reintroducing the very bug the update was added to fix.
            // One event = no ordering to lose.
            onLifecycle?.invoke(EVENT_UPDATED, next)
        } else {
            // One-shot: it really is done.
            onLifecycle?.invoke(EVENT_FIRED, action.copy(status = ScheduledAction.Status.FIRED))
        }
    }

    /** Best-effort mobile push to the family's phones that a reminder/alarm fired. */
    private fun notifyFamilyOfFire(action: ScheduledAction) {
        val isAlarm = action.vernacular == ScheduledAction.VERNACULAR_ALARM
        val title = if (isAlarm) "Alarm" else "Reminder"
        val body = action.notifyText.ifBlank { action.label }.ifBlank { title }
        FamilyAlertNotifier.notifyFamily(
            source = if (isAlarm) FamilyAlertNotifier.SOURCE_ALARM else FamilyAlertNotifier.SOURCE_REMINDER,
            refId = action.id,
            title = title,
            body = body
        )
    }

    companion object {
        private const val TAG = "ScheduledActionManager"
        // ~48s of retries covers a cold app start after a memory kill.
        private const val AI_TURN_MAX_RETRIES = 6
        private const val AI_TURN_RETRY_MS = 8_000L
        const val EVENT_CREATED = "created"
        const val EVENT_FIRED = "fired"
        const val EVENT_CANCELLED = "cancelled"
        const val EVENT_UPDATED = "updated"
    }
}
