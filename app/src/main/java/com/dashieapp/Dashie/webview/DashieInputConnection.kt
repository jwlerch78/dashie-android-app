package com.dashieapp.Dashie.webview

import android.util.Log
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * Custom InputConnectionWrapper that helps maintain a stable connection
 * between the keyboard and WebView input fields.
 *
 * This wrapper intercepts InputConnection calls and ensures they don't fail
 * silently when the connection becomes temporarily inactive (a known issue
 * on Fire tablets).
 */
class DashieInputConnection(
    target: InputConnection,
    mutable: Boolean
) : InputConnectionWrapper(target, mutable) {

    companion object {
        private const val TAG = "DashieInputConnection"
    }

    /**
     * Override commitText to ensure text commits work properly.
     * On Fire tablets, this can fail silently when InputConnection is "inactive".
     */
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        return try {
            super.commitText(text, newCursorPosition)
        } catch (e: Exception) {
            Log.w(TAG, "commitText failed: ${e.message}")
            false
        }
    }

    /**
     * Override deleteSurroundingText to handle backspace properly.
     */
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        return try {
            super.deleteSurroundingText(beforeLength, afterLength)
        } catch (e: Exception) {
            Log.w(TAG, "deleteSurroundingText failed: ${e.message}")
            false
        }
    }

    /**
     * Override setComposingText for IME composition.
     */
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        return try {
            super.setComposingText(text, newCursorPosition)
        } catch (e: Exception) {
            Log.w(TAG, "setComposingText failed: ${e.message}")
            false
        }
    }

    /**
     * Override finishComposingText to complete IME composition.
     */
    override fun finishComposingText(): Boolean {
        return try {
            super.finishComposingText()
        } catch (e: Exception) {
            Log.w(TAG, "finishComposingText failed: ${e.message}")
            false
        }
    }

    /**
     * Override getTextBeforeCursor - this is the one that fires the
     * "inactive InputConnection" warnings on Fire tablets.
     */
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        return try {
            super.getTextBeforeCursor(n, flags)
        } catch (e: Exception) {
            Log.w(TAG, "getTextBeforeCursor failed: ${e.message}")
            ""
        }
    }

    /**
     * Override getTextAfterCursor.
     */
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        return try {
            super.getTextAfterCursor(n, flags)
        } catch (e: Exception) {
            Log.w(TAG, "getTextAfterCursor failed: ${e.message}")
            ""
        }
    }

    /**
     * Override getSelectedText.
     */
    override fun getSelectedText(flags: Int): CharSequence? {
        return try {
            super.getSelectedText(flags)
        } catch (e: Exception) {
            Log.w(TAG, "getSelectedText failed: ${e.message}")
            null
        }
    }
}
