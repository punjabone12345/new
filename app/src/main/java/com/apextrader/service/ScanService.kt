package com.apextrader.service

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.apextrader.R
import com.apextrader.analysis.Action
import com.apextrader.analysis.Signal
import com.apextrader.analysis.SignalEngine
import com.apextrader.data.*
import com.apextrader.ui.MainActivity
import kotlinx.coroutines.*

class ScanService : Service() {

    companion object {
        const val ACTION_START     = "START"
        const val ACTION_STOP      = "STOP"
        const val ACTION_SCAN_NOW  = "SCAN_NOW"
        const val CHANNEL_ID       = "ApexTrader"
        const val CHANNEL_SIGNAL   = "ApexSignals"
        const val NOTIF_ID         = 1
        var isRunning              = false

        // Shared state — MainActivity reads this
        val latestPrices  = mutableMapOf<String, LiveTick>()
        val latestSignals = mutableMapOf<String, Signal>()
        var isScanning    = false
        var lastScanTime  = 0L
        var listeners     = mutableListOf<() -> Unit>()
    }

    private val fetcher = PriceFetcher()
    private val engine  = SignalEngine()
    private val scope   = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeTF = "H1"

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannels()
        startForeground(NOTIF_ID, buildNotif("Monitoring 8 forex pairs…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP     -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SCAN_NOW -> scope.launch { runFullScan() }
            ACTION_START    -> {
                activeTF = intent.getStringExtra("tf") ?: "H1"
                startLoops()
            }
        }
        return START_STICKY
    }

    private fun startLoops() {
        // Price ticker — every 8 seconds
        scope.launch {
            while (isActive) {
                refreshPrices()
                delay(8000)
            }
        }
        // Full scan — every 60 seconds
        scope.launch {
            delay(1000)
            while (isActive) {
                runFullScan()
                delay(60_000)
            }
        }
    }

    private suspend fun refreshPrices() {
        withContext(Dispatchers.IO) {
            try {
                val prices = fetcher.fetchAllPrices()
                withContext(Dispatchers.Main) {
                    latestPrices.putAll(prices)
                    notifyListeners()
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun runFullScan() {
        isScanning = true; notifyListeners()
        updateNotif("Scanning all pairs…")

        WATCHLIST.forEach { pair ->
            try {
                val candlesResult = withContext(Dispatchers.IO) { fetcher.fetchCandles(pair, activeTF, 150) }
                val candlesH4     = if (activeTF != "H4") withContext(Dispatchers.IO) { fetcher.fetchCandles(pair, "H4", 100).getOrNull() } else null
                val tick          = latestPrices[pair.displayName]

                candlesResult.onSuccess { candles ->
                    val signal = engine.analyze(candles, candlesH4, pair, activeTF,
                        tick?.price ?: 0.0, tick?.spread ?: 0.0, tick?.source ?: "Yahoo")
                    latestSignals[pair.displayName] = signal

                    // Fire notification for real signals
                    if (signal.action == Action.LONG || signal.action == Action.SHORT) {
                        fireSignalNotification(signal)
                    }
                }
            } catch (_: Exception) {}
            notifyListeners()
        }

        isScanning = false
        lastScanTime = System.currentTimeMillis()
        notifyListeners()
        val signalCount = latestSignals.values.count { it.action == Action.LONG || it.action == Action.SHORT }
        updateNotif(if (signalCount > 0) "$signalCount signal(s) found — tap to view" else "No signals now — next scan in 60s")
    }

    fun setTF(tf: String) { activeTF = tf; scope.launch { runFullScan() } }

    private fun notifyListeners() { listeners.forEach { it() } }

    private fun fireSignalNotification(signal: Signal) {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pi = PendingIntent.getActivity(this, signal.pair.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val col = if (signal.action == Action.LONG) 0xFF00e676.toInt() else 0xFFff1744.toInt()
        val arrow = if (signal.action == Action.LONG) "▲" else "▼"
        val notif = NotificationCompat.Builder(this, CHANNEL_SIGNAL)
            .setContentTitle("$arrow ${signal.action} — ${signal.pair}")
            .setContentText("Entry: ${fP(signal.entry)}  SL: ${fP(signal.stopLoss)}  TP: ${fP(signal.tp)}  Win: ${signal.winProbability}%")
            .setSmallIcon(R.drawable.ic_signal)
            .setColor(col).setAutoCancel(true).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(signal.pair.hashCode(), notif)
    }

    private fun fP(v: Double) = if (v > 99) "%.3f".format(v) else "%.5f".format(v)

    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, ScanService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        val scanPi = PendingIntent.getService(this, 1,
            Intent(this, ScanService::class.java).apply { action = ACTION_SCAN_NOW }, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ApexTrader").setContentText(text)
            .setSmallIcon(R.drawable.ic_chart)
            .addAction(0, "Scan Now", scanPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true).build()
    }

    private fun updateNotif(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotif(text))
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "ApexTrader Status", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_SIGNAL, "Trade Signals", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); enableLights(true)
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false; scope.cancel(); listeners.clear()
    }

    override fun onBind(intent: Intent?) = null
}
