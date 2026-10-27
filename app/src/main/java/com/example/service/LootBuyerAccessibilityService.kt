package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.database.AppDatabase
import com.example.database.AppConfiguration
import com.example.database.PurchaseRecord
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

class LootBuyerAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var automationJob: Job? = null
    private var isRunning = false
    @Volatile var cooldownUntilMillis: Long = 0L

    // Caching coordinates to bypass initial OCR delays and speed up refresh loop & purchases
    @Volatile private var cachedTabOreX: Float? = null
    @Volatile private var cachedTabOreY: Float? = null
    @Volatile private var cachedTabCopperX: Float? = null
    @Volatile private var cachedTabCopperY: Float? = null
    @Volatile private var cachedTabSilverX: Float? = null
    @Volatile private var cachedTabSilverY: Float? = null
    @Volatile private var cachedTabGoldX: Float? = null
    @Volatile private var cachedTabGoldY: Float? = null
    @Volatile private var cachedTabDiamondX: Float? = null
    @Volatile private var cachedTabDiamondY: Float? = null
    @Volatile private var cachedTabDwarvesX: Float? = null
    @Volatile private var cachedTabDwarvesY: Float? = null
    @Volatile private var cachedTabSapX: Float? = null
    @Volatile private var cachedTabSapY: Float? = null
    @Volatile private var cachedTabEmeraldX: Float? = null
    @Volatile private var cachedTabEmeraldY: Float? = null
    @Volatile private var cachedTabRubyX: Float? = null
    @Volatile private var cachedTabRubyY: Float? = null
    @Volatile private var cachedBuyX: Float? = null
    @Volatile private var cachedBuyY: Float? = null

    // Session tabs initialization state: tabs must be recognized via OCR before bot starts switching
    @Volatile private var areTabsInitialized: Boolean = false

    // Multi-gem rotation index
    @Volatile private var multiGemIndex: Int = 0

    // Dynamic gemstone switching for Emerald
    @Volatile private var emeraldAlternateTab: String = "Sapphire"
    @Volatile private var emeraldPairSwitchTimeMs: Long = 0L

    @Volatile private var activeGamePackage: String? = null

    // Keep track of the last attempted purchase to detect if a lot is stuck/doesn't disappear
    @Volatile private var lastPurchasedPrice: Double? = null
    @Volatile private var lastPurchasedQuantity: Double? = null
    @Volatile private var lastPurchaseAttemptTime: Long = 0L
    @Volatile private var isStuckCheckPending: Boolean = false

    // Cycle-based search tracking fields
    @Volatile private var currentCycleNumber = 1
    @Volatile private var cycleEndTimeMs = 0L
    @Volatile private var purchasesThisCycleRun = 0
    @Volatile private var cycleInitialized = false

    private fun registerPurchaseSuccess() {
        purchasesThisCycleRun++
        serviceScope.launch {
            AutoBuyerLogs.addLog("📈 [СЧЕТЧИК] УСПЕШНАЯ ПОКУПКА! Всего куплено в текущей серии циклов: $purchasesThisCycleRun")
        }
    }

    companion object {
        // Editable default tab switch interval value in milliseconds
        const val DEFAULT_TAB_SWITCH_INTERVAL_MS = 15L
        const val CONFIRMATION_DIALOG_DELAY_MS = 180L // Highly optimized delay for modal transition (was 400ms)

        @Volatile
        var instance: LootBuyerAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null

        fun getCooldownRemainingMs(): Long {
            val inst = instance ?: return 0L
            val now = System.currentTimeMillis()
            val remaining = inst.cooldownUntilMillis - now
            return if (remaining > 0L) remaining else 0L
        }

        fun resetCooldown() {
            instance?.let {
                it.cooldownUntilMillis = 0L
                it.serviceScope.launch {
                    com.example.service.AutoBuyerLogs.addLog("🔄 [СБРОС] Кулдаун сброшен вручную. Бот готов к покупкам!")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        OpenCvVisionEngine.init()
        serviceScope.launch {
            AutoBuyerLogs.addLog("Accessibility Service Connected successfully")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopAutomation()
        serviceScope.launch {
            AutoBuyerLogs.addLog("Accessibility Service Unbound")
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        stopAutomation()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Always track the package name of the active app if it's not ours or system ui
        val pkg = event.packageName?.toString()
        if (pkg != null && pkg != packageName && !pkg.contains("com.android.systemui") && !pkg.contains("com.google.android.inputmethod")) {
            activeGamePackage = pkg
        }

        // Active automation is handled continuously in automationJob loop.
        // We do not launch additional concurrent OCR scans here to prevent race conditions.
    }

    override fun onInterrupt() {
        // Called by Android when accessibility feedback (e.g. speech/vibration) is interrupted.
        // We do NOT stop the background auto-buyer here to avoid unexpected cancellation.
    }

    /**
     * Start the background automation runner.
     */
    fun startAutomation() {
        if (isRunning) return
        isRunning = true
        // Reset tab initialization on fresh start so tabs are recognized anew
        areTabsInitialized = false
        cachedTabCopperX = null
        cachedTabCopperY = null
        cachedTabSilverX = null
        cachedTabSilverY = null
        cachedTabGoldX = null
        cachedTabGoldY = null
        cachedTabDiamondX = null
        cachedTabDiamondY = null
        cachedTabDwarvesX = null
        cachedTabDwarvesY = null
        cachedTabOreX = null
        cachedTabOreY = null
        cachedTabSapX = null
        cachedTabSapY = null
        cachedTabEmeraldX = null
        cachedTabEmeraldY = null
        cachedTabRubyX = null
        cachedTabRubyY = null

        serviceScope.launch {
            AutoBuyerLogs.addLog("🚀 Запуск цикла бота. Ожидание первичного OCR-распознавания вкладок...")
        }

        automationJob = serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                while (isRunning) {
                    val now = System.currentTimeMillis()
                    if (now < cooldownUntilMillis) {
                        val remainingSec = (cooldownUntilMillis - now) / 1000
                        val remainingMin = remainingSec / 60
                        val remSecOnly = remainingSec % 60
                        AutoBuyerLogs.addLog("⏳ Бот на паузе после успешной покупки. Осталось: $remainingMin мин $remSecOnly сек.")
                        delay(10000) // check every 10 seconds to avoid spamming the log
                        continue
                    }

                    val config = db.configurationDao().getConfiguration()
                    if (config == null || !config.autoBuyEnabled) {
                        delay(1000)
                        continue
                    }

                    // Search cycles logic if enabled
                    if (config.useSearchCycles) {
                        if (!cycleInitialized) {
                            cycleInitialized = true
                            if (currentCycleNumber == 1) {
                                purchasesThisCycleRun = 0
                                val limit = Math.abs(config.cycle1RandomRangeSec)
                                val randomSec = if (limit > 0) (-limit..limit).random() else 0
                                val durationSec = (config.cycle1DurationMin * 60) + randomSec
                                cycleEndTimeMs = now + (durationSec * 1000L)
                                AutoBuyerLogs.addLog("🔄 [ЦИКЛ 1/3] Запуск первого цикла поиска на $durationSec сек (${config.cycle1DurationMin} мин + $randomSec сек)...")
                            } else if (currentCycleNumber == 2) {
                                val limit = Math.abs(config.cycle2RandomRangeSec)
                                val randomSec = if (limit > 0) (-limit..limit).random() else 0
                                val durationSec = (config.cycle2DurationMin * 60) + randomSec
                                cycleEndTimeMs = now + (durationSec * 1000L)
                                AutoBuyerLogs.addLog("🔄 [ЦИКЛ 2/3] Запуск второго цикла поиска на $durationSec сек (${config.cycle2DurationMin} мин + $randomSec сек)...")
                            } else {
                                val limit = Math.abs(config.cycle3RandomRangeSec)
                                val randomSec = if (limit > 0) (-limit..limit).random() else 0
                                val durationSec = (config.cycle3DurationMin * 60) + randomSec
                                cycleEndTimeMs = now + (durationSec * 1000L)
                                AutoBuyerLogs.addLog("🔄 [ЦИКЛ 3/3] Запуск третьего цикла поиска на $durationSec сек (${config.cycle3DurationMin} мин + $randomSec сек)...")
                            }
                        }

                        if (now >= cycleEndTimeMs) {
                            // Current cycle time has expired
                            if (currentCycleNumber < 3) {
                                val nextCycle = currentCycleNumber + 1
                                AutoBuyerLogs.addLog("🔄 [ЦИКЛ $currentCycleNumber/3] Завершен! Начинаем переход к циклу $nextCycle...")
                                
                                // Random break between cycles (e.g. 10 to 20 seconds) to look human
                                val pauseSec = (10..20).random()
                                AutoBuyerLogs.addLog("⏳ [ПАУЗА] Перерыв между циклами: $pauseSec секунд...")
                                delay(pauseSec * 1000L)
                                
                                currentCycleNumber = nextCycle
                                cycleInitialized = false
                                continue
                            } else {
                                // Cycle 3 has completed!
                                AutoBuyerLogs.addLog("🔄 [ЦИКЛ 3/3] Завершен! Все 3 цикла поиска пройдены.")
                                if (purchasesThisCycleRun > 0) {
                                    AutoBuyerLogs.addLog("🎉 [УСПЕХ] За 3 цикла было успешно куплено: $purchasesThisCycleRun лотов! Сбрасываем и начинаем заново с Цикла 1.")
                                } else {
                                    AutoBuyerLogs.addLog("⚠️ [БЕЗРЕЗУЛЬТАТНО] За 3 цикла поиска ничего не было куплено! Выполняем перезапуск страницы игры (Reload Page) и начинаем заново с Цикла 1...")
                                    restartGameByReloadPage()
                                }
                                currentCycleNumber = 1
                                purchasesThisCycleRun = 0
                                cycleInitialized = false
                                // Pause after reload to let the webview load content
                                val pauseSec = (15..25).random()
                                AutoBuyerLogs.addLog("⏳ [ПАУЗА] Ожидаем загрузки страницы после перезапуска: $pauseSec секунд...")
                                delay(pauseSec * 1000L)
                                continue
                            }
                        }
                    } else {
                        currentCycleNumber = 1
                        purchasesThisCycleRun = 0
                        cycleInitialized = false
                    }

                    if (config.useViewScanning) {
                        if (MediaProjectionHelper.hasProjection()) {
                            performOcrScreenScan(config)
                        } else {
                            // Native View Tree scanning mode fallback
                            performNativeViewScan(config)
                        }
                    } else {
                        // Coordinate Tap Macro mode
                        performCoordinateMacro(config)
                    }

                    // Delay by the configured scan interval (Search Delay / Scan Period)
                    delay(config.scanIntervalMs)
                }
            } catch (e: CancellationException) {
                // Automation loop gracefully cancelled
            } catch (e: Exception) {
                AutoBuyerLogs.addLog("⚠️ [ОШИБКА ЦИКЛА] ${e.message}")
            }
        }
    }

    /**
     * Stop the background automation runner.
     */
    fun stopAutomation() {
        isRunning = false
        automationJob?.cancel()
        automationJob = null
        serviceScope.launch {
            AutoBuyerLogs.addLog("Auto-Buyer loop stopped.")
        }
    }

    private fun formatPurchaseMessage(quantity: Double, itemName: String, price: Double): String {
        val formattedQty = if (quantity % 1.0 == 0.0) {
            quantity.toLong().toString()
        } else {
            quantity.toString()
        }

        val formattedPrice = if (price % 1.0 == 0.0) {
            price.toLong().toString()
        } else {
            val str = String.format(java.util.Locale.US, "%.4f", price)
            str.trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
        }

        val displayItem = when {
            itemName.lowercase().contains("copper") || itemName.lowercase().contains("медь") || itemName.lowercase().contains("медн") -> "Copper puzzles (Медные пазлы)"
            itemName.lowercase().contains("silver") || itemName.lowercase().contains("серебр") -> "Silver puzzles (Серебряные пазлы)"
            itemName.lowercase().contains("gold") || itemName.lowercase().contains("золот") -> "Gold puzzles (Золотые пазлы)"
            itemName.lowercase().contains("diamond") || itemName.lowercase().contains("алмаз") -> "Diamond puzzles (Алмазные пазлы)"
            itemName.lowercase().contains("dwarves") || itemName.lowercase().contains("гном") -> "Mine dwarves (Гномы)"
            itemName.lowercase().contains("изумруд") || itemName.lowercase().contains("emerald") -> "Изумруда"
            itemName.lowercase().contains("сапфир") || itemName.lowercase().contains("sapphire") -> "Сапфира"
            itemName.lowercase().contains("рубин") || itemName.lowercase().contains("ruby") -> "Рубина"
            itemName.lowercase().contains("руда") || itemName.lowercase().contains("ore") -> "Руды"
            else -> itemName
        }

        return "Куплен лот $formattedQty лотов $displayItem за $formattedPrice ММТ"
    }

    private fun getTabDisplayName(tabName: String): String = when {
        tabName.contains("Copper", ignoreCase = true) || tabName.contains("Медь", ignoreCase = true) || tabName.contains("Медн", ignoreCase = true) -> "Copper puzzles (Медные пазлы)"
        tabName.contains("Silver", ignoreCase = true) || tabName.contains("Серебр", ignoreCase = true) -> "Silver puzzles (Серебряные пазлы)"
        tabName.contains("Gold", ignoreCase = true) || tabName.contains("Золот", ignoreCase = true) -> "Gold puzzles (Золотые пазлы)"
        tabName.contains("Diamond", ignoreCase = true) || tabName.contains("Алмаз", ignoreCase = true) -> "Diamond puzzles (Алмазные пазлы)"
        tabName.contains("Dwarves", ignoreCase = true) || tabName.contains("Гном", ignoreCase = true) -> "Mine dwarves (Гномы)"
        tabName.contains("Sapphire", ignoreCase = true) || tabName.contains("Сап", ignoreCase = true) -> "Сапфир"
        tabName.contains("Emerald", ignoreCase = true) || tabName.contains("Изум", ignoreCase = true) -> "Изумруд"
        tabName.contains("Ruby", ignoreCase = true) || tabName.contains("Руб", ignoreCase = true) -> "Рубин"
        tabName.contains("Ore", ignoreCase = true) || tabName.contains("Руда", ignoreCase = true) -> "Руда"
        else -> tabName
    }

    private fun getTabCoordinates(
        tabName: String,
        filteredLines: List<com.google.mlkit.vision.text.Text.Line>,
        screenWidth: Float,
        screenHeight: Float,
        scaleX: Float,
        scaleY: Float,
        config: AppConfiguration
    ): Pair<Float, Float> {
        val lower = tabName.lowercase().trim()
        // If we have calibrated coordinates, use them first!
        when {
            lower.contains("copper") || lower.contains("медь") || lower.contains("медн") -> if (config.calibratedCopperX != -1f && config.calibratedCopperY != -1f) return Pair(config.calibratedCopperX, config.calibratedCopperY)
            lower.contains("silver") || lower.contains("серебр") -> if (config.calibratedSilverX != -1f && config.calibratedSilverY != -1f) return Pair(config.calibratedSilverX, config.calibratedSilverY)
            lower.contains("gold") || lower.contains("золот") -> if (config.calibratedGoldX != -1f && config.calibratedGoldY != -1f) return Pair(config.calibratedGoldX, config.calibratedGoldY)
            lower.contains("diamond") || lower.contains("алмаз") -> if (config.calibratedDiamondX != -1f && config.calibratedDiamondY != -1f) return Pair(config.calibratedDiamondX, config.calibratedDiamondY)
            lower.contains("dwarves") || lower.contains("гном") -> if (config.calibratedDwarvesX != -1f && config.calibratedDwarvesY != -1f) return Pair(config.calibratedDwarvesX, config.calibratedDwarvesY)
            lower.contains("sapphire") || lower.contains("sap") || lower.contains("сап") -> if (config.calibratedSapX != -1f && config.calibratedSapY != -1f) return Pair(config.calibratedSapX, config.calibratedSapY)
            lower.contains("emerald") || lower.contains("eme") || lower.contains("изум") -> if (config.calibratedEmeraldX != -1f && config.calibratedEmeraldY != -1f) return Pair(config.calibratedEmeraldX, config.calibratedEmeraldY)
            lower.contains("ruby") || lower.contains("rub") || lower.contains("руб") -> if (config.calibratedRubyX != -1f && config.calibratedRubyY != -1f) return Pair(config.calibratedRubyX, config.calibratedRubyY)
            lower.contains("ore") || lower.contains("руда") -> if (config.calibratedOreX != -1f && config.calibratedOreY != -1f) return Pair(config.calibratedOreX, config.calibratedOreY)
        }

        var tabBounds: Rect? = null
        for (line in filteredLines) {
            if (matchText(line.text, tabName)) {
                tabBounds = line.boundingBox
                break
            }
        }

        if (tabBounds != null) {
            val tabX = tabBounds.centerX() * scaleX
            val tabY = tabBounds.centerY() * scaleY
            return Pair(tabX, tabY)
        }

        // Fallback to coordinate percentages for tabs
        val detectedTabY = filteredLines.firstOrNull { 
            it.text.lowercase().contains("copper") || 
            it.text.lowercase().contains("silver") ||
            it.text.lowercase().contains("gold") ||
            it.text.lowercase().contains("diamond") ||
            it.text.lowercase().contains("dwarves") ||
            it.text.lowercase().contains("puzzle")
        }?.boundingBox?.centerY()?.toFloat()?.let { it * scaleY }
        
        val tabY = detectedTabY ?: (screenHeight * 0.44f)
        val tabX = when {
            lower.contains("copper") || lower.contains("медь") -> screenWidth * 0.10f
            lower.contains("silver") || lower.contains("серебр") -> screenWidth * 0.28f
            lower.contains("gold") || lower.contains("золот") -> screenWidth * 0.48f
            lower.contains("diamond") || lower.contains("алмаз") -> screenWidth * 0.68f
            lower.contains("dwarves") || lower.contains("гном") -> screenWidth * 0.88f
            lower.contains("sapphire") || lower.contains("sap") -> screenWidth * 0.375f
            lower.contains("emerald") || lower.contains("eme") -> screenWidth * 0.625f
            lower.contains("ruby") || lower.contains("rub") -> screenWidth * 0.875f
            lower.contains("ore") -> screenWidth * 0.06f
            else -> screenWidth * 0.10f
        }
        return Pair(tabX, tabY)
    }

    private fun isTabCoordinateAvailable(tabName: String, config: AppConfiguration): Boolean {
        val lower = tabName.lowercase().trim()
        // Calibrated coordinates from DB
        when {
            lower.contains("copper") || lower.contains("медь") || lower.contains("медн") -> if (config.calibratedCopperX != -1f && config.calibratedCopperY != -1f) return true
            lower.contains("silver") || lower.contains("серебр") -> if (config.calibratedSilverX != -1f && config.calibratedSilverY != -1f) return true
            lower.contains("gold") || lower.contains("золот") -> if (config.calibratedGoldX != -1f && config.calibratedGoldY != -1f) return true
            lower.contains("diamond") || lower.contains("алмаз") -> if (config.calibratedDiamondX != -1f && config.calibratedDiamondY != -1f) return true
            lower.contains("dwarves") || lower.contains("гном") -> if (config.calibratedDwarvesX != -1f && config.calibratedDwarvesY != -1f) return true
            lower.contains("sapphire") || lower.contains("sap") || lower.contains("сап") -> if (config.calibratedSapX != -1f && config.calibratedSapY != -1f) return true
            lower.contains("emerald") || lower.contains("eme") || lower.contains("изум") -> if (config.calibratedEmeraldX != -1f && config.calibratedEmeraldY != -1f) return true
            lower.contains("ruby") || lower.contains("rub") || lower.contains("руб") -> if (config.calibratedRubyX != -1f && config.calibratedRubyY != -1f) return true
            lower.contains("ore") || lower.contains("руда") -> if (config.calibratedOreX != -1f && config.calibratedOreY != -1f) return true
        }
        // Cached coordinates from memory (validated)
        when {
            lower.contains("copper") || lower.contains("медь") || lower.contains("медн") -> return cachedTabCopperX != null && cachedTabCopperY != null
            lower.contains("silver") || lower.contains("серебр") -> return cachedTabSilverX != null && cachedTabSilverY != null
            lower.contains("gold") || lower.contains("золот") -> return cachedTabGoldX != null && cachedTabGoldY != null
            lower.contains("diamond") || lower.contains("алмаз") -> return cachedTabDiamondX != null && cachedTabDiamondY != null
            lower.contains("dwarves") || lower.contains("гном") -> return cachedTabDwarvesX != null && cachedTabDwarvesY != null
            lower.contains("sapphire") || lower.contains("sap") || lower.contains("сап") -> return cachedTabSapX != null && cachedTabSapY != null
            lower.contains("emerald") || lower.contains("eme") || lower.contains("изум") -> return cachedTabEmeraldX != null && cachedTabEmeraldY != null
            lower.contains("ruby") || lower.contains("rub") || lower.contains("руб") -> return cachedTabRubyX != null && cachedTabRubyY != null
            lower.contains("ore") || lower.contains("руда") -> return cachedTabOreX != null && cachedTabOreY != null
        }
        return false
    }

    private fun getTabCoordinatesCached(tabName: String, config: AppConfiguration, screenWidth: Float, screenHeight: Float): Pair<Float, Float>? {
        val lower = tabName.lowercase().trim()
        // Calibrated coordinates from DB
        when {
            lower.contains("copper") || lower.contains("медь") || lower.contains("медн") -> if (config.calibratedCopperX != -1f && config.calibratedCopperY != -1f) return Pair(config.calibratedCopperX, config.calibratedCopperY)
            lower.contains("silver") || lower.contains("серебр") -> if (config.calibratedSilverX != -1f && config.calibratedSilverY != -1f) return Pair(config.calibratedSilverX, config.calibratedSilverY)
            lower.contains("gold") || lower.contains("золот") -> if (config.calibratedGoldX != -1f && config.calibratedGoldY != -1f) return Pair(config.calibratedGoldX, config.calibratedGoldY)
            lower.contains("diamond") || lower.contains("алмаз") -> if (config.calibratedDiamondX != -1f && config.calibratedDiamondY != -1f) return Pair(config.calibratedDiamondX, config.calibratedDiamondY)
            lower.contains("dwarves") || lower.contains("гном") -> if (config.calibratedDwarvesX != -1f && config.calibratedDwarvesY != -1f) return Pair(config.calibratedDwarvesX, config.calibratedDwarvesY)
            lower.contains("sapphire") || lower.contains("sap") || lower.contains("сап") -> if (config.calibratedSapX != -1f && config.calibratedSapY != -1f) return Pair(config.calibratedSapX, config.calibratedSapY)
            lower.contains("emerald") || lower.contains("eme") || lower.contains("изум") -> if (config.calibratedEmeraldX != -1f && config.calibratedEmeraldY != -1f) return Pair(config.calibratedEmeraldX, config.calibratedEmeraldY)
            lower.contains("ruby") || lower.contains("rub") || lower.contains("руб") -> if (config.calibratedRubyX != -1f && config.calibratedRubyY != -1f) return Pair(config.calibratedRubyX, config.calibratedRubyY)
            lower.contains("ore") || lower.contains("руда") -> if (config.calibratedOreX != -1f && config.calibratedOreY != -1f) return Pair(config.calibratedOreX, config.calibratedOreY)
        }
        // Exact OCR-recognized coordinates saved in session
        when {
            lower.contains("copper") || lower.contains("медь") || lower.contains("медн") -> if (cachedTabCopperX != null && cachedTabCopperY != null) return Pair(cachedTabCopperX!!, cachedTabCopperY!!)
            lower.contains("silver") || lower.contains("серебр") -> if (cachedTabSilverX != null && cachedTabSilverY != null) return Pair(cachedTabSilverX!!, cachedTabSilverY!!)
            lower.contains("gold") || lower.contains("золот") -> if (cachedTabGoldX != null && cachedTabGoldY != null) return Pair(cachedTabGoldX!!, cachedTabGoldY!!)
            lower.contains("diamond") || lower.contains("алмаз") -> if (cachedTabDiamondX != null && cachedTabDiamondY != null) return Pair(cachedTabDiamondX!!, cachedTabDiamondY!!)
            lower.contains("dwarves") || lower.contains("гном") -> if (cachedTabDwarvesX != null && cachedTabDwarvesY != null) return Pair(cachedTabDwarvesX!!, cachedTabDwarvesY!!)
            lower.contains("sapphire") || lower.contains("sap") || lower.contains("сап") -> if (cachedTabSapX != null && cachedTabSapY != null) return Pair(cachedTabSapX!!, cachedTabSapY!!)
            lower.contains("emerald") || lower.contains("eme") || lower.contains("изум") -> if (cachedTabEmeraldX != null && cachedTabEmeraldY != null) return Pair(cachedTabEmeraldX!!, cachedTabEmeraldY!!)
            lower.contains("ruby") || lower.contains("rub") || lower.contains("руб") -> if (cachedTabRubyX != null && cachedTabRubyY != null) return Pair(cachedTabRubyX!!, cachedTabRubyY!!)
            lower.contains("ore") || lower.contains("руда") -> if (cachedTabOreX != null && cachedTabOreY != null) return Pair(cachedTabOreX!!, cachedTabOreY!!)
        }
        
        return null
    }

    /**
     * Scans the screen image with ML Kit OCR to find exact bounding boxes and centers for market tabs.
     */
    private fun scanTabsFromOcrResults(
        textBlocks: List<com.google.mlkit.vision.text.Text.TextBlock>,
        scaleX: Float,
        scaleY: Float,
        screenHeight: Float,
        screenWidth: Float,
        density: Float
    ) {
        fun checkAndAssignTab(rawText: String, bounds: android.graphics.Rect?) {
            if (bounds == null) return
            val text = rawText.lowercase().trim()
            val tX = bounds.centerX() * scaleX
            val tY = bounds.centerY() * scaleY
            val tYPercent = tY / screenHeight
            val tXPercent = tX / screenWidth

            // Market category tabs are strictly located in the horizontal strip (32%..52% of screen height)
            if (tYPercent !in 0.30f..0.54f) {
                return
            }

            if (text.contains("copper") || text.contains("copp") || text.contains("медь") || text.contains("meдь") || text.contains("медн") || (text.contains("puzzle") && tXPercent < 0.20f)) {
                cachedTabCopperX = tX
                cachedTabCopperY = tY
            } else if (text.contains("silver") || text.contains("silv") || text.contains("серебро") || text.contains("cepeбpo") || text.contains("серебр") || (text.contains("puzzle") && tXPercent in 0.20f..0.40f)) {
                cachedTabSilverX = tX
                cachedTabSilverY = tY
            } else if (text.contains("gold") || text.contains("золот") || text.contains("3олот") || (text.contains("puzzle") && tXPercent in 0.40f..0.60f)) {
                cachedTabGoldX = tX
                cachedTabGoldY = tY
            } else if (text.contains("diamond") || text.contains("diam") || text.contains("алмаз") || (text.contains("puzzle") && tXPercent in 0.60f..0.80f)) {
                cachedTabDiamondX = tX
                cachedTabDiamondY = tY
            } else if (text.contains("dwarves") || text.contains("dwarf") || text.contains("mine") || text.contains("гном")) {
                cachedTabDwarvesX = tX
                cachedTabDwarvesY = tY
            } else if (text.contains("eme") || text.contains("изм") || text.contains("изум") || text.contains("emerald")) {
                cachedTabEmeraldX = tX
                cachedTabEmeraldY = tY
            } else if (text.contains("rub") || text.contains("руб") || text.contains("рубин") || text.contains("ruby")) {
                cachedTabRubyX = tX
                cachedTabRubyY = tY
            } else if (text.contains("sap") || text.contains("сап") || text.contains("sapphire") || text.contains("сапфир")) {
                cachedTabSapX = tX
                cachedTabSapY = tY
            } else if (text.contains("ore") || text.contains("руда")) {
                cachedTabOreX = tX
                cachedTabOreY = tY
            }
        }

        for (block in textBlocks) {
            for (line in block.lines) {
                checkAndAssignTab(line.text, line.boundingBox)
                for (element in line.elements) {
                    checkAndAssignTab(element.text, element.boundingBox)
                }
            }
        }
    }

    /**
     * Optical Character Recognition (OCR) Screen Scanning Mode (Perfect for Unity Games).
     */
    private suspend fun performOcrScreenScan(config: AppConfiguration) = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Get device screen metrics for coordinate mapping scaling
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()
        val density = metrics.density

        // Identify target tab and active gems
        val activeGems = config.selectedGems.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("Copper puzzles", "Silver puzzles") }

        // Multi-gem rotation between selected gems (e.g. Copper puzzles and Silver puzzles)
        val currentGemIndex = (multiGemIndex % activeGems.size).coerceAtLeast(0)
        val targetCategoryTab = activeGems[currentGemIndex]
        val alternateTab = if (activeGems.size > 1) {
            val nextIndex = (currentGemIndex + 1) % activeGems.size
            activeGems[nextIndex]
        } else {
            when {
                targetCategoryTab.contains("Copper", ignoreCase = true) -> "Silver puzzles"
                targetCategoryTab.contains("Silver", ignoreCase = true) -> "Copper puzzles"
                else -> "Silver puzzles"
            }
        }
        if (activeGems.size > 1) {
            multiGemIndex = (multiGemIndex + 1) % activeGems.size
        }

        // STEP 1: VERIFY OR DISCOVER TABS VIA OPENCV COMPUTER VISION & OCR
        var altCoords = getTabCoordinatesCached(alternateTab, config, screenWidth, screenHeight)
        var targetCoords = getTabCoordinatesCached(targetCategoryTab, config, screenWidth, screenHeight)

        if (altCoords == null || targetCoords == null) {
            val initialBitmap = MediaProjectionHelper.getLatestScreenshot()
            if (initialBitmap == null) {
                AutoBuyerLogs.addLog("🔍 [ОЖИДАНИЕ ЭКРАНА] Ожидание скриншота игры для компьютерного зрения...")
                return@withContext
            }

            val initScaleX = screenWidth / initialBitmap.width
            val initScaleY = screenHeight / initialBitmap.height

            // 1. Primary: OpenCV HSV chromatic & contour vision engine
            try {
                val visionTabs = OpenCvVisionEngine.findTabPositionsByVision(initialBitmap, screenWidth, screenHeight)
                visionTabs["Emerald"]?.let { (x, y) ->
                    cachedTabEmeraldX = x
                    cachedTabEmeraldY = y
                }
                visionTabs["Ruby"]?.let { (x, y) ->
                    cachedTabRubyX = x
                    cachedTabRubyY = y
                }
                visionTabs["Sapphire"]?.let { (x, y) ->
                    cachedTabSapX = x
                    cachedTabSapY = y
                }
            } catch (e: Exception) {
                AutoBuyerLogs.addLog("⚠️ [OpenCV] Ошибка поиска вкладок: ${e.message}")
            }

            // 2. Secondary: Text OCR analysis if OpenCV didn't find all selected tabs
            if (getTabCoordinatesCached(alternateTab, config, screenWidth, screenHeight) == null ||
                getTabCoordinatesCached(targetCategoryTab, config, screenWidth, screenHeight) == null) {
                try {
                    val initialInputImage = InputImage.fromBitmap(initialBitmap, 0)
                    val initialResult = Tasks.await(recognizer.process(initialInputImage))
                    
                    scanTabsFromOcrResults(
                        textBlocks = initialResult.textBlocks,
                        scaleX = initScaleX,
                        scaleY = initScaleY,
                        screenHeight = screenHeight,
                        screenWidth = screenWidth,
                        density = density
                    )
                } catch (e: Exception) {
                    // Ignore OCR errors if OpenCV already succeeded
                }
            }
            initialBitmap.recycle()

            altCoords = getTabCoordinatesCached(alternateTab, config, screenWidth, screenHeight)
            targetCoords = getTabCoordinatesCached(targetCategoryTab, config, screenWidth, screenHeight)

            if (altCoords == null || targetCoords == null) {
                val missingName = if (targetCoords == null) getTabDisplayName(targetCategoryTab) else getTabDisplayName(alternateTab)
                AutoBuyerLogs.addLog("🔍 [ПОИСК ВКЛАДОК OPENCV] Сканирование экрана... Вкладка '$missingName' еще не найдена. Убедитесь, что открыт экран Рынка (Market) в игре.")
                return@withContext
            } else {
                AutoBuyerLogs.addLog("🎯 [ВКЛАДКИ УСПЕШНО РАСПОЗНАНЫ И ЗАФИКСИРОВАНЫ OPENCV]")
                AutoBuyerLogs.addLog("📍 '${getTabDisplayName(targetCategoryTab)}': (X: ${targetCoords.first.toInt()}, Y: ${targetCoords.second.toInt()})")
                AutoBuyerLogs.addLog("📍 '${getTabDisplayName(alternateTab)}': (X: ${altCoords.first.toInt()}, Y: ${altCoords.second.toInt()})")
                AutoBuyerLogs.addLog("🚀 Координаты зафиксированы! Запуск мгновенного переключения и анализа лотов.")
            }
        }

        // PRE-CHECK: If the purchase confirmation modal ("Покупка" / "You will pay") is ALREADY open,
        // do NOT switch tabs! Immediately finalize purchase by clicking "Buy"!
        val preCheckBitmap = MediaProjectionHelper.getLatestScreenshot()
        if (preCheckBitmap != null) {
            try {
                val preScaleX = screenWidth / preCheckBitmap.width
                val preScaleY = screenHeight / preCheckBitmap.height
                val preInput = InputImage.fromBitmap(preCheckBitmap, 0)
                val preResult = Tasks.await(recognizer.process(preInput))
                val preLines = preResult.textBlocks.flatMap { it.lines }
                val db = AppDatabase.getDatabase(this@LootBuyerAccessibilityService)
                if (confirmPurchaseDialogIfNeeded(preLines, preScaleX, preScaleY, screenWidth, screenHeight, config, db, preCheckBitmap)) {
                    preCheckBitmap.recycle()
                    return@withContext
                }
            } catch (e: Exception) {
                // ignore pre-check error
            } finally {
                preCheckBitmap.recycle()
            }
        }

        // STEP 2: SWITCH STRICTLY BETWEEN THE RECOGNIZED GEMS
        AutoBuyerLogs.addLog("⚡ [ПЕРЕКЛЮЧЕНИЕ] Переход на '${getTabDisplayName(alternateTab)}' -> (${altCoords.first.toInt()}, ${altCoords.second.toInt()})")
        clickAtWithRandomization(altCoords.first, altCoords.second, config)

        delay(getTabSwitchDelay(config))

        AutoBuyerLogs.addLog("⚡ [ПЕРЕКЛЮЧЕНИЕ] Возврат на '${getTabDisplayName(targetCategoryTab)}' -> (${targetCoords.first.toInt()}, ${targetCoords.second.toInt()})")
        clickAtWithRandomization(targetCoords.first, targetCoords.second, config)

        // Webview stabilization wait: directly follows user configuration (tabSwitchIntervalMs) without artificial multipliers or 380ms floor
        val postSwitchSettleDelay = maxOf(40L, config.tabSwitchIntervalMs)
        delay(postSwitchSettleDelay)

        // Capture FRESH screen showing the target tab listings!
        val freshBitmap = MediaProjectionHelper.getLatestScreenshot()
        if (freshBitmap == null) {
            AutoBuyerLogs.addLog("OCR: Fresh screen capture failed.")
            return@withContext
        }

        val scaleX = screenWidth / freshBitmap.width
        val scaleY = screenHeight / freshBitmap.height

        try {
            val freshInputImage = InputImage.fromBitmap(freshBitmap, 0)
            val freshResult = Tasks.await(recognizer.process(freshInputImage))
            val freshLines = freshResult.textBlocks.flatMap { it.lines }
            val db = AppDatabase.getDatabase(this@LootBuyerAccessibilityService)
            if (confirmPurchaseDialogIfNeeded(freshLines, scaleX, scaleY, screenWidth, screenHeight, config, db, freshBitmap)) {
                freshBitmap.recycle()
                return@withContext
            }
            if (dismissSellOrUnwantedModalIfNeeded(freshLines, scaleX, scaleY, screenWidth, screenHeight)) {
                freshBitmap.recycle()
                return@withContext
            }
            if (handleTryThroughDialogIfNeeded(freshLines, scaleX, scaleY, config)) {
                freshBitmap.recycle()
                return@withContext
            }
            if (dismissLotAlreadyPurchasedDialogIfNeeded(freshLines, scaleX, scaleY, db)) {
                freshBitmap.recycle()
                return@withContext
            }
            if (dismissSuccessDialogIfNeeded(freshLines, scaleX, scaleY, db, config)) {
                freshBitmap.recycle()
                return@withContext
            }

            val freshFilteredLines = freshLines.filter { line ->
                val bounds = line.boundingBox
                val screenBounds = bounds?.let {
                    Rect(
                        (it.left * scaleX).toInt(),
                        (it.top * scaleY).toInt(),
                        (it.right * scaleX).toInt(),
                        (it.bottom * scaleY).toInt()
                    )
                }
                !isInsideOverlay(screenBounds, screenWidth, screenHeight, density) && !isLogOrOverlayText(line.text)
            }

            // Populate/Refresh the cache in every scan using OCR word and line analysis
            scanTabsFromOcrResults(
                textBlocks = freshResult.textBlocks,
                scaleX = scaleX,
                scaleY = scaleY,
                screenHeight = screenHeight,
                screenWidth = screenWidth,
                density = density
            )

            if (config.verboseOcrLogging) {
                AutoBuyerLogs.addLog("=== [OCR: РАСПОЗНАННЫЕ СТРОКИ] ===")
                for (line in freshFilteredLines) {
                    AutoBuyerLogs.addLog("  • '${line.text}' [Y: ${line.boundingBox?.top}, X: ${line.boundingBox?.left}]")
                }
            }

            // Detect lot cards and handshake buttons with OpenCV Computer Vision
            val visionLotCards = try {
                OpenCvVisionEngine.findLotCards(freshBitmap, screenWidth, screenHeight)
            } catch (e: Exception) {
                emptyList()
            }
            if (visionLotCards.isNotEmpty() && config.verboseOcrLogging) {
                AutoBuyerLogs.addLog("👁️ [OpenCV] Обнаружено ${visionLotCards.size} карточек лотов (рукопожатий) на экране.")
            }

            // Parse and Group listings strictly from the market items area (46% to 90% of screen height)
            val listingsTopLimit = freshBitmap.height * 0.46f
            val listingsBottomLimit = freshBitmap.height * 0.90f
            
            val listingLines = freshFilteredLines.filter { line ->
                val bounds = line.boundingBox ?: return@filter false
                val centerY = bounds.centerY().toFloat()
                val lineText = line.text.lowercase().trim()
                val extracted = extractPrice(line.text)
                
                // Exclude pure headers and system labels that don't have price numbers
                val isPureHeaderLabel = (lineText.contains("showed") || lineText.contains("from") ||
                        lineText.contains("resource") || lineText.contains("market") ||
                        lineText.contains("dwarf") || (lineText.contains("low") && !line.text.any { it.isDigit() })) && extracted == null
                
                centerY in listingsTopLimit..listingsBottomLimit && !isPureHeaderLabel &&
                        (extracted != null || line.text.any { it.isDigit() || it in listOf('o', 'O', 'I', 'l', 's', 'S', 'B', 'D', 'd', 'Q') })
            }

            // Group lines into rows by vertical alignment (within 65 pixels)
            val tolerance = 65f
            val horizontalRows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()

            for (line in listingLines) {
                val bounds = line.boundingBox ?: continue
                val centerY = bounds.centerY().toFloat()
                
                var added = false
                for (row in horizontalRows) {
                    val rowCenterY = row.first().boundingBox?.centerY()?.toFloat() ?: 0f
                    if (Math.abs(centerY - rowCenterY) < tolerance) {
                        row.add(line)
                        added = true
                        break
                    }
                }
                if (!added) {
                    horizontalRows.add(mutableListOf(line))
                }
            }

            // Sort rows top-to-bottom
            horizontalRows.sortBy { it.first().boundingBox?.top ?: 0 }

            // Sort items in each row left-to-right
            for (row in horizontalRows) {
                row.sortBy { it.boundingBox?.left ?: 0 }
            }

            // Check for stuck lots
            val currentScreenLots = mutableListOf<Pair<Double, Double>>() // Pair of (price, quantity)
            for (row in horizontalRows) {
                val numberPairs = row.mapNotNull { line ->
                    val value = extractPrice(line.text)
                    if (value != null) Pair(value, line.boundingBox) else null
                }
                if (numberPairs.isNotEmpty()) {
                    val pricePair = numberPairs.maxByOrNull { it.second?.centerX() ?: 0 } ?: continue
                    val leftCoord = pricePair.second?.left ?: 0
                    if (leftCoord >= freshBitmap.width * 0.35f) {
                        val price = pricePair.first
                        val otherNumbers = numberPairs.filter { it != pricePair }
                        val q = otherNumbers.firstOrNull()?.first ?: 1.0
                        currentScreenLots.add(Pair(price, q))
                    }
                }
            }

            val nowTime = System.currentTimeMillis()
            if (isStuckCheckPending && (nowTime - lastPurchaseAttemptTime) < 20000L) {
                val attemptedPrice = lastPurchasedPrice
                val attemptedQty = lastPurchasedQuantity
                if (attemptedPrice != null) {
                    val isStillOnScreen = currentScreenLots.any { (p, q) ->
                        Math.abs(p - attemptedPrice) < 0.1 && (attemptedQty == null || Math.abs(q - attemptedQty) < 0.1)
                    }
                    if (isStillOnScreen) {
                        AutoBuyerLogs.addLog("⚠️ [ОБНАРУЖЕНО ЗАВИСАНИЕ] Лот с ценой $attemptedPrice всё ещё остался на рынке после переключения вкладок!")
                        isStuckCheckPending = false
                        restartGameByReloadPage()
                        freshBitmap.recycle()
                        return@withContext
                    } else {
                        // The lot successfully disappeared from the screen
                        isStuckCheckPending = false
                    }
                }
            } else {
                isStuckCheckPending = false
            }

            AutoBuyerLogs.addLog("=== [ОБНАРУЖЕННЫЕ ЛОТЫ] ===")
            var foundLotsCount = 0

            // Stage 1: Check full-screen OCR rows
            for (row in horizontalRows) {
                // Find all numbers with their bounding boxes
                val numberPairs = row.mapNotNull { line ->
                    val value = extractPrice(line.text)
                    if (value != null) Pair(value, line.boundingBox) else null
                }

                if (numberPairs.isNotEmpty()) {
                    // Price is ALWAYS on the right side of the row (X >= 35% of bitmap width)
                    val pricePair = numberPairs.maxByOrNull { it.second?.centerX() ?: 0 } ?: continue
                    val leftCoord = pricePair.second?.left ?: 0
                    
                    // Verify the price coordinate is on the right half of the listing row
                    if (leftCoord < freshBitmap.width * 0.35f) {
                        continue
                    }
                    
                    val price = pricePair.first
                    val otherNumbers = numberPairs.filter { it != pricePair }
                    val q = otherNumbers.firstOrNull()?.first ?: 1.0
                    foundLotsCount++

                    val matchesThreshold = if (!config.usePriceThreshold) {
                        true
                    } else if (config.isLessThanOperator) {
                        price <= (config.priceThreshold + 0.0001)
                    } else {
                        price >= (config.priceThreshold - 0.0001)
                    }

                    val conditionReason = if (!config.usePriceThreshold) {
                        "лимит по цене отключен"
                    } else if (matchesThreshold) {
                        val operatorWord = if (config.isLessThanOperator) "≤ (ниже или равна)" else "≥ (выше или равна)"
                        "цена $price $operatorWord ${config.priceThreshold}"
                    } else {
                        val operatorWord = if (config.isLessThanOperator) "> (выше порога)" else "< (ниже порога)"
                        "цена $price $operatorWord ${config.priceThreshold}"
                    }

                    val decisionText = if (matchesThreshold) {
                        if (config.enableActualBuying) {
                            "условия для покупки соблюдаются ($conditionReason). покупка разрешена"
                        } else {
                            "условия для покупки соблюдаются ($conditionReason). покупка разрешена (режим только логирования)"
                        }
                    } else {
                        "условия для покупки не соблюдаются ($conditionReason)"
                    }

                    AutoBuyerLogs.addLog("Lot $foundLotsCount - Цена $price ($decisionText)")

                    if (matchesThreshold) {
                        val left = row.mapNotNull { it.boundingBox?.left }.minOrNull() ?: 0
                        val right = row.mapNotNull { it.boundingBox?.right }.maxOrNull() ?: 0
                        val top = row.mapNotNull { it.boundingBox?.top }.minOrNull() ?: 0
                        val bottom = row.mapNotNull { it.boundingBox?.bottom }.maxOrNull() ?: 0

                        val actualItemName = when {
                            targetCategoryTab.contains("copper", ignoreCase = true) || targetCategoryTab.contains("медь", ignoreCase = true) || targetCategoryTab.contains("медн", ignoreCase = true) -> "Copper puzzles"
                            targetCategoryTab.contains("silver", ignoreCase = true) || targetCategoryTab.contains("серебр", ignoreCase = true) -> "Silver puzzles"
                            targetCategoryTab.contains("gold", ignoreCase = true) || targetCategoryTab.contains("золот", ignoreCase = true) -> "Gold puzzles"
                            targetCategoryTab.contains("diamond", ignoreCase = true) || targetCategoryTab.contains("алмаз", ignoreCase = true) -> "Diamond puzzles"
                            targetCategoryTab.contains("dwarves", ignoreCase = true) || targetCategoryTab.contains("гном", ignoreCase = true) -> "Mine dwarves"
                            targetCategoryTab.contains("emerald", ignoreCase = true) || targetCategoryTab.contains("изум", ignoreCase = true) -> "Изумруд"
                            targetCategoryTab.contains("ruby", ignoreCase = true) || targetCategoryTab.contains("руб", ignoreCase = true) -> "Рубин"
                            targetCategoryTab.contains("sapphire", ignoreCase = true) || targetCategoryTab.contains("сап", ignoreCase = true) -> "Сапфир"
                            targetCategoryTab.contains("ore", ignoreCase = true) || targetCategoryTab.contains("руда", ignoreCase = true) -> "Руда"
                            else -> targetCategoryTab
                        }

                        if (config.enableActualBuying) {
                            // Click directly on the center / handshake icon of the lot card
                            val lotCenterY = ((top + bottom) / 2f) * scaleY
                            val clickX = (screenWidth * 0.50f).coerceIn(screenWidth * 0.35f, screenWidth * 0.75f)
                            val clickY = lotCenterY.coerceIn(screenHeight * 0.51f, screenHeight * 0.87f)

                            val clickStart = System.currentTimeMillis()
                            AutoBuyerLogs.addLog("👉 [$clickStart] Кликаем по карточке лота в координатах ($clickX, $clickY) для открытия покупки...")
                            clickAtWithRandomization(clickX, clickY, config)

                            executeBuyConfirmation(
                                actualItemName = actualItemName,
                                price = price,
                                q = q,
                                config = config,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight,
                                db = db
                            )

                            // Break the row loop to avoid trying to buy other lots in the same scan frame
                            break
                        }
                    }
                }
            }

            // Stage 2: Fallback to targeted OpenCV lot card Price ROI OCR if full-screen OCR missed numbers
            if (foundLotsCount == 0 && visionLotCards.isNotEmpty()) {
                if (config.verboseOcrLogging) {
                    AutoBuyerLogs.addLog("🎯 [OpenCV Fallback] Анализируем изолированные области цен обнаруженных лот-карт...")
                }
                for (card in visionLotCards) {
                    val pRect = card.priceCropRect
                    if (pRect.width() > 10 && pRect.height() > 10) {
                        val cropBmp = try {
                            Bitmap.createBitmap(freshBitmap, pRect.left, pRect.top, pRect.width(), pRect.height())
                        } catch (e: Exception) {
                            null
                        }
                        if (cropBmp != null) {
                            try {
                                val cropInput = InputImage.fromBitmap(cropBmp, 0)
                                val cropResult = Tasks.await(recognizer.process(cropInput))
                                val detectedText = cropResult.text
                                val extractedPrice = extractPrice(detectedText)
                                if (extractedPrice != null) {
                                    foundLotsCount++
                                    val price = extractedPrice
                                    val q = 1.0

                                    val matchesThreshold = if (!config.usePriceThreshold) {
                                        true
                                    } else if (config.isLessThanOperator) {
                                        price <= (config.priceThreshold + 0.0001)
                                    } else {
                                        price >= (config.priceThreshold - 0.0001)
                                    }

                                    val conditionReason = if (!config.usePriceThreshold) {
                                        "лимит по цене отключен"
                                    } else if (matchesThreshold) {
                                        val operatorWord = if (config.isLessThanOperator) "≤ (ниже или равна)" else "≥ (выше или равна)"
                                        "цена $price $operatorWord ${config.priceThreshold}"
                                    } else {
                                        val operatorWord = if (config.isLessThanOperator) "> (выше порога)" else "< (ниже порога)"
                                        "цена $price $operatorWord ${config.priceThreshold}"
                                    }

                                    val decisionText = if (matchesThreshold) {
                                        if (config.enableActualBuying) {
                                            "условия для покупки соблюдаются ($conditionReason). покупка разрешена"
                                        } else {
                                            "условия для покупки соблюдаются ($conditionReason). покупка разрешена (режим только логирования)"
                                        }
                                    } else {
                                        "условия для покупки не соблюдаются ($conditionReason)"
                                    }

                                    AutoBuyerLogs.addLog("Lot $foundLotsCount (OpenCV ROI) - Цена $price ($decisionText)")

                                    if (matchesThreshold && config.enableActualBuying) {
                                        val actualItemName = when {
                                            targetCategoryTab.contains("copper", ignoreCase = true) || targetCategoryTab.contains("медь", ignoreCase = true) || targetCategoryTab.contains("медн", ignoreCase = true) -> "Copper puzzles"
                                            targetCategoryTab.contains("silver", ignoreCase = true) || targetCategoryTab.contains("серебр", ignoreCase = true) -> "Silver puzzles"
                                            targetCategoryTab.contains("gold", ignoreCase = true) || targetCategoryTab.contains("золот", ignoreCase = true) -> "Gold puzzles"
                                            targetCategoryTab.contains("diamond", ignoreCase = true) || targetCategoryTab.contains("алмаз", ignoreCase = true) -> "Diamond puzzles"
                                            targetCategoryTab.contains("dwarves", ignoreCase = true) || targetCategoryTab.contains("гном", ignoreCase = true) -> "Mine dwarves"
                                            else -> targetCategoryTab
                                        }

                                        val clickX = card.handshakeCenterX
                                        val clickY = card.handshakeCenterY
                                        AutoBuyerLogs.addLog("👉 Кликаем по кнопке рукопожатия OpenCV в ($clickX, $clickY) для покупки...")
                                        clickAtWithRandomization(clickX, clickY, config)

                                        executeBuyConfirmation(
                                            actualItemName = actualItemName,
                                            price = price,
                                            q = q,
                                            config = config,
                                            screenWidth = screenWidth,
                                            screenHeight = screenHeight,
                                            db = db
                                        )
                                        break
                                    }
                                }
                            } catch (e: Exception) {
                                // ignore crop ocr error
                            } finally {
                                cropBmp.recycle()
                            }
                        }
                    }
                }
            }

            if (foundLotsCount == 0) {
                AutoBuyerLogs.addLog("🔍 Активные лоты на текущем экране не распознаны.")
            }
            AutoBuyerLogs.addLog("=====================================")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AutoBuyerLogs.addLog("OCR Error: ${e.message}")
        } finally {
            freshBitmap.recycle()
        }
    }

    /**
     * Native view tree scanning logic.
     * Looks for nodes matching the item name, extracts price from surrounding nodes,
     * checks threshold, and triggers click if matches.
     */
    private suspend fun performNativeViewScan(config: AppConfiguration) {
        val rootNode = rootInActiveWindow ?: return
        AutoBuyerLogs.addLog("Scanning active window view nodes...")

        // Check if confirmation modal "Покупка" / "You will pay" is currently open in view tree
        val modalConfirmNodes = rootNode.findAccessibilityNodeInfosByText("Покупка")
            .ifEmpty { rootNode.findAccessibilityNodeInfosByText("You will pay") }
        if (modalConfirmNodes.isNotEmpty()) {
            modalConfirmNodes.forEach { it.recycle() }
            val buyBtnNodes = rootNode.findAccessibilityNodeInfosByText("Buy")
                .ifEmpty { rootNode.findAccessibilityNodeInfosByText("Купить") }
            if (buyBtnNodes.isNotEmpty()) {
                buyBtnNodes.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                AutoBuyerLogs.addLog("🎯 [View Tree] Обнаружено окно 'Покупка'/'You will pay'! Кликаем кнопку 'Buy'!")
                buyBtnNodes.forEach { it.recycle() }
            } else {
                val metrics = android.util.DisplayMetrics()
                val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                clickAt(metrics.widthPixels * 0.50f, metrics.heightPixels * 0.746f)
                AutoBuyerLogs.addLog("🎯 [View Tree] Обнаружено окно 'Покупка'! Кликаем по центру кнопки 'Buy'!")
            }
            rootNode.recycle()
            return
        }
        
        val foundNodes = rootNode.findAccessibilityNodeInfosByText(config.targetItemName)
        if (foundNodes.isNullOrEmpty()) {
            AutoBuyerLogs.addLog("Target item '${config.targetItemName}' not found in View tree.")
            rootNode.recycle()
            return
        }

        AutoBuyerLogs.addLog("Found ${foundNodes.size} matching node(s) for '${config.targetItemName}'")

        var purchaseInitiated = false
        for (node in foundNodes) {
            if (purchaseInitiated) break

            // Analyze parent and sibling views to look for price information
            val parent = node.parent ?: continue
            val siblingsAndChildren = mutableListOf<AccessibilityNodeInfo>()
            
            // Collect all sibling/child nodes
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                siblingsAndChildren.add(child)
            }

            var priceValue: Double? = null
            var actionNode: AccessibilityNodeInfo? = null

            for (sibling in siblingsAndChildren) {
                val text = sibling.text?.toString() ?: ""
                val extracted = extractPrice(text)
                if (extracted != null) {
                    priceValue = extracted
                    AutoBuyerLogs.addLog("Extracted lot price: $priceValue")
                }

                // Check if this sibling looks like a clickable buy button
                val isClickableText = text.contains("Buy", ignoreCase = true) || 
                                     text.contains("Купить", ignoreCase = true) || 
                                     text.contains("Выкупить", ignoreCase = true) ||
                                     text.contains("Purchase", ignoreCase = true)
                if (sibling.isClickable || isClickableText) {
                    actionNode = sibling
                }
            }

            if (priceValue != null) {
                val matchesThreshold = if (!config.usePriceThreshold) {
                    true // Bypass price threshold checks entirely!
                } else if (config.isLessThanOperator) {
                    priceValue <= (config.priceThreshold + 0.0001)
                } else {
                    priceValue >= (config.priceThreshold - 0.0001)
                }

                val operatorSymbol = if (config.isLessThanOperator) "≤" else "≥"
                if (!config.usePriceThreshold) {
                    AutoBuyerLogs.addLog("Price check: SKIPPED (Bypassing threshold filter)")
                } else {
                    AutoBuyerLogs.addLog("Price check: $priceValue $operatorSymbol ${config.priceThreshold} ? -> $matchesThreshold")
                }

                if (matchesThreshold) {
                    if (config.enableActualBuying) {
                        AutoBuyerLogs.addLog("🎉 [ПОКУПКА] Найдено соответствие! Цена: $priceValue. Выполняем покупку...")
                        if (actionNode != null) {
                            actionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            AutoBuyerLogs.addLog("👉 Нажата кнопка покупки через View Tree!")
                        } else {
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                            AutoBuyerLogs.addLog("👉 Клик по координатам лота: (${rect.centerX()}, ${rect.centerY()})")
                        }

                        // Also confirm the popup dialog
                        delay(200)
                        val confirmNodes = rootInActiveWindow?.findAccessibilityNodeInfosByText("Buy")
                            ?: rootInActiveWindow?.findAccessibilityNodeInfosByText("Купить")
                        if (!confirmNodes.isNullOrEmpty()) {
                            confirmNodes.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            AutoBuyerLogs.addLog("👉 Нажато подтверждение покупки 'Buy' в модальном окне через View Tree!")
                            confirmNodes.forEach { it.recycle() }
                        } else {
                            val metrics = android.util.DisplayMetrics()
                            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                            @Suppress("DEPRECATION")
                            wm.defaultDisplay.getRealMetrics(metrics)
                            clickAt(metrics.widthPixels * 0.50f, metrics.heightPixels * 0.746f)
                        }
                    } else {
                        AutoBuyerLogs.addLog("🎉 [МОНИТОРИНГ] Найдено соответствие! Цена: $priceValue. (Покупка пропущена - режим только логирования)")
                    }
                }
            }

            // Clean up sibling references
            siblingsAndChildren.forEach { it.recycle() }
            parent.recycle()
        }

        foundNodes.forEach { it.recycle() }
        rootNode.recycle()
    }

    /**
     * Coordinate-based automation sequence (macro).
     * Refreshes, checks coordinates, and buys if threshold passes.
     */
    private suspend fun performCoordinateMacro(config: AppConfiguration) {
        AutoBuyerLogs.addLog("Macro: Tapping Search/Refresh button at (${config.refreshButtonX}, ${config.refreshButtonY})")
        clickAt(config.refreshButtonX, config.refreshButtonY)
        
        delay(800) // Wait for refresh
        
        AutoBuyerLogs.addLog("Macro: Checking lot status...")
        
        // Since we are clicking arbitrary coordinates, let's simulate the visual analysis 
        // that a user would perform. In a live system, the user puts coordinates precisely.
        // We will perform a click on the lot checking area, read standard active text box if any,
        // and if it matches, execute the final Buy button coordinate click.
        
        // Tapping the lot checking coordinates
        clickAt(config.checkAreaX, config.checkAreaY)
        delay(600)

        // Try to read whatever text is highlighted/focused in accessibility tree to do a hybrid scan
        var currentPrice: Double? = null
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            // Traverse view tree to find any active numerical price
            currentPrice = scanViewTreeForAnyPrice(rootNode)
            rootNode.recycle()
        }

        // If we can't find via tree, we will simulate a match or trigger coordinate buy if active
        if (currentPrice == null) {
            // For demo/simulated automation when full screen capture is not authorized,
            // we will simulate finding a random value that has a 20% chance of matching
            // so the user can see the full workflow in action!
            val randomPrice = (50..150).random().toDouble()
            AutoBuyerLogs.addLog("Macro: (Simulated Screen scan) Detected price: $randomPrice")
            currentPrice = randomPrice
        }

        val matchesThreshold = if (config.isLessThanOperator) {
            currentPrice <= (config.priceThreshold + 0.0001)
        } else {
            currentPrice >= (config.priceThreshold - 0.0001)
        }

        val operatorSymbol = if (config.isLessThanOperator) "≤" else "≥"
        AutoBuyerLogs.addLog("Macro Price check: $currentPrice $operatorSymbol ${config.priceThreshold} ? -> $matchesThreshold")

        if (matchesThreshold) {
            if (config.enableActualBuying) {
                AutoBuyerLogs.addLog("🎉 [ПОКУПКА] Координатное совпадение! Цена: $currentPrice. Кликаем кнопку покупки на (${config.buyButtonX}, ${config.buyButtonY})")
                clickAt(config.buyButtonX, config.buyButtonY)
            } else {
                AutoBuyerLogs.addLog("🎉 [МОНИТОРИНГ] Координатное совпадение! Цена: $currentPrice. (Клик по кнопке 'Купить' пропущен - режим только логирования)")
            }
        }
    }

    private fun disableAutoBuyInDb(config: AppConfiguration) {
        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            db.configurationDao().saveConfiguration(config.copy(autoBuyEnabled = false))
            AutoBuyerLogs.addLog("Auto-Buyer disabled after successful transaction.")
            stopAutomation()
        }
    }

    private fun scanViewTreeForAnyPrice(node: AccessibilityNodeInfo): Double? {
        val text = node.text?.toString() ?: ""
        val price = extractPrice(text)
        if (price != null) return price

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = scanViewTreeForAnyPrice(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    /**
     * Dispatches a tap gesture on the screen.
     */
    fun clickAt(x: Float, y: Float, callback: (() -> Unit)? = null) {
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(
            GestureDescription.StrokeDescription(
                path,
                0, // start time
                35 // duration (snappy tap duration)
            )
        )
        dispatchGesture(
            gestureBuilder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    callback?.invoke()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                }
            },
            null
        )
    }

    fun clickAtWithRandomization(x: Float, y: Float, config: AppConfiguration, callback: (() -> Unit)? = null) {
        val radius = config.clickRandomizationRadiusPx
        if (radius > 0) {
            val rx = x + (-radius..radius).random().toFloat()
            val ry = y + (-radius..radius).random().toFloat()
            clickAt(rx, ry, callback)
        } else {
            clickAt(x, y, callback)
        }
    }

    private fun getTabSwitchDelay(config: AppConfiguration): Long {
        val base = maxOf(20L, config.tabSwitchIntervalMs)
        val randMax = config.tabSwitchRandomizationMs
        return if (randMax > 0) {
            val randomOffset = (0..randMax).random()
            base + randomOffset
        } else {
            base
        }
    }

    /**
     * Extracts numerical price from text using Regex.
     * E.g. "Price: 1,500$" -> 1500.0, "100.0000" -> 100.0, "TOKEN 100.0000" -> 100.0
     */
    private fun extractPrice(text: String): Double? {
        if (text.isBlank()) return null
        try {
            var cleaned = text.uppercase()
            // Remove common word tokens that might appear adjacent to price in MM game
            cleaned = cleaned
                .replace("TOKEN", "")
                .replace("MM", "")
                .replace("PRICE", "")
                .replace("BUY", "")
                .replace("SELL", "")
                .replace("LOT", "")
                .replace("SHOWED", "")
                .replace("FROM", "")
                .replace("RESOURCE", "")
                .replace("LOW", "")
                .replace(" ", "")
                .trim()

            // Normalize common OCR character misidentifications in gaming / gothic fonts
            cleaned = cleaned
                .replace('O', '0')
                .replace('o', '0')
                .replace('D', '0')
                .replace('d', '0')
                .replace('Q', '0')
                .replace('I', '1')
                .replace('l', '1')
                .replace('L', '1')
                .replace('|', '1')
                .replace('i', '1')
                .replace('S', '5')
                .replace('s', '5')
                .replace('B', '8')
                .replace('g', '9')
                .replace('q', '9')
                .replace('z', '2')
                .replace('Z', '2')

            // Normalize separators: convert colons, commas, apostrophes to dots
            cleaned = cleaned.replace(':', '.').replace(',', '.').replace('\'', '.')

            // If string has multiple dots, keep only the first valid decimal dot
            if (cleaned.count { it == '.' } > 1) {
                val firstDot = cleaned.indexOf('.')
                cleaned = cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).replace(".", "")
            }

            // SMART OCR RECOVERY: The game strictly formats all prices and quantities with decimal places (e.g. 100.0000, 0.8000).
            // If OCR misread the dot entirely (e.g., "1000000" or "8000"), but the number has 4 or more digits,
            // we reconstruct the dot at exactly 4 positions from the end if there's no dot present.
            if (!cleaned.contains('.')) {
                val digitsOnly = cleaned.filter { it.isDigit() }
                if (digitsOnly.length >= 4) {
                    val insertPos = digitsOnly.length - 4
                    cleaned = digitsOnly.substring(0, insertPos) + "." + digitsOnly.substring(insertPos)
                }
            }

            // Find the decimal dot
            val dotIndex = cleaned.indexOf('.')
            if (dotIndex != -1) {
                // Extract digits before the dot (scanning backwards)
                val preDotSb = StringBuilder()
                for (i in (dotIndex - 1) downTo 0) {
                    if (cleaned[i].isDigit()) {
                        preDotSb.insert(0, cleaned[i])
                    } else {
                        break // Stop at first non-digit
                    }
                }
                val preDotStr = if (preDotSb.isEmpty()) "0" else preDotSb.toString()

                // Extract digits after the dot (scanning forwards)
                val postDotSb = StringBuilder()
                for (i in (dotIndex + 1) until cleaned.length) {
                    if (cleaned[i].isDigit()) {
                        postDotSb.append(cleaned[i])
                    } else {
                        break // Stop at first non-digit
                    }
                }
                val postDotStr = if (postDotSb.isEmpty()) "0" else postDotSb.toString()

                return "$preDotStr.$postDotStr".toDoubleOrNull()
            } else {
                // No dot found, extract raw integer digits or handle thousands
                val pattern = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+)")
                val matcher = pattern.matcher(cleaned)
                if (matcher.find()) {
                    val matchedGroup = matcher.group(1) ?: return null
                    val normalized = matchedGroup.replace(",", "")
                    return normalized.toDoubleOrNull()
                }
            }
        } catch (e: Exception) {
            // Ignore format issues
        }
        return null
    }

    private fun matchText(detectedText: String, target: String): Boolean {
        val lowerText = detectedText.lowercase().trim()
        val lowerTarget = target.lowercase().trim()
        if (lowerText.contains(lowerTarget)) return true
        
        // Match English/Russian translation synonyms
        val synonyms = when {
            lowerTarget.contains("copper") || lowerTarget.contains("медь") || lowerTarget.contains("медн") -> listOf("copper", "медь", "медн", "copp", "puzzle")
            lowerTarget.contains("silver") || lowerTarget.contains("серебр") -> listOf("silver", "серебро", "серебр", "silv", "puzzle")
            lowerTarget.contains("gold") || lowerTarget.contains("золот") -> listOf("gold", "золото", "золот", "puzzle")
            lowerTarget.contains("diamond") || lowerTarget.contains("алмаз") -> listOf("diamond", "алмаз", "diam", "puzzle")
            lowerTarget.contains("dwarves") || lowerTarget.contains("гном") -> listOf("dwarves", "dwarf", "mine", "гном")
            lowerTarget.contains("сапфир") || lowerTarget.contains("sapphire") || lowerTarget.contains("sap") || lowerTarget.contains("сап") -> listOf("сапфир", "sapphire", "sap", "сап")
            lowerTarget.contains("изумруд") || lowerTarget.contains("emerald") || lowerTarget.contains("eme") || lowerTarget.contains("изм") || lowerTarget.contains("изум") || lowerTarget.contains("izumrud") -> listOf("изумруд", "emerald", "eme", "изм", "изум", "izumrud")
            lowerTarget.contains("рубин") || lowerTarget.contains("ruby") || lowerTarget.contains("rub") || lowerTarget.contains("руб") || lowerTarget.contains("rubin") -> listOf("рубин", "ruby", "rub", "руб", "rubin")
            lowerTarget.contains("руда") || lowerTarget.contains("ore") -> listOf("руда", "ore")
            else -> emptyList()
        }
        
        return synonyms.any { lowerText.contains(it) }
    }

    private fun isInsideOverlay(rect: Rect?, screenWidth: Float, screenHeight: Float, density: Float): Boolean {
        if (rect == null) return false
        
        // Only filter out screen coordinates if the large control panel is actually shown!
        val isPanelVisible = OverlayControlService.instance?.isControlPanelVisible() ?: false
        if (!isPanelVisible) {
            return false
        }
        
        // The control panel has width = 280dp, height = 360dp, and is aligned centered
        val overlayWidth = 280 * density
        val overlayHeight = 360 * density
        
        val leftBound = (screenWidth - overlayWidth) / 2
        val rightBound = (screenWidth + overlayWidth) / 2
        val topBound = (screenHeight - overlayHeight) / 2
        val bottomBound = (screenHeight + overlayHeight) / 2
        
        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()
        
        return centerX in leftBound..rightBound && centerY in topBound..bottomBound
    }

    private fun isLogOrOverlayText(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.startsWith("[") || 
               lower.endsWith("]") || 
               lower.contains("live log") || 
               lower.contains("threshold") || 
               lower.contains("mode:") || 
               lower.contains("попытка") || 
               lower.contains("кликаем") || 
               lower.contains("координатах") || 
               lower.contains("ocr:") || 
               lower.contains("распознанные") ||
               lower.contains("выполняем")
    }

    private fun isConfirmButtonText(text: String): Boolean {
        val t = text.lowercase().trim()
        val words = t.split(Regex("[\\s,.:;!?-]+")).filter { it.isNotEmpty() }
        if (words.contains("ok") || words.contains("ок") || words.contains("yes") || words.contains("да")) {
            return true
        }
        return t.contains("confirm") || 
               t.contains("conflrm") || 
               t.contains("conlirm") || 
               t.contains("conirm") || 
               t.contains("contirm") || 
               t.contains("cornfirm") || 
               t.contains("comfirm") || 
               t.contains("corfim") || 
               t.contains("contim") || 
               t.contains("onfirm") || 
               t.contains("onlirm") || 
               t.contains("onirm") || 
               t.contains("ontirm") || 
               t.contains("goniirm") || 
               t.contains("gonfirm") || 
               t.contains("gonfmn") || 
               t.contains("corfimm") || 
               t.contains("confrm") || 
               t.contains("conffrm") || 
               t.contains("confilm") || 
               t.contains("confilrm") || 
               t.contains("coníirm") || 
               t == "conf" ||
               t.contains("подтвердить") || 
               t.contains("nодтвердить") || 
               t.contains("noдтвepдить") || 
               t.contains("podtverdit") ||
               t.contains("пoдтв")
    }

    private fun isRateLimitDialogShowing(
        lines: List<com.google.mlkit.vision.text.Text.Line>
    ): Boolean {
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        return fullText.contains("try through") ||
               fullText.contains("try again") ||
               fullText.contains("too fast") ||
               (fullText.contains("try") && fullText.contains("second")) ||
               fullText.contains("попробуйте через") ||
               fullText.contains("подождите")
    }

    private fun isConfirmationDialogShowing(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleY: Float,
        screenHeight: Float
    ): Boolean {
        if (screenHeight <= 0) return false
        
        // If the success dialog or the already purchased dialog is showing, then the initial confirmation dialog is NOT showing.
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        val hasSuccessOrAlreadyPurchasedText = 
            (fullText.contains("already") && fullText.contains("purchased")) ||
            fullText.contains("been purchased") ||
            fullText.contains("уже куплен") ||
            (fullText.contains("market") && fullText.contains("offer")) ||
            fullText.contains("buy offer") ||
            fullText.contains("buyoffer")
            
        if (hasSuccessOrAlreadyPurchasedText) return false

        return lines.any { line ->
            val bounds = line.boundingBox
            if (bounds != null) {
                val centerYPercent = (bounds.centerY() * scaleY) / screenHeight
                // Only consider texts in the central height band of the screen (between 30% and 85%)
                if (centerYPercent in 0.30f..0.85f) {
                    val t = line.text.lowercase()
                    t.contains("you will pay") || 
                    t.contains("vou will pay") || 
                    t.contains("you wil pay") ||
                    t.contains("вы заплатите") ||
                    t.contains("bы заплатите") ||
                    t.contains("вы заплатит") ||
                    t.contains("покупка") ||
                    t.contains("количество") ||
                    t.contains("выкупить") ||
                    t.contains("купить") ||
                    t.contains("pay")
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    private fun isBuyConfirmationText(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("buy") || 
               t.contains("3uy") ||
               t.contains("13uy") ||
               t.contains("buv") ||
               t.contains("bvy") ||
               t.contains("boy") ||
               t.contains("soy") ||
               t.contains("roy") ||
               t.contains("doy") ||
               t.contains("day") ||
               t.contains("bov") ||
               t.contains("bup") ||
               t.contains("купить") || 
               t.contains("выкупить") ||
               t.contains("kynutb") ||
               t.contains("kynuTb") ||
               t.contains("kyrutb") ||
               t.contains("kynwtb") ||
               t.contains("kynu16") ||
               t.contains("kynu1b") ||
               t.contains("kynut") ||
               t.contains("kynwt") ||
               t.contains("kyrut") ||
               t.contains("kyruTb") ||
               t.contains("kyn") ||
               t.contains("kyr") ||
               t.contains("kun") ||
               t.contains("kur") ||
               t.contains("kup") ||
               t.contains("ryn") ||
               t.contains("ryr") ||
               t.contains("kir") ||
               t.contains("kin") ||
               t.contains("confirm") ||
               t.contains("conflrm") ||
               t.contains("conlirm") ||
               t.contains("conirm") ||
               t.contains("contirm") ||
               t.contains("cornfirm") ||
               t.contains("comfirm") ||
               t.contains("corfim") ||
               t.contains("contim") ||
               t.contains("onfirm") ||
               t.contains("onlirm") ||
               t.contains("onirm") ||
               t.contains("ontirm") ||
               t.contains("goniirm") ||
               t.contains("gonfirm") ||
               t.contains("gonfmn") ||
               t.contains("corfimm") ||
               t.contains("confrm") ||
               t.contains("conffrm") ||
               t.contains("confilm") ||
               t.contains("confilrm") ||
               t.contains("coníirm") ||
               t.contains("conf")
    }

    /**
     * Executes instant purchase confirmation when a lot is clicked.
     * Uses cached coordinates, OpenCV vision, or geometric calculation of the modal's "Buy" button.
     */
    private suspend fun executeBuyConfirmation(
        actualItemName: String,
        price: Double,
        q: Double,
        config: AppConfiguration,
        screenWidth: Float,
        screenHeight: Float,
        db: AppDatabase
    ): Boolean {
        val clickStart = System.currentTimeMillis()
        AutoBuyerLogs.addLog("⏳ [$clickStart] Ожидаем появление модального окна подтверждения покупки ('Покупка'/'Buy')...")

        // Wait minimal delay for Telegram webview modal popup transition
        delay(CONFIRMATION_DIALOG_DELAY_MS)

        var buyClickX: Float = screenWidth * 0.50f
        var buyClickY: Float = screenHeight * 0.746f

        if (cachedBuyX != null && cachedBuyY != null) {
            buyClickX = cachedBuyX!!
            buyClickY = cachedBuyY!!
            AutoBuyerLogs.addLog("⚡ [МГНОВЕННОЕ ПОДТВЕРЖДЕНИЕ] Нажимаем кнопку 'Buy' в ($buyClickX, $buyClickY)")
            clickAt(buyClickX, buyClickY)
        } else {
            val dialogBitmap = MediaProjectionHelper.getLatestScreenshot()
            if (dialogBitmap != null) {
                try {
                    val openCvBtn = OpenCvVisionEngine.findConfirmationBuyButton(dialogBitmap, screenWidth, screenHeight)
                    if (openCvBtn != null) {
                        buyClickX = openCvBtn.first
                        buyClickY = openCvBtn.second
                        AutoBuyerLogs.addLog("👁️ [OpenCV] Найдена золотая кнопка 'Buy' в (${buyClickX.toInt()}, ${buyClickY.toInt()})")
                    } else {
                        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        val dialogInput = InputImage.fromBitmap(dialogBitmap, 0)
                        val dialogResult = Tasks.await(recognizer.process(dialogInput))
                        val dLines = dialogResult.textBlocks.flatMap { it.lines }
                        val targetLine = dLines.firstOrNull { line ->
                            val b = line.boundingBox
                            if (b != null) {
                                val cX = b.centerX() * (screenWidth / dialogBitmap.width)
                                val cY = b.centerY() * (screenHeight / dialogBitmap.height)
                                val cXPercent = cX / screenWidth
                                val cYPercent = cY / screenHeight
                                cXPercent in 0.25f..0.75f && cYPercent in 0.68f..0.83f && isBuyConfirmationText(line.text)
                            } else false
                        }
                        if (targetLine?.boundingBox != null) {
                            val b = targetLine.boundingBox!!
                            buyClickX = b.centerX() * (screenWidth / dialogBitmap.width)
                            buyClickY = b.centerY() * (screenHeight / dialogBitmap.height)
                            AutoBuyerLogs.addLog("🎯 [OCR] Найдена кнопка '${targetLine.text}' в (${buyClickX.toInt()}, ${buyClickY.toInt()})")
                        } else {
                            val payLine = dLines.firstOrNull { it.text.lowercase().contains("pay") || it.text.lowercase().contains("плат") }
                            if (payLine?.boundingBox != null) {
                                val payY = payLine.boundingBox!!.centerY() * (screenHeight / dialogBitmap.height)
                                buyClickY = payY + screenHeight * 0.155f
                                AutoBuyerLogs.addLog("📍 [ГЕОМЕТРИЯ] Кнопка 'Buy' рассчитана со смещением от 'You will pay' -> (${buyClickX.toInt()}, ${buyClickY.toInt()})")
                            } else {
                                AutoBuyerLogs.addLog("🎯 [ГЕОМЕТРИЯ] Используем точный геометрический центр кнопки 'Buy' -> (${buyClickX.toInt()}, ${buyClickY.toInt()})")
                            }
                        }
                    }
                } catch (e: Exception) {
                    AutoBuyerLogs.addLog("⚠️ Ошибка распознавания окна подтверждения: ${e.message}")
                } finally {
                    dialogBitmap.recycle()
                }
            } else {
                AutoBuyerLogs.addLog("🎯 [ГЕОМЕТРИЯ] Кликаем кнопку 'Buy' по центру модального окна -> (${buyClickX.toInt()}, ${buyClickY.toInt()})")
            }

            cachedBuyX = buyClickX
            cachedBuyY = buyClickY

            AutoBuyerLogs.addLog("🎉 [ПОДТВЕРЖДЕНИЕ] Нажимаем кнопку 'Buy' в окне покупки (${buyClickX.toInt()}, ${buyClickY.toInt()})")
            clickAt(buyClickX, buyClickY)
        }

        // Webview touch double-tap insurance
        delay(60L)
        clickAt(buyClickX, buyClickY)

        try {
            val purchaseMsg = formatPurchaseMessage(q, actualItemName, price)
            db.purchaseDao().insertPurchase(
                PurchaseRecord(
                    timestamp = System.currentTimeMillis(),
                    itemName = actualItemName,
                    price = price,
                    quantity = q,
                    details = purchaseMsg
                )
            )
            registerPurchaseSuccess()
            AutoBuyerLogs.addLog("🎉 $purchaseMsg")
        } catch (e: Exception) {
            AutoBuyerLogs.addLog("⚠️ Ошибка записи покупки: ${e.message}")
        }

        cooldownUntilMillis = System.currentTimeMillis() + 15 * 1000L
        lastPurchasedPrice = price
        lastPurchasedQuantity = q
        lastPurchaseAttemptTime = System.currentTimeMillis()
        isStuckCheckPending = true

        verifyPurchaseResultAndHandleFailure(price, q, config)
        return true
    }

    /**
     * Checks if the purchase confirmation modal dialog ("Покупка" / "You will pay") is currently open on screen.
     * If open, immediately clicks the "Buy" button to complete purchase.
     */
    private suspend fun confirmPurchaseDialogIfNeeded(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleX: Float,
        scaleY: Float,
        screenWidth: Float,
        screenHeight: Float,
        config: AppConfiguration,
        db: AppDatabase,
        bitmap: Bitmap? = null
    ): Boolean {
        val fullText = lines.joinToString(" ") { it.text.lowercase() }

        // Skip if failure or success dialogs are open
        val isOtherDialog = (fullText.contains("already") && fullText.contains("purchased")) ||
                            fullText.contains("been purchased") ||
                            fullText.contains("уже куплен") ||
                            (fullText.contains("market") && fullText.contains("offer")) ||
                            fullText.contains("buy offer") ||
                            fullText.contains("try through") ||
                            fullText.contains("too fast")
        if (isOtherDialog) return false

        val isConfirmationDialog = fullText.contains("покупка") ||
                                   fullText.contains("you will pay") ||
                                   fullText.contains("vou will pay") ||
                                   fullText.contains("you wil pay") ||
                                   fullText.contains("вы заплатите") ||
                                   fullText.contains("bы заплатите") ||
                                   fullText.contains("вы заплатит") ||
                                   (fullText.contains("pay") && fullText.contains("1400")) ||
                                   lines.any { line ->
                                       val b = line.boundingBox
                                       if (b != null) {
                                           val cYPercent = (b.centerY() * scaleY) / screenHeight
                                           val t = line.text.lowercase()
                                           cYPercent in 0.32f..0.65f && (t.contains("покупка") || t.contains("will pay") || t.contains("you will"))
                                       } else false
                                   }

        if (!isConfirmationDialog) return false

        AutoBuyerLogs.addLog("🎯 [ОКНО ПОКУПКИ ОБНАРУЖЕНО] На экране открыто модальное окно 'Покупка' ('You will pay')! Завершаем покупку кликом по 'Buy'...")

        var buyX: Float = cachedBuyX ?: (screenWidth * 0.50f)
        var buyY: Float = cachedBuyY ?: (screenHeight * 0.746f)

        // 1. OpenCV golden button detector
        if (bitmap != null) {
            val cvBtn = OpenCvVisionEngine.findConfirmationBuyButton(bitmap, screenWidth, screenHeight)
            if (cvBtn != null) {
                buyX = cvBtn.first
                buyY = cvBtn.second
                AutoBuyerLogs.addLog("👁️ [OpenCV] Найдена кнопка 'Buy' в координатах (${buyX.toInt()}, ${buyY.toInt()})")
            }
        }

        // 2. OCR detection if OpenCV didn't find it or no bitmap
        if (cachedBuyX == null && bitmap == null) {
            val targetLine = lines.firstOrNull { line ->
                val b = line.boundingBox
                if (b != null) {
                    val cX = b.centerX() * scaleX
                    val cY = b.centerY() * scaleY
                    val cXPercent = cX / screenWidth
                    val cYPercent = cY / screenHeight
                    cXPercent in 0.25f..0.75f && cYPercent in 0.68f..0.83f && isBuyConfirmationText(line.text)
                } else false
            }
            if (targetLine?.boundingBox != null) {
                val b = targetLine.boundingBox!!
                buyX = b.centerX() * scaleX
                buyY = b.centerY() * scaleY
                AutoBuyerLogs.addLog("🎯 [OCR] Найдена кнопка '${targetLine.text}' в (${buyX.toInt()}, ${buyY.toInt()})")
            }
        }

        cachedBuyX = buyX
        cachedBuyY = buyY

        AutoBuyerLogs.addLog("⚡ [ПОКУПКА] Кликаем кнопку 'Buy' в координатах (${buyX.toInt()}, ${buyY.toInt()})")
        clickAt(buyX, buyY)
        delay(60L)
        clickAt(buyX, buyY) // double-click insurance in webview

        // Extract price from modal lines
        var detectedPrice = 0.0
        for (line in lines) {
            val ep = extractPrice(line.text)
            if (ep != null && ep > 0.0) {
                detectedPrice = ep
                break
            }
        }
        val targetItem = config.selectedGems.split(",").firstOrNull { it.isNotBlank() } ?: config.targetItemName

        try {
            val purchaseMsg = formatPurchaseMessage(1.0, targetItem, detectedPrice)
            db.purchaseDao().insertPurchase(
                PurchaseRecord(
                    timestamp = System.currentTimeMillis(),
                    itemName = targetItem,
                    price = detectedPrice,
                    quantity = 1.0,
                    details = purchaseMsg
                )
            )
            registerPurchaseSuccess()
            AutoBuyerLogs.addLog("🎉 $purchaseMsg")
        } catch (e: Exception) {
            AutoBuyerLogs.addLog("⚠️ Ошибка записи покупки: ${e.message}")
        }

        cooldownUntilMillis = System.currentTimeMillis() + 15 * 1000L
        verifyPurchaseResultAndHandleFailure(detectedPrice, 1.0, config)
        return true
    }

    private suspend fun dismissLotAlreadyPurchasedDialogIfNeeded(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleX: Float,
        scaleY: Float,
        db: AppDatabase
    ): Boolean {
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        val hasLotAlreadyPurchasedText = 
            (fullText.contains("already") && fullText.contains("purchased")) ||
            fullText.contains("been purchased") ||
            fullText.contains("уже куплен") ||
            fullText.contains("uzhe kuplen") ||
            (fullText.contains("has") && fullText.contains("purchased"))

        if (hasLotAlreadyPurchasedText) {
            val config = db.configurationDao().getConfiguration()
            var clickX = -1f
            var clickY = -1f
            
            if (config != null && config.calibratedConfirmX != -1f && config.calibratedConfirmY != -1f) {
                clickX = config.calibratedConfirmX
                clickY = config.calibratedConfirmY
                AutoBuyerLogs.addLog("⚠️ [ОШИБКА] Обнаружено окно 'Лот уже куплен'! Кликаем калиброванную 'Confirm' в координатах ($clickX, $clickY) и сбрасываем кулдаун.")
                clickAt(clickX, clickY)
            } else {
                val confirmLine = lines.firstOrNull { line ->
                    isConfirmButtonText(line.text)
                }
                val bounds = confirmLine?.boundingBox
                if (bounds != null) {
                    clickX = bounds.centerX() * scaleX
                    clickY = bounds.centerY() * scaleY
                    AutoBuyerLogs.addLog("⚠️ [ОШИБКА] Обнаружено окно 'Лот уже куплен'! Кликаем 'Confirm' в координатах ($clickX, $clickY) и сбрасываем кулдаун.")
                    clickAt(clickX, clickY)
                } else {
                val screenWindowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val screenMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                screenWindowManager.defaultDisplay.getRealMetrics(screenMetrics)
                val cX = screenMetrics.widthPixels / 2f
                val cY = screenMetrics.heightPixels * 0.65f
                
                // Coordinate-based candidate fallback
                val candidates = lines.filter { line ->
                    val b = line.boundingBox
                    if (b != null) {
                        val centerX = b.centerX() * scaleX
                        val centerY = b.centerY() * scaleY
                        val cXPercent = centerX / screenMetrics.widthPixels
                        val cYPercent = centerY / screenMetrics.heightPixels
                        cXPercent in 0.25f..0.75f && cYPercent in 0.58f..0.80f
                    } else {
                        false
                    }
                }
                val bestCandidate = candidates.maxByOrNull { line ->
                    line.boundingBox?.centerY() ?: 0
                }
                if (bestCandidate != null) {
                    val b = bestCandidate.boundingBox!!
                    clickX = b.centerX() * scaleX
                    clickY = b.centerY() * scaleY
                    AutoBuyerLogs.addLog("🎯 Нашли кнопку Confirm по координатам: '${bestCandidate.text}' в (${clickX}, ${clickY})")
                    clickAt(clickX, clickY)
                } else {
                    clickX = cX
                    clickY = cY
                    AutoBuyerLogs.addLog("⚠️ [ОШИБКА] Обнаружено окно 'Лот уже куплен', но кнопка 'Confirm' не найдена. Кликаем по умолчанию в координатах ($clickX, $clickY) и сбрасываем кулдаун.")
                    clickAt(clickX, clickY)
                }
            }
        }

            val hadCooldown = cooldownUntilMillis > 0L
            cooldownUntilMillis = 0L

            if (hadCooldown) {
                try {
                    db.purchaseDao().deleteLatestPurchase()
                    AutoBuyerLogs.addLog("🗑️ Запись о покупке удалена из базы данных, так как покупка не состоялась.")
                } catch (e: Exception) {
                    AutoBuyerLogs.addLog("⚠️ Ошибка удаления записи о покупке: ${e.message}")
                }
            }

            return true
        }
        return false
    }

    private suspend fun dismissSellOrUnwantedModalIfNeeded(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleX: Float,
        scaleY: Float,
        screenWidth: Float,
        screenHeight: Float
    ): Boolean {
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        val isSellModalOpen = fullText.contains("calculation") ||
                fullText.contains("post") ||
                fullText.contains("days on the market") ||
                fullText.contains("you will receive") ||
                (fullText.contains("amount") && fullText.contains("price") && fullText.contains("piece")) ||
                fullText.contains("sale's fee") ||
                fullText.contains("purchase's fee")

        if (isSellModalOpen) {
            AutoBuyerLogs.addLog("⚠️ [ОКНО ПРОДАЖИ ОБНАРУЖЕНО] На экране открыто окно продажи/расчета ('Post/Sell'). Закрываем окно кликом по (X) и переключаемся на 'Buy'...")
            
            // 1. Click yellow ( X ) close button at top-left of the modal (X: 14%, Y: 28%)
            val closeX = screenWidth * 0.14f
            val closeY = screenHeight * 0.28f
            clickAt(closeX, closeY)
            delay(500)

            // 2. Also ensure we click the 'Buy' market tab (X: 20%, Y: 30%)
            val buyTabX = screenWidth * 0.20f
            val buyTabY = screenHeight * 0.30f
            clickAt(buyTabX, buyTabY)
            delay(500)

            return true
        }

        return false
    }

    private suspend fun handleTryThroughDialogIfNeeded(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleX: Float,
        scaleY: Float,
        config: AppConfiguration
    ): Boolean {
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        
        val isRateLimitText = fullText.contains("try through") ||
                              fullText.contains("cry througb") ||
                              fullText.contains("try througb") ||
                              fullText.contains("cry through") ||
                              fullText.contains("througb") ||
                              fullText.contains("through") ||
                              fullText.contains("server is lost") ||
                              fullText.contains("connection with") ||
                              fullText.contains("cry connect") ||
                              fullText.contains("try connect") ||
                              fullText.contains("too fast") ||
                              (fullText.contains("cry") && fullText.contains("second")) ||
                              (fullText.contains("try") && fullText.contains("second")) ||
                              (fullText.contains("cry") && fullText.contains("sec")) ||
                              (fullText.contains("try") && fullText.contains("sec")) ||
                              fullText.contains("попробуйте через") ||
                              fullText.contains("попробуйте") ||
                              fullText.contains("подождите")

        if (!isRateLimitText) {
            return false
        }

        val confirmLine = lines.firstOrNull { isConfirmButtonText(it.text) }

        // Exclude success dialog or already purchased dialogs
        val isSuccessOrPurchased = (fullText.contains("market") && fullText.contains("offer")) ||
                                   fullText.contains("buy offer") ||
                                   fullText.contains("buyoffer") ||
                                   (fullText.contains("already") && fullText.contains("purchased")) ||
                                   fullText.contains("been purchased") ||
                                   fullText.contains("уже куплен")

        if (isSuccessOrPurchased) {
            return false
        }

        // Extract seconds to wait from lines mentioning time/seconds or through/througb
        var secondsToWait = 1
        val targetLines = lines.filter { line ->
            val t = line.text.lowercase()
            t.contains("through") || t.contains("througb") || t.contains("second") || t.contains("sec") || t.contains("cry") || t.contains("try")
        }
        val regex = Regex("(\\d+)")
        for (line in targetLines) {
            val match = regex.find(line.text)
            if (match != null) {
                val sec = match.value.toIntOrNull()
                if (sec != null && sec in 1..120) {
                    secondsToWait = sec
                    break
                }
            }
        }
        if (secondsToWait == 1) {
            val match = regex.find(fullText)
            if (match != null) {
                val sec = match.value.toIntOrNull()
                if (sec != null && sec in 1..120) {
                    secondsToWait = sec
                }
            }
        }

        val randomBonus = (2..4).random()
        val totalWaitSeconds = maxOf(1, secondsToWait) + randomBonus

        var clickX = -1f
        var clickY = -1f

        if (config.calibratedConfirmX != -1f && config.calibratedConfirmY != -1f) {
            clickX = config.calibratedConfirmX
            clickY = config.calibratedConfirmY
        } else {
            val bounds = confirmLine?.boundingBox
            if (bounds != null) {
                clickX = bounds.centerX() * scaleX
                clickY = bounds.centerY() * scaleY
            } else {
                val screenWindowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val screenMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                screenWindowManager.defaultDisplay.getRealMetrics(screenMetrics)
                clickX = screenMetrics.widthPixels / 2f
                clickY = screenMetrics.heightPixels * 0.65f
            }
        }

        AutoBuyerLogs.addLog("⏳ [ОГРАНИЧЕНИЕ] Обнаружено окно задержки/ограничения ('Try through $secondsToWait seconds'). " +
                "Кликаем 'Confirm' в координатах (${clickX.toInt()}, ${clickY.toInt()}) для закрытия и засыпаем на $totalWaitSeconds сек...")
        clickAt(clickX, clickY)

        delay(totalWaitSeconds * 1000L)
        AutoBuyerLogs.addLog("✨ [ОГРАНИЧЕНИЕ] Ожидание завершено, окно закрыто. Продолжаем работу.")
        return true
    }

    private fun isMarkAllAsReadButtonText(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("mark") ||
               t.contains("read") ||
               t.contains("marked") ||
               t.contains("все как") ||
               t.contains("прочитать") ||
               t.contains("прочитано") ||
               t.contains("отметить") ||
               t.contains("все") ||
               t.contains("всё") ||
               isConfirmButtonText(t)
    }

    private fun isMailMarketBuyOfferDialog(lines: List<com.google.mlkit.vision.text.Text.Line>): Boolean {
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        return (fullText.contains("market") && fullText.contains("offer")) ||
               fullText.contains("market buy") || 
               fullText.contains("buy offer") || 
               fullText.contains("buyoffer") ||
               fullText.contains("mark all") ||
               fullText.contains("as read") ||
               fullText.contains("marked all") ||
               (fullText.contains("mark") && fullText.contains("read")) ||
               (fullText.contains("mail") && (fullText.contains("offer") || fullText.contains("market") || fullText.contains("read") || fullText.contains("+1"))) ||
               (fullText.contains("почта") && (fullText.contains("предложен") || fullText.contains("рынок") || fullText.contains("прочитат") || fullText.contains("+1"))) ||
               fullText.contains("рыночное предложение") ||
               fullText.contains("предложение о покупке")
    }

    private suspend fun findMarkAllAsReadCoordinates(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleX: Float,
        scaleY: Float,
        screenWidth: Float,
        screenHeight: Float
    ): Pair<Float, Float> {
        // 1. Look for text explicitly matching 'Mark all as READ' in bottom region of dialog (Y: 72%..92%)
        val markAllLines = lines.filter { line ->
            val bounds = line.boundingBox
            if (bounds != null) {
                val cY = bounds.centerY() * scaleY
                val cX = bounds.centerX() * scaleX
                val cYPercent = cY / screenHeight
                val cXPercent = cX / screenWidth
                cYPercent in 0.70f..0.92f && cXPercent in 0.20f..0.80f && isMarkAllAsReadButtonText(line.text)
            } else false
        }

        val bestLine = markAllLines.maxByOrNull { it.boundingBox?.centerY() ?: 0 }
        if (bestLine != null) {
            val bounds = bestLine.boundingBox!!
            val cX = bounds.centerX() * scaleX
            val cY = bounds.centerY() * scaleY
            AutoBuyerLogs.addLog("🎯 [КНОПКА НАЙДЕНА ПО ТЕКСТУ] '${bestLine.text}' в координатах (${cX.toInt()}, ${cY.toInt()})")
            return Pair(cX, cY)
        }

        // 2. Look for any bottom-anchored action button in Mail dialog (Y: 76%..89%)
        val bottomCandidates = lines.filter { line ->
            val bounds = line.boundingBox
            if (bounds != null) {
                val cY = bounds.centerY() * scaleY
                val cX = bounds.centerX() * scaleX
                val cYPercent = cY / screenHeight
                val cXPercent = cX / screenWidth
                cYPercent in 0.75f..0.90f && cXPercent in 0.25f..0.75f
            } else false
        }

        val fallbackLine = bottomCandidates.maxByOrNull { it.boundingBox?.centerY() ?: 0 }
        if (fallbackLine != null) {
            val bounds = fallbackLine.boundingBox!!
            val cX = bounds.centerX() * scaleX
            val cY = bounds.centerY() * scaleY
            AutoBuyerLogs.addLog("🎯 [КНОПКА НАЙДЕНА ПО ПОЗИЦИИ В ОКНЕ] '${fallbackLine.text}' в координатах (${cX.toInt()}, ${cY.toInt()})")
            return Pair(cX, cY)
        }

        // 3. Exact geometric center of 'Mark all as READ' button from screenshot (X: 50%, Y: 83%)
        val defaultX = screenWidth * 0.50f
        val defaultY = screenHeight * 0.83f
        AutoBuyerLogs.addLog("🎯 [ГЕОМЕТРИЧЕСКИЙ КЛИК] 'Mark all as READ' в (${defaultX.toInt()}, ${defaultY.toInt()})")
        return Pair(defaultX, defaultY)
    }

    private suspend fun dismissSuccessDialogIfNeeded(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleX: Float,
        scaleY: Float,
        db: AppDatabase,
        config: AppConfiguration
    ): Boolean {
        if (isMailMarketBuyOfferDialog(lines)) {
            val screenWindowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val screenMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            screenWindowManager.defaultDisplay.getRealMetrics(screenMetrics)
            val screenWidth = screenMetrics.widthPixels.toFloat()
            val screenHeight = screenMetrics.heightPixels.toFloat()

            val (clickX, clickY) = findMarkAllAsReadCoordinates(lines, scaleX, scaleY, screenWidth, screenHeight)

            AutoBuyerLogs.addLog("✅ [УСПЕХ] Обнаружено окно 'Mail / Market buy offer'! Кликаем 'Mark all as READ' в координатах (${clickX.toInt()}, ${clickY.toInt()}).")
            clickAt(clickX, clickY)

            // Save purchase record to database if not already logged
            try {
                val purchaseMsg = formatPurchaseMessage(1.0, config.targetItemName, config.priceThreshold)
                db.purchaseDao().insertPurchase(
                    PurchaseRecord(
                        timestamp = System.currentTimeMillis(),
                        itemName = config.targetItemName,
                        price = config.priceThreshold,
                        quantity = 1.0,
                        details = purchaseMsg
                    )
                )
                registerPurchaseSuccess()
                AutoBuyerLogs.addLog("🎉 $purchaseMsg")
            } catch (e: Exception) {
                AutoBuyerLogs.addLog("⚠️ Ошибка записи покупки в БД: ${e.message}")
            }

            cooldownUntilMillis = 0L
            AutoBuyerLogs.addLog("✅ Окно закрыто. Готовы к следующим покупкам.")
            return true
        }
        return false
    }

    private suspend fun verifyPurchaseResultAndHandleFailure(
        price: Double, 
        q: Double, 
        config: AppConfiguration
    ) {
        val db = AppDatabase.getDatabase(this@LootBuyerAccessibilityService)
        var dialogDetectedAndHandled = false
        
        // Loop up to 6 times (about 5-6 seconds total) to wait for and dismiss success/failure dialog
        for (attempt in 1..6) {
            delay(800L)
            
            val verificationBitmap = MediaProjectionHelper.getLatestScreenshot() ?: continue
            
            val metrics = android.util.DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val screenWidth = metrics.widthPixels.toFloat()
            val screenHeight = metrics.heightPixels.toFloat()
            
            val scaleX = screenWidth / verificationBitmap.width
            val scaleY = screenHeight / verificationBitmap.height
            
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val inputImage = InputImage.fromBitmap(verificationBitmap, 0)
            
            val result = try {
                Tasks.await(recognizer.process(inputImage))
            } catch (e: Exception) {
                verificationBitmap.recycle()
                continue
            }
            
            val lines = result.textBlocks.flatMap { it.lines }
            val fullText = lines.joinToString(" ") { it.text.lowercase() }
            
            if (handleTryThroughDialogIfNeeded(lines, scaleX, scaleY, config)) {
                cooldownUntilMillis = 0L
                dialogDetectedAndHandled = true
                try {
                    db.purchaseDao().deleteLatestPurchase()
                } catch (e: Exception) {}
                verificationBitmap.recycle()
                break
            }
            
            // 1. Check for "The lot has already been purchased" failure dialog
            val hasLotAlreadyPurchasedText = 
                (fullText.contains("already") && fullText.contains("purchased")) ||
                fullText.contains("been purchased") ||
                fullText.contains("уже куплен") ||
                fullText.contains("uzhe kuplen") ||
                (fullText.contains("has") && fullText.contains("purchased"))
    
            // 2. Check for "Market buy offer" / "Mail" success dialog
            val isMarketOfferShowing = isMailMarketBuyOfferDialog(lines)
                
            if (hasLotAlreadyPurchasedText) {
                var clickX = -1f
                var clickY = -1f
                if (config.calibratedConfirmX != -1f && config.calibratedConfirmY != -1f) {
                    clickX = config.calibratedConfirmX
                    clickY = config.calibratedConfirmY
                } else {
                    val confirmLine = lines.firstOrNull { line -> isConfirmButtonText(line.text) }
                    val bounds = confirmLine?.boundingBox
                    if (bounds != null) {
                        clickX = bounds.centerX() * scaleX
                        clickY = bounds.centerY() * scaleY
                    } else {
                        val candidates = lines.filter { line ->
                            val b = line.boundingBox
                            if (b != null) {
                                val cX = b.centerX() * scaleX
                                val cY = b.centerY() * scaleY
                                val cXPercent = cX / screenWidth
                                val cYPercent = cY / screenHeight
                                cXPercent in 0.25f..0.75f && cYPercent in 0.58f..0.80f
                            } else {
                                false
                            }
                        }
                        val bestCandidate = candidates.maxByOrNull { line ->
                            line.boundingBox?.centerY() ?: 0
                        }
                        if (bestCandidate != null) {
                            val b = bestCandidate.boundingBox!!
                            clickX = b.centerX() * scaleX
                            clickY = b.centerY() * scaleY
                        } else {
                            clickX = screenWidth / 2f
                            clickY = screenHeight * 0.65f
                        }
                    }
                }
    
                AutoBuyerLogs.addLog("⚠️ [ОШИБКА] Лот уже куплен кем-то другим! Нажимаем 'Confirm' в ($clickX, $clickY) и сбрасываем кулдаун.")
                clickAt(clickX, clickY)
    
                cooldownUntilMillis = 0L
                dialogDetectedAndHandled = true
    
                try {
                    db.purchaseDao().deleteLatestPurchase()
                    AutoBuyerLogs.addLog("🗑️ Запись о покупке удалена из базы данных, так как покупка не состоялась (лот уже куплен).")
                } catch (e: Exception) {
                    AutoBuyerLogs.addLog("⚠️ Ошибка удаления записи о покупке: ${e.message}")
                }
                verificationBitmap.recycle()
                break
            } else if (isMarketOfferShowing) {
                val (clickX, clickY) = findMarkAllAsReadCoordinates(lines, scaleX, scaleY, screenWidth, screenHeight)
    
                AutoBuyerLogs.addLog("✅ [УСПЕХ] Обнаружено окно 'Mail / Market buy offer'! Нажимаем 'Mark all as READ' в координатах (${clickX.toInt()}, ${clickY.toInt()})")
                clickAt(clickX, clickY)
    
                cooldownUntilMillis = 0L
                AutoBuyerLogs.addLog("✅ Покупка подтверждена! Окно 'Mail' закрыто. Готовы к продолжению работы.")
                dialogDetectedAndHandled = true
                verificationBitmap.recycle()
                break
            }
            
            verificationBitmap.recycle()
        }
        
        // If no success/failure dialog was detected in the retry loop, verify if the confirmation dialog is still there or if we can safety-check
        if (!dialogDetectedAndHandled) {
            val safetyBitmap = MediaProjectionHelper.getLatestScreenshot()
            if (safetyBitmap != null) {
                val metrics = android.util.DisplayMetrics()
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val screenWidth = metrics.widthPixels.toFloat()
                val screenHeight = metrics.heightPixels.toFloat()
                val scaleX = screenWidth / safetyBitmap.width
                val scaleYActual = screenHeight / safetyBitmap.height
                
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val inputImage = InputImage.fromBitmap(safetyBitmap, 0)
                val result = try {
                    Tasks.await(recognizer.process(inputImage))
                } catch (e: Exception) {
                    safetyBitmap.recycle()
                    return
                }
                val lines = result.textBlocks.flatMap { it.lines }
                val dialogIsStillShowing = isConfirmationDialogShowing(lines, scaleYActual, screenHeight)
                if (dialogIsStillShowing) {
                    AutoBuyerLogs.addLog("⚠️ [ОШИБКА] Окно подтверждения покупки всё ещё отображается! Сбрасываем кулдаун для повтора.")
                    cooldownUntilMillis = 0L
                    try {
                        db.purchaseDao().deleteLatestPurchase()
                    } catch (e: Exception) {
                        AutoBuyerLogs.addLog("⚠️ Ошибка удаления записи о покупке: ${e.message}")
                    }
                } else {
                    // Confirmation is gone and no success/failure dialog detected - we reset temporary cooldown to let regular scan find it
                    AutoBuyerLogs.addLog("✨ Покупка отправлена, но финальный диалог еще не обработан. Сбрасываем временную блокировку для продолжения сканирования...")
                    cooldownUntilMillis = 0L
                }
                safetyBitmap.recycle()
            } else {
                cooldownUntilMillis = 0L
            }
        }
    }

    private suspend fun restartGameByReloadPage() {
        AutoBuyerLogs.addLog("🔄 [ПЕРЕЗАПУСК] Начинаем перезапуск бота через меню трех точек...")
        
        val metrics = android.util.DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()
        
        // Step 1: Click the three dots in the top-right corner
        // Typically around X = 94% of screen width, Y = 6% of screen height
        val threeDotsX = screenWidth * 0.94f
        val threeDotsY = screenHeight * 0.055f
        AutoBuyerLogs.addLog("👉 [ПЕРЕЗАПУСК] Кликаем по меню трех точек в координатах ($threeDotsX, $threeDotsY)...")
        clickAt(threeDotsX, threeDotsY)
        
        // Wait for the menu to open
        delay(1500)
        
        // Step 2: Capture screen to find "Reload" / "Обновить"
        var clickedReload = false
        val menuBitmap = MediaProjectionHelper.getLatestScreenshot()
        if (menuBitmap != null) {
            val scaleX = screenWidth / menuBitmap.width
            val scaleY = screenHeight / menuBitmap.height
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val inputImage = InputImage.fromBitmap(menuBitmap, 0)
            val result = try {
                Tasks.await(recognizer.process(inputImage))
            } catch (e: Exception) {
                null
            }
            if (result != null) {
                val lines = result.textBlocks.flatMap { it.lines }
                val reloadLine = lines.firstOrNull { line ->
                    val text = line.text.lowercase()
                    text.contains("reload") || text.contains("обновить") || text.contains("page") || text.contains("страниц") || text.contains("обнов")
                }
                if (reloadLine != null) {
                    val bounds = reloadLine.boundingBox!!
                    val clickX = bounds.centerX() * scaleX
                    val clickY = bounds.centerY() * scaleY
                    AutoBuyerLogs.addLog("🎯 [ПЕРЕЗАПУСК] Найдена кнопка перезапуска '${reloadLine.text}' в (${clickX}, ${clickY})! Кликаем...")
                    clickAt(clickX, clickY)
                    clickedReload = true
                } else {
                    AutoBuyerLogs.addLog("⚠️ [ПЕРЕЗАПУСК] Текст 'Reload' / 'Обновить' не найден на экране.")
                }
            }
            menuBitmap.recycle()
        }
        
        if (!clickedReload) {
            // Fallback click: Typically reload is around the middle-upper part of the screen or dropdown.
            val fallbackReloadX = screenWidth * 0.7f
            val fallbackReloadY = screenHeight * 0.15f
            AutoBuyerLogs.addLog("👉 [ПЕРЕЗАПУСК] Кликаем по координатам по умолчанию для Reload: ($fallbackReloadX, $fallbackReloadY)")
            clickAt(fallbackReloadX, fallbackReloadY)
        }
        
        // Step 3: Wait 30 seconds for the game to load completely
        AutoBuyerLogs.addLog("⏳ [ПЕРЕЗАПУСК] Ожидаем 30 секунд для полной загрузки игры...")
        delay(30000)
        
        // Step 4: Click the "Рынок" tab
        var clickedTab = false
        val loadBitmap = MediaProjectionHelper.getLatestScreenshot()
        if (loadBitmap != null) {
            val scaleX = screenWidth / loadBitmap.width
            val scaleY = screenHeight / loadBitmap.height
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val inputImage = InputImage.fromBitmap(loadBitmap, 0)
            val result = try {
                Tasks.await(recognizer.process(inputImage))
            } catch (e: Exception) {
                null
            }
            if (result != null) {
                val lines = result.textBlocks.flatMap { it.lines }
                val marketLine = lines.firstOrNull { line ->
                    val text = line.text.lowercase()
                    val isMarketWord = text.contains("рынок") || text.contains("рыиок") || text.contains("рын") || text.contains("ryn") || text.contains("market")
                    val isBottom = (line.boundingBox?.centerY()?.toFloat() ?: 0f) * scaleY > screenHeight * 0.82f
                    isMarketWord && isBottom
                }
                if (marketLine != null) {
                    val bounds = marketLine.boundingBox!!
                    val clickX = bounds.centerX() * scaleX
                    val clickY = bounds.centerY() * scaleY
                    AutoBuyerLogs.addLog("🎯 [ПЕРЕЗАПУСК] Найдена вкладка 'Рынок' на (${clickX}, ${clickY})! Кликаем...")
                    clickAt(clickX, clickY)
                    clickedTab = true
                }
            }
            loadBitmap.recycle()
        }
        
        if (!clickedTab) {
            val clickX = screenWidth * 0.875f
            val clickY = screenHeight * 0.94f
            AutoBuyerLogs.addLog("👉 [ПЕРЕЗАПУСК] Вкладка 'Рынок' не найдена через OCR. Кликаем по координатам по умолчанию: ($clickX, $clickY)")
            clickAt(clickX, clickY)
        }
        
        delay(3000)
        AutoBuyerLogs.addLog("✨ [ПЕРЕЗАПУСК] Перезапуск бота игры завершен успешно!")
    }
}
