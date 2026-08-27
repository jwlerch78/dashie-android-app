package com.dashieapp.Dashie.halite.schedule

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.dashieapp.Dashie.halite.TtsManager
import com.dashieapp.Dashie.halite.audio.AlertSoundService

/**
 * Performs a scheduled action when it fires. NOTIFY = a center-stage on-screen alert that
 * stays until dismissed, plus sound (a single chime for "reminder", a looped alarm for
 * "alarm") and a spoken line. This is the "notification path" the tools contract calls out
 * — it runs with no conversation in scope, so it speaks via the native [TtsManager] and
 * renders into the activity overlay container (no SYSTEM_ALERT_WINDOW), mirroring
 * TimerOverlayManager.
 *
 * Handles are read through providers so this is robust to component init-ordering and
 * WebView recreation (mirrors the repo's `*Provider` idiom).
 */
class ScheduledActionExecutor(
    private val alertSoundProvider: () -> AlertSoundService?,
    private val ttsProvider: () -> TtsManager?,
    private val overlayContainerProvider: () -> ViewGroup?,
    private val chimeName: String = DEFAULT_CHIME
) {
    private val main = Handler(Looper.getMainLooper())
    private var currentAlert: View? = null
    // One bottom-right corner slot, shared by error cards and DEMOTED fired cards — a new
    // corner card replaces the previous one (they'd overlap in the same corner otherwise).
    private var currentCorner: View? = null

    /** One presentation of a fired action — everything needed to show (and sound) it later.
     *  [withSound] false = chime/TTS already happened (announcements are pre-spoken by the
     *  pipeline; a card re-queued by an alarm preempt already played on first show). */
    private data class Presentation(
        val action: ScheduledAction,
        val isAlarm: Boolean,
        val announcement: Boolean = false,
        val haCommand: Boolean = false,
        val title: String? = null,
        val firedAtMs: Long = System.currentTimeMillis(),
        val withSound: Boolean = true,
    )

    // FIRED-CARD QUEUE (item 13, John 2026-07-18): ONE fired card holds the stage at a time.
    // A fire arriving while a card is up WAITS here (the visible card shows a "+N more"
    // badge); dismiss or the demote timeout reveals the next. Previously a second fire
    // REPLACED the visible card — the first fire's outcome was silently lost.
    private val queue = ArrayDeque<Presentation>()
    private var currentPresentation: Presentation? = null

    fun execute(action: ScheduledAction) {
        Log.i(TAG, "NOTIFY ${action.id}: \"${action.notifyText}\"")
        main.post {
            offer(Presentation(action, isAlarm = action.vernacular == ScheduledAction.VERNACULAR_ALARM))
        }
    }

    /** Queue-or-present. Alarms preempt a non-alarm card (their looping sound demands the
     *  Dismiss button NOW); the preempted card is re-queued at the FRONT, nothing lost. */
    private fun offer(p: Presentation) {
        val visible = currentPresentation
        when {
            currentAlert == null -> present(p)
            p.isAlarm && visible != null && !visible.isAlarm -> {
                Log.i(TAG, "🗂️ FIRED-CARD QUEUE: alarm preempts the visible card (re-queued at front)")
                queue.addFirst(visible.copy(withSound = false))
                dismissAlert(stopAlarm = false)
                currentPresentation = null
                present(p)
            }
            else -> {
                queue.addLast(p)
                Log.i(TAG, "🗂️ FIRED-CARD QUEUE: card queued behind the visible one (${queue.size} waiting)")
                (currentAlert as? ReminderAlertView)?.updateQueueBadge(queue.size)
            }
        }
    }

    /** Take the stage: sound + card + speech for this presentation. */
    private fun present(p: Presentation) {
        currentPresentation = p
        if (p.withSound && !p.announcement) {
            val sound = alertSoundProvider()
            if (p.isAlarm) sound?.startAlarm() else sound?.playChime(chimeName)
            // Brief gap so the sound doesn't clip the first TTS syllable.
            main.postDelayed({ speak(spokenLine(p.action)) }, CHIME_TO_SPEECH_GAP_MS)
        }
        showAlert(p.action, p.isAlarm, p.announcement, p.haCommand, p.title, p.firedAtMs)
        // No overlay container (early boot): the card was skipped (sound/TTS still ran).
        // Don't hold the stage for a card that isn't showing — let the queue drain.
        if (currentAlert == null) {
            currentPresentation = null
            queue.removeFirstOrNull()?.let { present(it) }
        }
    }

    /** Dismiss/timeout of the visible card → reveal the next queued one, if any. */
    private fun advanceQueue(stopAlarm: Boolean) {
        dismissAlert(stopAlarm)
        currentPresentation = null
        queue.removeFirstOrNull()?.let {
            Log.i(TAG, "🗂️ FIRED-CARD QUEUE: revealing next card (${queue.size} still waiting)")
            present(it)
        }
    }

    /**
     * Visual-only reminder card for a fired AI-turn (WS5-a): the voice pipeline
     * already spoke the answer, so this shows the SAME reminder chrome (no chime,
     * no TTS) so a scheduled callback reads as a notification, not a conversation.
     *
     * STAYS UP until the user taps Dismiss (2026-07-18) — a fired card that
     * auto-vanished while you were out of the room left no trace the action ran,
     * which is why it now also carries a fired-at timestamp. [title] is the LLM-set
     * label of the original request ("Turn off the string lights") so the outcome
     * ("Turned off the switch") reads with its context.
     */
    fun showAnnouncementCard(
        text: String?,
        isHaCommand: Boolean = false,
        title: String? = null,
        firedAtMs: Long = System.currentTimeMillis(),
    ) {
        val body = text?.takeIf { it.isNotBlank() } ?: return
        main.post {
            val action = ScheduledAction(
                id = "ann_${System.identityHashCode(body)}",
                createdAt = 0L, fireAtEpochMs = 0L, notifyText = body,
            )
            offer(Presentation(action, isAlarm = false, announcement = true, haCommand = isHaCommand,
                title = title, firedAtMs = firedAtMs, withSound = false))
        }
    }

    /**
     * A scheduled action FAILED — small dismissible card, bottom-right (not the center-stage
     * modal: a failure is "you should know", not a demand). Its own view + handle, so it can't
     * be clobbered by (or clobber) a success card.
     *
     * This exists because the AI-turn failure path used to draw NOTHING — it only spoke "Sorry,
     * I couldn't process that", so a scheduled check that failed while you were out of the room
     * (or muted) left zero trace.
     */
    fun showErrorCard(
        text: String?,
        isHaCommand: Boolean = false,
        occurredAtMs: Long = System.currentTimeMillis(),
        dismissAfterMs: Long = ERROR_DISMISS_MS,
    ) {
        val body = text?.takeIf { it.isNotBlank() } ?: return
        Log.w(TAG, "Scheduled action error card: \"$body\"")
        showCornerCard(body, isHaCommand, occurredAtMs, dismissAfterMs)
    }

    /** Bottom-right corner card (ScheduledErrorCard chrome) — used for failures (timed) and as
     *  the DEMOTED resting state of a fired card ([dismissAfterMs] = null → stays until ✕). */
    private fun showCornerCard(message: String, isHaCommand: Boolean, atMs: Long, dismissAfterMs: Long?) {
        main.post {
            val container = overlayContainerProvider()
            if (container == null) {
                Log.w(TAG, "No overlay container — corner card skipped")
                return@post
            }
            dismissCorner()
            val view = ScheduledErrorCard(container.context, message, isHaCommand, atMs) { dismissCorner() }
            currentCorner = view
            container.addView(view)
            // Identity-checked so a delayed dismiss can't kill a NEWER card in the slot.
            if (dismissAfterMs != null) {
                main.postDelayed({ if (currentCorner === view) dismissCorner() }, dismissAfterMs)
            }
        }
    }

    private fun dismissCorner() {
        currentCorner?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        currentCorner = null
    }

    /** Center-stage alert that stays on screen until the user taps Dismiss (or the demote
     *  timeout). Only called from [present] — the queue guarantees the stage is free. */
    private fun showAlert(
        action: ScheduledAction, isAlarm: Boolean, announcement: Boolean = false, haCommand: Boolean = false,
        title: String? = null, firedAtMs: Long = System.currentTimeMillis(),
    ) {
        val container = overlayContainerProvider()
        if (container == null) {
            Log.w(TAG, "No overlay container — reminder alert UI skipped (sound + TTS only)")
            return
        }
        dismissAlert(stopAlarm = false) // defensive — the queue should have freed the stage
        // No title on plain NOTIFY reminders — the body IS the request text ("CHECK THE
        // OVEN"), so a label title just duplicated it (2026-07-18). Announcement/HA
        // cards keep theirs via [title]: their body is the OUTCOME and needs the context.
        val view = ReminderAlertView(container.context, action, announcement, haCommand,
            title = title,
            firedAtMs = firedAtMs) { advanceQueue(stopAlarm = isAlarm) }
        currentAlert = view
        container.addView(view)
        view.updateQueueBadge(queue.size)

        // Demote after 45s: center-stage modal → small bottom-right card (error-card chrome),
        // scrim gone, info persists until ✕ (2026-07-18) — attention-grabbing at first,
        // then out of the way without losing the trace. Demotion also reveals the next queued
        // card, if any. ALARMS are exempt: the modal's Dismiss is what stops the looping
        // sound, so it must stay until the user acts.
        if (!isAlarm) {
            val summary = buildString {
                title?.takeIf { it.isNotBlank() }?.let { append("“").append(it).append("” — ") }
                append(action.notifyText.ifBlank { if (announcement) "Done" else "Reminder" })
            }
            main.postDelayed({
                if (currentAlert === view) {
                    showCornerCard(summary, haCommand, firedAtMs, dismissAfterMs = null)
                    advanceQueue(stopAlarm = false)
                }
            }, DEMOTE_TO_CORNER_MS)
        }
    }

    private fun dismissAlert(stopAlarm: Boolean) {
        currentAlert?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        currentAlert = null
        if (stopAlarm) alertSoundProvider()?.stopAlarm()
    }

    private fun speak(text: String) {
        val tts = ttsProvider()?.getTts()
        if (tts == null) {
            Log.w(TAG, "TTS unavailable — cannot speak: $text")
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun spokenLine(action: ScheduledAction): String {
        val label = action.notifyText.ifBlank { "this is your reminder" }
        val prefix = action.vernacular.replaceFirstChar { it.uppercase() }
        return "$prefix: $label"
    }

    companion object {
        private const val TAG = "ScheduledActionExecutor"
        private const val DEFAULT_CHIME = "notify_bell_tap"
        private const val CHIME_TO_SPEECH_GAP_MS = 700L
        private const val UTTERANCE_ID = "scheduled_action"

        // Fired SUCCESS cards never fully vanish on their own (2026-07-18): the modal
        // holds the stage briefly, then DEMOTES to a persistent bottom-right corner card
        // (scrim gone) that stays until ✕ — attention first, trace forever.
        const val DEMOTE_TO_CORNER_MS = 45_000L
        // A failure's small corner card keeps a (long) window so it can't become furniture.
        const val ERROR_DISMISS_MS = 60_000L
    }
}
