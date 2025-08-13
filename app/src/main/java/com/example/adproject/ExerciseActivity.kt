package com.example.adproject

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.adproject.api.ApiClient
import com.example.adproject.model.QsInform
import kotlinx.coroutines.*

class ExerciseActivity : AppCompatActivity() {

    // --- 网络：统一用 ApiClient 单例 ---
    val api by lazy { ApiClient.api }

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 当前搜索词 & 防抖任务
    private var currentQuery: String = ""
    private var searchJob: Job? = null

    // 固定选项（全量，仅做兜底）
    private val gradeOptions = arrayOf(
        "grade1","grade2","grade3","grade4","grade5","grade6",
        "grade7","grade8","grade9","grade10","grade11","grade12"
    )
    private val subjectOptions = arrayOf("language science","natural science","social science")
    private val categoryOptions = arrayOf(
        "Adaptations","Adaptations and natural selection","Age of Exploration","Analyzing literature",
        "Anatomy and physiology","Animals","Asia: society and environment","Astronomy","Atoms and molecules",
        "Author's purpose","Author's purpose and tone","Basic economic principles","Banking and finance",
        "Biochemistry","Capitalization","Categories","Cells","Chemical reactions","Cities","Classification",
        "Classification and scientific names","Climate change","Colonial America","Comprehension strategies",
        "Conservation","Conservation and natural resources","Context clues","Creative techniques","Cultural celebrations",
        "Designing experiments","Descriptive details","Developing and supporting arguments","Domain-specific vocabulary",
        "Early 19th century American history","Early China","Early Modern Europe","Early Americas",
        "Earth events","Earth's features","Economics","Editing and revising","Electricity","Ecological interactions",
        "Engineering practices","English colonies in North America","Fossils","Force and motion","Formatting",
        "Genes to traits","Geography","Greece","Government","Heat and thermal energy","Heredity","Historical figures",
        "Independent reading comprehension","Informational texts: level 1","Islamic empires","Kinetic and potential energy",
        "Literary devices","Magnets","Maps","Materials","Mixtures","Natural resources and human impacts",
        "Oceania: geography","Oceans and continents","Opinion writing","Particle motion and energy","Persuasive strategies",
        "Phrases and clauses","Photosynthesis","Physical Geography","Physical and chemical change","Plant reproduction",
        "Plants","Poetry elements","Pronouns","Pronouns and antecedents","Read-alone texts","Reading-comprehension",
        "Reference skills","Research skills","Rhyming","Rocks and minerals","Scientific names","Science-and-engineering-practices",
        "Sentences, fragments, and run-ons","Shades of meaning","Short and long vowels","Social studies skills","Solutions",
        "States","State capitals","States of matter","Supply and demand","Text structure","The Americas: geography",
        "The Antebellum period","The American Revolution","The Civil War","The Civil War and Reconstruction",
        "The Constitution","The Early Republic","The Jacksonian period","The Silk Road","Thermal energy",
        "Topographic maps","Traits","Traits and heredity","Units and measurement","Velocity, acceleration, and forces",
        "Verb tense","Visual elements","Water cycle","Weather and climate","Word usage and nuance","World religions"
    )
    private val topicOptions = arrayOf(
        "capitalization","chemistry","civics","culture","economics","earth-science",
        "figurative-language","global-studies","grammar","literacy-in-science","phonological-awareness",
        "physics","pronouns","punctuation","reading-comprehension","reference-skills","science-and-engineering-practices",
        "topicOptions","units-and-measurement","us-history","verbs","vocabulary","word-study",
        "world-history","writing-strategies"
    )

    // 当前可选项（由返回数据动态收敛）
    private var currentSubjectOptions = subjectOptions.toMutableList()
    private var currentCategoryOptions = categoryOptions.toMutableList()
    private var currentTopicOptions    = topicOptions.toMutableList()

    // 用户已选
    private val selectedGrades = mutableSetOf<String>()
    private val selectedSubjects = mutableSetOf<String>()
    private val selectedCategories = mutableSetOf<String>()
    private val selectedTopics = mutableSetOf<String>()

    // 列表 & 适配器
    private lateinit var adapter: QuestionAdapter
    private lateinit var questionList: ListView

    // 分页
    private var currentPage = 1
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_exercise)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        initViews()
        loadInitialData()
    }

    private fun initViews() {
        val gradeButton = findViewById<Button>(R.id.gradeButton)
        val subjectButton = findViewById<Button>(R.id.subjectButton)
        val categoryButton = findViewById<Button>(R.id.categoryButton)
        val topicButton = findViewById<Button>(R.id.topicButton)

        questionList = findViewById(R.id.questionList)

        val searchInput = findViewById<EditText>(R.id.searchEditText)
        val searchIcon = findViewById<ImageView>(R.id.searchIcon)

        // 点击放大镜
        searchIcon.setOnClickListener {
            currentQuery = searchInput.text?.toString()?.trim().orEmpty()
            applyFilters()
            hideKeyboard(searchInput)
        }
        // 软键盘“搜索”
        searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                currentQuery = v.text?.toString()?.trim().orEmpty()
                applyFilters()
                hideKeyboard(searchInput)
                true
            } else false
        }
        // 防抖
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim().orEmpty()
                searchJob?.cancel()
                searchJob = coroutineScope.launch {
                    delay(400)
                    if (text != currentQuery) {
                        currentQuery = text
                        applyFilters()
                    }
                }
            }
        })

        // 适配器
        adapter = QuestionAdapter(this, mutableListOf())
        questionList.adapter = adapter

        // 年级按钮（固定候选 → 影响后续动态收敛）
        gradeButton.setOnClickListener {
            showMultiChoiceDialog(
                title = "Select Grade",
                displayOptions = gradeOptions.toList(),
                selectedSet = selectedGrades
            )
        }

        // 下级三个按钮使用“当前可选项”（由结果集动态驱动）
        subjectButton.setOnClickListener {
            showMultiChoiceDialog("Select Subject", currentSubjectOptions, selectedSubjects)
        }
        categoryButton.setOnClickListener {
            showMultiChoiceDialog("Select Category", currentCategoryOptions, selectedCategories)
        }
        topicButton.setOnClickListener {
            showMultiChoiceDialog("Select Topic", currentTopicOptions, selectedTopics)
        }

        // 底部导航
        val exerciseButton = findViewById<Button>(R.id.exerciseButton)
        val dashboardButton = findViewById<Button>(R.id.dashboardButton)
        val classButton = findViewById<Button>(R.id.classButton)
        val homeButton = findViewById<Button>(R.id.homeButton)
        setSelectedButton(exerciseButton)

        exerciseButton.setOnClickListener { setSelectedButton(exerciseButton) }
        dashboardButton.setOnClickListener {
            setSelectedButton(dashboardButton)
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        classButton.setOnClickListener {
            setSelectedButton(classButton)
            startActivity(Intent(this, ClassActivity::class.java))
        }
        homeButton.setOnClickListener {
            setSelectedButton(homeButton)
            startActivity(Intent(this, HomeActivity::class.java))
        }

        // 列表点击 → 进入题目页
        questionList.setOnItemClickListener { _, _, position, _ ->
            val questionId = adapter.getItem(position)?.id ?: return@setOnItemClickListener
            findViewById<View>(R.id.searchCard).visibility = View.GONE
            findViewById<View>(R.id.filterCard).visibility = View.GONE
            questionList.visibility = View.GONE
            findViewById<View>(R.id.fragmentContainer).visibility = View.VISIBLE

            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, QuestionFragment.newInstance(questionId))
                .addToBackStack(null)
                .commit()
        }

        // 滚动触底自动加载
        questionList.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView, scrollState: Int) {}
            override fun onScroll(
                view: android.widget.AbsListView,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                if (!isLoading && firstVisibleItem + visibleItemCount >= totalItemCount && totalItemCount != 0) {
                    loadNextPage()
                }
            }
        })
    }

    private fun loadInitialData() {
        applyFilters()
    }

    // 仅使用“显示集合”的弹窗，保证勾选与显示一致
    private fun showMultiChoiceDialog(
        title: String,
        displayOptions: List<String>,
        selectedSet: MutableSet<String>
    ) {
        val checkedItems = BooleanArray(displayOptions.size) { i ->
            displayOptions[i] in selectedSet
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(displayOptions.toTypedArray(), checkedItems) { _, which, isChecked ->
                val value = displayOptions[which]
                if (isChecked) selectedSet.add(value) else selectedSet.remove(value)
            }
            .setPositiveButton("Apply") { d, _ ->
                applyFilters()
                d.dismiss()
            }
            .setNegativeButton("Clear") { d, _ ->
                selectedSet.clear()
                applyFilters()
                d.dismiss()
            }
            .setNeutralButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    private fun setSelectedButton(selectedButton: Button) {
        findViewById<Button>(R.id.exerciseButton).isSelected = false
        findViewById<Button>(R.id.dashboardButton).isSelected = false
        findViewById<Button>(R.id.classButton).isSelected = false
        findViewById<Button>(R.id.homeButton).isSelected = false
        selectedButton.isSelected = true
    }

    /** 应用当前筛选（包含搜索词）→ 刷新第一页，并重建级联候选项 */
    private fun applyFilters() {
        currentPage = 1
        coroutineScope.launch {
            try {
                val gradeParam = selectedGrades.joinToString(",")
                val subjectParam = selectedSubjects.joinToString(",")
                val categoryParam = selectedCategories.joinToString(",")
                val topicParam = selectedTopics.joinToString(",")

                val response = withContext(Dispatchers.IO) {
                    api.viewQuestion(
                        keyword = "",
                        questionName = currentQuery,
                        grade = gradeParam,
                        subject = subjectParam,
                        topic = topicParam,
                        category = categoryParam,
                        page = currentPage,
                        questionIndex = -1
                    )
                }

                if (response.isSuccessful) {
                    response.body()?.let { resultDTO ->
                        if (resultDTO.errorMessage == null) {
                            val questions: List<QsInform> = resultDTO.data?.items ?: emptyList()
                            if (questions.isEmpty()) {
                                Toast.makeText(this@ExerciseActivity, "No matching questions", Toast.LENGTH_SHORT).show()
                            }
                            adapter.updateData(questions.toMutableList())
                            rebuildFacetOptionsFromData()
                        } else {
                            Toast.makeText(this@ExerciseActivity, "Error: ${resultDTO.errorMessage}", Toast.LENGTH_SHORT).show()
                        }
                    } ?: Toast.makeText(this@ExerciseActivity, "Request failed: server returned empty data", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ExerciseActivity, "Network error: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: retrofit2.HttpException) {
                Toast.makeText(this@ExerciseActivity, "HTTP error: ${e.message()}", Toast.LENGTH_SHORT).show()
            } catch (e: java.io.IOException) {
                Toast.makeText(this@ExerciseActivity, "Network connection failed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@ExerciseActivity, "Unknown error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 滚动触底或“下一题”末尾触发的加载下一页 */
    fun loadNextPage(onAppended: ((List<QsInform>) -> Unit)? = null) {
        if (isLoading) return
        isLoading = true
        val nextPage = currentPage + 1

        coroutineScope.launch {
            try {
                val gradeParam = selectedGrades.joinToString(",")
                val subjectParam = selectedSubjects.joinToString(",")
                val categoryParam = selectedCategories.joinToString(",")
                val topicParam = selectedTopics.joinToString(",")

                val response = withContext(Dispatchers.IO) {
                    api.viewQuestion(
                        keyword = "",
                        questionName = currentQuery,
                        grade = gradeParam,
                        subject = subjectParam,
                        topic = topicParam,
                        category = categoryParam,
                        page = nextPage,
                        questionIndex = -1
                    )
                }

                if (response.isSuccessful) {
                    val resultDTO = response.body()
                    if (resultDTO?.errorMessage == null) {
                        val questions: List<QsInform> = resultDTO?.data?.items ?: emptyList()
                        if (questions.isNotEmpty()) {
                            currentPage = nextPage
                            adapter.addItems(questions.toMutableList())
                            rebuildFacetOptionsFromData()
                            onAppended?.invoke(questions)
                        } else {
                            Toast.makeText(this@ExerciseActivity, "No more questions", Toast.LENGTH_SHORT).show()
                            onAppended?.invoke(emptyList())
                        }
                    } else {
                        Toast.makeText(this@ExerciseActivity, "Error: ${resultDTO.errorMessage}", Toast.LENGTH_SHORT).show()
                        onAppended?.invoke(emptyList())
                    }
                } else {
                    Toast.makeText(this@ExerciseActivity, "Network error: ${response.code()}", Toast.LENGTH_SHORT).show()
                    onAppended?.invoke(emptyList())
                }
            } catch (e: retrofit2.HttpException) {
                Toast.makeText(this@ExerciseActivity, "HTTP error: ${e.message()}", Toast.LENGTH_SHORT).show()
                onAppended?.invoke(emptyList())
            } catch (e: java.io.IOException) {
                Toast.makeText(this@ExerciseActivity, "Network connection failed", Toast.LENGTH_SHORT).show()
                onAppended?.invoke(emptyList())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@ExerciseActivity, "Unknown error: ${e.message}", Toast.LENGTH_SHORT).show()
                onAppended?.invoke(emptyList())
            } finally {
                isLoading = false
            }
        }
    }

    /** 级联：根据当前列表数据重建 subject/category/topic 候选项，并清理无效已选 */
    private fun rebuildFacetOptionsFromData() {
        val data = adapter.getData()

        if (data.isEmpty()) {
            // 如果这一页没数据，保留当前候选（或退回全量）
            currentSubjectOptions = subjectOptions.toMutableList()
            currentCategoryOptions = categoryOptions.toMutableList()
            currentTopicOptions    = topicOptions.toMutableList()
            return
        }

        // ⚠️ 确保 QsInform 的字段名确实是 subject/category/topic，否则改成你的真实字段
        val subjectsInData   = data.mapNotNull { it.subject?.trim() }.filter { it.isNotEmpty() }.toSet()
        val categoriesInData = data.mapNotNull { it.category?.trim() }.filter { it.isNotEmpty() }.toSet()
        val topicsInData     = data.mapNotNull { it.topic?.trim() }.filter { it.isNotEmpty() }.toSet()

        currentSubjectOptions =
            if (subjectsInData.isNotEmpty()) subjectsInData.sorted().toMutableList()
            else currentSubjectOptions // 不回全量，尽量保持“动态”

        currentCategoryOptions =
            if (categoriesInData.isNotEmpty()) categoriesInData.sorted().toMutableList()
            else currentCategoryOptions

        currentTopicOptions =
            if (topicsInData.isNotEmpty()) topicsInData.sorted().toMutableList()
            else currentTopicOptions

        // 清理不再合法的已选
        selectedSubjects.retainAll(currentSubjectOptions.toSet())
        selectedCategories.retainAll(currentCategoryOptions.toSet())
        selectedTopics.retainAll(currentTopicOptions.toSet())
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun getAdapterData(): List<QsInform> = adapter.getData()

    fun getNextQuestionIdOrLoad(currentId: Int, onReady: (Int?) -> Unit) {
        val data = adapter.getData()
        val idx = data.indexOfFirst { it.id == currentId }
        if (idx == -1) { onReady(null); return }

        if (idx + 1 < data.size) {
            onReady(data[idx + 1].id); return
        }
        loadNextPage { appended ->
            if (appended.isNotEmpty()) onReady(appended.first().id) else onReady(null)
        }
    }

    fun getPrevQuestionId(currentId: Int): Int? {
        val data = adapter.getData()
        val idx = data.indexOfFirst { it.id == currentId }
        return if (idx > 0) data[idx - 1].id else null
    }

    fun showMainUI() {
        findViewById<View>(R.id.searchCard).visibility = View.VISIBLE
        findViewById<View>(R.id.filterCard).visibility = View.VISIBLE
        questionList.visibility = View.VISIBLE
        findViewById<View>(R.id.fragmentContainer).visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}
