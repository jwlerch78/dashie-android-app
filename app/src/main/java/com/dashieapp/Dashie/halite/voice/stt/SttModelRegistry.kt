package com.dashieapp.Dashie.halite.voice.stt

import android.content.Context
import java.io.File

/**
 * Which on-device STT models exist, where they come from, and what "installed" means.
 *
 * ## Why a registry rather than constants at the call sites
 *
 * Three things have to agree about a model — its download URL, its sha256, and the directory the
 * recognizer loads from — and they are consumed by different layers (installer, settings row,
 * [SherpaEngineLoader]). Spreading them means a family can be half-added: offered in settings,
 * unknown to the loader. One table, and adding a family is one entry.
 *
 * ## 🔴 The digests are the SAME ones the build script pins
 *
 * `scripts/fetch-stt-models.sh` fetches these exact archives at build time with these exact
 * sha256s. Keeping them equal is deliberate: the model a developer bundles and the model a user
 * downloads are then provably the same artifact. ⚠️ If you bump one, bump the other — they are a
 * hand-mirror across a build/runtime boundary, which is the tier-3 case (`JS_KOTLIN_CONTRACTS`
 * reasoning applies even though neither side is JS).
 *
 * ## Neutral upstream — ⚠️ PARTIALLY SPENT as of 2026-08-23, deliberately
 *
 * The original rule: the URLs point at **k2-fsa's own GitHub releases**, not at anything Dashie
 * controls, so an account-free Chickadee device downloading a model depends on no Dashie service
 * — the edition-independence constraint, satisfied by using upstream as published, decompressing
 * rather than re-hosting.
 *
 * 🔴 **`moonshine-base` no longer satisfies it, by a decision taken with the rule in view**
 * (2026-08-23). Its download moved to a **Dashie-hosted** archive to cut the wire size from
 * 111 MB to 48 MB (and on-disk 141 MB → 64 MB) by shipping the un-fused `.onnx` export instead of
 * the duplicated-weight `.ort` one. The neutral alternative — HuggingFace at the pinned revision
 * — was rejected on cost, not on principle: HF serves **individual files, not a `.tar.bz2`**, so
 * it would have required a second, multi-file download mode in `ModelInstaller` for one family.
 *
 * **What that costs, stated plainly rather than left for someone to discover:** an HA-edition /
 * account-free device that wants this model now depends on a Dashie-operated host being up. It
 * does not need an account, and nothing is sent but a plain GET — but the *independence* claim is
 * narrower than it was, and this KDoc is no longer a complete description of the policy.
 * `moonshine-tiny` is unchanged and still upstream-neutral.
 *
 * 📌 The mitigation is that the archive is **content-addressed**: [Family.archiveSha256] pins the
 * exact bytes, verified before extraction, so re-hosting moves *who serves it* and not *what is
 * served*. Anyone can re-point [Family.url] at a mirror without touching anything else.
 */
object SttModelRegistry {

    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    /**
     * Dashie-operated public object storage — the SAME prod Supabase bucket pattern the wake-word
     * models already use (`WakeWordModelManager.MANIFEST_URL`), so this adds no new host to the
     * app's outbound surface, only a new path on one it already contacts.
     *
     * Public and unauthenticated on purpose: an account-free device must be able to GET it. See
     * the "PARTIALLY SPENT" section in this file's KDoc for what using it costs.
     */
    private const val DASHIE_MODELS =
        "https://cseaywxcvnxcsypaqaid.supabase.co/storage/v1/object/public/stt-models"

    /** Directory under `filesDir` holding every downloaded STT model family. */
    private const val ROOT = "stt_models"

    /**
     * @param id matches the bundled asset directory name, so [SherpaEngineLoader] can use one id
     *   whether the model came from assets or from a download.
     * @param archiveSha256 digest of the ARCHIVE as published upstream — what [ModelInstaller]
     *   verifies before anything is extracted.
     * @param approxMb the DOWNLOAD (wire) size the user is told BEFORE committing — John's
     *   2026-08-18 ruling: every user-facing size is the download number (the announce says
     *   "~30 MB"). Measured from the published archives (tiny 29,858,559 B / base 111,266,225 B),
     *   rounded up. On-disk-after-install is [extractedBytes]; the dialog states both.
     * @param encoder / @param decoder the model filenames INSIDE this family's archive.
     *   ⚠️ **Per-family, and that is the point.** They used to be one shared `MOONSHINE_MEMBERS`
     *   constant, which was correct exactly while both families shipped the same `.ort` export.
     *   Since 2026-08-23 base ships `.onnx` and tiny still ships `.ort`, so a shared constant is
     *   the wrong shape (seam rule: share the thing that is actually shared). [members] is
     *   DERIVED from these rather than declared beside them — [SherpaEngineLoader] reads the same
     *   two fields to build its paths, so "what the installer requires" and "what the recognizer
     *   opens" cannot drift apart. Before this they were a hand-mirror that agreed by luck.
     */
    data class Family(
        val id: String,
        /**
         * The USER-FACING name of this model, and the download dialog's title
         * (`"Download $label?"`, [SttModelDownloadPrompt]).
         *
         * ⚠️ Must stay coherent with the settings picker row that opens that dialog
         * (`VoiceAiOptions`: **"On-Device (Open Source)"**) — the shared "On-Device" prefix is
         * what makes them read as one thing. It previously said "Local speech recognition
         * (accurate)": a different name AND the word John retired, one tap apart from the row.
         * A picker row and the dialog it opens are the same "row gate and tap popup can never
         * disagree" discipline.
         *
         * 📌 The dialog spells out "Speech Recognition" AND carries the row's qualifier. Dropping
         * the qualifier here (the first shape tried) left "On-Device" as the only shared token the
         * moment the row gained "(Open Source)" — a weaker coupling than the one this KDoc exists
         * to protect. Spelling it out is what keeps "Download …?" reading as a sentence.
         */
        val label: String,
        val url: String,
        val archiveSha256: String,
        val approxMb: Int,
        val encoder: String,
        val decoder: String,
        /** Path inside the archive; upstream nests everything under a versioned directory. */
        val archiveDir: String,
        /**
         * Exact byte total of [members] once extracted — the denominator of the INSTALLING
         * progress ("X of Y MB"), which is the ~7×-longer phase and previously showed bytes with
         * no target (2026-08-18). A constant, not an estimate: the archive is pinned by
         * [archiveSha256], so its extracted sizes can no more drift than the digest can. Measured
         * from the published artifacts (`tar -tjvf`, sha-verified first); ⚠️ bump alongside
         * [archiveSha256] when a family's archive is ever updated.
         */
        val extractedBytes: Long,
        val tokens: String = "tokens.txt",
    ) {
        /**
         * The files that must exist after extraction for the family to be usable — DERIVED, never
         * declared. See [encoder]: this is the one list `isInstalled`, the installer's extract
         * filter, and its completeness check all read, and it is built from the same two fields
         * the recognizer opens.
         */
        val members: List<String> get() = listOf(encoder, decoder, tokens)
    }

    val FAMILIES: List<Family> = listOf(
        Family(
            id = SherpaEngineLoader.MODEL_MOONSHINE_TINY,
            label = "On-Device Speech Recognition (Open Source, legacy)",
            url = "$GH/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2",
            archiveSha256 = "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d",
            approxMb = 30,  // wire: 29,858,559 B archive
            encoder = "encoder_model.ort",
            decoder = "decoder_model_merged.ort",
            archiveDir = "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27",
            extractedBytes = 44_243_206,  // decoder 30,412,256 + encoder 13,281,600 + tokens 549,350
        ),
        Family(
            // ⚠️ SAME id as the `.ort` build it replaces, deliberately.
            // `isInstalled` checks every member, so an existing `.ort` install now reports NOT
            // installed and the picker offers a 48 MB download — smaller than the 111 MB those
            // users already fetched, and the only path on which they get the RAM win at all. A new
            // id would spare the re-download and strand every current user on the heavy model
            // forever without a migration mapping, and grow RETIRED_FAMILY_IDS again.
            id = SherpaEngineLoader.MODEL_MOONSHINE_BASE,
            label = "On-Device Speech Recognition (Open Source)",
            // 🔴 The one Dashie-hosted model URL. See the "PARTIALLY SPENT" section above before
            // adding a second, and DATA.md's outbound-host table, which discloses it.
            url = "$DASHIE_MODELS/moonshine-base-en-onnx-2026-08-23.tar.bz2",
            archiveSha256 = "89781f83d51cc082f6da98a4e61eb39607b75e31bd0bdb858bd408becfe0da08",
            approxMb = 48,  // wire: 48,202,438 B archive (was 111 — MEASURED;
                            // the "~59 MB" that circulated on 08-23 was a pre-measurement estimate)
            // .onnx, not .ort: the un-fused upstream export. Same weights, ~52 MB less duplication.
            encoder = "encoder_model.onnx",
            decoder = "decoder_model_merged.onnx",
            archiveDir = "moonshine-base-en-onnx-2026-08-23",
            extractedBytes = 63_561_283,  // decoder 42,498,870 + encoder 20,513,063 + tokens 549,350
        ),
    )

    /**
     * Families that are no longer OFFERED but are still fully recognised: an installed copy keeps
     * working, the id still resolves, and a stored selection still migrates. Retired ≠ unknown —
     * dropping the id outright is what turns an old device's selection into a dead pointer.
     *
     * moonshine-tiny retired 2026-08-20 ("Yes - B"): it hallucinates sentences out of
     * non-speech, and S's 641-clip run cleared base as the single on-device model.
     */
    val RETIRED_FAMILY_IDS: Set<String> = setOf(SherpaEngineLoader.MODEL_MOONSHINE_TINY)

    /** The family a retired selection should move to. */
    const val REPLACEMENT_FOR_RETIRED: String = SherpaEngineLoader.MODEL_MOONSHINE_BASE

    /** Families the settings picker may offer. */
    fun offeredFamilies(): List<Family> = FAMILIES.filterNot { it.id in RETIRED_FAMILY_IDS }

    /**
     * One-way migration for a stored `voice.sttProvider` naming a RETIRED family.
     * Returns the replacement provider value, or null when [current] needs no migration.
     *
     * Deliberately unconditional on install state: the replacement's Priority chain keeps the
     * retired model as a fallback rung, so rewriting the selection cannot demote a device that
     * has only the old model — it keeps transcribing locally until the new one lands. Making the
     * rewrite conditional instead would leave the picker showing a model we no longer offer.
     */
    fun migrateRetiredProviderValue(current: String): String? {
        val family = byProviderValue(current) ?: return null
        if (family.id !in RETIRED_FAMILY_IDS) return null
        val replacement = byId(REPLACEMENT_FOR_RETIRED) ?: return null
        return providerValue(replacement)
    }

    fun byId(id: String): Family? = FAMILIES.firstOrNull { it.id == id }

    /**
     * The settings-picker value naming this family, e.g. `moonshine-tiny` → `sherpa_moonshine_tiny`.
     *
     * ⚠️ These ids are FROZEN by `JS_KOTLIN_CONTRACTS` #16 (`lint:voice-options` cross-checks the
     * STT id set against `js/data/settings/voice-ai-value-ids.js`) — the underscore form is the
     * wire value, the hyphen form is the asset/directory name, and they differ.
     *
     * 🔴 Both directions live HERE so the mapping exists once. The forward form was written at the
     * settings call site and the reverse would have been written at the download callback: two
     * hand-mirrors of one rule, in different packages, which is the seam rule's exact target. Add
     * a family whose id does not follow the pattern and both sides change together, or neither.
     */
    fun providerValue(family: Family): String = "sherpa_" + family.id.replace('-', '_')

    /** Inverse of [providerValue]; null when the value names no known family. */
    fun byProviderValue(value: String): Family? =
        FAMILIES.firstOrNull { providerValue(it) == value }

    /**
     * The directory every downloaded family lives under.
     *
     * Exposed so no caller spells [ROOT] itself — the same reason
     * [SherpaEngineLoader.bundledModels] exists for the asset root. The orphan sweep
     * ([SttModelInstaller.sweepOrphans]) needs to enumerate this directory rather than reach it
     * through some family, and a second `File(filesDir, "stt_models")` is exactly the hand-mirror
     * that goes stale silently: change [ROOT] and the sweep would tidy a directory nothing uses
     * while the real orphans stay.
     */
    fun root(context: Context): File = File(context.filesDir, ROOT)

    /** Where a downloaded family lives. Not created here — the installer's rename creates it. */
    fun dir(context: Context, family: Family): File = File(root(context), family.id)

    /**
     * Is this family installed and usable?
     *
     * ⚠️ **Checks every member, not just the directory.** The installer renames a fully-extracted
     * directory into place, so directory-presence *should* imply completeness — but this is the
     * predicate the STT lane gates on, and "should" is how a present-but-broken engine gets
     * offered. Cheap to check, and it also catches a user or a cleaner deleting one file.
     */
    fun isInstalled(context: Context, family: Family): Boolean {
        val d = dir(context, family)
        return d.isDirectory && family.members.all { File(d, it).length() > 0 }
    }

    /** Families the user could still download, i.e. not already installed and not bundled. */
    fun installable(context: Context): List<Family> =
        FAMILIES.filter { !isInstalled(context, it) && !SherpaEngineLoader.modelAvailable(context, it.id) }
}
