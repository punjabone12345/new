package com.apextrader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Candle(
    val timestamp: Long, val open: Double, val high: Double,
    val low: Double, val close: Double, val volume: Double
)

data class LiveTick(
    val symbol: String, val price: Double,
    val spread: Double, val source: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class TradingPair(
    val displayName: String,
    val yahooSymbol: String,
    val frankfurterBase: String,
    val frankfurterQuote: String,
    val pipSize: Double,
    val flag1: String,   // emoji flags for UI
    val flag2: String
)

val WATCHLIST = listOf(
    TradingPair("EUR/USD", "EURUSD=X", "EUR", "USD", 0.0001, "🇪🇺", "🇺🇸"),
    TradingPair("GBP/USD", "GBPUSD=X", "GBP", "USD", 0.0001, "🇬🇧", "🇺🇸"),
    TradingPair("USD/JPY", "USDJPY=X", "USD", "JPY", 0.01,   "🇺🇸", "🇯🇵"),
    TradingPair("GBP/JPY", "GBPJPY=X", "GBP", "JPY", 0.01,   "🇬🇧", "🇯🇵"),
    TradingPair("EUR/JPY", "EURJPY=X", "EUR", "JPY", 0.01,   "🇪🇺", "🇯🇵"),
    TradingPair("USD/CHF", "USDCHF=X", "USD", "CHF", 0.0001, "🇺🇸", "🇨🇭"),
    TradingPair("AUD/USD", "AUDUSD=X", "AUD", "USD", 0.0001, "🇦🇺", "🇺🇸"),
    TradingPair("NZD/USD", "NZDUSD=X", "NZD", "USD", 0.0001, "🇳🇿", "🇺🇸"),
)

val TF_CONFIG = mapOf(
    "M15" to Pair("15m", "5d"),
    "H1"  to Pair("1h",  "30d"),
    "H4"  to Pair("4h",  "60d"),
    "D1"  to Pair("1d",  "1y"),
)

class PriceFetcher {

    // ── Live price: Frankfurter → ExchangeRate-API → Yahoo fallback ────────────
    suspend fun fetchLivePrice(pair: TradingPair): Result<LiveTick> =
        withContext(Dispatchers.IO) {
            fetchFrankfurter(pair).onSuccess { return@withContext Result.success(it) }
            fetchExchangeRate(pair).onSuccess { return@withContext Result.success(it) }
            fetchYahooPrice(pair)
        }

    // ── All pairs in one batch call (faster for dashboard) ────────────────────
    suspend fun fetchAllPrices(): Map<String, LiveTick> =
        withContext(Dispatchers.IO) {
            val results = mutableMapOf<String, LiveTick>()
            try {
                // Fetch EUR base rates (covers EUR/USD, EUR/JPY)
                val eurRates = fetchFrankfurterBatch("EUR", listOf("USD","JPY","CHF","GBP"))
                // Fetch GBP base rates (covers GBP/USD, GBP/JPY)
                val gbpRates = fetchFrankfurterBatch("GBP", listOf("USD","JPY"))
                // Fetch USD base rates (covers USD/JPY, USD/CHF)
                val usdRates = fetchFrankfurterBatch("USD", listOf("JPY","CHF"))
                // Fetch AUD, NZD
                val audRates = fetchFrankfurterBatch("AUD", listOf("USD"))
                val nzdRates = fetchFrankfurterBatch("NZD", listOf("USD"))

                WATCHLIST.forEach { pair ->
                    val price = when(pair.displayName) {
                        "EUR/USD" -> eurRates["USD"]
                        "EUR/JPY" -> eurRates["JPY"]
                        "GBP/USD" -> gbpRates["USD"]
                        "GBP/JPY" -> gbpRates["JPY"]
                        "USD/JPY" -> usdRates["JPY"]
                        "USD/CHF" -> usdRates["CHF"]
                        "AUD/USD" -> audRates["USD"]
                        "NZD/USD" -> nzdRates["USD"]
                        else -> null
                    }
                    if (price != null) {
                        results[pair.displayName] = LiveTick(
                            symbol = pair.displayName, price = price,
                            spread = estimateSpread(pair, price), source = "Frankfurter"
                        )
                    }
                }
            } catch (_: Exception) {}

            // Fill missing with individual fallback calls
            WATCHLIST.forEach { pair ->
                if (!results.containsKey(pair.displayName)) {
                    fetchLivePrice(pair).onSuccess { results[pair.displayName] = it }
                }
            }
            results
        }

    private fun fetchFrankfurterBatch(base: String, quotes: List<String>): Map<String, Double> {
        return try {
            val url = "https://api.frankfurter.app/latest?from=$base&to=${quotes.joinToString(",")}"
            val json = JSONObject(get(url))
            val rates = json.getJSONObject("rates")
            quotes.mapNotNull { q ->
                if (rates.has(q)) q to rates.getDouble(q) else null
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun fetchFrankfurter(pair: TradingPair): Result<LiveTick> = try {
        val url = "https://api.frankfurter.app/latest?from=${pair.frankfurterBase}&to=${pair.frankfurterQuote}"
        val rate = JSONObject(get(url)).getJSONObject("rates").getDouble(pair.frankfurterQuote)
        val spread = estimateSpread(pair, rate)
        Result.success(LiveTick(pair.displayName, rate, spread, "Frankfurter"))
    } catch (e: Exception) { Result.failure(e) }

    private fun fetchExchangeRate(pair: TradingPair): Result<LiveTick> = try {
        val url = "https://open.er-api.com/v6/latest/${pair.frankfurterBase}"
        val json = JSONObject(get(url))
        if (json.getString("result") != "success") throw Exception("API error")
        val rate = json.getJSONObject("rates").getDouble(pair.frankfurterQuote)
        val spread = estimateSpread(pair, rate)
        Result.success(LiveTick(pair.displayName, rate, spread, "ExchangeRate-API"))
    } catch (e: Exception) { Result.failure(e) }

    private fun fetchYahooPrice(pair: TradingPair): Result<LiveTick> = try {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${pair.yahooSymbol}?interval=1m&range=1d"
        val json = JSONObject(get(url, mapOf("User-Agent" to "Mozilla/5.0")))
        val meta = json.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
        val price = meta.getDouble("regularMarketPrice")
        val spread = estimateSpread(pair, price)
        Result.success(LiveTick(pair.displayName, price, spread, "Yahoo~"))
    } catch (e: Exception) { Result.failure(e) }

    suspend fun fetchCandles(pair: TradingPair, tf: String, count: Int = 150): Result<List<Candle>> =
        withContext(Dispatchers.IO) {
            try {
                val (interval, range) = TF_CONFIG[tf] ?: Pair("1h","30d")
                val url = "https://query1.finance.yahoo.com/v8/finance/chart/${pair.yahooSymbol}" +
                          "?interval=$interval&range=$range&includePrePost=false"
                val json = JSONObject(get(url, mapOf("User-Agent" to "Mozilla/5.0")))
                val result = json.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val ts   = result.getJSONArray("timestamp")
                val q    = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
                val opens= q.getJSONArray("open"); val highs=q.getJSONArray("high")
                val lows = q.getJSONArray("low");  val closes=q.getJSONArray("close")
                val vols = q.getJSONArray("volume")
                val candles = mutableListOf<Candle>()
                for (i in 0 until ts.length()) {
                    if (closes.isNull(i)||opens.isNull(i)) continue
                    candles.add(Candle(ts.getLong(i), opens.getDouble(i), highs.getDouble(i),
                        lows.getDouble(i), closes.getDouble(i),
                        if(vols.isNull(i)) 0.0 else vols.getDouble(i)))
                }
                if (candles.isEmpty()) Result.failure(Exception("No candles"))
                else Result.success(candles.takeLast(count))
            } catch (e: Exception) { Result.failure(e) }
        }

    private fun estimateSpread(pair: TradingPair, price: Double) = when(pair.displayName) {
        "EUR/USD" -> 0.00012; "GBP/USD" -> 0.00015; "USD/JPY" -> 0.013
        "GBP/JPY" -> 0.025;  "EUR/JPY" -> 0.018;   "USD/CHF" -> 0.00018
        "AUD/USD" -> 0.00018; "NZD/USD" -> 0.00022; else -> price * 0.0001
    }

    private fun get(url: String, headers: Map<String,String> = emptyMap()): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 7000; conn.readTimeout = 8000; conn.requestMethod = "GET"
        headers.forEach { (k,v) -> conn.setRequestProperty(k,v) }
        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
        val r = conn.inputStream.bufferedReader().readText(); conn.disconnect(); return r
    }
}
