package com.example.adproject

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.adproject.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuestionFragment : Fragment() {

    companion object {
        private const val ARG_QUESTION_ID = "questionId"
        fun newInstance(questionId: Int) = QuestionFragment().apply {
            arguments = Bundle().apply { putInt(ARG_QUESTION_ID, questionId) }
        }
    }

    private var questionId: Int = 0

    // 统一用全局 ApiClient
    private val api by lazy { ApiClient.api }

    // UI
    private lateinit var questionText: TextView
    private lateinit var questionImage: ImageView
    private lateinit var optionsGroup: RadioGroup
    private lateinit var confirmBtn: Button
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var returnBtn: Button
    private lateinit var feedbackText: TextView

    // Data
    private var choices: List<String> = emptyList()
    private var correctAnswerIndex: Int = -1
    private var selectedOptionIndex: Int = -1
    private var isAnswered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        questionId = arguments?.getInt(ARG_QUESTION_ID) ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_question, container, false).also { view ->
        questionText = view.findViewById(R.id.questionText)
        questionImage = view.findViewById(R.id.questionImage)
        optionsGroup = view.findViewById(R.id.optionsGroup)
        confirmBtn = view.findViewById(R.id.confirmButton)
        prevBtn = view.findViewById(R.id.prevButton)
        nextBtn = view.findViewById(R.id.nextButton)
        returnBtn = view.findViewById(R.id.returnButton)
        feedbackText = view.findViewById(R.id.feedbackText)

        confirmBtn.setOnClickListener { onConfirmClicked() }
        prevBtn.setOnClickListener { navigateByDelta(-1) }
        nextBtn.setOnClickListener { goNext() }
        returnBtn.setOnClickListener {
            parentFragmentManager.popBackStack()
            (activity as? ExerciseActivity)?.showMainUI()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadAndDisplayQuestion()
        // 初次根据当前数据更新上一题按钮可用性
        val act = activity as? ExerciseActivity
        prevBtn.isEnabled = (act?.getPrevQuestionId(questionId) != null)
        // nextBtn 始终可点（若到末尾会自动拉下一页）
        nextBtn.isEnabled = true
    }

    private fun loadAndDisplayQuestion() {
        // 重置状态
        isAnswered = false
        selectedOptionIndex = -1
        feedbackText.visibility = View.GONE
        feedbackText.text = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val resp = withContext(Dispatchers.IO) { api.getQuestionById(questionId) }
            if (!isAdded) return@launch

            if (resp.isSuccessful) {
                val dto = resp.body()?.data
                if (dto != null) {
                    questionText.text = dto.question
                    correctAnswerIndex = dto.answer
                    choices = dto.choices.orEmpty()

                    // 图像
                    if (!dto.image.isNullOrEmpty()) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val bmp = withContext(Dispatchers.IO) {
                                    val bytes = Base64.decode(dto.image, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }
                                if (isAdded) questionImage.setImageBitmap(bmp)
                            } catch (_: Exception) {
                                if (isAdded) questionImage.setImageResource(R.drawable.error_image)
                            }
                        }
                    } else {
                        questionImage.setImageResource(R.drawable.placeholder_image)
                    }

                    // 选项
                    optionsGroup.removeAllViews()
                    choices.forEach { text ->
                        val rb = RadioButton(requireContext()).apply {
                            id = View.generateViewId()
                            this.text = text
                            setPadding(8, 8, 8, 8)
                        }
                        optionsGroup.addView(rb)
                    }
                    optionsGroup.setOnCheckedChangeListener { group, checkedId ->
                        selectedOptionIndex = group.indexOfChild(group.findViewById(checkedId))
                    }

                    // 上一题按钮
                    val act = (activity as? ExerciseActivity)
                    prevBtn.isEnabled = (act?.getPrevQuestionId(questionId) != null)
                } else {
                    Toast.makeText(requireContext(), "Question data is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Load failed: ${resp.code()}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onConfirmClicked() {
        if (selectedOptionIndex == -1) {
            Toast.makeText(requireContext(), "Please select an option", Toast.LENGTH_SHORT).show()
            return
        }
        val correct = (selectedOptionIndex == correctAnswerIndex)
        val msg = if (correct) "✅ Correct!"
        else "❌ Wrong, correct answer: ${choices.getOrNull(correctAnswerIndex) ?: "-"}"

        feedbackText.text = msg
        feedbackText.visibility = View.VISIBLE

        // 提交结果
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val rsp = withContext(Dispatchers.IO) {
                    api.answerQuestion(
                        id = questionId,
                        correct = if (correct) 1 else 0,
                        param = selectedOptionIndex
                    )
                }
                if (rsp.isSuccessful && rsp.body()?.code == 1) {
                    Toast.makeText(requireContext(), "Submitted successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Submission failed", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(requireContext(), "Network error", Toast.LENGTH_SHORT).show()
            }
        }

        isAnswered = true
    }

    private fun goNext() {
        val act = activity as? ExerciseActivity ?: return
        nextBtn.isEnabled = false
        // 交给 Activity：若当前是最后一题，会自动拉下一页
        act.getNextQuestionIdOrLoad(questionId) { nextId ->
            if (!isAdded) return@getNextQuestionIdOrLoad
            nextBtn.isEnabled = true
            if (nextId != null) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, newInstance(nextId))
                    .commit()
            } else {
                Toast.makeText(requireContext(), "This is the last question", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun navigateByDelta(delta: Int) {
        val act = (activity as? ExerciseActivity) ?: return
        val nextId = if (delta < 0) act.getPrevQuestionId(questionId) else null
        if (delta > 0) { // 下一题按钮单独由 goNext() 处理分页，所以这里只处理上一题
            goNext(); return
        }
        if (nextId == null) {
            Toast.makeText(requireContext(), "This is the first question", Toast.LENGTH_SHORT).show()
            return
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, newInstance(nextId))
            .commit()
    }
}
