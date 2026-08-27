package com.dashieapp.Dashie.halite.voice

import android.util.Log
import com.dashieapp.Dashie.voice.realtime.HaAssistConverse

/**
 * WS-D.1 — answers a $0-DEGRADED QUESTION without ever touching the cloud brain.
 *
 * At $0 the credit gate declines any cloud-brain turn (`insufficient_credits`), which re-pops the
 * CR2 chooser AND opens the full conversation dialog — jarring for a plain "what's the weather".
 * Local device commands already run via the HA fast-path (never billed); a QUESTION should instead
 * go to HA's OWN conversation agent — the free "brain" [FreeVoicePlan.useHaConversation] promises —
 * or get a graceful spoken decline. Found on-device 2026-07-21: the question was still routed to the
 * cloud brain (declined) because `useHaConversation`/`brainAllowed` were computed but never consumed.
 *
 * Pure + stateless (same shape as [BrainToolResolver] / [FreeVoicePlan]): inject the HA executor,
 * return the line to speak. ⚠️ [answer] BLOCKS on the HA HTTP call — call it OFF the main thread.
 */
class DegradedQuestionHandler(
    private val haAssistConverse: HaAssistConverse,
) {
    /**
     * The spoken reply for a degraded question. [brainAllowed] false (no HA configured) → straight
     * decline. Otherwise route to HA's conversation agent and speak whatever it says — its LLM answer
     * if it has one, or its own "sorry, I didn't understand" (which IS the graceful decline). Only when
     * HA gives nothing back (unreachable / empty) do we fall to our own line.
     */
    fun answer(transcript: String, brainAllowed: Boolean): String {
        if (brainAllowed) {
            val speech = runCatching { haAssistConverse.process(transcript) }
                .onFailure { Log.w(TAG, "HA conversation-agent call failed: ${it.message}") }
                .getOrNull()?.speech?.takeIf { it.isNotBlank() }
            if (speech != null) {
                Log.i(TAG, "🏠 degraded question answered by HA conversation agent")
                return speech
            }
        }
        Log.i(TAG, "💳 degraded question — no HA answer, graceful decline")
        return DECLINE
    }

    companion object {
        private const val TAG = "DegradedVoice"
        private const val DECLINE = "Sorry, I can't answer that while you're out of credits."
    }
}
