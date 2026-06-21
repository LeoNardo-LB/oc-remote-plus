package dev.leonardo.ocremotev2.ui.screens.chat.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An image attachment ready to send. */
internal data class ImageAttachment(
    val uri: Uri,
    val mime: String,
    val filename: String,
    val dataUrl: String // "data:<mime>;base64,..."
)

internal data class PreparedAttachment(
    val attachment: ImageAttachment,
    val comparison: AttachmentComparison? = null
)

internal data class AttachmentComparison(
    val originalBytes: Int,
    val optimizedBytes: Int,
    val originalEstimatedTokens: Int,
    val optimizedEstimatedTokens: Int
)

internal fun decodeDataUrlBytes(dataUrl: String): ByteArray? {
    val encoded = dataUrl.substringAfter(',', missingDelimiterValue = "")
    if (encoded.isBlank()) return null
    return try {
        Base64.decode(encoded, Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}

internal fun decodePartFileBytes(file: dev.leonardo.ocremotev2.domain.model.Part.File): ByteArray? {
    val url = file.url ?: return null
    val encoded = if (url.contains(',')) url.substringAfter(',') else url
    if (encoded.isBlank()) return null
    return try {
        Base64.decode(encoded, Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}

internal fun extensionForMime(mime: String): String {
    return when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "img"
    }
}

internal fun imageThumbnailModel(attachment: ImageAttachment): Any {
    if (attachment.uri.scheme.equals("data", ignoreCase = true)) {
        val encoded = attachment.dataUrl.substringAfter(',', missingDelimiterValue = "")
        if (encoded.isNotBlank()) {
            return try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (_: Exception) {
                attachment.dataUrl
            }
        }
    }
    return attachment.uri
}

internal fun estimateVisionTokens(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 0
    return ((width.toLong() * height.toLong()) / 750.0).toInt()
}

internal suspend fun buildAttachmentFromUri(
    contentResolver: ContentResolver,
    uri: Uri,
    compressImages: Boolean,
    maxLongSidePx: Int = 1440,
    webpQuality: Int = 60
): PreparedAttachment? = withContext(Dispatchers.IO) {
    val mimeType = contentResolver.getType(uri) ?: "image/png"
    val acceptedTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf")
    if (mimeType !in acceptedTypes) return@withContext null

    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
    val originalFilename = uri.lastPathSegment?.substringAfterLast('/') ?: "image.png"

    val shouldOptimize = compressImages && (mimeType == "image/png" || mimeType == "image/jpeg")
    if (!shouldOptimize) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }

    val srcWidth = bitmap.width
    val srcHeight = bitmap.height
    val longSide = maxOf(srcWidth, srcHeight)
    val resizeEnabled = maxLongSidePx > 0
    val scale = if (resizeEnabled && longSide > maxLongSidePx) {
        maxLongSidePx.toFloat() / longSide.toFloat()
    } else {
        1f
    }
    val outWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
    val outHeight = (srcHeight * scale).toInt().coerceAtLeast(1)
    val resizedBitmap = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true) else bitmap

    val output = java.io.ByteArrayOutputStream()
    @Suppress("DEPRECATION")
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }
    val compressed = resizedBitmap.compress(format, webpQuality.coerceIn(1, 100), output)
    if (resizedBitmap !== bitmap) {
        resizedBitmap.recycle()
    }
    bitmap.recycle()

    if (!compressed) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }

    val webpBytes = output.toByteArray()
    if (scale >= 0.999f && webpBytes.size >= bytes.size) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }
    val base64 = Base64.encodeToString(webpBytes, Base64.NO_WRAP)
    val optimizedFilename = originalFilename.substringBeforeLast('.', originalFilename) + ".webp"
    return@withContext PreparedAttachment(
        attachment = ImageAttachment(
            uri = uri,
            mime = "image/webp",
            filename = optimizedFilename,
            dataUrl = "data:image/webp;base64,$base64"
        ),
        comparison = AttachmentComparison(
            originalBytes = bytes.size,
            optimizedBytes = webpBytes.size,
            originalEstimatedTokens = estimateVisionTokens(srcWidth, srcHeight),
            optimizedEstimatedTokens = estimateVisionTokens(outWidth, outHeight)
        )
    )
}
