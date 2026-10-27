package com.example.service

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

data class DetectedItemResult(
    val itemName: String,
    val confidence: Float,
    val colorScore: Map<String, Float>,
    val dominantColorDescription: String
)

object OpenCvVisionEngine {
    private var isInitialized = false

    fun init(): Boolean {
        if (isInitialized) return true
        try {
            isInitialized = OpenCVLoader.initDebug()
            if (isInitialized) {
                AutoBuyerLogs.addLogBlocking("👁️ [OpenCV] Движок компьютерного зрения OpenCV 4.x успешно инициализирован.")
            } else {
                AutoBuyerLogs.addLogBlocking("⚠️ [OpenCV] Не удалось инициализировать нативный OpenCV Loader.")
            }
        } catch (e: Exception) {
            AutoBuyerLogs.addLogBlocking("⚠️ [OpenCV] Ошибка инициализации OpenCV: ${e.message}")
            isInitialized = false
        }
        return isInitialized
    }

    /**
     * Identifies the mineral/gem item in a given bitmap region using OpenCV HSV color segmentation,
     * contour analysis and chromatic saturation filtering.
     */
    fun detectItemInRegion(fullBitmap: Bitmap, region: Rect): DetectedItemResult {
        if (!isInitialized) {
            init()
        }

        // Clamp crop bounds to bitmap
        val cropLeft = region.left.coerceIn(0, fullBitmap.width - 1)
        val cropTop = region.top.coerceIn(0, fullBitmap.height - 1)
        val cropWidth = region.width().coerceAtLeast(4).coerceAtMost(fullBitmap.width - cropLeft)
        val cropHeight = region.height().coerceAtLeast(4).coerceAtMost(fullBitmap.height - cropTop)

        if (cropWidth <= 0 || cropHeight <= 0) {
            return DetectedItemResult("Неизвестно", 0f, emptyMap(), "Пустая область")
        }

        val croppedBitmap = try {
            Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
        } catch (e: Exception) {
            return DetectedItemResult("Неизвестно", 0f, emptyMap(), "Ошибка создания Bitmap")
        }

        val rgbaMat = Mat()
        val rgbMat = Mat()
        val hsvMat = Mat()

        try {
            Utils.bitmapToMat(croppedBitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            // 1. Изумруд (Emerald) - Bright vibrant green (H: 35..85, S: 45..255, V: 45..255)
            val emeraldMask = Mat()
            Core.inRange(hsvMat, Scalar(35.0, 45.0, 45.0), Scalar(85.0, 255.0, 255.0), emeraldMask)
            val emeraldPixels = Core.countNonZero(emeraldMask)
            emeraldMask.release()

            // 2. Сапфир (Sapphire) - Deep blue / cyan (H: 90..135, S: 45..255, V: 45..255)
            val sapphireMask = Mat()
            Core.inRange(hsvMat, Scalar(90.0, 45.0, 45.0), Scalar(135.0, 255.0, 255.0), sapphireMask)
            val sapphirePixels = Core.countNonZero(sapphireMask)
            sapphireMask.release()

            // 3. Рубин (Ruby) - Bright crimson red (H: 0..15 & 160..180, S: 50..255, V: 45..255)
            val rubyMask1 = Mat()
            val rubyMask2 = Mat()
            val rubyMaskCombined = Mat()
            Core.inRange(hsvMat, Scalar(0.0, 50.0, 45.0), Scalar(15.0, 255.0, 255.0), rubyMask1)
            Core.inRange(hsvMat, Scalar(160.0, 50.0, 45.0), Scalar(180.0, 255.0, 255.0), rubyMask2)
            Core.bitwise_or(rubyMask1, rubyMask2, rubyMaskCombined)
            val rubyPixels = Core.countNonZero(rubyMaskCombined)
            rubyMask1.release()
            rubyMask2.release()
            rubyMaskCombined.release()

            // 4. Золото (Gold / Gold Ore) - Golden yellow / warm amber (H: 16..34, S: 65..255, V: 60..255)
            val goldMask = Mat()
            Core.inRange(hsvMat, Scalar(16.0, 65.0, 60.0), Scalar(34.0, 255.0, 255.0), goldMask)
            val goldPixels = Core.countNonZero(goldMask)
            goldMask.release()

            // 5. Медь (Copper / Copper Ore) - Reddish-orange / brownish copper (H: 10..22, S: 40..170, V: 40..190)
            val copperMask = Mat()
            Core.inRange(hsvMat, Scalar(10.0, 40.0, 40.0), Scalar(22.0, 170.0, 190.0), copperMask)
            val copperPixels = Core.countNonZero(copperMask)
            copperMask.release()

            val rawScores = mapOf(
                "Рубин" to rubyPixels,
                "Изумруд" to emeraldPixels,
                "Сапфир" to sapphirePixels,
                "Золото" to goldPixels,
                "Медь" to copperPixels
            )

            val totalColorPixels = (rubyPixels + emeraldPixels + sapphirePixels + goldPixels + copperPixels).coerceAtLeast(1)
            val maxEntry = rawScores.maxByOrNull { it.value }

            val detectedName: String
            val confidence: Float

            // Require at least 20 colored pixels for positive identification
            if (maxEntry != null && maxEntry.value >= 20) {
                detectedName = maxEntry.key
                confidence = (maxEntry.value.toFloat() / totalColorPixels.toFloat()).coerceIn(0f, 1f)
            } else {
                detectedName = "Неизвестно"
                confidence = 0f
            }

            val desc = "Рубин: $rubyPixels px, Изумруд: $emeraldPixels px, Сапфир: $sapphirePixels px, Золото: $goldPixels px"

            val scoresMap = rawScores.mapValues { it.value.toFloat() / totalColorPixels.toFloat() }

            return DetectedItemResult(
                itemName = detectedName,
                confidence = confidence,
                colorScore = scoresMap,
                dominantColorDescription = desc
            )
        } catch (e: Exception) {
            return DetectedItemResult("Неизвестно", 0f, emptyMap(), "Ошибка OpenCV: ${e.message}")
        } finally {
            rgbaMat.release()
            rgbMat.release()
            hsvMat.release()
            croppedBitmap.recycle()
        }
    }

    /**
     * Checks if the detected lot item matches what the user configured to buy or the active tab.
     * Prevents buying Gold when target is Emerald, but allows valid gem matches without false positives.
     */
    fun isItemMatchingTarget(detectedItem: DetectedItemResult, targetItemName: String, activeTabName: String? = null): Boolean {
        val target = targetItemName.lowercase().trim()
        val activeTab = activeTabName?.lowercase()?.trim() ?: ""
        val detected = detectedItem.itemName.lowercase().trim()

        if (detected == "неизвестно" || detectedItem.confidence < 0.20f) {
            // Visual confidence is neutral/low; fallback to game tab filter
            return true
        }

        val isTargetRuby = target.contains("рубин") || target.contains("ruby") || target.contains("руб") || activeTab.contains("ruby") || activeTab.contains("рубин")
        val isTargetEmerald = target.contains("изумруд") || target.contains("emerald") || target.contains("изм") || activeTab.contains("emerald") || activeTab.contains("изумруд")
        val isTargetSapphire = target.contains("сапфир") || target.contains("sapphire") || target.contains("сап") || activeTab.contains("sapphire") || activeTab.contains("сапфир")
        val isTargetGold = target.contains("золот") || target.contains("gold") || activeTab.contains("gold") || activeTab.contains("золот")
        val isTargetCopper = target.contains("мед") || target.contains("copper") || activeTab.contains("copper") || activeTab.contains("медь")
        val isTargetSilver = target.contains("серебр") || target.contains("silver") || activeTab.contains("silver") || activeTab.contains("серебр")
        val isPuzzleMode = target.contains("puzzle") || activeTab.contains("puzzle") || target.contains("пазл")

        if (isPuzzleMode) return true
        if (isTargetSilver) return true
        if (isTargetRuby && detected == "рубин") return true
        if (isTargetEmerald && detected == "изумруд") return true
        if (isTargetSapphire && detected == "сапфир") return true
        if (isTargetGold && detected == "золото") return true
        if (isTargetCopper && detected == "медь") return true

        // If target is Emerald but OpenCV detects Gold or Ruby with high confidence, block purchase
        if (isTargetEmerald && (detected == "золото" || detected == "рубин")) return false
        // If target is Ruby but OpenCV detects Gold or Emerald with high confidence, block purchase
        if (isTargetRuby && (detected == "золото" || detected == "изумруд")) return false
        // If target is Sapphire but OpenCV detects Gold or Ruby, block purchase
        if (isTargetSapphire && (detected == "золото" || detected == "рубин")) return false

        return true
    }

    /**
     * Finds tab icon button positions on the market screen using OpenCV Computer Vision
     * (HSV chromatic segmentation + connected component contour analysis).
     */
    fun findTabPositionsByVision(
        fullBitmap: Bitmap,
        screenWidth: Float,
        screenHeight: Float
    ): Map<String, Pair<Float, Float>> {
        if (!isInitialized) init()
        val results = mutableMapOf<String, Pair<Float, Float>>()

        val scaleX = screenWidth / fullBitmap.width.toFloat()
        val scaleY = screenHeight / fullBitmap.height.toFloat()

        // Crop the horizontal tab strip (30% to 52% of image height)
        val cropTop = (fullBitmap.height * 0.30f).toInt().coerceIn(0, fullBitmap.height - 1)
        val cropBottom = (fullBitmap.height * 0.52f).toInt().coerceIn(cropTop + 1, fullBitmap.height)
        val cropHeight = cropBottom - cropTop
        val cropLeft = 0
        val cropWidth = fullBitmap.width

        if (cropHeight <= 10 || cropWidth <= 10) return results

        val croppedBitmap = try {
            Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
        } catch (e: Exception) {
            return results
        }

        val rgbaMat = Mat()
        val rgbMat = Mat()
        val hsvMat = Mat()

        try {
            Utils.bitmapToMat(croppedBitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            fun findBestCentroidForMask(mask: Mat): Pair<Float, Float>? {
                val contours = mutableListOf<org.opencv.core.MatOfPoint>()
                val hierarchy = Mat()
                Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                hierarchy.release()

                var bestContour: org.opencv.core.MatOfPoint? = null
                var maxArea = 15.0

                for (c in contours) {
                    val a = Imgproc.contourArea(c)
                    if (a > maxArea) {
                        maxArea = a
                        bestContour = c
                    }
                }

                if (bestContour != null) {
                    val rect = Imgproc.boundingRect(bestContour)
                    val localCenterX = rect.x + rect.width / 2.0f
                    val localCenterY = rect.y + rect.height / 2.0f
                    val globalX = (cropLeft + localCenterX) * scaleX
                    val globalY = (cropTop + localCenterY) * scaleY
                    contours.forEach { it.release() }
                    return Pair(globalX, globalY)
                }
                contours.forEach { it.release() }
                return null
            }

            // 1. Изумруд (Emerald) - Green color in tab bar
            val emeraldMask = Mat()
            Core.inRange(hsvMat, Scalar(35.0, 50.0, 45.0), Scalar(85.0, 255.0, 255.0), emeraldMask)
            findBestCentroidForMask(emeraldMask)?.let { results["Emerald"] = it }
            emeraldMask.release()

            // 2. Рубин (Ruby) - Red color in tab bar
            val rubyMask1 = Mat()
            val rubyMask2 = Mat()
            val rubyMaskCombined = Mat()
            Core.inRange(hsvMat, Scalar(0.0, 60.0, 45.0), Scalar(15.0, 255.0, 255.0), rubyMask1)
            Core.inRange(hsvMat, Scalar(160.0, 60.0, 45.0), Scalar(180.0, 255.0, 255.0), rubyMask2)
            Core.bitwise_or(rubyMask1, rubyMask2, rubyMaskCombined)
            findBestCentroidForMask(rubyMaskCombined)?.let { results["Ruby"] = it }
            rubyMask1.release()
            rubyMask2.release()
            rubyMaskCombined.release()

            // 3. Сапфир (Sapphire) - Blue color in tab bar
            val sapphireMask = Mat()
            Core.inRange(hsvMat, Scalar(90.0, 50.0, 45.0), Scalar(135.0, 255.0, 255.0), sapphireMask)
            findBestCentroidForMask(sapphireMask)?.let { results["Sapphire"] = it }
            sapphireMask.release()

        } catch (e: Exception) {
            AutoBuyerLogs.addLogBlocking("⚠️ [OpenCV Vision] Ошибка поиска вкладок: ${e.message}")
        } finally {
            rgbaMat.release()
            rgbMat.release()
            hsvMat.release()
            croppedBitmap.recycle()
        }

        return results
    }

    /**
     * Template matching using OpenCV TM_CCOEFF_NORMED
     */
    fun matchTemplate(source: Bitmap, template: Bitmap, threshold: Double = 0.8): List<android.graphics.Point> {
        if (!isInitialized) init()
        val sourceMat = Mat()
        val templateMat = Mat()
        val resultMat = Mat()
        val matches = mutableListOf<android.graphics.Point>()

        try {
            Utils.bitmapToMat(source, sourceMat)
            Utils.bitmapToMat(template, templateMat)

            val resultCols = sourceMat.cols() - templateMat.cols() + 1
            val resultRows = sourceMat.rows() - templateMat.rows() + 1
            if (resultCols <= 0 || resultRows <= 0) return emptyList()

            resultMat.create(resultRows, resultCols, CvType.CV_32FC1)
            Imgproc.matchTemplate(sourceMat, templateMat, resultMat, Imgproc.TM_CCOEFF_NORMED)

            val minMaxResult = Core.minMaxLoc(resultMat)
            if (minMaxResult.maxVal >= threshold) {
                matches.add(
                    android.graphics.Point(
                        minMaxResult.maxLoc.x.toInt() + templateMat.cols() / 2,
                        minMaxResult.maxLoc.y.toInt() + templateMat.rows() / 2
                    )
                )
            }
        } catch (e: Exception) {
            AutoBuyerLogs.addLogBlocking("⚠️ [OpenCV TemplateMatch] Ошибка: ${e.message}")
        } finally {
            sourceMat.release()
            templateMat.release()
            resultMat.release()
        }
        return matches
    }

    data class LotCardLocation(
        val handshakeCenterX: Float,
        val handshakeCenterY: Float,
        val cardRect: Rect,
        val priceCropRect: Rect
    )

    /**
     * Finds active lot cards and handshake "deal/buy" buttons using OpenCV HSV segmentation.
     * In the "Money Mining" Telegram Mini App, lot cards are polygonal containers with a prominent
     * golden handshake icon in the horizontal center and a gold coin/price on the right.
     */
    fun findLotCards(
        fullBitmap: Bitmap,
        screenWidth: Float,
        screenHeight: Float
    ): List<LotCardLocation> {
        if (!isInitialized) init()
        val cards = mutableListOf<LotCardLocation>()

        val scaleX = screenWidth / fullBitmap.width.toFloat()
        val scaleY = screenHeight / fullBitmap.height.toFloat()

        // The market items area is between 46% and 89% of the screen height
        val cropTop = (fullBitmap.height * 0.46f).toInt().coerceIn(0, fullBitmap.height - 1)
        val cropBottom = (fullBitmap.height * 0.89f).toInt().coerceIn(cropTop + 1, fullBitmap.height)
        val cropHeight = cropBottom - cropTop
        val cropWidth = fullBitmap.width

        if (cropHeight <= 20 || cropWidth <= 20) return cards

        val croppedBitmap = try {
            Bitmap.createBitmap(fullBitmap, 0, cropTop, cropWidth, cropHeight)
        } catch (e: Exception) {
            return cards
        }

        val rgbaMat = Mat()
        val rgbMat = Mat()
        val hsvMat = Mat()
        val yellowMask = Mat()

        try {
            Utils.bitmapToMat(croppedBitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            // Golden handshake and price text color (Bright golden yellow)
            // H: 16..36, S: 100..255, V: 120..255
            Core.inRange(hsvMat, Scalar(16.0, 100.0, 120.0), Scalar(36.0, 255.0, 255.0), yellowMask)

            // Morphological close to join adjacent handshake contour regions
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(5.0, 5.0))
            Imgproc.morphologyEx(yellowMask, yellowMask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val contours = mutableListOf<org.opencv.core.MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(yellowMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()

            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                val area = Imgproc.contourArea(contour)
                val centerXPercent = (rect.x + rect.width / 2f) / cropWidth.toFloat()

                // Handshake is centered horizontally (35% to 65% of screen width)
                // Width is typically 35..250px, height 25..150px
                if (centerXPercent in 0.35f..0.65f && rect.width >= 35 && rect.height >= 25 && area >= 120.0) {
                    val globalHandshakeX = (rect.x + rect.width / 2f) * scaleX
                    val globalHandshakeY = (cropTop + rect.y + rect.height / 2f) * scaleY

                    // Estimate lot card bounding box in full bitmap coordinates
                    val cardLeft = ((rect.x + rect.width / 2f) - cropWidth * 0.46f).toInt().coerceAtLeast(0)
                    val cardRight = ((rect.x + rect.width / 2f) + cropWidth * 0.46f).toInt().coerceAtMost(cropWidth)
                    val cardTop = (cropTop + rect.y - rect.height * 0.9f).toInt().coerceAtLeast(0)
                    val cardBottom = (cropTop + rect.y + rect.height * 1.9f).toInt().coerceAtMost(fullBitmap.height)

                    // Price ROI is on the right side of the handshake, vertically below the coin
                    val priceLeft = (rect.x + rect.width + 10).coerceAtMost(cropWidth - 10)
                    val priceRight = (cardRight - 10).coerceAtLeast(priceLeft + 10).coerceAtMost(cropWidth)
                    val priceTop = (cropTop + rect.y - 10).coerceAtLeast(0)
                    val priceBottom = (cardBottom - 5).coerceAtLeast(priceTop + 10).coerceAtMost(fullBitmap.height)

                    cards.add(
                        LotCardLocation(
                            handshakeCenterX = globalHandshakeX,
                            handshakeCenterY = globalHandshakeY,
                            cardRect = Rect(cardLeft, cardTop, cardRight, cardBottom),
                            priceCropRect = Rect(priceLeft, priceTop, priceRight, priceBottom)
                        )
                    )
                }
                contour.release()
            }
        } catch (e: Exception) {
            AutoBuyerLogs.addLogBlocking("⚠️ [OpenCV] Ошибка анализа лот-карт: ${e.message}")
        } finally {
            rgbaMat.release()
            rgbMat.release()
            hsvMat.release()
            yellowMask.release()
            croppedBitmap.recycle()
        }

        return cards
    }

    /**
     * Detects the golden "Buy" button in the purchase confirmation modal dialog.
     * The button is bordered in gold/yellow and located in the lower-central region of the modal
     * (horizontally 20%..80%, vertically 66%..83%).
     */
    fun findConfirmationBuyButton(
        fullBitmap: Bitmap,
        screenWidth: Float,
        screenHeight: Float
    ): Pair<Float, Float>? {
        if (!isInitialized) init()
        val scaleX = screenWidth / fullBitmap.width.toFloat()
        val scaleY = screenHeight / fullBitmap.height.toFloat()

        val cropTop = (fullBitmap.height * 0.66f).toInt().coerceIn(0, fullBitmap.height - 1)
        val cropBottom = (fullBitmap.height * 0.83f).toInt().coerceIn(cropTop + 1, fullBitmap.height)
        val cropLeft = (fullBitmap.width * 0.20f).toInt().coerceIn(0, fullBitmap.width - 1)
        val cropRight = (fullBitmap.width * 0.80f).toInt().coerceIn(cropLeft + 1, fullBitmap.width)
        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop

        if (cropWidth <= 20 || cropHeight <= 20) return null

        val croppedBitmap = try {
            Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
        } catch (e: Exception) {
            return null
        }

        val rgbaMat = Mat()
        val rgbMat = Mat()
        val hsvMat = Mat()
        val yellowMask = Mat()

        try {
            Utils.bitmapToMat(croppedBitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            // Golden border & text (H: 15..36, S: 80..255, V: 100..255)
            Core.inRange(hsvMat, Scalar(15.0, 80.0, 100.0), Scalar(36.0, 255.0, 255.0), yellowMask)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(5.0, 5.0))
            Imgproc.morphologyEx(yellowMask, yellowMask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val contours = mutableListOf<org.opencv.core.MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(yellowMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()

            var bestCandidate: org.opencv.core.Rect? = null
            var maxArea = 0.0

            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                val area = Imgproc.contourArea(contour)
                val centerX = rect.x + rect.width / 2f
                val centerXPercent = (cropLeft + centerX) / fullBitmap.width.toFloat()

                // Button should be centered horizontally (38%..62% of screen width)
                if (centerXPercent in 0.38f..0.62f && (rect.width >= 60 || area >= 150.0)) {
                    if (area > maxArea) {
                        maxArea = area
                        bestCandidate = rect
                    }
                }
                contour.release()
            }

            if (bestCandidate != null) {
                val btnCenterX = (cropLeft + bestCandidate.x + bestCandidate.width / 2f) * scaleX
                val btnCenterY = (cropTop + bestCandidate.y + bestCandidate.height / 2f) * scaleY
                return Pair(btnCenterX, btnCenterY)
            }
        } catch (e: Exception) {
            // ignore OpenCV error
        } finally {
            rgbaMat.release()
            rgbMat.release()
            hsvMat.release()
            yellowMask.release()
            croppedBitmap.recycle()
        }

        return null
    }
}
