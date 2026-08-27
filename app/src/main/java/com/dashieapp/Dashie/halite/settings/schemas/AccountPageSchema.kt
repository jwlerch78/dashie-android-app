package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection

/**
 * Schema definition for the Account settings page.
 *
 * Mode-aware layout:
 * - Signed in: Account info (email), Mode (kiosk toggle), Security (PIN)
 * - Not signed in: Account (sign in action), Security (PIN)
 */
object AccountPageSchema {

    private val isLinked = Condition.IsTrue("account.isLinked")

    /** Auto-refill row visibility. On amazon the row exists ONLY while auto-refill is ON, so the
     *  single reachable action is turning it OFF (a spend-reducing control — compliant); an OFF
     *  state would render an enable affordance pointing at external payment, so it's hidden.
     *  Other flavors show it in both states. See 20260714_TABLET_AUTOREFILL_UX §3. */
    private val autorefillRowGate: Condition? =
        if (com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon")
            Condition.IsTrue("credits.autorefillEnabled")
        else null   // no extra gate — the section's isLinked + hasCredits already scope it
    private val notLinked = Condition.IsFalse("account.isLinked")

    /** Advanced tablet-control visibility — see AdvancedPageSchema for the full rationale. */
    private val advancedVisible = Condition.Or(listOf(
        Condition.IsTrue("home_assistant.enabled"),
        Condition.IsTrue("advanced.tabletControlsEnabled")
    ))

    /**
     * The account sections carry NO brand (2026-07-29). There is exactly one account —
     * one `auth.users` row, one Stripe customer — and naming it after either product invited
     * the question "which of my two accounts is this?", which has no answer. The brand belongs
     * on the ENTITLEMENTS below (Dashie Cloud = the usage meter, Dashie Plan = the family
     * subscription), because those genuinely are two separable things you can hold zero, one,
     * or both of. Briefly hub-branded earlier the same day; that only moved the confusion.
     */
    fun create() = SettingsPageSchema(
        id = "account",
        title = "Account",
        // 🔴 `listOfNotNull` + `takeIf`, NOT a `visibleWhen` — the distinction is the point.
        // A section hidden by a Condition is still IN the schema and is one state-change away
        // from rendering; an absent one leaves nothing to reach. Same rule as the cloud-calendar
        // rows (CalendarPageSchema), and the same reason: for an edition that lacks a concept,
        // the option must not EXIST rather than exist-and-be-hidden.
        //
        // The page itself STAYS in both editions (2026-08-03) — PIN Settings lives here and
        // is a real Chickadee function, so nothing is re-homed and nothing can be silently lost.
        sections = listOfNotNull(
            accountInfoSection(),
            kioskAccountInfoSection(),
            // The permanent sign-in offer. Gated on the SAME capability as the Supabase
            // credentials, because it is the same question — does this product have a cloud
            // account concept at all.
            //
            // ⚠️ Why this is not merely tidy: `signInSection` is gated on `notLinked`, and a
            // Chickadee device is NEVER linked — so it did not sometimes appear, it appeared
            // ALWAYS. A permanent invitation to sign into a Dashie account was the most visible
            // account surface in the account-free edition, hiding behind a condition that reads
            // like it would hide it.
            signInSection().takeIf { com.dashieapp.Dashie.edition.EditionSeams.hasAccounts }
        ) +
            // The billing sections ("Dashie Plan" / "Subscribe to Dashie") live in the edition
            // source set, not here: they name the PAID product, so in the account-free edition
            // they must be absent from the artifact rather than merely gated off. Concatenated
            // rather than `takeIf`-ed because the seam owns how many sections there are — see
            // `EditionSeams.accountBillingSections`. Page order is unchanged.
            com.dashieapp.Dashie.edition.EditionSeams.accountBillingSections +
            listOfNotNull(
                creditsSection(),
                securitySection()
            )
    )

    // ── Dashie Cloud (FB27): the USAGE meter, when the account has a credit balance ──
    //
    // Named for the product, not the noun ("Credits"), because this page carries TWO
    // independent entitlements on ONE identity and the old headers hid that: credits are the
    // Dashie Cloud meter (hosted STT/TTS/brain) and the plan below is the Dashie product
    // subscription. Nothing couples them — a Dashie plan grants no credits — so naming both
    // after their products is what stops "my Dashie subscription includes voice" from being a
    // reasonable misreading.
    //
    // T2h (2026-07-31): this header used to read "Chickadee Cloud", under a deliberate
    // decision (2026-07-28) that the meter stay Chickadee-branded even on Dashie
    // surfaces — monetization living under the open brand, Nabu Casa style. That decision
    // died with the Chickadee brand on 2026-07-30 (one brand, two editions). The meter is
    // still unconditional and still edition-neutral — it is just called Dashie Cloud now,
    // matching CreditUrls.CREDITS_NAME and the web add-credits page.
    private fun creditsSection() = SettingsSection(
        header = "Dashie Cloud",
        // Shown on ANY linked device, kiosk included (unlike Subscription). A linked kiosk is
        // provisioned with the household JWT + authUserId (KioskSessionProvisioner), so the balance
        // reader authenticates and the add-credits QR resolves an identity. And unlike the plan —
        // which is the kiosk's stale local copy — the balance is fetched LIVE (CreditBalanceReader),
        // so it's accurate on a kiosk. Gate is isLinked (not linkedNotKiosk) so haOnlyDisplay kiosks
        // still qualify; hasCredits keeps it off accounts that have never had a balance.
        visibleWhen = Condition.And(listOf(isLinked, Condition.IsTrue("credits.hasCredits"))),
        items = listOf(
            SchemaItem.Info(
                id = "credit_balance",
                label = "Balance",
                valueKey = "credits.balance"
            ),
            // WS-L.3 P2 — the native kill switch. Required on EVERY flavor including amazon:
            // standing authority to charge a stored card must be revocable from the device
            // that's spending, without a trip to the console.
            //
            // Amazon compliance (20260714_TABLET_AUTOREFILL_UX §3): when auto-refill is ON this
            // row is state + a spend-REDUCING control, which is compliant (Netflix/Spotify
            // shape). When it's OFF the row must be ABSENT on amazon rather than showing an
            // enable affordance — a toggle labelled "turn on auto-refill" points at an external
            // purchase. Hence the flavor-dependent visibility below: kill-switch-only on amazon,
            // both directions elsewhere.
            SchemaItem.Toggle(
                id = "autorefill_enabled",
                label = "Auto-Refill Credits",
                settingKey = "credits.autorefillEnabled",
                sublabelKey = "credits.autorefillSummary",
                visibleWhen = autorefillRowGate,
                onChanged = null   // the async interceptor owns persistence (server write)
            )
        ) + (
            // Amazon Appstore IAP compliance: no in-app path to web billing (same as Subscribe).
            // Orange Action (like Manage account) → the add-credits QR modal.
            if (com.dashieapp.Dashie.BuildConfig.FLAVOR != "amazon") listOf(
                SchemaItem.Action(
                    id = "add_credits",
                    label = "Add Credits",
                    action = "accountAddCredits",
                    destructive = false
                )
            ) else emptyList()
        ) + listOf(
            // Brand-split T4: the free alternative to buying credits, in the SAME section
            // as the Add Credits row. Cloud inference is metered because it costs real
            // money — but that only reads as a meter rather than a paywall if the user can
            // see, without leaving the view, that they can run it for nothing.
            //
            // Present on EVERY flavor, amazon included: it routes to a free alternative,
            // not to payment, so the IAP rule that strips the row above doesn't apply. On
            // amazon it's the only row here besides Balance, which is the correct shape —
            // state plus a free path, no purchase direction.
            //
            // 🔵 **Dashie-only BY DESIGN, and this note exists because the mechanism hides the
            // intent** (the ruling, 2026-08-03). "Every flavor" above is about FLAVORS; this
            // row also sits inside `creditsSection`, gated `isLinked AND hasCredits`, so in the
            // account-free edition it never renders. That reads as an accident — a BYOK row
            // absent from the edition whose whole premise is BYOK — and the next reader would
            // reasonably "fix" it.
            //
            // It is not a gap: **Chickadee configures keys in its CONSOLE**, which has API Keys
            // and Local Engines pages. A wall tablet is a poor place to type an API key, and the
            // console is that edition's admin surface. So the capability exists; only this
            // ON-DEVICE entry point is Dashie's.
            SchemaItem.Action(
                id = "byok_or_local",
                label = "Use your own keys or a local model",
                action = "accountUseOwnKeysOrLocal",
                destructive = false
            )
        )
    )

    // ── Signed in (normal device): show email + sign out ──────────────

    private fun accountInfoSection() = SettingsSection(
        header = "Account",
        // Shown for a real Sign Out: full-dashboard accounts (not haOnlyDisplay) AND direct
        // voice-only accounts (haOnlyDisplay, but a per-device login the user did here via
        // flow=voice — Sign Out sticks). Only a household-SHARED kiosk is excluded: it silently
        // re-provisions on the next boot, so a local Sign Out is a lie — that gets the
        // "manage in console" section below. Discriminator: account.haOnlyVoiceSignup.
        visibleWhen = Condition.And(listOf(
            isLinked,
            Condition.Or(listOf(
                // SESSION origin, not the display flag — see kioskProvisionedSession.
                Condition.IsFalse("account.kioskProvisionedSession"),
                Condition.IsTrue("account.haOnlyVoiceSignup")
            ))
        )),
        items = listOf(
            SchemaItem.Info(
                id = "account_email",
                label = "Email",
                valueKey = "account.email"
            ),
            SchemaItem.Navigation(
                id = "sign_out",
                label = "Sign Out",
                navigateTo = "ext:sign_out"
            )
        )
    )

    // ── Signed in as a KIOSK (D2): logged in, but still displaying Home Assistant ──
    //
    // This tablet authorized itself into the household account via Home Assistant (Kiosk Real
    // Login). Two things must be honest here:
    //
    //  1. **It IS signed in** — D1's rule is that a silent, human-free provision must never be an
    //     invisible one. The device should be able to tell you whose account it joined.
    //  2. **Signing out locally would not stick.** While household sharing is on, the tablet
    //     re-provisions on its next boot. So we do NOT offer a Sign Out button that quietly undoes
    //     itself; we point at household sharing (D6), which does stop re-provisioning.
    //
    // ⚠️ **"Remove device" in the console is NOT an off-switch** (Thread M case 2b, 2026-08-01):
    // it is a SOFT delete — both the D5 liveness check and the register guard ignore `is_active`,
    // so the session keeps working and the device re-provisions anyway. It was previously named
    // here as one of two controls that work; it is not one, and naming it was worse than naming
    // nothing because it sent the user to a control that silently does nothing. Until D lands a
    // shared live-grant predicate, **household sharing off is the only thing that ends this
    // session**, plus the real Sign Out that a non-kiosk session now correctly gets.
    private fun kioskAccountInfoSection() = SettingsSection(
        header = "Account",
        // Only a household-SHARED kiosk (re-provisions on boot) — NOT a direct voice-only
        // account (account.haOnlyVoiceSignup), which shows a real Sign Out above.
        visibleWhen = Condition.And(listOf(
            isLinked,
            Condition.IsTrue("account.kioskProvisionedSession"),
            Condition.IsFalse("account.haOnlyVoiceSignup")
        )),
        items = listOf(
            SchemaItem.Info(
                id = "kiosk_account_email",
                label = "Signed in via Home Assistant",
                valueKey = "account.email"
            ),
            // No local "Sign Out": while Household Sharing is on, this tablet silently
            // re-provisions on its next boot, so the button would undo itself. The real
            // off-switches live where they can stick — remove the device (D5) or turn sharing
            // off (D6) — both in the console. Kept to one short line; the long explanation
            // belongs in docs, not on a wall tablet. Value names whichever add-on serves the
            // gateway ("Manage in Dashie for Home Assistant" on a published hub, else
            // "Manage in Dashie Console") — ConnectionSettingsWiring.account.disconnectHint.
            SchemaItem.Info(
                id = "kiosk_signout_hint",
                label = "Disconnect",
                valueKey = "account.disconnectHint"
            )
        )
    )

    // ── Not signed in: show sign in action ───────────────────────────
    //
    // Unbranded for the same reason as the signed-in sections, and with extra force here:
    // before you have an account there is nothing to brand, and asking a signed-out user to
    // pick between two product names is the choice that made this page confusing. "Create an
    // Account" makes the identity; what it entitles you to is decided afterwards.
    private fun signInSection() = SettingsSection(
        header = "Account",
        visibleWhen = notLinked,
        items = listOf(
            SchemaItem.Navigation(
                id = "sign_in",
                label = "Sign In",
                navigateTo = "ext:sign_in"
            ),
            SchemaItem.Navigation(
                id = "sign_up",
                label = "Create an Account",
                navigateTo = "ext:sign_up"
            )
        )
    )

    // ── Subscription status (only when signed in) ────────────────────

    // Amazon-flavor extra gate: the Subscription section is hidden in its
    // entirety. Showing ANY subscription status (Active, Complimentary,
    // even Free Trial / Trial Ended) on the Account page is the kind of
    // hint toward "this app has a payable subscription system" that
    // Amazon's IAP policy bars. Trial state is still surfaced in the
    // Control Center promo strip (which itself has its Purchase Now
    // button gated off on amazon), so the user doesn't lose all visibility
    // — just no settings-page row that name-drops the subscription model.
    // Non-amazon flavors keep the original isLinked behavior unchanged.
    private val amazonSubscriptionGate: Condition =
        if (com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon")
            Condition.Never
        else
            isLinked

    // ── Mode: kiosk toggle (only when signed in) ─────────────────────

    private fun modeSection() = SettingsSection(
        header = "Mode",
        visibleWhen = isLinked,
        items = listOf(
            SchemaItem.Toggle(
                id = "force_kiosk",
                label = "Use Kiosk Mode",
                sublabel = "Show only Home Assistant dashboards",
                settingKey = "account.forceKioskMode",
                onChanged = "accountSwitchMode"
            )
        )
    )

    // ── Security: PIN protection (always visible) ────────────────────

    private fun securitySection() = SettingsSection(
        header = "Security",
        visibleWhen = advancedVisible,
        items = listOf(
            SchemaItem.Navigation(
                id = "set_pin",
                label = "PIN Settings",
                navigateTo = "ext:set_pin"
            )
        )
    )

    // Manage account (QR to the mobile site — the owner manages/deletes from their own
    // phone; never from the shared tablet) moved into subscriptionSection (FB27).
    // Callback: SettingsCallbackWiring "accountManageAccount".
}
