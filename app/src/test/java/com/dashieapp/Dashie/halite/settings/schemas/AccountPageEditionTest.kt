package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.edition.EditionSeams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the edition split on the Account page.
 *
 * ## Why this needs a test rather than a reading
 *
 * The defect it guards is **invisible on inspection**: `signInSection` is gated on
 * `notLinked`, which reads like a condition that would hide it — and on Dashie it does. But a
 * Chickadee device is **never** linked, so `notLinked` is permanently true and the section
 * rendered **always**: a standing invitation to sign into a Dashie account, in the account-free
 * edition. Nobody spotted it for months because the guard looks like it is doing the job.
 *
 * ## Why it asserts on CONSTRUCTION, not on visibility
 *
 * The fix builds the section conditionally (`listOfNotNull` + `takeIf`) rather than adding
 * another `visibleWhen`. A hidden section is still in the schema and is one state-change from
 * rendering; an absent one leaves nothing to reach. So the test asks *"is it in the list at
 * all"* — the property actually being claimed.
 *
 * This runs per variant: the `chickadeeDev` unit-test variant compiles `chickadeeStub`'s
 * `EditionSeams`, the Dashie variants compile the real one. Both directions are asserted from
 * one body, keyed off the same capability the production code reads, so the test cannot pass
 * vacuously by agreeing with itself about which edition it is in.
 */
class AccountPageEditionTest {

    private fun sectionHeaders() = AccountPageSchema.create().sections.map { it.header }

    @Test
    fun `sign-in section exists only where the product has accounts`() {
        val hasSignIn = AccountPageSchema.create().sections.any { section ->
            section.items.any { it.id == "sign_in" || it.id == "sign_up" }
        }
        assertEquals(
            "The sign-in/sign-up section must be present iff this edition has accounts. " +
                "It is gated on `notLinked`, which is PERMANENTLY true in an account-free " +
                "edition — so a Condition would never have hidden it.",
            EditionSeams.hasAccounts,
            hasSignIn
        )
    }

    @Test
    fun `billing sections exist only where the product has a plan to sell`() {
        // Same defect shape as the sign-in section, one page down: `subscriptionGate` requires
        // `account.isLinked`, permanently false in the account-free edition — so the rows never
        // rendered there and still compiled "Dashie Plan" / "Subscribe to Dashie" into that
        // edition's artifact. Asserting on CONSTRUCTION is again the property actually claimed:
        // they must be ABSENT, not hidden.
        val schema = AccountPageSchema.create()
        val hasPlanHeader = schema.sections.any { it.header == "Dashie Plan" }
        val hasSubscribeRow = schema.sections.any { s -> s.items.any { it.id == "subscribe" } }

        assertEquals(
            "The billing sections must be present iff this edition has accounts. They come from " +
                "EditionSeams.accountBillingSections, whose Chickadee twin returns emptyList().",
            EditionSeams.hasAccounts,
            hasPlanHeader
        )
        assertEquals(
            "The Subscribe row must be present iff this edition has accounts.",
            EditionSeams.hasAccounts,
            hasSubscribeRow
        )
    }

    @Test
    fun `the credit meter stays in BOTH editions`() {
        // The counterpart assertion, and it is here to stop an over-correction rather than a
        // regression: "Dashie Cloud" is edition-NEUTRAL by decision (John, 2026-07-30/08-03) —
        // the meter is the same product on both editions. When the billing sections moved to
        // src/dashie it would have been easy, and wrong, to take this with them.
        val hasCredits = AccountPageSchema.create().sections.any { it.header == "Dashie Cloud" }
        assertTrue(
            "The Dashie Cloud credit section must exist in EVERY edition — it is deliberately " +
                "edition-neutral, not part of the paid-plan split.",
            hasCredits
        )
    }

    @Test
    fun `PIN settings survive in BOTH editions`() {
        // The page is kept in Chickadee precisely because PIN lives on it (John, 2026-08-03).
        // If a future change gates the whole page instead, this fails — which is the point:
        // losing PIN is the silent regression that decision was made to avoid.
        val hasPin = AccountPageSchema.create().sections.any { section ->
            section.items.any { it.id == "set_pin" }
        }
        assertTrue("PIN Settings must exist in every edition — it is a kiosk function, " +
            "not an account function, and it is why the Account page stays.", hasPin)
    }

    @Test
    fun `the page itself is never edition-gated away`() {
        assertTrue("The Account page must exist in both editions.", sectionHeaders().isNotEmpty())
    }
}
