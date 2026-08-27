package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dashieapp.Dashie.halite.preferences.ConnectionPreferences
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the crash where a SocketTimeoutException from the
 * `get_signed_urls` edge function escaped getNextPhoto() and killed the app
 * (crash report 2026-05-28). callEdgeFunction now catches IOException, so a
 * network failure during URL signing must degrade gracefully instead of
 * throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupabasePhotoSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: SupabasePhotoSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectionPrefs = ConnectionPreferences(context).apply { supabaseJwt = "test-jwt" }
        val screensaverPrefs = ScreensaverPreferences(context)

        source = SupabasePhotoSource(
            context = context,
            connectionPrefs = connectionPrefs,
            screensaverPrefs = screensaverPrefs,
            edgeFunctionUrl = server.url("/functions/v1/database-operations").toString(),
            // Short read timeout so NO_RESPONSE trips a SocketTimeoutException
            // quickly instead of waiting the production 60s.
            readTimeoutMs = 300L
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getNextPhoto does not throw when URL signing fails`() = runTest {
        // Route by operation: list_photos always succeeds (populates the index
        // with 2 unsigned photos); every get_signed_urls hangs with no response
        // until the read timeout fires — the exact SocketTimeoutException that
        // crashed the app. A signing timeout must therefore be swallowed, both
        // in sync() and (the regression) in getNextPhoto().
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return when {
                    body.contains("list_photos") -> MockResponse().setBody(
                        """{"photos":[
                            {"id":"p1","filename":"a.jpg","storage_path":"x/a.jpg"},
                            {"id":"p2","filename":"b.jpg","storage_path":"x/b.jpg"}
                        ]}"""
                    )
                    body.contains("get_signed_urls") ->
                        MockResponse().apply { socketPolicy = SocketPolicy.NO_RESPONSE }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val syncResult = source.sync()
        assertTrue("sync should survive a signing timeout (error=${syncResult.error})", syncResult.success)
        assertEquals(2, syncResult.photosFound)

        // Must return a fallback (null here — no cached photos) rather than
        // throwing. Any escaping exception fails the test.
        val photo = source.getNextPhoto()
        assertNull(photo)
    }
}
