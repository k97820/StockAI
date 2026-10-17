package com.example.mddanalyzer

import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PriceBar(val date: Date, val close: Double)

data class Cycle(
    val cycleNum: Int,
    val peakDate: Date,
    val peakPrice: Double,
    val mddDate: Date,
    val mddPrice: Double,
    val mddPercent: Double,       // 실제 도달 MDD (%)
    val peakToMddDays: Long,      // 직전고점 ~ MDD 기간
    val recoveryDate: Date,
    val mddToRecovDays: Long,     // MDD ~ 직전고점 회복 기간
    var nextPeakPrice: Double = 0.0,
    var recovToNextPeakReturn: Double = 0.0, // 회복 후 다음 최고점까지 상승률
    var recovToNextPeakDays: Long = 0L       // 회복 후 다음 최고점까지 기간
)

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etTicker = findViewById<EditText>(R.id.etTicker)
        val etStart = findViewById<EditText>(R.id.etStartDate)
        val etEnd = findViewById<EditText>(R.id.etEndDate)
        val etThreshold = findViewById<EditText>(R.id.etThresholdMDD)
        val btnAnalyze = findViewById<Button>(R.id.btnAnalyze)

        btnAnalyze.setOnClickListener {
            val ticker = etTicker.text.toString().trim()
            val startStr = etStart.text.toString().trim()
            val endStr = etEnd.text.toString().trim()
            val threshold = etThreshold.text.toString().toDoubleOrNull() ?: 15.0

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prices = fetchYahooData(ticker, startStr, endStr)
                    val cycles = analyzeCycles(prices, threshold)

                    withContext(Dispatchers.Main) {
                        displayTable(cycles)
                        drawLineChart(prices)
                        drawHistogram(cycles)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "오류: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // 야후 파이낸스 API에서 일간 종가 다운로드
    private fun fetchYahooData(ticker: String, startStr: String, endStr: String): List<PriceBar> {
        val p1 = dateFormat.parse(startStr)!!.time / 1000
        val p2 = dateFormat.parse(endStr)!!.time / 1000
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?period1=$p1&period2=$p2&interval=1d"

        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val response = client.newCall(request).execute()
        val jsonStr = response.body?.string() ?: throw Exception("데이터 수신 실패")

        val result = JSONObject(jsonStr).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val timestamps = result.getJSONArray("timestamp")
        val quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val closes = quote.getJSONArray("close")

        val list = mutableListOf<PriceBar>()
        for (i in 0 until timestamps.length()) {
            if (!closes.isNull(i)) {
                val date = Date(timestamps.getLong(i) * 1000)
                list.add(PriceBar(date, closes.getDouble(i)))
            }
        }
        return list
    }

    // 핵심 MDD 사이클 탐색 로직
    private fun analyzeCycles(data: List<PriceBar>, thresholdPercent: Double): List<Cycle> {
        val cycles = mutableListOf<Cycle>()
        if (data.isEmpty()) return cycles

        var curPeak = data[0].close
        var curPeakDate = data[0].date
        var minPriceInCycle = curPeak
        var minDateInCycle = curPeakDate
        var inCycle = false
        var cycleCount = 1

        for (bar in data) {
            val dd = (bar.close - curPeak) / curPeak * 100.0

            if (!inCycle) {
                if (bar.close > curPeak) {
                    curPeak = bar.close
                    curPeakDate = bar.date
                } else if (dd <= -thresholdPercent) {
                    // 평가 MDD 이상 하락 진입
                    inCycle = true
                    minPriceInCycle = bar.close
                    minDateInCycle = bar.date
                }
            } else {
                // 하락 진행 중 최저점(MDD) 갱신
                if (bar.close < minPriceInCycle) {
                    minPriceInCycle = bar.close
                    minDateInCycle = bar.date
                }
                // 직전 최고점 회복 시 한 사이클 완성
                if (bar.close >= curPeak) {
                    val mddVal = (minPriceInCycle - curPeak) / curPeak * 100.0
                    val pToMDays = (minDateInCycle.time - curPeakDate.time) / (1000 * 3600 * 24)
                    val mToRDays = (bar.date.time - minDateInCycle.time) / (1000 * 3600 * 24)

                    val cycle = Cycle(
                        cycleNum = cycleCount++,
                        peakDate = curPeakDate,
                        peakPrice = curPeak,
                        mddDate = minDateInCycle,
                        mddPrice = minPriceInCycle,
                        mddPercent = mddVal,
                        peakToMddDays = pToMDays,
                        recoveryDate = bar.date,
                        mddToRecovDays = mToRDays
                    )
                    cycles.add(cycle)

                    // 다음 사이클 준비
                    inCycle = false
                    curPeak = bar.close
                    curPeakDate = bar.date
                }
            }
        }

        // 회복 시점 ~ 다음 사이클 고점까지의 상승률 및 기간 역계산
        for (i in 0 until cycles.size - 1) {
            val cur = cycles[i]
            val next = cycles[i + 1]
            cur.nextPeakPrice = next.peakPrice
            cur.recovToNextPeakReturn = ((next.peakPrice - cur.peakPrice) / cur.peakPrice) * 100.0
            cur.recovToNextPeakDays = (next.peakDate.time - cur.recoveryDate.time) / (1000 * 3600 * 24)
        }

        return cycles
    }

    // 표(Table) 채우기
    private fun displayTable(cycles: List<Cycle>) {
        val table = findViewById<TableLayout>(R.id.tableResult)
        table.removeAllViews()

        val headers = arrayOf("회차", "MDD(%)", "고점~MDD", "MDD~회복", "다음고점상승률", "상승기간")
        val headerRow = TableRow(this)
        for (h in headers) {
            val tv = TextView(this).apply {
                text = h
                setPadding(16, 12, 16, 12)
                setBackgroundColor(Color.LTGRAY)
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            }
            headerRow.addView(tv)
        }
        table.addView(headerRow)

        for (c in cycles) {
            val row = TableRow(this)
            val values = arrayOf(
                "${c.cycleNum}회",
                String.format(Locale.KOREA, "%.2f%%", c.mddPercent),
                "${c.peakToMddDays}일",
                "${c.mddToRecovDays}일",
                if (c.nextPeakPrice > 0) String.format(Locale.KOREA, "+%.2f%%", c.recovToNextPeakReturn) else "-",
                if (c.nextPeakPrice > 0) "${c.recovToNextPeakDays}일" else "-"
            )
            for (v in values) {
                val tv = TextView(this).apply {
                    text = v
                    setPadding(16, 12, 16, 12)
                    textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                }
                row.addView(tv)
            }
            table.addView(row)
        }
    }

    // 주가 선 그래프 출력
    private fun drawLineChart(prices: List<PriceBar>) {
        val chart = findViewById<LineChart>(R.id.lineChart)
        val entries = prices.mapIndexed { index, bar -> Entry(index.toFloat(), bar.close.toFloat()) }
        val dataSet = LineDataSet(entries, "종가 (Close)").apply {
            color = Color.BLUE
            setDrawCircles(false)
            lineWidth = 1.5f
        }
        chart.data = LineData(dataSet)
        chart.description.isEnabled = false
        chart.invalidate()
    }

    // 가로축 10개 구간 히스토그램 (MDD 크기 분포)
    private fun drawHistogram(cycles: List<Cycle>) {
        val chart = findViewById<BarChart>(R.id.histogramChart)
        if (cycles.isEmpty()) return

        // 10개 Bin으로 분할
        val mdds = cycles.map { abs(it.mddPercent) }
        val minVal = mdds.minOrNull() ?: 0.0
        val maxVal = mdds.maxOrNull() ?: 100.0
        val binCount = 10
        val step = (maxVal - minVal) / binCount
        val bins = IntArray(binCount)

        for (m in mdds) {
            var idx = ((m - minVal) / step).toInt()
            if (idx >= binCount) idx = binCount - 1
            bins[idx]++
        }

        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()
        for (i in 0 until binCount) {
            entries.add(BarEntry(i.toFloat(), bins[i].toFloat()))
            val startR = minVal + (i * step)
            val endR = startR + step
            labels.add(String.format(Locale.KOREA, "%.0f~%.0f%%", startR, endR))
        }

        val dataSet = BarDataSet(entries, "MDD 빈도수").apply {
            color = Color.rgb(240, 100, 100)
        }

        chart.data = BarData(dataSet)
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            valueFormatter = IndexAxisValueFormatter(labels)
            granularity = 1f
            labelRotationAngle = 45f
            setDrawGridLines(false)
        }
        chart.description.isEnabled = false
        chart.invalidate()
    }
}