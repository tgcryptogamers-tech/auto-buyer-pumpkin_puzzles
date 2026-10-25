package com.example.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object AutoBuyerLogs {
    private val _logsFlow = MutableSharedFlow<String>(replay = 500)
    val logsFlow: SharedFlow<String> = _logsFlow.asSharedFlow()

    private val logHistory = Collections.synchronizedList(mutableListOf<String>())
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    suspend fun addLog(message: String) {
        val timeStamp = dateFormat.format(Date())
        val formatted = "[$timeStamp] $message"
        synchronized(logHistory) {
            if (logHistory.size > 1000) {
                logHistory.removeAt(0)
            }
            logHistory.add(formatted)
        }
        _logsFlow.emit(formatted)
    }

    fun addLogBlocking(message: String) {
        val timeStamp = dateFormat.format(Date())
        val formatted = "[$timeStamp] $message"
        synchronized(logHistory) {
            if (logHistory.size > 1000) {
                logHistory.removeAt(0)
            }
            logHistory.add(formatted)
        }
        _logsFlow.tryEmit(formatted)
    }

    fun getAllLogsText(): String {
        return synchronized(logHistory) {
            if (logHistory.isEmpty()) {
                _logsFlow.replayCache.joinToString("\n")
            } else {
                logHistory.joinToString("\n")
            }
        }
    }

    fun copyToClipboard(context: Context, extraHeader: String = ""): Boolean {
        val logs = getAllLogsText()
        val fullText = if (extraHeader.isNotBlank()) "$extraHeader\n$logs" else logs
        val safeText = if (fullText.length > 250_000) fullText.takeLast(250_000) else fullText
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val clip = ClipData.newPlainText("AutoBuyer Logs", safeText)
                clipboard.setPrimaryClip(clip)
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            false
        }
    }

    fun shareLogs(context: Context, extraHeader: String = "") {
        val logs = getAllLogsText()
        val fullText = if (extraHeader.isNotBlank()) "$extraHeader\n$logs" else logs
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, fullText)
            type = "text/plain"
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val chooser = Intent.createChooser(sendIntent, "Экспорт логов бота").apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }

    fun clearLogs() {
        synchronized(logHistory) {
            logHistory.clear()
        }
    }
}
