package com.example.adproject

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.example.adproject.api.ApiClient
import com.example.adproject.model.AnswerRecord
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

class AnswerHistoryActivity : AppCompatActivity() {

    private lateinit var spinner: Spinner
    private lateinit var historyList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var adapter: AnswerHistoryAdapter

    private val allRecords = mutableListOf<AnswerRecord>()
    private val api by lazy { ApiClient.api }

    private val TAG = "AnswerHistory"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_answer_history)

        // 顶部 header 适配状态栏
        val header = findViewById<View>(R.id.header)
        val initialTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, initialTop + topInset, v.paddingRight, v.paddingBottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        spinner = findViewById(R.id.filterSpinner)
        historyList = findViewById(R.id.historyList)
        loading = findViewById(R.id.loading)

        historyList.layoutManager = LinearLayoutManager(this)
        adapter = AnswerHistoryAdapter(mutableListOf())
        historyList.adapter = adapter

        val opts = listOf("All", "Correct", "Wrong")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) = applyFilter()
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        fetchHistory()
    }

    /** 第一步：调用 /recommend，取到 records: [{questionId, isCorrect}] */
    private fun fetchHistory() {
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val resp: Response<JsonObject> = withContext(Dispatchers.IO) {
                try {
                    api.triggerRecommend()
                } catch (e: Exception) {
                    Log.e(TAG, "recommend failed", e)
                    null
                }
            } ?: run {
                loading.visibility = View.GONE
                Toast.makeText(this@AnswerHistoryActivity, "Network error", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (!resp.isSuccessful || resp.body() == null) {
                loading.visibility = View.GONE
                Toast.makeText(this@AnswerHistoryActivity, "Network error ${resp.code()}", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val root = resp.body()!!
                val data = root.getAsJsonObject("data")
                val arr = data.getAsJsonArray("records")

                allRecords.clear()
                arr.forEach { el ->
                    val obj = el.asJsonObject
                    val qid = obj.get("questionId")?.asInt ?: return@forEach
                    val correct = (obj.get("isCorrect")?.asInt ?: 0) == 1
                    allRecords += AnswerRecord(qid, correct, title = null, imageBase64 = null)
                }

                applyFilter()               // 先把列表显示出来（只有对错）
                fetchTitlesSequentially(0)  // 逐个补题干/图片

            } catch (e: Exception) {
                loading.visibility = View.GONE
                Log.e(TAG, "parse error", e)
                Toast.makeText(this@AnswerHistoryActivity, "Parse error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 第二步：逐个用 /doquestion?id=xx 拉题干和图片，更新列表 */
    private fun fetchTitlesSequentially(index: Int) {
        if (index >= allRecords.size) {
            loading.visibility = View.GONE
            return
        }
        val rec = allRecords[index]

        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { api.getQuestionById(rec.questionId) }
                if (r.isSuccessful) {
                    val dto = r.body()?.data
                    if (dto != null) {
                        val title = dto.question ?: "Question #${rec.questionId}"
                        val img = dto.image
                        rec.title = title
                        rec.imageBase64 = img
                        // 局部刷新
                        adapter.updateTitleAndImage(rec.questionId, title, img)
                    }
                } else {
                    Log.w(TAG, "getQuestionById ${rec.questionId} -> ${r.code()}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "getQuestionById failed qid=${rec.questionId} ${t.message}")
            } finally {
                fetchTitlesSequentially(index + 1)
            }
        }
    }

    /** 根据筛选刷新显示 */
    private fun applyFilter() {
        val pos = spinner.selectedItemPosition // 0 All, 1 Correct, 2 Wrong
        val filtered = when (pos) {
            1 -> allRecords.filter { it.isCorrect }
            2 -> allRecords.filter { !it.isCorrect }
            else -> allRecords
        }
        adapter.submitList(filtered)
    }
}
