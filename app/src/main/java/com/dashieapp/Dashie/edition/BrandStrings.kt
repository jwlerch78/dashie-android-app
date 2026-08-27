package com.dashieapp.Dashie.edition

import android.content.Context
import com.dashieapp.Dashie.R

/**
 * The product name as it appears in prose — "Dashie" or "Chickadee", resolved per edition.
 *
 * ## Why this exists
 *
 * `@string/brand_name` is the seam (main/ says Dashie, `src/chickadeeBrand/res` overrides it),
 * but reaching it from Kotlin is a mouthful — `context.getString(com.dashieapp.Dashie.R.string
 * .brand_name)` — and a mouthful inside a sentence is exactly what makes the next author write
 * `"…Dashie…"` instead. Half the residue this was created to clean up sits in files that already
 * had a `Context` in hand.
 *
 * ## Which shape to use where
 *
 * Both are already precedent in this tree and both are correct:
 *
 *  - **A string resource with a format argument** — `getString(R.string.x, context.brandName())`.
 *    Right when the sentence itself might need translating or per-edition rewording.
 *  - **Inline interpolation** — `"…${context.brandName()} console"`. Right when the sentence is
 *    edition-neutral and only the NAME varies, which is the overwhelming majority.
 *
 * 🔴 What is NOT acceptable is the third shape: a copy of the sentence per source set. That is
 * standing rule 1's target — the copies drift the moment one is edited, and "Use Dashie without
 * Internet" survived the Chickadee split unnoticed until it was photographed on a Fire tablet.
 *
 * ⚠️ Not every "Dashie" in a user-visible string is brand prose. Filesystem paths
 * (`/Pictures/Dashie`) and package names are FUNCTIONAL — rewriting them moves a user's photo
 * folder or breaks an intent. Change the ones a reader reads, not the ones a machine resolves.
 */
fun Context.brandName(): String = getString(R.string.brand_name)
