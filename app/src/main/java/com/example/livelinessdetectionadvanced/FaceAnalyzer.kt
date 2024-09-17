package com.example.livelinessdetectionadvanced

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.media.Image
import android.media.Image.Plane
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
 class FaceAnalyzer(
    private val context : Context,
    private val overlayView: OverlayView,
    private val cameraSelector: CameraSelector
) : ImageAnalysis.Analyzer {

    private val faceDetector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build()
        )
    }

    private val expandFactor = 0.5f
    private val shrinkFactor = -0.25f

    private var lastDetectedFaces = -1
    private var lastSaveTime = 0L

    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image ?: run {
            image.close()
            return
        }

        val bitmap = imageToBitmap(mediaImage)
        val rotationDegrees = image.imageInfo.rotationDegrees
        Log.d("RotationInfo", "Rotation Degrees: $rotationDegrees") // Log rotation information
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        val imageWidth = mediaImage.height
        val imageHeight = mediaImage.width

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                val currentTime = System.currentTimeMillis()
                if (faces.isNotEmpty()) {
                    val boundingBoxes = faces.map { face ->
                        val boundingBox = face.boundingBox
                        calculateBoundingBox(boundingBox, imageWidth, imageHeight)
                    }

                    overlayView.setBoxColor(android.graphics.Color.GREEN)
                    overlayView.updateBoxes(boundingBoxes)

                    if (faces.size != lastDetectedFaces) {
                        showToast("Detected ${faces.size} face(s)")
                        lastDetectedFaces = faces.size
                    }

                    if (bitmap != null && (currentTime - lastSaveTime) >= 5000) {
                        val boundingBox = faces[0].boundingBox
                        val croppedBitmap = cropImage(bitmap, boundingBox)
                        if (croppedBitmap != null) {
                            //saveImageToDevice(croppedBitmap)
                        }
                        lastSaveTime = currentTime // Update last save time
                    }
                } else {
                    overlayView.setBoxColor(android.graphics.Color.RED)
                    overlayView.updateBoxes(emptyList())

                    if (0 != lastDetectedFaces) {
                        showToast("Detected 0 face(s)")
                        lastDetectedFaces = 0
                    }
                }
            }
            .addOnFailureListener {
                overlayView.setBoxColor(android.graphics.Color.RED)
                showToast("Face detection failed")
            }
            .addOnCompleteListener {
                image.close()
            }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes: Array<Plane> = image.planes
        val yBuffer: ByteBuffer = planes[0].buffer
        val uBuffer: ByteBuffer = planes[1].buffer
        val vBuffer: ByteBuffer = planes[2].buffer

        val ySize: Int = yBuffer.remaining()
        val uSize: Int = uBuffer.remaining()
        val vSize: Int = vBuffer.remaining()

        val yBytes = ByteArray(ySize)
        val uBytes = ByteArray(uSize)
        val vBytes = ByteArray(vSize)

        yBuffer.get(yBytes)
        uBuffer.get(uBytes)
        vBuffer.get(vBytes)

        val yuvImage = YuvImage(yBytes, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val imageBytes = out.toByteArray()

        var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        val matrix = Matrix()
        matrix.postRotate(270f)
        bitmap?.let {
            bitmap = Bitmap.createBitmap(it, 0, 0, it.width, it.height, matrix, true)
        }

        return bitmap
    }

    private fun cropImage(bitmap: Bitmap, boundingBox: Rect): Bitmap? {
        val adjustedBox = Rect(
            boundingBox.left.coerceAtLeast(0),
            boundingBox.top.coerceAtLeast(0),
            boundingBox.right.coerceAtMost(bitmap.width),
            boundingBox.bottom.coerceAtMost(bitmap.height)
        )

        return try {
            Bitmap.createBitmap(bitmap, adjustedBox.left, adjustedBox.top,
                adjustedBox.width(), adjustedBox.height())
        } catch (e: Exception) {
            Log.e("MeditationActivity", "Error while cropping image: ${e.message}")
            null
        }
    }

    private fun saveImageToDevice(bitmap: Bitmap) {
        val filename = "face_${System.currentTimeMillis()}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)

        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                showToast("Image saved to gallery")
            }
        } ?: showToast("Error saving image to gallery")
    }

    private fun calculateBoundingBox(boundingBox: Rect, imageWidth: Int, imageHeight: Int): RectF {
        val scaleX = overlayView.width.toFloat() / imageWidth
        val scaleY = overlayView.height.toFloat() / imageHeight

        val left = (boundingBox.left - boundingBox.width() * expandFactor).coerceAtLeast(0F)
        val top = (boundingBox.top + boundingBox.height() * shrinkFactor).coerceAtLeast(0F)
        val right = (boundingBox.right + boundingBox.width() * expandFactor).coerceAtMost(imageWidth.toFloat())
        val bottom = (boundingBox.bottom - boundingBox.height() * shrinkFactor).coerceAtMost(imageHeight.toFloat())

        val mirroredLeft = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
            (imageWidth - right) * scaleX else left * scaleX
        val mirroredRight = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
            (imageWidth - left) * scaleX else right * scaleX

        val clippedTop = top * scaleY
        val clippedBottom = bottom * scaleY

        Log.d("BoundingBox", "Original Bounding Box: left=${boundingBox.left}, top=${boundingBox.top}, right=${boundingBox.right}, bottom=${boundingBox.bottom}")
        Log.d("BoundingBox", "Scaled Bounding Box: left=$mirroredLeft, top=$clippedTop, right=$mirroredRight, bottom=$clippedBottom")

        return RectF(mirroredLeft, clippedTop, mirroredRight, clippedBottom)
    }

    private var currentToast: Toast? = null

    private fun showToast(message: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
        currentToast?.show()
    }
}