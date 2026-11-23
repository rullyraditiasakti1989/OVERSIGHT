package com.vector.console.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

data class ReverseTarget(val label: String, val url: String)

data class FaceCrop(val bitmap: Bitmap, val faces: Int, val box: IntArray)

/** Average-hash comparison of two face crops. 0 = unrelated, 100 = identical. */
data class FaceMatch(val similarity: Int, val verdict: String, val distance: Int)

/**
 * Face pipeline: decode → detect → crop the largest face → hand the crop to a
 * reverse-image engine. Yandex accepts an anonymous multipart upload and returns a
 * cbir_id that resolves to a normal search page, so the whole chain works keyless.
 */
object FaceSearch {

    private const val YANDEX_UPLOAD =
        "https://yandex.com/images-apphost/image-download?cbird=111&images_avatars_size=preview"

    /** Link-based engines. Need a publicly reachable image URL. */
    fun links(imageUrl: String): List<ReverseTarget> {
        val encoded = URLEncoder.encode(imageUrl, "UTF-8")
        return listOf(
            ReverseTarget("GOOGLE LENS", "https://lens.google.com/uploadbyurl?url=$encoded"),
            ReverseTarget("YANDEX", "https://yandex.com/images/search?rpt=imageview&url=$encoded"),
            ReverseTarget("BING VISUAL", "https://www.bing.com/images/searchbyimage/upload?cbir=sbi&imgurl=$encoded"),
            ReverseTarget("TINEYE", "https://tineye.com/search?url=$encoded")
        )
    }

    suspend fun decode(bytes: ByteArray): Bitmap = withContext(Dispatchers.IO) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("File is not a readable image.")
    }

    suspend fun largestFace(bitmap: Bitmap): FaceCrop = suspendCancellableCoroutine { cont ->
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build()
        val detector = FaceDetection.getClient(options)
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                detector.close()
                if (cont.isActive) cont.resume(cropToLargest(bitmap, faces))
            }
            .addOnFailureListener {
                detector.close()
                if (cont.isActive) cont.resume(FaceCrop(bitmap, 0, IntArray(0)))
            }
    }

    /** Every detected face, largest first, each already cropped with margin. */
    suspend fun allFaces(bitmap: Bitmap): List<FaceCrop> = suspendCancellableCoroutine { cont ->
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setMinFaceSize(0.05f)
            .build()
        val detector = FaceDetection.getClient(options)
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                detector.close()
                val ordered = faces.sortedByDescending {
                    it.boundingBox.width().toLong() * it.boundingBox.height()
                }
                if (cont.isActive) cont.resume(ordered.map { cropOne(bitmap, it) }.filter { it.box.isNotEmpty() })
            }
            .addOnFailureListener {
                detector.close()
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    private fun cropOne(
        bitmap: Bitmap,
        face: com.google.mlkit.vision.face.Face
    ): FaceCrop {
        val box = face.boundingBox
        val marginX = (box.width() * 0.45f).toInt()
        val marginY = (box.height() * 0.45f).toInt()
        val left = max(0, box.left - marginX)
        val top = max(0, box.top - marginY)
        val right = min(bitmap.width, box.right + marginX)
        val bottom = min(bitmap.height, box.bottom + marginY)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return FaceCrop(bitmap, 0, IntArray(0))
        return FaceCrop(
            Bitmap.createBitmap(bitmap, left, top, width, height),
            1,
            intArrayOf(left, top, width, height)
        )
    }

    // ------------------------------------------------------------ compare

    /** 64-bit average hash on an 8x8 grayscale reduction. */
    fun averageHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val values = IntArray(64)
        var sum = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val pixel = small.getPixel(x, y)
                val luma = ((pixel shr 16 and 0xFF) * 299 +
                    (pixel shr 8 and 0xFF) * 587 +
                    (pixel and 0xFF) * 114) / 1000
                values[y * 8 + x] = luma
                sum += luma
            }
        }
        if (small !== bitmap) small.recycle()
        val mean = sum / 64.0
        var hash = 0L
        for (i in 0 until 64) {
            if (values[i] > mean) hash = hash or (1L shl i)
        }
        return hash
    }

    fun compare(a: Bitmap, b: Bitmap): FaceMatch {
        val distance = java.lang.Long.bitCount(averageHash(a) xor averageHash(b))
        val similarity = ((64 - distance) * 100) / 64
        val verdict = when {
            distance <= 6 -> "STRONG MATCH"
            distance <= 12 -> "LIKELY MATCH"
            distance <= 20 -> "POSSIBLE MATCH"
            else -> "DIFFERENT"
        }
        return FaceMatch(similarity, verdict, distance)
    }

    private fun cropToLargest(
        bitmap: Bitmap,
        faces: List<com.google.mlkit.vision.face.Face>
    ): FaceCrop {
        if (faces.isEmpty()) return FaceCrop(bitmap, 0, IntArray(0))
        val largest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return FaceCrop(bitmap, 0, IntArray(0))
        val box = largest.boundingBox
        // 45% margin so ears, jaw and hairline survive the crop.
        val marginX = (box.width() * 0.45f).toInt()
        val marginY = (box.height() * 0.45f).toInt()
        val left = max(0, box.left - marginX)
        val top = max(0, box.top - marginY)
        val right = min(bitmap.width, box.right + marginX)
        val bottom = min(bitmap.height, box.bottom + marginY)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return FaceCrop(bitmap, faces.size, IntArray(0))
        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
        return FaceCrop(cropped, faces.size, intArrayOf(left, top, width, height))
    }

    fun toJpeg(bitmap: Bitmap, quality: Int = 92): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Anonymous upload. Returns the resolvable Yandex result page. */
    suspend fun yandexUpload(jpeg: ByteArray): ReverseTarget = withContext(Dispatchers.IO) {
        val body = Net.postMultipart(
            url = YANDEX_UPLOAD,
            fieldName = "upfile",
            fileName = "face.jpg",
            mimeType = "image/jpeg",
            bytes = jpeg
        )
        val root = JSONObject(body)
        val cbir = root.optString("cbir_id").takeIf { it.isNotBlank() }
        val direct = root.optString("url").takeIf { it.isNotBlank() }
        if (cbir == null && direct == null) {
            throw IllegalStateException("Yandex returned neither cbir_id nor url.")
        }
        ReverseTarget(
            label = "YANDEX CROP",
            url = if (direct != null) {
                "https://yandex.com/images/search?rpt=imageview&url=${URLEncoder.encode(direct, "UTF-8")}"
            } else {
                "https://yandex.com/images/search?rpt=imageview&cbir_id=$cbir"
            }
        )
    }

    /** Any provider that takes a multipart field named [fieldName] and answers JSON. */
    suspend fun customProvider(
        jpeg: ByteArray,
        endpoint: String,
        apiKey: String,
        fieldName: String = "image",
        resultUrlKey: String = "url"
    ): ReverseTarget = withContext(Dispatchers.IO) {
        require(endpoint.startsWith("https://")) { "Endpoint wajib HTTPS." }
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
        val body = Net.postMultipart(
            url = endpoint,
            fieldName = fieldName,
            fileName = "face.jpg",
            mimeType = "image/jpeg",
            bytes = jpeg,
            headers = headers
        )
        val root = runCatching { JSONObject(body) }.getOrNull()
        val link = root?.optString(resultUrlKey)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Provider did not return '$resultUrlKey'.")
        ReverseTarget("PROVIDER", link)
    }
}
