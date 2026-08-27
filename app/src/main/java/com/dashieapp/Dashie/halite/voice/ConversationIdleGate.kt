package com.dashieapp.Dashie.halite.voice

import android.os.Handler
import android.util.Log

/**
 * Parks work until the user isn't talking to Dashie, then releases it.
 *
 * A scheduled action must never barge into a LIVE conversation. Firing blind preempted the
 * pipeline unconditionally: an announcement arriving mid-turn truncated Dashie in the middle of
 * an answer (observed — a nightly joke cut off "The capital of France is Paris." 2.8s in, so the
 * user never heard what they asked for), and one arriving while the mic was armed for a follow-up
 * seized it.
 *
 * "Idle" deliberately means the CONVERSATION is over, not merely that the current turn ended —
 * the weaker rule would still grab the armed mic and drop a card over an open chat. Bounded in
 * practice: a dialog idle-closes in ~13s.
 *
 * This gates PRESENTATION, never an ACTION. A scheduled HA command still runs ON TIME (the porch
 * light goes off at 9:30 whether or not you're mid-chat — queueing a physical action behind a
 * conversation would be a bug); only its card is parked. For a prompt-kind action the announcement
 * IS the action, so the whole turn parks.
 *
 * @param isBusy true while a turn is in flight or a dialog is open (mic armed for a follow-up).
 */
class ConversationIdleGate(
    private val handler: Handler,
    private val isBusy: () -> Boolean,
) {
    private val pending = ArrayDeque<Pair<String, () -> Unit>>()

    /** Run [block] now if the user isn't talking to Dashie, else when the conversation ends. */
    fun runWhenIdle(tag: String, block: () -> Unit) {
        handler.post {
            if (!isBusy()) { block(); return@post }
            Log.i(TAG, "⏸️ deferring '$tag' — conversation busy")
            pending.addLast(tag to block)
        }
    }

    /**
     * Release ONE parked task, if the conversation is over. One at a time on purpose: a task that
     * starts its own turn drives the pipeline busy again, and the next return to idle drains the
     * next one — so a queued announcement can't stomp the one before it.
     */
    fun drain() {
        if (pending.isEmpty() || isBusy()) return
        val (tag, block) = pending.removeFirst()
        Log.i(TAG, "▶️ conversation idle — running deferred '$tag' (${pending.size} still queued)")
        handler.post(block)   // post so the task can't re-enter the caller's state machine mid-drain
    }

    private companion object {
        private const val TAG = "ConversationIdleGate"
    }
}
