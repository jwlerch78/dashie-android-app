package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URL

/**
 * The HA URL prefs trim leading/trailing whitespace. A stray trailing space
 * ("…:8123 ") made java.net.URL throw MalformedURLException ("For input
 * string: 8123 ") in HaConnectionMonitor's ping loop (observed on rockchip
 * rk3576s_u, June 2026) and corrupted the appended dashboard path on load.
 *
 * Trimming on *get* is what repairs already-stored bad values on existing
 * devices, so each test also writes the raw (untrimmed) value directly to
 * SharedPreferences to exercise that path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HaUrlTrimTest {

    private lateinit var context: Context
    private lateinit var connection: ConnectionPreferences
    private lateinit var performance: PerformancePreferences

    private val dirtyUrl = "http://192.168.1.103:8123 "
    private val cleanUrl = "http://192.168.1.103:8123"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        connection = ConnectionPreferences(context)
        performance = PerformancePreferences(context)
        // Both pref classes share this SharedPreferences file.
        context.getSharedPreferences("dashie_lite_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** Write a value straight to disk, bypassing the trimming setter. */
    private fun writeRaw(key: String, value: String) {
        context.getSharedPreferences("dashie_lite_prefs", Context.MODE_PRIVATE)
            .edit().putString(key, value).commit()
    }

    @Test
    fun `haUrl trims on set`() {
        connection.haUrl = dirtyUrl
        assertEquals(cleanUrl, connection.haUrl)
    }

    @Test
    fun `haUrl trims an already-stored value on get`() {
        writeRaw("ha_url", dirtyUrl)
        assertEquals(cleanUrl, connection.haUrl)
    }

    @Test
    fun `haBaseUrl trims an already-stored value on get`() {
        writeRaw("ha_base_url", dirtyUrl)
        assertEquals(cleanUrl, connection.haBaseUrl)
    }

    @Test
    fun `localHaUrl trims an already-stored value on get`() {
        writeRaw("local_ha_url", dirtyUrl)
        assertEquals(cleanUrl, performance.localHaUrl)
    }

    /**
     * End-to-end reproduction: building a java.net.URL from the stored value —
     * exactly what HaConnectionMonitor.pingHaWithReason does — must no longer
     * throw, and the parsed port must be correct.
     */
    @Test
    fun `stored url is parseable by java net URL after repair`() {
        writeRaw("local_ha_url", dirtyUrl)
        val parsed = URL(performance.localHaUrl) // would throw MalformedURLException pre-fix
        assertEquals(8123, parsed.port)
        assertEquals("192.168.1.103", parsed.host)
    }
}
