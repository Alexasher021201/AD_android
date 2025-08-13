package com.example.adproject

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.adproject.api.ApiClient
import com.example.adproject.model.RecommendedPractice
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecommendedActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var adapter: RecommendedAdapter

    // 列表数据交由适配器维护：避免重复 add
    private val seenIds = mutableSetOf<Int>()

    private var isLoading = false
    private val maxRetry = 4
    private val delayMs = 800L

    // 统一 API
    private val api by lazy { ApiClient.api }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_recommend)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }
        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }

        recycler = findViewById(R.id.recycler)
        progress = findViewById(R.id.progressBar)
        errorText = findViewById(R.id.errorText)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = RecommendedAdapter(mutableListOf()) { item ->
            val itn = Intent(this, ExerciseActivity::class.java)
            itn.putExtra("practice_id", item.id)
            itn.putExtra("practice_title", item.title)
            startActivity(itn)
        }
        recycler.adapter = adapter

        // 底部导航
        findViewById<Button>(R.id.exerciseButton).setOnClickListener {
            startActivity(Intent(this, ExerciseActivity::class.java))
        }
        findViewById<Button>(R.id.dashboardButton).apply {
            isSelected = true
            setOnClickListener { startActivity(Intent(this@RecommendedActivity, DashboardActivity::class.java)) }
        }
        findViewById<Button>(R.id.classButton).setOnClickListener {
            startActivity(Intent(this, ClassActivity::class.java))
        }
        findViewById<Button>(R.id.homeButton).setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
        }

        seenIds.clear()
        loadRecommendations()
    }

    /** 触发推荐 -> 轮询拿推荐 ID -> 逐个拉题目详情 */
    private fun loadRecommendations() {
        if (isLoading) return
        isLoading = true
        showLoading(true)
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            // 1) 触发推荐
            val trigOk = withContext(Dispatchers.IO) {
                try {
                    val r = api.triggerRecommend()
                    r.isSuccessful && r.body()?.get("code")?.asInt == 1
                } catch (_: Exception) { false }
            }
            if (!trigOk) return@launch fail("Failed to trigger recommendation")

            // 2) 轮询拿 ID
            val ids = fetchRecommendIdsWithRetry()
            if (ids.isEmpty()) return@launch fail("No recommended questions available")

            // 3) 清空现有并逐个拉详情
            adapter.clear()
            fetchQuestionOneByOne(ids, 0)
        }
    }

    /** 最多重试 maxRetry 次去拿推荐 ID */
    private suspend fun fetchRecommendIdsWithRetry(): List<Int> {
        repeat(maxRetry) { attempt ->
            val ids = withContext(Dispatchers.IO) {
                try {
                    val r = api.getRecommendIds()
                    if (!r.isSuccessful) return@withContext emptyList<Int>()
                    val root = r.body()
                    if (root?.get("code")?.asInt != 1) return@withContext emptyList<Int>()
                    extractIds(root)
                } catch (_: Exception) { emptyList() }
            }
            val dedup = ids.distinct().filter { seenIds.add(it) }
            if (dedup.isNotEmpty()) return dedup
            delay(delayMs) // 等后端生成
        }
        return emptyList()
    }

    private fun extractIds(root: JsonObject?): List<Int> {
        val data = root?.getAsJsonObject("data") ?: return emptyList()
        val arr: JsonArray? = when {
            data.has("questionIds") -> data.getAsJsonArray("questionIds")
            data.has("ids") -> data.getAsJsonArray("ids")
            else -> null
        }
        val list = mutableListOf<Int>()
        if (arr != null) for (e in arr) list += e.asInt
        return list
    }

    /** 逐个请求题目详情（使用你已有的 getQuestionById 返回 SelectQuestionDTO） */
    private fun fetchQuestionOneByOne(ids: List<Int>, index: Int) {
        if (index >= ids.size) {
            showLoading(false)
            isLoading = false
            Toast.makeText(this, "Fetched ${adapter.itemCount} recommendations", Toast.LENGTH_SHORT).show()
            return
        }
        val id = ids[index]
        lifecycleScope.launch {
            val item: RecommendedPractice? = withContext(Dispatchers.IO) {
                try {
                    val resp = api.getQuestionById(id)
                    val dto = resp.body()?.data ?: return@withContext null
                    val title = dto.question ?: "Question #$id"
                    val b64 = dto.image
                    RecommendedPractice(
                        id = id,
                        title = title,
                        subject = "—",
                        grade = "—",
                        questions = 10,
                        difficulty = "Medium",
                        imageBase64 = b64
                    )
                } catch (_: Exception) {
                    RecommendedPractice(
                        id = id,
                        title = "Question #$id",
                        subject = "—",
                        grade = "—",
                        questions = 10,
                        difficulty = "Medium",
                        imageBase64 = null
                    )
                }
            }
            item?.let { adapter.addItem(it) }
            fetchQuestionOneByOne(ids, index + 1)
        }
    }

    private fun showLoading(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
        recycler.alpha = if (show) 0.4f else 1f
    }

    private fun fail(msg: String) {
        isLoading = false
        showLoading(false)
        errorText.text = msg
        errorText.visibility = View.VISIBLE
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
