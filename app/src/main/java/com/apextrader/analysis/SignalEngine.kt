package com.apextrader.analysis

import com.apextrader.data.Candle
import com.apextrader.data.TradingPair
import kotlin.math.*

data class Signal(
    val pair: String,
    val timeframe: String,
    val action: Action,
    val entry: Double,
    val stopLoss: Double,
    val tp: Double,
    val confluenceScore: Int,
    val winProbability: Int,
    val riskReward: Double,
    val direction: Direction,
    val rsi: Double,
    val macdSignal: String,
    val emaAlignment: String,
    val trend: String,
    val atr: Double,
    val spread: Double,
    val priceSource: String,
    val passedConditions: List<String>,
    val skipReason: String?,
    val suggestedTF: String?
)

enum class Action    { LONG, SHORT, SKIP, SWITCH_TF }
enum class Direction { BULL, BEAR, NEUTRAL }

private data class MACD(val line: Double, val sig: Double, val hist: Double,
                        val pLine: Double, val pSig: Double, val pHist: Double,
                        val crossBull: Boolean, val crossBear: Boolean)
private data class BB(val upper: Double, val mid: Double, val lower: Double, val width: Double)

class SignalEngine {

    fun analyze(candles: List<Candle>, candlesH4: List<Candle>?, pair: TradingPair,
                tf: String, livePrice: Double = 0.0, spread: Double = 0.0, src: String = "Yahoo"): Signal {

        if (candles.size < 40) return skip(pair, tf, spread, src, "Need 40+ candles, got ${candles.size}", null)

        val patched = if (livePrice > 0) candles.dropLast(1) + candles.last().copy(close = livePrice) else candles
        val closes  = patched.map { it.close }
        val highs   = patched.map { it.high }
        val lows    = patched.map { it.low }
        val vols    = patched.map { it.volume }
        val price   = closes.last()

        val rsi14   = rsi(closes, 14)
        val rsiH4   = if (candlesH4 != null && candlesH4.size >= 20) rsi(candlesH4.map { it.close }, 14) else rsi14
        val macd    = macd(closes)
        val ema8    = ema(closes, 8);  val ema21 = ema(closes, 21)
        val ema50   = ema(closes, 50); val ema200 = ema(closes, minOf(200, closes.size))
        val atr14   = atr(patched, 14)
        val bb      = bollinger(closes, 20)
        val sr      = supportResistance(highs, lows, atr14)
        val struct  = structure(highs, lows)
        val trend   = trend(closes, ema21, ema50, ema200)
        val vol     = volume(vols, patched)
        val patt    = candlePattern(patched, atr14)
        val div     = divergence(closes, rsi14, patched)

        // Spread guard
        if (spread > 0 && spread / price > 0.001)
            return skip(pair, tf, spread, src, "Spread too wide — unfavorable R:R", null)

        var lS = 0; var sS = 0
        val lP = mutableListOf<String>(); val sP = mutableListOf<String>()

        // 1. RSI (max +2)
        when { rsi14 < 28 -> { lS+=2; lP.add("RSI ${rsi14.f1()} — extremely oversold (+2)") }
               rsi14 < 38 -> { lS+=1; lP.add("RSI ${rsi14.f1()} — oversold zone") }
               rsi14 > 72 -> { sS+=2; sP.add("RSI ${rsi14.f1()} — extremely overbought (+2)") }
               rsi14 > 62 -> { sS+=1; sP.add("RSI ${rsi14.f1()} — overbought zone") } }

        // 2. HTF RSI (+1)
        if (rsiH4 < 52) { lS+=1; lP.add("H4 RSI ${rsiH4.f1()} — higher TF supports long") }
        if (rsiH4 > 48) { sS+=1; sP.add("H4 RSI ${rsiH4.f1()} — higher TF supports short") }

        // 3. MACD (max +2)
        if (macd.crossBull)                              { lS+=2; lP.add("MACD bullish crossover (+2)") }
        else if (macd.hist>0 && macd.hist>macd.pHist)   { lS+=1; lP.add("MACD expanding bullish") }
        if (macd.crossBear)                              { sS+=2; sP.add("MACD bearish crossover (+2)") }
        else if (macd.hist<0 && macd.hist<macd.pHist)   { sS+=1; sP.add("MACD expanding bearish") }

        // 4. EMA stack (max +2)
        val sBull = ema8>ema21 && ema21>ema50; val sBear = ema8<ema21 && ema21<ema50
        if (sBull && price>ema8)       { lS+=2; lP.add("EMA 8>21>50 bull stack (+2)") }
        else if (ema21>ema50&&price>ema21) { lS+=1; lP.add("EMA21>50 + price above") }
        if (sBear && price<ema8)       { sS+=2; sP.add("EMA 8<21<50 bear stack (+2)") }
        else if (ema21<ema50&&price<ema21) { sS+=1; sP.add("EMA21<50 + price below") }

        // 5. S/R (max +2)
        val atSup = sr.filter { it.third=="S" && abs(price-it.first)/price < 0.004 }
        val atRes = sr.filter { it.third=="R" && abs(price-it.first)/price < 0.004 }
        if (atSup.isNotEmpty()) { val s=atSup.maxOf{it.second}; lS+=if(s>=3)2 else 1; lP.add("At support ${atSup.first().first.fP()} tested ${s}x${if(s>=3)" (+2)""}" ) }
        if (atRes.isNotEmpty()) { val s=atRes.maxOf{it.second}; sS+=if(s>=3)2 else 1; sP.add("At resistance ${atRes.first().first.fP()} tested ${s}x${if(s>=3)" (+2)""}" ) }

        // 6. Bollinger Band (+1)
        val bbPos = if(bb.upper!=bb.lower)(price-bb.lower)/(bb.upper-bb.lower) else 0.5
        if (bbPos<0.12) { lS+=1; lP.add("At lower Bollinger Band — oversold squeeze") }
        if (bbPos>0.88) { sS+=1; sP.add("At upper Bollinger Band — overbought squeeze") }

        // 7. Structure (+1)
        if (struct=="HH_HL") { lS+=1; lP.add("Higher highs/lows — uptrend structure") }
        if (struct=="LH_LL") { sS+=1; sP.add("Lower highs/lows — downtrend structure") }

        // 8. Candle pattern (+1)
        if (patt in listOf("BULL_ENGULF","HAMMER","BULL_PIN")) { lS+=1; lP.add("$patt — bullish reversal candle") }
        if (patt in listOf("BEAR_ENGULF","SHOOTING_STAR","BEAR_PIN")) { sS+=1; sP.add("$patt — bearish reversal candle") }

        // 9. Volume (+1)
        if (vol=="HIGH_BULL") { lS+=1; lP.add("High volume on bull candle — conviction") }
        if (vol=="HIGH_BEAR") { sS+=1; sP.add("High volume on bear candle — conviction") }

        // 10. Divergence (+1 bonus)
        if (div=="BULL") { lS+=1; lP.add("Bullish RSI divergence detected") }
        if (div=="BEAR") { sS+=1; sP.add("Bearish RSI divergence detected") }

        // TF suggestion
        val choppy = bb.width/price < 0.002
        val sugTF = when { choppy&&tf=="M15"->"H1"; choppy&&tf=="H1"->"H4"; choppy&&tf=="H4"->"D1"; else->null }

        val isLong = lS >= sS; val score = if(isLong) lS else sS
        val passed = if(isLong) lP else sP

        if (score < 7) {
            val why = "Score ${score}/12 (need 7). ${if(passed.isNotEmpty()) passed.first() else "No strong setup."}" +
                if(sugTF!=null) " 💡 Try $sugTF." else ""
            return skip(pair, tf, spread, src, why, sugTF, rsi14, macd, trend, atr14, passed)
        }

        val slM = when { score>=10->1.2; score>=8->1.4; else->1.6 }
        val sl  = if(isLong) price-atr14*slM  else price+atr14*slM
        val tp  = if(isLong) price+atr14*slM*2 else price-atr14*slM*2

        var wp = 55+(score-7)*5
        if(macd.crossBull&&isLong||macd.crossBear&&!isLong) wp+=6
        if(div=="BULL"&&isLong||div=="BEAR"&&!isLong)       wp+=5
        if(sBull&&isLong||sBear&&!isLong)                   wp+=4
        if(atSup.isNotEmpty()||atRes.isNotEmpty())          wp+=4
        if(vol.contains("HIGH"))                            wp+=3
        wp = wp.coerceIn(60, 92)

        val macdStr = when { macd.crossBull->"BULL CROSS"; macd.crossBear->"BEAR CROSS"; macd.hist>0->"BULLISH"; else->"BEARISH" }
        val emaStr  = when { sBull&&price>ema8->"STRONGLY BULL"; ema21>ema50->"BULLISH"; sBear&&price<ema8->"STRONGLY BEAR"; ema21<ema50->"BEARISH"; else->"NEUTRAL" }

        return Signal(pair=pair.displayName, timeframe=tf,
            action=if(isLong)Action.LONG else Action.SHORT,
            entry=price, stopLoss=sl, tp=tp,
            confluenceScore=score, winProbability=wp, riskReward=slM*2/slM,
            direction=if(isLong)Direction.BULL else Direction.BEAR,
            rsi=rsi14, macdSignal=macdStr, emaAlignment=emaStr, trend=trend,
            atr=atr14, spread=spread, priceSource=src,
            passedConditions=passed, skipReason=null, suggestedTF=sugTF)
    }

    private fun rsi(c: List<Double>, p: Int): Double {
        if(c.size<p+1) return 50.0
        var g=0.0; var l=0.0
        for(i in 1..p){val d=c[i]-c[i-1];if(d>0)g+=d else l-=d}
        g/=p;l/=p
        for(i in p+1 until c.size){val d=c[i]-c[i-1];g=(g*(p-1)+maxOf(0.0,d))/p;l=(l*(p-1)+maxOf(0.0,-d))/p}
        return if(l==0.0)100.0 else 100.0-100.0/(1.0+g/l)
    }
    private fun ema(c: List<Double>, p: Int): Double {
        if(c.isEmpty())return 0.0; val k=2.0/(p+1); var e=c.take(minOf(p,c.size)).average()
        c.drop(minOf(p,c.size)).forEach{e=it*k+e*(1-k)}; return e
    }
    private fun macd(c: List<Double>): MACD {
        val line=ema(c,12)-ema(c,26); val pl=ema(c.dropLast(1),12)-ema(c.dropLast(1),26)
        val sig=line*0.15+pl*0.85; val ps=pl*0.85
        return MACD(line,sig,line-sig,pl,ps,pl-ps,line>sig&&pl<=sig,line<sig&&pl>=sig)
    }
    private fun atr(c: List<Candle>, p: Int): Double {
        if(c.size<2)return 0.001; val n=minOf(p,c.size-1)
        return(c.size-n until c.size).sumOf{maxOf(c[it].high-c[it].low,abs(c[it].high-c[it-1].close),abs(c[it].low-c[it-1].close))}/n
    }
    private fun bollinger(c: List<Double>, p: Int): BB {
        val s=c.takeLast(p);val m=s.average();val std=sqrt(s.map{(it-m)*(it-m)}.average())
        return BB(m+2*std,m,m-2*std,4*std)
    }
    private fun supportResistance(h: List<Double>, l: List<Double>, atr: Double): List<Triple<Double,Int,String>> {
        val lvls=mutableListOf<Triple<Double,Int,String>>();val n=minOf(60,h.size-2);val tol=atr*0.5
        for(i in 1 until n){
            val hi=h.size-1-i;val li=l.size-1-i
            if(hi>0&&hi<h.size-1&&h[hi]>h[hi-1]&&h[hi]>h[hi+1]){val ex=lvls.indexOfFirst{abs(it.first-h[hi])<tol&&it.third=="R"};if(ex>=0)lvls[ex]=lvls[ex].copy(second=lvls[ex].second+1) else lvls.add(Triple(h[hi],1,"R"))}
            if(li>0&&li<l.size-1&&l[li]<l[li-1]&&l[li]<l[li+1]){val ex=lvls.indexOfFirst{abs(it.first-l[li])<tol&&it.third=="S"};if(ex>=0)lvls[ex]=lvls[ex].copy(second=lvls[ex].second+1) else lvls.add(Triple(l[li],1,"S"))}
        }
        return lvls.sortedByDescending{it.second}
    }
    private fun structure(h: List<Double>, l: List<Double>): String {
        if(h.size<8)return "UNCLEAR";val hh=h.takeLast(8);val ll=l.takeLast(8)
        val hhC=(1 until hh.size).count{hh[it]>hh[it-1]};val hlC=(1 until ll.size).count{ll[it]>ll[it-1]}
        val lhC=(1 until hh.size).count{hh[it]<hh[it-1]};val llC=(1 until ll.size).count{ll[it]<ll[it-1]}
        return when{hhC>=5&&hlC>=5->"HH_HL";lhC>=5&&llC>=5->"LH_LL";else->"RANGING"}
    }
    private fun trend(c: List<Double>, e21: Double, e50: Double, e200: Double): String {
        val p=c.last()
        return when{p>e21&&e21>e50&&e50>e200->"STRONG UPTREND";p>e21&&e21>e50->"UPTREND";p<e21&&e21<e50&&e50<e200->"STRONG DOWNTREND";p<e21&&e21<e50->"DOWNTREND";else->"SIDEWAYS"}
    }
    private fun volume(v: List<Double>, c: List<Candle>): String {
        if(v.all{it==0.0})return "N/A";val avg=v.takeLast(20).average();val last=v.last();val bull=c.last().close>=c.last().open
        return when{last>avg*1.5&&bull->"HIGH_BULL";last>avg*1.5&&!bull->"HIGH_BEAR";else->"NORMAL"}
    }
    private fun candlePattern(c: List<Candle>, atr: Double): String {
        if(c.size<2)return "NONE";val cur=c.last();val prev=c[c.size-2]
        val cB=abs(cur.close-cur.open);val pB=abs(prev.close-prev.open)
        val bull=cur.close>cur.open;val pBull=prev.close>prev.open
        val upW=cur.high-maxOf(cur.open,cur.close);val dnW=minOf(cur.open,cur.close)-cur.low
        if(bull&&!pBull&&cur.close>prev.open&&cur.open<prev.close&&cB>pB)return "BULL_ENGULF"
        if(!bull&&pBull&&cur.close<prev.open&&cur.open>prev.close&&cB>pB)return "BEAR_ENGULF"
        if(dnW>cB*2.5&&upW<cB)return "HAMMER";if(upW>cB*2.5&&dnW<cB)return "SHOOTING_STAR"
        if(bull&&dnW>atr*0.6&&cB>atr*0.3)return "BULL_PIN";if(!bull&&upW>atr*0.6&&cB>atr*0.3)return "BEAR_PIN"
        return "NONE"
    }
    private fun divergence(c: List<Double>, rsi: Double, candles: List<Candle>): String {
        if(c.size<12)return "NONE";val prev8=candles.dropLast(1).takeLast(8);val pRSI=rsi(c.dropLast(4),14)
        if(candles.last().low<prev8.minOf{it.low}&&rsi>pRSI+4)return "BULL"
        if(candles.last().high>prev8.maxOf{it.high}&&rsi<pRSI-4)return "BEAR"
        return "NONE"
    }
    private fun skip(pair: TradingPair, tf: String, spread: Double, src: String, reason: String,
        sugTF: String?, rsi: Double=50.0, macd: MACD?=null, trend: String="UNKNOWN",
        atr: Double=0.0, passed: List<String>=emptyList()
    ) = Signal(pair=pair.displayName, timeframe=tf, action=if(sugTF!=null)Action.SWITCH_TF else Action.SKIP,
        entry=0.0, stopLoss=0.0, tp=0.0, confluenceScore=passed.size,
        winProbability=0, riskReward=0.0, direction=Direction.NEUTRAL, rsi=rsi,
        macdSignal=if(macd?.hist?:0.0>0)"BULLISH" else "BEARISH", emaAlignment="NEUTRAL",
        trend=trend, atr=atr, spread=spread, priceSource=src, passedConditions=passed,
        skipReason=reason, suggestedTF=sugTF)

    private fun Double.f1() = "%.1f".format(this)
    private fun Double.fP() = if(this>99) "%.3f".format(this) else "%.5f".format(this)
}
