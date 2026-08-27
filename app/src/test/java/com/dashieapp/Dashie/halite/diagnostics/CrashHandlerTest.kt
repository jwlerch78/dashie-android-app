package com.dashieapp.Dashie.halite.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards against the crash-handler recursion loop that surfaced on rockchip
 * rk3576s_u (June 2026): the default-handler chain formed a cycle (chromium's
 * JavaExceptionReporter re-installed itself and captured CrashHandler as its
 * delegate, while CrashHandler held it — or, after a double install, itself —
 * as defaultHandler). Each loop re-ran buildCrashReport(), pegging the main
 * thread until the OS fired a 5s ANR. The ANR tombstone showed the handler
 * recursing 244 deep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashHandlerTest {

    private lateinit var context: Context
    private var originalDefault: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalDefault = Thread.getDefaultUncaughtExceptionHandler()
        resetHandlingFlag()
        setDefaultHandlerField(null)
    }

    @After
    fun tearDown() {
        // Restore JVM-wide state so we don't pollute other tests in the runner.
        Thread.setDefaultUncaughtExceptionHandler(originalDefault)
        resetHandlingFlag()
    }

    /**
     * The headline regression: a default handler that re-invokes CrashHandler
     * (i.e. a cyclic chain) must NOT cause infinite recursion. With the
     * re-entrancy guard, the re-entrant call short-circuits, so the chained
     * handler is invoked exactly once. Without the guard this StackOverflows
     * (or, on-device, ANRs).
     */
    @Test
    fun `re-entrant default handler does not recurse infinitely`() {
        val chainInvocations = AtomicInteger(0)
        val reentrant = Thread.UncaughtExceptionHandler { t, e ->
            chainInvocations.incrementAndGet()
            // Simulate chromium's reporter chaining back into us.
            CrashHandler.uncaughtException(t, e)
        }
        setDefaultHandlerField(reentrant)

        // Must complete without StackOverflowError.
        CrashHandler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(
            "chained handler should run once; the re-entry must short-circuit",
            1,
            chainInvocations.get()
        )
    }

    /** install() must refuse to capture itself as the delegate (self-loop root). */
    @Test
    fun `install refuses to capture self as default handler`() {
        // Pretend we're already the registered default (e.g. a double install).
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)
        setDefaultHandlerField(null)

        CrashHandler.install(context)

        assertNotSame(
            "defaultHandler must never be CrashHandler itself",
            CrashHandler,
            defaultHandlerField()
        )
    }

    /** A normal install captures the previous handler and registers itself. */
    @Test
    fun `install captures previous handler and registers itself`() {
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        setDefaultHandlerField(null)

        CrashHandler.install(context)

        assertSame("previous handler should be captured", previous, defaultHandlerField())
        assertSame(
            "CrashHandler should be the registered default",
            CrashHandler,
            Thread.getDefaultUncaughtExceptionHandler()
        )
    }

    // ── reflection helpers (CrashHandler is a singleton object) ──────────────

    private fun resetHandlingFlag() {
        val f = CrashHandler.javaClass.getDeclaredField("isHandling").apply { isAccessible = true }
        (f.get(CrashHandler) as AtomicBoolean).set(false)
    }

    private fun setDefaultHandlerField(handler: Thread.UncaughtExceptionHandler?) {
        val f = CrashHandler.javaClass.getDeclaredField("defaultHandler").apply { isAccessible = true }
        f.set(CrashHandler, handler)
    }

    private fun defaultHandlerField(): Thread.UncaughtExceptionHandler? {
        val f = CrashHandler.javaClass.getDeclaredField("defaultHandler").apply { isAccessible = true }
        return f.get(CrashHandler) as Thread.UncaughtExceptionHandler?
    }
}
