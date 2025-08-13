package com.example.adproject

import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.adproject.api.ApiClient            // ✅ 新增
import com.example.adproject.api.ApiService
import com.example.adproject.model.*
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*

class AnnouncementActivity : AppCompatActivity() {

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var listView: ListView
    private lateinit var emptyState: View
    private lateinit var emptyText: android.widget.TextView
    private lateinit var toolbar: MaterialToolbar

    private lateinit var adapter: AnnouncementAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 当前筛选：null 表示“全部”；否则按班级 id 过滤
    private var selectedClassId: Int? = null
    private var joinedClasses: List<StudentClass> = emptyList()
    private var classNameMap: Map<Int, String> = emptyMap()

    // ✅ 统一用全局 ApiClient（带 Cookie）
    private val api: ApiService by lazy { ApiClient.api }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_announcement)

        toolbar = findViewById(R.id.topAppBar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.menu_announcement_filter)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_filter -> { showFilterDialog(); true }
                else -> false
            }
        }

        swipe = findViewById(R.id.swipeRefresh)
        listView = findViewById(R.id.announcementList)
        emptyState = findViewById(R.id.emptyState)
        emptyText = emptyState.findViewById(R.id.emptyText)

        adapter = AnnouncementAdapter(this)
        listView.adapter = adapter

        swipe.setOnRefreshListener { loadAnnouncements() }

        // 先取我加入的班级，再加载公告
        scope.launch {
            loadMyClasses()
            loadAnnouncements()
        }
    }

    /** 加载我加入的班级 */
    private suspend fun loadMyClasses() {
        try {
            val resp = withContext(Dispatchers.IO) { api.viewClass() }
            if (resp.isSuccessful && resp.body()?.code == 1) {
                joinedClasses = resp.body()?.data?.list.orEmpty()
                classNameMap = joinedClasses.associate { it.classId to it.className }
            } else {
                joinedClasses = emptyList()
                classNameMap = emptyMap()
            }
        } catch (_: Exception) {
            joinedClasses = emptyList()
            classNameMap = emptyMap()
        }
    }

    /** 根据筛选加载公告：全部 or 某个班级 */
    private fun loadAnnouncements() {
        swipe.isRefreshing = true
        scope.launch {
            try {
                val data: List<AnnouncementItem> = when (val cid = selectedClassId) {
                    null -> fetchAllAnnouncements()
                    else -> fetchOneClassAnnouncements(cid)
                }
                applyData(data)
            } catch (e: Exception) {
                Toast.makeText(this@AnnouncementActivity, "Load failed: ${e.message}", Toast.LENGTH_SHORT).show()
                applyData(emptyList())
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    /** 全部：并发拉取每个班级的公告，合并并按时间倒序 */
    private suspend fun fetchAllAnnouncements(): List<AnnouncementItem> = coroutineScope {
        if (joinedClasses.isEmpty()) return@coroutineScope emptyList()

        val jobs = joinedClasses.map { cls ->
            async(Dispatchers.IO) {
                try {
                    val r = api.selectAnnouncement(cls.classId)
                    if (r.isSuccessful && r.body()?.code == 1) {
                        r.body()?.data?.list.orEmpty().map { it.copy(classId = cls.classId) }  // 需要 AnnouncementItem 有 classId
                    } else emptyList()
                } catch (_: Exception) { emptyList() }
            }
        }
        jobs.awaitAll().flatten().sortedByDescending { toMillis(it.createTime) }
    }

    /** 单个班级 */
    private suspend fun fetchOneClassAnnouncements(classId: Int): List<AnnouncementItem> =
        withContext(Dispatchers.IO) {
            val r = api.selectAnnouncement(classId)
            if (r.isSuccessful && r.body()?.code == 1)
                r.body()?.data?.list.orEmpty().map { it.copy(classId = classId) }
            else emptyList()
        }

    /** 应用到 UI */
    private fun applyData(list: List<AnnouncementItem>) {
        // 用映射把 classId -> className 补上（如果后端没直接给）
        val enriched = list.map { it.copy(className = it.className ?: it.classId?.let(classNameMap::get)) }

        adapter.setItems(enriched)

        val empty = enriched.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyText.text = if (selectedClassId == null) "No announcements yet" else "No announcements for current class"
    }

    /** 弹出筛选对话框 */
    private fun showFilterDialog() {
        val names = mutableListOf("All Messages") + joinedClasses.map { it.className }
        val checked = when (val cid = selectedClassId) {
            null -> 0
            else -> (joinedClasses.indexOfFirst { it.classId == cid }.takeIf { it >= 0 } ?: -1) + 1
        }

        AlertDialog.Builder(this)
            .setTitle("Filter Class")
            .setSingleChoiceItems(names.toTypedArray(), checked) { d, which ->
                selectedClassId = if (which == 0) null else joinedClasses[which - 1].classId
                d.dismiss()
                loadAnnouncements()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** 把 [yyyy,MM,dd,HH,mm] 转成毫秒，便于排序 */
    private fun toMillis(arr: List<Int>?): Long {
        if (arr == null || arr.size < 5) return 0
        return try {
            val cal = java.util.GregorianCalendar(arr[0], arr[1] - 1, arr[2], arr[3], arr[4])
            cal.timeInMillis
        } catch (_: Exception) { 0 }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
