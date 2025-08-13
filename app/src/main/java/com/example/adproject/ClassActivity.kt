package com.example.adproject

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.adproject.api.ApiClient
import com.example.adproject.model.StudentClass
import com.example.adproject.model.ViewClassResponse
import kotlinx.coroutines.*
import java.io.IOException

class ClassActivity : AppCompatActivity() {

    // --- 网络：统一用 ApiClient 单例 ---
    private val api by lazy { ApiClient.api }

    // --- 协程 ---
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- UI ---
    private lateinit var homeworkListView: ListView
    private lateinit var adapter: ClassListAdapter

    // 从 JoinClassActivity 返回成功时刷新
    private val joinLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            loadMyClasses()
            Toast.makeText(this, "Joined successfully", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_class)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // 顶部三个按钮
        findViewById<Button>(R.id.announcementButton).setOnClickListener {
            startActivity(Intent(this, AnnouncementActivity::class.java))
        }
        findViewById<Button>(R.id.quizButton).setOnClickListener {
            Toast.makeText(this, "Go to Quiz", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.leaveButton).setOnClickListener {
            Toast.makeText(this, "Leave class (TODO)", Toast.LENGTH_SHORT).show()
        }

        // 底部导航
        val exerciseButton = findViewById<Button>(R.id.exerciseButton)
        val dashboardButton = findViewById<Button>(R.id.dashboardButton)
        val classButton = findViewById<Button>(R.id.classButton)
        val homeButton = findViewById<Button>(R.id.homeButton)
        setSelectedButton(classButton)

        exerciseButton.setOnClickListener {
            setSelectedButton(exerciseButton)
            startActivity(Intent(this, ExerciseActivity::class.java))
        }
        dashboardButton.setOnClickListener {
            setSelectedButton(dashboardButton)
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        classButton.setOnClickListener { setSelectedButton(classButton) }
        homeButton.setOnClickListener {
            setSelectedButton(homeButton)
            startActivity(Intent(this, HomeActivity::class.java))
        }

        // 列表
        homeworkListView = findViewById(R.id.homeworkListView)

        // 顶部固定“Join Class” 头部卡片（addHeaderView 必须在 setAdapter 之前）
        val header = layoutInflater.inflate(R.layout.header_join_class, homeworkListView, false)
        homeworkListView.addHeaderView(header, null, false)
        header.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGoJoin)
            .setOnClickListener {
                joinLauncher.launch(Intent(this, JoinClassActivity::class.java))
            }

        adapter = ClassListAdapter(mutableListOf())
        homeworkListView.adapter = adapter

        // 点击某个班级（注意 header 偏移）
        homeworkListView.setOnItemClickListener { _, _, position, _ ->
            val realPos = position - homeworkListView.headerViewsCount
            if (realPos !in 0 until adapter.items.size) return@setOnItemClickListener
            val item = adapter.items[realPos]
            Toast.makeText(this, "Entering class: ${item.className} (id=${item.classId})", Toast.LENGTH_SHORT).show()
            // val intent = Intent(this, ClassDetailActivity::class.java)
            // intent.putExtra("classId", item.classId)
            // startActivity(intent)
        }

        // 拉取我的班级
        loadMyClasses()
    }

    private fun loadMyClasses() {
        uiScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api.viewClass() }
                if (resp.isSuccessful) {
                    val body: ViewClassResponse? = resp.body()
                    if (body?.code == 1) {
                        val list = body.data?.list ?: emptyList()
                        adapter.replace(list)
                    } else {
                        Toast.makeText(this@ClassActivity, body?.msg ?: "Failed to load classes", Toast.LENGTH_SHORT).show()
                        adapter.replace(emptyList())
                    }
                } else {
                    Toast.makeText(this@ClassActivity, "Network error: ${resp.code()}", Toast.LENGTH_SHORT).show()
                    adapter.replace(emptyList())
                }
            } catch (e: IOException) {
                Toast.makeText(this@ClassActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                adapter.replace(emptyList())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@ClassActivity, "Unknown error: ${e.message}", Toast.LENGTH_SHORT).show()
                adapter.replace(emptyList())
            }
        }
    }

    private fun setSelectedButton(selectedButton: Button) {
        findViewById<Button>(R.id.exerciseButton).isSelected = false
        findViewById<Button>(R.id.dashboardButton).isSelected = false
        findViewById<Button>(R.id.classButton).isSelected = false
        findViewById<Button>(R.id.homeButton).isSelected = false
        selectedButton.isSelected = true
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    // ====== 适配器：复用 row_homework.xml 的两行样式 ======
    inner class ClassListAdapter(val items: MutableList<StudentClass>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].classId.toLong()

        fun replace(newItems: List<StudentClass>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@ClassActivity)
                .inflate(R.layout.row_homework, parent, false)

            val item = items[position]
            val starIcon = view.findViewById<ImageView>(R.id.starIcon)
            val subjectText = view.findViewById<TextView>(R.id.subjectText)
            val dueText = view.findViewById<TextView>(R.id.dueText)

            subjectText.text = item.className
            dueText.text = item.description

            // 星标本地切换（demo）
            val taggedKey = "class_fav_${item.classId}"
            val isFav = view.getTag(taggedKey.hashCode()) as? Boolean ?: false
            starIcon.setImageResource(if (isFav) R.drawable.star_yellow else R.drawable.star_black)
            starIcon.setOnClickListener {
                val nowFav = !(view.getTag(taggedKey.hashCode()) as? Boolean ?: false)
                view.setTag(taggedKey.hashCode(), nowFav)
                starIcon.setImageResource(if (nowFav) R.drawable.star_yellow else R.drawable.star_black)
            }

            return view
        }
    }
}
