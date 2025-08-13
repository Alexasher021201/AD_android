package com.example.adproject

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.adproject.api.ApiClient
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardActivity : AppCompatActivity() {

    private lateinit var chartView: LineChart
    private lateinit var last7Days: TextView
    private var accuracyRates: List<Float> = emptyList()

    // 统一使用你自己的 ApiClient / ApiService
    private val api by lazy { ApiClient.api }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 底部导航
        val exerciseButton = findViewById<Button>(R.id.exerciseButton)
        val dashboardButton = findViewById<Button>(R.id.dashboardButton)
        val classButton = findViewById<Button>(R.id.classButton)
        val homeButton = findViewById<Button>(R.id.homeButton)

        setSelectedButton(dashboardButton)

        exerciseButton.setOnClickListener {
            setSelectedButton(exerciseButton)
            startActivity(Intent(this, ExerciseActivity::class.java))
        }
        dashboardButton.setOnClickListener {
            setSelectedButton(dashboardButton) // 留在当前页
        }
        classButton.setOnClickListener {
            setSelectedButton(classButton)
            startActivity(Intent(this, ClassActivity::class.java))
        }
        homeButton.setOnClickListener {
            setSelectedButton(homeButton)
            startActivity(Intent(this, HomeActivity::class.java))
        }

        // 其它按钮
        findViewById<Button>(R.id.answerHistoryButton).setOnClickListener {
            startActivity(Intent(this, AnswerHistoryActivity::class.java))
        }
        findViewById<Button>(R.id.recommendedPracticeButton).setOnClickListener {
            startActivity(Intent(this, RecommendedActivity::class.java))
        }

        // 图表
        chartView = findViewById(R.id.chartView)
        setupChart()

        last7Days = findViewById(R.id.last7Days)
        last7Days.setOnClickListener { showDayOptions() }

        // 请求数据
        fetchAccuracyRates()
    }

    private fun setupChart() {
        chartView.description.isEnabled = false
        chartView.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chartView.xAxis.granularity = 1f
        chartView.axisRight.isEnabled = false
        chartView.axisLeft.axisMinimum = 0f
        chartView.axisLeft.axisMaximum = 100f // percentage
    }

    private fun updateChartForDays(days: Int) {
        if (accuracyRates.isEmpty()) {
            Toast.makeText(this, "No chart data", Toast.LENGTH_SHORT).show()
            chartView.clear()
            return
        }

        // 取“最近 N 天”，不足 N 天就展示全部
        val recentRates = accuracyRates.takeLast(days)
        Log.d("ChartUpdate", "Showing last $days days: $recentRates")

        val entries = recentRates.mapIndexed { index, value ->
            Entry(index.toFloat(), value * 100f) // 转成百分比
        }

        val dataSet = LineDataSet(entries, "Accuracy").apply {
            color = getColor(R.color.black)
            valueTextColor = getColor(R.color.black)
            lineWidth = 2f
            circleRadius = 4f
            setDrawValues(true)
            setDrawCircles(true)
        }

        chartView.data = LineData(dataSet)
        chartView.invalidate()
    }

    private fun showDayOptions() {
        val options = arrayOf("Last 3 Days", "Last 5 Days", "Last 7 Days")
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Select Duration")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> { last7Days.text = "Last 3 Days ▼"; updateChartForDays(3) }
                1 -> { last7Days.text = "Last 5 Days ▼"; updateChartForDays(5) }
                2 -> { last7Days.text = "Last 7 Days ▼"; updateChartForDays(7) }
            }
        }
        builder.show()
    }

    private fun fetchAccuracyRates() {
        lifecycleScope.launch {
            val (ok, msg, data) = withContext(Dispatchers.IO) {
                try {
                    val resp = api.dashboard()
                    if (!resp.isSuccessful) {
                        Triple(false, "Network error: ${resp.code()}", emptyList<Float>())
                    } else {
                        val body = resp.body()
                        // 约定：code==1 成功；data.accuracyRates 为 List<Float/Double/Int> 任意数值
                        val listAny = body?.data?.accuracyRates ?: emptyList()
                        val rates = listAny.map {
                            when (it) {
                                is Number -> it.toFloat()
                                else -> it.toString().toFloatOrNull() ?: 0f
                            }
                        }
                        val message = body?.msg ?: "OK"
                        val success = (body?.code == 1)
                        Triple(success, message, rates)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Request failed", emptyList<Float>())
                }
            }

            if (!ok) {
                Toast.makeText(this@DashboardActivity, msg, Toast.LENGTH_SHORT).show()
                // fallback：避免图表空白
                accuracyRates = listOf(0.6f, 0.7f, 0.3f, 0.9f, 0.5f, 0.8f, 1.0f)
            } else {
                accuracyRates = data
                if (accuracyRates.isEmpty()) {
                    Toast.makeText(this@DashboardActivity, "No chart data", Toast.LENGTH_SHORT).show()
                }
            }
            updateChartForDays(7)
        }
    }

    private fun setSelectedButton(selectedButton: Button) {
        findViewById<Button>(R.id.exerciseButton).isSelected = false
        findViewById<Button>(R.id.dashboardButton).isSelected = false
        findViewById<Button>(R.id.classButton).isSelected = false
        findViewById<Button>(R.id.homeButton).isSelected = false
        selectedButton.isSelected = true
    }
}
