package ir.swiftvpn.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a share link as a QR bitmap, so a profile can be moved to another
 * phone by scanning it.
 *
 * Pure encoder — no camera, no permission. Scanning lives in the capture screen.
 */
object QrCode {

    /**
     * Encodes [text] as a square QR bitmap of [size] px.
     *
     * Error-correction level L, not the library default M: share links are long
     * (a VLESS+Reality link runs past 200 characters) and higher correction costs
     * modules, which shrinks each one on screen and makes the code harder for a
     * camera to resolve. L is ample when the code is being read off a bright
     * display a few centimetres away.
     *
     * Returns null rather than throwing when the text is too long to encode at
     * all, so the caller can show a message instead of crashing.
     */
    fun encode(text: String, size: Int = 720): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // Quiet zone in modules. The spec asks for 4; 1 is enough on screen
            // and leaves more pixels for the payload. The dialog adds visual
            // padding around the bitmap anyway.
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)

        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        // Always black-on-white regardless of app theme: inverted QR codes are
        // rejected by many scanners, so the code keeps its own colours and the
        // dialog frames it in a white card.
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
