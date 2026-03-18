package com.apextrader.ui

import android.content.Intent
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.apextrader.R
import com.apextrader.analysis.Action
import com.apextrader.analysis.Signal
import com.apextrader.data.WATCHLIST
import com.apextrader.service.ScanService

class MainActivity : AppCompatActivity() {

    private lateinit var tvScanStatus: TextView
    private lateinit var tvLastScan: TextView
    private lateinit var pairContainer: LinearLayout
    private lateinit var signalContainer: LinearLayout
    private lateinit var tfGroup: RadioGroup
    private var activeTF = "H1"
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { updateUI(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF050912.toInt()
        setContentView(buildLayout())
        startService()
        ScanService.listeners.add { runOnUiThread { updateUI() } }
    }

    override fun onResume()  { super.onResume();  handler.post(ticker) }
    override fun onPause()   { super.onPause();   handler.removeCallbacks(ticker) }
    override fun onDestroy() { super.onDestroy(); ScanService.listeners.clear() }

    // ─── LAYOUT ──────────────────────────────────────────────────────────────
    private fun buildLayout(): View {
        val root = ScrollView(this).apply { setBackgroundColor(0xFF050912.toInt()) }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── Header ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080d18.toInt())
            setPadding(dp(16), dp(44), dp(16), dp(12))
        }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "APEX"; textSize = 24f; setTextColor(0xFF40c4ff.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "TRADER"; textSize = 24f; setTextColor(0xFF00e676.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            addView(Button(this@MainActivity).apply {
                text = "⟳ SCAN ALL"; textSize = 10f; isAllCaps = false
                setTextColor(0xFF40c4ff.toInt()); background = roundBg(0xFF0d1f38.toInt(), 6)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { scanNow() }
            })
        })

        // Scan status row
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        tvScanStatus = TextView(this).apply {
            text = "Starting…"; textSize = 10f; setTextColor(0xFF40c4ff.toInt())
            typeface = Typeface.MONOSPACE
        }
        tvLastScan = TextView(this).apply {
            text = ""; textSize = 10f; setTextColor(0xFF3d4f66.toInt())
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        }
        statusRow.addView(tvScanStatus); statusRow.addView(tvLastScan)
        header.addView(statusRow)

        // TF selector
        tfGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL; setPadding(0, dp(10), 0, dp(2))
        }
        listOf("M15","H1","H4","D1").forEach { tf ->
            tfGroup.addView(RadioButton(this).apply {
                text = tf; textSize = 11f; id = tf.hashCode()
                setTextColor(0xFF6b83a0.toInt())
                buttonDrawable = null
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundBg(0xFF0d1525.toInt(), 4)
                layoutParams = RadioGroup.LayoutParams(RadioGroup.LayoutParams.WRAP_CONTENT, RadioGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        setTextColor(0xFF40c4ff.toInt()); background = roundBg(0xFF0d1f38.toInt(), 4)
                        activeTF = tf
                        startService(Intent(this@MainActivity, ScanService::class.java).apply {
                            action = "SET_TF"; putExtra("tf", tf)
                        })
                    } else {
                        setTextColor(0xFF6b83a0.toInt()); background = roundBg(0xFF0d1525.toInt(), 4)
                    }
                }
                if (tf == "H1") isChecked = true
            })
        }
        header.addView(tfGroup)
        ll.addView(header)

        // ── Signal banner (shows when signal exists) ──
        signalContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(0))
        }
        ll.addView(signalContainer)

        // ── Pair list ──
        pairContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(80))
        }
        ll.addView(pairContainer)

        root.addView(ll); return root
    }

    // ─── UI UPDATE ───────────────────────────────────────────────────────────
    private fun updateUI() {
        // Scan status
        val scanning = ScanService.isScanning
        tvScanStatus.text = if (scanning) "● SCANNING…" else "● LIVE"
        tvScanStatus.setTextColor(if (scanning) 0xFFffd740.toInt() else 0xFF00e676.toInt())
        val lastScan = ScanService.lastScanTime
        tvLastScan.text = if (lastScan > 0) "Last scan: ${secsAgo(lastScan)}" else "Waiting for scan…"

        // Signal banner
        signalContainer.removeAllViews()
        val signals = ScanService.latestSignals.values.filter { it.action == Action.LONG || it.action == Action.SHORT }
        if (signals.isNotEmpty()) {
            signalContainer.addView(TextView(this).apply {
                text = "🔔 ${signals.size} SIGNAL${if(signals.size>1)"S" else ""} FOUND"
                textSize = 10f; setTextColor(0xFFffd740.toInt()); typeface = Typeface.MONOSPACE
                setPadding(dp(4), dp(4), 0, dp(6))
            })
            signals.forEach { sig -> signalContainer.addView(buildSignalCard(sig)) }
        }

        // Pair rows
        pairContainer.removeAllViews()
        val sectionTitle = TextView(this).apply {
            text = "ALL PAIRS  ·  $activeTF"
            textSize = 9f; setTextColor(0xFF2d3f55.toInt()); typeface = Typeface.MONOSPACE
            setPadding(dp(4), dp(4), 0, dp(8))
        }
        pairContainer.addView(sectionTitle)
        WATCHLIST.forEach { pair -> pairContainer.addView(buildPairRow(pair.displayName)) }
    }

    // ─── PAIR ROW ─────────────────────────────────────────────────────────────
    private fun buildPairRow(name: String): View {
        val tick   = ScanService.latestPrices[name]
        val signal = ScanService.latestSignals[name]
        val pair   = WATCHLIST.find { it.displayName == name }!!

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundBg(0xFF0b1018.toInt(), 8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }

        // Left: flags + name
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = "${pair.flag1}${pair.flag2}  $name"
            textSize = 13f; setTextColor(0xFFdde4f0.toInt()); typeface = Typeface.DEFAULT_BOLD
        })
        val signalLabel = when (signal?.action) {
            Action.LONG      -> "▲ LONG  ${signal.winProbability}%"
            Action.SHORT     -> "▼ SHORT  ${signal.winProbability}%"
            Action.SWITCH_TF -> "⟳ SWITCH TO ${signal.suggestedTF}"
            Action.SKIP      -> "⊘ SKIP"
            null             -> if (ScanService.isScanning) "…" else "—"
            else             -> "—"
        }
        val sigCol = when (signal?.action) {
            Action.LONG  -> 0xFF00e676.toInt()
            Action.SHORT -> 0xFFff1744.toInt()
            Action.SWITCH_TF -> 0xFFffd740.toInt()
            else -> 0xFF3d4f66.toInt()
        }
        left.addView(TextView(this).apply {
            text = signalLabel; textSize = 10f; setTextColor(sigCol); typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(left.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })

        // Right: price + source
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val priceStr = tick?.let { fP(it.price) } ?: "…"
        right.addView(TextView(this).apply {
            text = priceStr; textSize = 14f; typeface = Typeface.MONOSPACE
            setTextColor(if ((tick?.price ?: 0.0) > 0) 0xFFdde4f0.toInt() else 0xFF3d4f66.toInt())
        })
        right.addView(TextView(this).apply {
            text = tick?.source?.let { if(it.contains("Yahoo"))"~ delayed" else "● live" } ?: ""
            textSize = 9f; typeface = Typeface.MONOSPACE
            setTextColor(if(tick?.source?.contains("Yahoo") == true) 0xFFff9100.toInt() else 0xFF00e676.toInt())
        })
        row.addView(right)

        // Tap to expand signal detail
        row.setOnClickListener {
            if (signal?.action == Action.LONG || signal?.action == Action.SHORT)
                showSignalDetail(signal)
        }

        // Left border for signals
        if (signal?.action == Action.LONG || signal?.action == Action.SHORT) {
            row.background = layeredBg(0xFF0b1018.toInt(), sigCol, 8)
        }

        return row
    }

    // ─── SIGNAL CARD (banner) ─────────────────────────────────────────────────
    private fun buildSignalCard(sig: Signal): View {
        val bull = sig.action == Action.LONG
        val col  = if (bull) 0xFF00e676.toInt() else 0xFFff1744.toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = layeredBg(if(bull) 0xFF061812.toInt() else 0xFF180608.toInt(), col, 8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            setOnClickListener { showSignalDetail(sig) }
        }

        // Title row
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "${if(bull)"▲" else "▼"} ${sig.action}  ${sig.pair}"; textSize = 16f
            setTextColor(col); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "${sig.winProbability}% WIN\n${sig.confluenceScore}/12 conf"
            textSize = 10f; setTextColor(col); typeface = Typeface.MONOSPACE; gravity = Gravity.END
        })
        card.addView(titleRow)

        // Divider
        card.addView(View(this).apply {
            setBackgroundColor(Color.argb(40, Color.red(col), Color.green(col), Color.blue(col)))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin=dp(8); bottomMargin=dp(8) }
        })

        // Levels grid
        val grid = GridLayout(this).apply { columnCount = 4; rowCount = 2 }
        listOf(
            Pair("ENTRY",     fP(sig.entry)),
            Pair("STOP LOSS", fP(sig.stopLoss)),
            Pair("TP",        fP(sig.tp1)),
            Pair("R:R",       "1 : ${"%.1f".format(sig.riskReward)}")
        ).forEachIndexed { i, (label, value) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), 0, dp(4), dp(4))
                layoutParams = GridLayout.LayoutParams().apply {
                    columnSpec = GridLayout.spec(i % 4, 1f)
                    rowSpec = GridLayout.spec(i / 4)
                    width = 0; setMargins(dp(2),0,dp(2),0)
                }
            }
            cell.addView(TextView(this).apply {
                text = label; textSize = 8f; setTextColor(0xFF4a5568.toInt()); typeface = Typeface.MONOSPACE
            })
            val vCol = when(label) { "ENTRY" -> 0xFF40c4ff.toInt(); "STOP LOSS" -> 0xFFff1744.toInt(); else -> 0xFF00e676.toInt() }
            cell.addView(TextView(this).apply {
                text = value; textSize = 11f; setTextColor(vCol); typeface = Typeface.MONOSPACE
            })
            grid.addView(cell)
        }
        card.addView(grid)

        // R:R + TF
        card.addView(TextView(this).apply {
            text = "R:R 1:2.0  ·  ${sig.timeframe}  ·  src: ${sig.priceSource}"
            textSize = 9f; setTextColor(0xFF3d4f66.toInt()); typeface = Typeface.MONOSPACE
            setPadding(dp(4), dp(4), 0, 0)
        })

        return card
    }

    // ─── SIGNAL DETAIL DIALOG ─────────────────────────────────────────────────
    private fun showSignalDetail(sig: Signal) {
        val bull = sig.action == Action.LONG
        val col  = if (bull) 0xFF00e676.toInt() else 0xFFff1744.toInt()
        val dlg  = android.app.AlertDialog.Builder(this, R.style.SignalDialog)
        val ll   = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(20),dp(20),dp(20)); setBackgroundColor(0xFF080d18.toInt()) }

        ll.addView(TextView(this).apply { text="${if(bull)"▲" else "▼"} ${sig.action} — ${sig.pair}"; textSize=20f; setTextColor(col); typeface=Typeface.DEFAULT_BOLD; setPadding(0,0,0,dp(4)) })
        ll.addView(TextView(this).apply { text="Confidence: ${sig.confluenceScore}/12  ·  Win probability: ${sig.winProbability}%"; textSize=11f; setTextColor(0xFF6b83a0.toInt()); typeface=Typeface.MONOSPACE; setPadding(0,0,0,dp(14)) })

        listOf("ENTRY" to fP(sig.entry), "STOP LOSS" to fP(sig.stopLoss), "TAKE PROFIT" to fP(sig.tp1)).forEach { (lbl, v) ->
            val vc = when(lbl) { "ENTRY"->0xFF40c4ff.toInt(); "STOP LOSS"->0xFFff1744.toInt(); else->0xFF00e676.toInt() }
            val r = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,dp(6),0,dp(6)) }
            r.addView(TextView(this).apply { text=lbl; textSize=11f; setTextColor(0xFF4a5568.toInt()); typeface=Typeface.MONOSPACE; layoutParams=LinearLayout.LayoutParams(dp(90),LinearLayout.LayoutParams.WRAP_CONTENT) })
            r.addView(TextView(this).apply { text=v; textSize=13f; setTextColor(vc); typeface=Typeface.MONOSPACE })
            ll.addView(r)
        }

        ll.addView(View(this).apply { setBackgroundColor(0xFF1c2840.toInt()); layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(1)).apply{topMargin=dp(10);bottomMargin=dp(10)} })
        ll.addView(TextView(this).apply { text="WHY THIS SIGNAL:"; textSize=9f; setTextColor(0xFF2d3f55.toInt()); typeface=Typeface.MONOSPACE; setPadding(0,0,0,dp(6)) })
        sig.passedConditions.take(6).forEach { r ->
            ll.addView(TextView(this).apply { text="✓ $r"; textSize=10f; setTextColor(0xFF5a7a5a.toInt()); setPadding(0,dp(2),0,dp(2)) })
        }
        ll.addView(TextView(this).apply { text="\nRSI: ${"%.1f".format(sig.rsi)}  MACD: ${sig.macdSignal}  EMA: ${sig.emaAlignment}\nTrend: ${sig.trend}  TF: ${sig.timeframe}  Src: ${sig.priceSource}"; textSize=9f; setTextColor(0xFF3d4f66.toInt()); typeface=Typeface.MONOSPACE })

        dlg.setView(ScrollView(this).apply { addView(ll) })
        dlg.setPositiveButton("Close") { d, _ -> d.dismiss() }
        dlg.show()
    }

    // ─── SERVICE ──────────────────────────────────────────────────────────────
    private fun startService() {
        val i = Intent(this, ScanService::class.java).apply { action = ScanService.ACTION_START; putExtra("tf", activeTF) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun scanNow() {
        startService(Intent(this, ScanService::class.java).apply { action = ScanService.ACTION_SCAN_NOW })
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────
    private fun secsAgo(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s/60}m ago"; else -> "old" }
    }

    private fun fP(v: Double) = if (v > 99) "%.3f".format(v) else "%.5f".format(v)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun layeredBg(bgColor: Int, borderColor: Int, r: Int) =
        android.graphics.drawable.LayerDrawable(arrayOf(
            roundBg(bgColor, r),
            android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadius = dp(r).toFloat()
                setStroke(dp(1), Color.argb(120, Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor)))
            }
        ))
}
