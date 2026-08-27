package com.dashieapp.Dashie.halite.wiring

import android.util.Log
import com.dashieapp.Dashie.halite.HaliteComponentRegistry

/**
 * Wires photos-widget-specific JS bridge callbacks (openPhotosExplorer,
 * openPhotosUpload, getPhotosSourceType, setPhotosSourceType) to the
 * native PhotoWidgetController.
 *
 * Menu rendering is NOT here — that's handled by WidgetOverlayWiring via
 * the generic widget overlay bridge. This file is only for photos-domain
 * actions: opening the fullscreen explorer, the upload QR dialog, and
 * reading/writing the active photo source type.
 *
 * Bridge callbacks fire on the JavaBridge thread; UI work is wrapped in
 * runOnUiThread to avoid CalledFromWrongThreadException.
 */
object PhotosBridgeWiring {
    private const val TAG = "PhotosBridgeWiring"

    fun wirePhotosBridgeCallbacks(registry: HaliteComponentRegistry) {
        val jsBridge = registry.jsBridge ?: run {
            Log.w(TAG, "JS bridge not ready; photos bridge callbacks NOT wired")
            return
        }
        val delegate = jsBridge.layoutDelegate

        delegate.onPhotosExplorerOpen = { _ ->
            registry.activityRef.runOnUiThread {
                registry.photoWidgetController?.openExplorer()
                    ?: Log.w(TAG, "openPhotosExplorer: photoWidgetController is null")
            }
        }
        delegate.onPhotosUploadOpen = { _ ->
            registry.activityRef.runOnUiThread {
                registry.photoWidgetController?.showUploadQRDialog()
                    ?: Log.w(TAG, "openPhotosUpload: photoWidgetController is null")
            }
        }
        // Manual cycle from the JS photos-menu 'activated' state.
        delegate.onPhotoNext = { _ ->
            registry.activityRef.runOnUiThread {
                registry.photoWidgetController?.next()
                    ?: Log.w(TAG, "nextPhoto: photoWidgetController is null")
            }
        }
        delegate.onPhotoPrevious = { _ ->
            registry.activityRef.runOnUiThread {
                registry.photoWidgetController?.previous()
                    ?: Log.w(TAG, "previousPhoto: photoWidgetController is null")
            }
        }
        // Sync getter — must return immediately on the calling thread.
        delegate.onPhotosSourceTypeQuery = {
            registry.photoWidgetController?.currentSourceType() ?: ""
        }
        delegate.onPhotosSourceTypeSet = { sourceType ->
            registry.activityRef.runOnUiThread {
                registry.photoWidgetController?.setSourceType(sourceType)
                    ?: Log.w(TAG, "setPhotosSourceType: photoWidgetController is null")
            }
        }
        // Sync getter — JS reads this when the user d-pads onto the photo
        // widget so the menu can skip the activated/hint state and open
        // the Upload bar directly.
        delegate.onPhotosShowingDashieCloudEmptyQuery = {
            registry.photoWidgetController?.isShowingDashieCloudEmptyState() ?: false
        }

        Log.i(TAG, "Photos bridge callbacks wired")
    }
}
