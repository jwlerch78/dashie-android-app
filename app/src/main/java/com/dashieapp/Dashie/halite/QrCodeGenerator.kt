package com.dashieapp.Dashie.halite

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Utility for generating QR codes for license purchase URLs.
 */
object QrCodeGenerator {

    /**
     * Generate a QR code bitmap for the given URL.
     *
     * @param url The URL to encode
     * @param size The width/height of the QR code in pixels
     * @param foregroundColor Color of the QR modules (default: black). Use the
     *   Dashie orange (0xFFFF9500) for brand-tinted codes.
     * @param backgroundColor Color behind the modules (default: white)
     * @return A Bitmap containing the QR code, or null if generation fails
     */
    fun generateQrCode(
        url: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,  // Smaller margin
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size, hints)

            // ARGB_8888 needed when foreground/background aren't pure RGB565-safe colors.
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) foregroundColor else backgroundColor)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
