package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

object MediaProjectionHelper {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var latestCachedBitmap: Bitmap? = null
    private val bitmapLock = Any()

    var width = 720
        private set
    var height = 1280
        private set
    private var dpi = 240

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w("MediaProjectionHelper", "MediaProjection stopped by system")
            AutoBuyerLogs.addLogBlocking("⚠️ [СКРИНШОТ] Захваченное разрешение экрана (MediaProjection) было остановлено системой!")
            release()
        }
    }

    fun initProjection(projection: MediaProjection, context: Context) {
        // Release previous projection if any
        release()
        
        mediaProjection = projection
        try {
            mediaProjection?.registerCallback(projectionCallback, null)
        } catch (e: Exception) {
            Log.e("MediaProjectionHelper", "Error registering callback: ${e.message}")
        }

        // Get actual screen metrics for correct image aspect ratio
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        dpi = metrics.densityDpi

        // Capping screen resolution to improve performance, OCR speed, and avoid RAM issues.
        val maxDimension = 1080
        val currentMax = Math.max(width, height)
        if (currentMax > maxDimension) {
            val scale = maxDimension.toFloat() / currentMax
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }

        // Ensure width is even (required by some encoders/VirtualDisplays)
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--

        // Using maxImages = 4 for smoother frame queueing on physical devices
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        
        // AUTO_MIRROR + PUBLIC flags allow VirtualDisplay to capture external apps/games on real devices
        val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AutoBuyerCapture",
            width,
            height,
            dpi,
            displayFlags,
            imageReader?.surface,
            null,
            null
        )

        // Set up listener to continuously cache the latest display frame
        imageReader?.setOnImageAvailableListener({ reader ->
            processAvailableFrame(reader)
        }, null)
    }

    private fun processAvailableFrame(reader: ImageReader) {
        var image: android.media.Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val planes = image.planes
            if (planes.isEmpty()) return
            
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmapWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = if (rowPadding == 0) {
                bitmap
            } else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (cropped != bitmap) {
                    bitmap.recycle()
                }
                cropped
            }

            synchronized(bitmapLock) {
                latestCachedBitmap?.recycle()
                latestCachedBitmap = croppedBitmap
            }
        } catch (e: Exception) {
            Log.e("MediaProjectionHelper", "Error processing frame: ${e.message}", e)
        } finally {
            image?.close()
        }
    }

    @SuppressLint("WrongConstant")
    fun getLatestScreenshot(): Bitmap? {
        // 1. Try to return a fresh copy of the listener-cached bitmap if available
        synchronized(bitmapLock) {
            latestCachedBitmap?.let { cached ->
                if (!cached.isRecycled) {
                    return cached.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
        }

        // 2. Fallback: Directly poll imageReader with retry loop
        val reader = imageReader
        if (reader == null) {
            Log.e("MediaProjectionHelper", "getLatestScreenshot: imageReader is null! MediaProjection may not be initialized.")
            AutoBuyerLogs.addLogBlocking("⚠️ [СКРИНШОТ] Ошибка: Захват экрана не инициализирован (MediaProjection == null). Запустите заново из приложения.")
            return null
        }

        for (attempt in 1..12) {
            var image: android.media.Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmapWidth = width + rowPadding / pixelStride
                        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                        buffer.rewind()
                        bitmap.copyPixelsFromBuffer(buffer)

                        val croppedBitmap = if (rowPadding == 0) {
                            bitmap
                        } else {
                            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            if (cropped != bitmap) {
                                bitmap.recycle()
                            }
                            cropped
                        }

                        synchronized(bitmapLock) {
                            latestCachedBitmap?.recycle()
                            latestCachedBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        }

                        return croppedBitmap
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaProjectionHelper", "getLatestScreenshot attempt $attempt failed: ${e.message}", e)
            } finally {
                image?.close()
            }

            try {
                Thread.sleep(60)
            } catch (e: InterruptedException) {
                break
            }
        }

        Log.e("MediaProjectionHelper", "getLatestScreenshot: Failed to acquire screenshot after 12 attempts")
        AutoBuyerLogs.addLogBlocking("⚠️ [СКРИНШОТ] Ошибка: Не удалось получить снимок экрана за 12 попыток.")
        return null
    }

    fun release() {
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
            // Ignore
        }
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        
        synchronized(bitmapLock) {
            latestCachedBitmap?.recycle()
            latestCachedBitmap = null
        }
    }

    fun hasProjection(): Boolean = mediaProjection != null
}
