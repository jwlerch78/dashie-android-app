// GENERATED FILE — DO NOT EDIT BY HAND.
// Source of truth: dashieapp_staging/js/ai/ai-models-catalog.js
// Regenerate:      node scripts/gen-android-ai-models.mjs  (then rebuild the APK)
package com.dashieapp.Dashie.halite.settings.schemas

/** A selectable cloud AI model, generated from the shared model catalog. */
data class AiModelEntry(
    val id: String,
    val label: String,
    val description: String,
    val provider: String,       // "claude" | "openai" | "gemini" | "bedrock"
    val providerLabel: String   // section header, e.g. "Google Gemini"
)

object AiModelCatalog {
    /** Cloud models, grouped by provider (the "home_assistant" sentinel is
     *  prepended by VoiceAiOptions, not part of this generated list). */
    val MODELS: List<AiModelEntry> = listOf(
        AiModelEntry("gemini-3.5-flash", "Gemini 3.5 Flash", "Top flash quality (premium)", "gemini", "Google Gemini"),
        AiModelEntry("gemini-2.5-flash", "Gemini 2.5 Flash", "Best price-performance (recommended)", "gemini", "Google Gemini"),
        AiModelEntry("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", "Fastest and cheapest", "gemini", "Google Gemini"),
        AiModelEntry("gemini-2.5-pro", "Gemini 2.5 Pro", "State-of-the-art reasoning", "gemini", "Google Gemini"),
        AiModelEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", "Best balance of speed and quality", "claude", "Claude AI"),
        AiModelEntry("claude-opus-4-8", "Claude Opus 4.8", "Most capable (slower)", "claude", "Claude AI"),
        AiModelEntry("claude-haiku-4-5", "Claude Haiku 4.5", "Fastest Claude responses", "claude", "Claude AI"),
        AiModelEntry("gpt-5.5", "GPT-5.5", "Most capable GPT", "openai", "OpenAI (ChatGPT)"),
        AiModelEntry("gpt-5.4", "GPT-5.4", "Capable and affordable", "openai", "OpenAI (ChatGPT)"),
        AiModelEntry("gpt-5.4-mini", "GPT-5.4 Mini", "Fast and affordable", "openai", "OpenAI (ChatGPT)"),
        AiModelEntry("gpt-5.4-nano", "GPT-5.4 Nano", "Fastest and cheapest GPT", "openai", "OpenAI (ChatGPT)"),
        AiModelEntry("us.amazon.nova-2-lite-v1:0", "Amazon Nova 2 Lite", "Fast multimodal reasoning", "bedrock", "Amazon Bedrock"),
        AiModelEntry("us.amazon.nova-pro-v1:0", "Amazon Nova Pro", "Best for complex tasks (300K context)", "bedrock", "Amazon Bedrock"),
        AiModelEntry("us.amazon.nova-micro-v1:0", "Amazon Nova Micro", "Fastest and cheapest (text only)", "bedrock", "Amazon Bedrock")
    )
}
