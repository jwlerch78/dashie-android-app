package com.dashieapp.Dashie.halite.settings.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.halite.voice.stt.SttAutoSelect
import com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress
import com.dashieapp.Dashie.halite.voice.stt.SttModelInstaller
import com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry

/**
 * The tap handler for an on-device STT row whose model is not installed yet.
 *
 * ## Why the row is DISABLED rather than selectable
 *
 * Selecting a model that is not on the device would leave the STT lane pointing at an engine that
 * cannot run — John's *present-but-broken* state, which the whole on-demand design exists to avoid.
 * So the row is not selectable; tapping it opens this, and the selection is written **only after
 * the install succeeds**. Either the option works or it does not exist: there is no in-between
 * state a user can select their way into.
 *
 * ## The size is stated before anything is fetched
 *
 * 42–135 MB is a real cost on a wall tablet's storage and on a metered link. The confirm step is
 * not ceremony — it is where the user learns the number, which is also why the row itself already
 * carries "download required (~N MB)" (see `VoiceAiOptions.sherpaDesc`). Nothing here nags: a user
 * on SpeechRecognizer or a cloud engine never reaches this code.
 *
 * ## ⚠️ The dialog is not the download
 *
 * The install runs on its own thread against the APPLICATION context and survives this dialog and
 * this activity. "Hide" hides the progress card without cancelling anything — the Control Center
 * strip (`ControlCenterOverlay`) keeps showing the same [SttInstallProgress] state, with the same
 * Cancel. **The selection is applied by [SttAutoSelect] on completion regardless of any UI being
 * alive** — the 2026-08-18 Fire session proved the settings screen is usually gone by the time
 * extraction ends, and the user had to come back and pick the model they had just downloaded.
 */
object SttModelDownloadPrompt {

    private const val TAG = "SttModelDownload"

    /**
     * Offer to install [family]. On success the STT selection is applied process-wide by
     * [SttAutoSelect]; [onInstalled] is ONLY the screen refresh (pop the picker, rebuild the
     * page) and is skipped when the activity is gone — the selection no longer rides on it.
     */
    fun offer(activity: Activity, family: SttModelRegistry.Family, onInstalled: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = "Download ${family.label}?"
        view.findViewById<TextView>(R.id.dialogMessage).text =
            // approxMb is the WIRE size (the ruling: the user-facing number is the download);
            // the parenthetical states the on-disk cost so neither fact hides the other.
            "This downloads about ${family.approxMb} MB once " +
            "(${family.extractedBytes / 1_000_000} MB installed). After that, speech recognition " +
            "runs entirely on this device — offline, with no cloud service and no per-use cost.\n\n" +
            "Keep Dashie open until the install finishes — Android stops background downloads, " +
            "and granting a permission restarts the app."

        val dialog = house(activity, view)
        view.findViewById<Button>(R.id.buttonNegative).apply {
            text = "Cancel"
            setOnClickListener { dialog.dismiss() }
        }
        view.findViewById<Button>(R.id.buttonPositive).apply {
            text = "Download"
            setOnClickListener {
                dialog.dismiss()
                start(activity, family, onInstalled)
            }
        }
        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    private fun start(activity: Activity, family: SttModelRegistry.Family, onInstalled: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = "Downloading ${family.label}"
        val message = view.findViewById<TextView>(R.id.dialogMessage)
        message.text = "Starting…"

        val dialog = house(activity, view)

        // Both phases render from the shared install state — the same snapshots the Control
        // Center strip shows, so hiding this card loses nothing.
        val listener = SttInstallProgress.Listener { snap ->
            if (!dialog.isShowing) return@Listener
            if (snap == null || snap.familyId != family.id) return@Listener
            message.text = SttInstallProgress.render(snap)
        }
        SttInstallProgress.addListener(listener)
        dialog.setOnDismissListener { SttInstallProgress.removeListener(listener) }

        view.findViewById<Button>(R.id.buttonPositive).apply {
            text = "Hide"
            // Hides the card; the install thread is unaffected and the Control Center strip
            // still shows it. See the class KDoc.
            setOnClickListener { dialog.dismiss() }
        }
        view.findViewById<Button>(R.id.buttonNegative).apply {
            // A real cancel now: the installer polls the flag per chunk in BOTH phases and
            // unwinds through its own cleanup. The card closes when Result.Cancelled arrives.
            text = "Cancel"
            setOnClickListener {
                isEnabled = false
                message.text = "Cancelling…"
                SttInstallProgress.requestCancel(family.id)
            }
        }
        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)

        // Application context: this outlives the dialog AND the activity by design.
        val appContext = activity.applicationContext
        Thread({
            val result = SttModelInstaller.install(appContext, family)
            // 🔴 Selection first, UI second — and from the APP context, not the activity. The
            // user tapped this model to USE it; that intent must not depend on a screen
            // surviving a 2-minute extraction.
            if (result is SttModelInstaller.Result.Installed) {
                SttAutoSelect.apply(appContext, SttModelRegistry.providerValue(family))
            }
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                if (activity.isFinishing || activity.isDestroyed) {
                    Log.i(TAG, "${family.id} finished after settings closed: $result")
                    return@runOnUiThread
                }
                when (result) {
                    is SttModelInstaller.Result.Installed -> onInstalled()
                    is SttModelInstaller.Result.Cancelled -> Unit // user's own act; no card
                    is SttModelInstaller.Result.AlreadyRunning ->
                        report(activity, family, "This model is already downloading.")
                    is SttModelInstaller.Result.Failed ->
                        // The reason is already logged as a DROP: by the installer; surfacing it
                        // verbatim is what makes a failed download visible rather than a row that
                        // quietly stays un-selectable.
                        report(activity, family, "Download failed — ${result.reason}")
                }
            }
        }, "stt-install-${family.id}").start()
    }

    /** One-button outcome card. [body] is the user-facing reason — see the Failed branch. */
    private fun report(activity: Activity, family: SttModelRegistry.Family, body: String) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = family.label
        view.findViewById<TextView>(R.id.dialogMessage).text = body
        val dialog = house(activity, view)
        view.findViewById<Button>(R.id.buttonNegative).visibility = android.view.View.GONE
        view.findViewById<Button>(R.id.buttonPositive).apply {
            text = "Close"
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /** The house window treatment — same as SettingsDialogHelper's confirm dialogs. */
    private fun house(activity: Activity, view: android.view.View): AlertDialog =
        AlertDialog.Builder(activity).setView(view).setCancelable(true).create().apply {
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes?.apply { dimAmount = 0.5f }
            }
        }
}
