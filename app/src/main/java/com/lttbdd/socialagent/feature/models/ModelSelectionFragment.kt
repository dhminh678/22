package com.lttbdd.socialagent.feature.models

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import com.lttbdd.socialagent.databinding.FragmentModelSelectionBinding
import com.lttbdd.socialagent.databinding.ItemTaskModelBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Màn chọn provider + model cho từng loại việc (F8): viết bài, phân loại comment, sinh ảnh. */
@AndroidEntryPoint
class ModelSelectionFragment : Fragment() {
    private var _binding: FragmentModelSelectionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ModelSelectionViewModel by viewModels()

    /** `true` khi đang vẽ lại từ state, để không gửi ngược thay đổi về ViewModel. */
    private var rendering = false
    private var sections: Map<LlmTask, ItemTaskModelBinding> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModelSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setAccessibilityHeading(binding.modelsTitle, true)
        sections = mapOf(
            LlmTask.WRITE_POST to binding.writePostSection,
            LlmTask.CLASSIFY_COMMENT to binding.classifySection,
            LlmTask.GENERATE_IMAGE to binding.imageSection,
        )
        binding.manageKeysButton.setOnClickListener {
            val navController = findNavController()
            if (navController.currentDestination?.id == R.id.modelSelectionFragment) {
                navController.navigate(R.id.action_model_selection_to_api_keys)
            }
        }
        sections.forEach { (task, section) -> setUpSection(task, section) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    private fun setUpSection(task: LlmTask, section: ItemTaskModelBinding) {
        section.taskTitle.setText(titleFor(task))
        section.taskDescription.setText(descriptionFor(task))
        ViewCompat.setAccessibilityHeading(section.taskTitle, true)
        section.geminiButton.text = getString(R.string.api_keys_gemini)
        section.openAiButton.text = getString(R.string.api_keys_openai)
        section.providerToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !rendering) {
                viewModel.onProviderSelected(
                    task,
                    if (checkedId == R.id.open_ai_button) LlmProvider.OPENAI else LlmProvider.GEMINI,
                )
            }
        }
        section.modelInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!rendering) viewModel.onModelEdited(task, s?.toString().orEmpty())
            }
        })
        section.saveButton.setOnClickListener { viewModel.save(task) }
        section.resetButton.setOnClickListener { viewModel.reset(task) }
    }

    private fun render(state: ModelSelectionUiState) {
        binding.progress.visibility = if (state.loading || state.busy) View.VISIBLE else View.GONE
        binding.loadError.visibility = if (state.loadFailed) View.VISIBLE else View.GONE
        rendering = true
        try {
            sections.forEach { (task, section) ->
                val taskState = state.tasks[task]
                section.root.visibility = if (taskState == null) View.GONE else View.VISIBLE
                if (taskState != null) renderSection(section, taskState, state)
            }
        } finally {
            rendering = false
        }
    }

    private fun renderSection(section: ItemTaskModelBinding, task: TaskModelUiState, state: ModelSelectionUiState) {
        val busy = state.busy
        val geminiKey = state.providerKeys[LlmProvider.GEMINI] == true
        val openAiKey = state.providerKeys[LlmProvider.OPENAI] == true
        // Provider chưa có key bị khóa (nút mờ, dòng trạng thái nói rõ lý do): thêm key ở màn API key trước.
        section.geminiButton.isEnabled = !busy && geminiKey
        section.openAiButton.isEnabled = !busy && openAiKey
        section.providerToggle.check(if (task.provider == LlmProvider.OPENAI) R.id.open_ai_button else R.id.gemini_button)

        if (section.modelInput.text?.toString() != task.modelInput) {
            // setText(…, false) không lọc lại danh sách gợi ý và không mở dropdown.
            section.modelInput.setText(task.modelInput, false)
            section.modelInput.setSelection(section.modelInput.text?.length ?: 0)
        }
        if (section.modelInput.tag != task.suggestions) {
            section.modelInput.tag = task.suggestions
            section.modelInput.setAdapter(
                ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, task.suggestions),
            )
        }
        section.modelInput.isEnabled = !busy
        section.saveButton.isEnabled = !busy
        section.resetButton.isEnabled = !busy
        section.taskStatus.text = statusText(task)
        section.taskMessage.text = task.message?.let { getString(messageResource(it)) }.orEmpty()
    }

    private fun statusText(task: TaskModelUiState): String = when (task.status) {
        TaskModelStatus.READY -> getString(
            if (task.isDefault) R.string.models_status_ready_default else R.string.models_status_ready,
            providerName(task.provider),
            task.modelInput,
        )
        TaskModelStatus.NEEDS_API_KEY -> getString(R.string.models_status_needs_key, providerName(task.provider))
        TaskModelStatus.NOT_SELECTED -> getString(R.string.models_status_not_selected)
        TaskModelStatus.INVALID_SELECTION -> getString(R.string.models_status_invalid)
    }

    private fun providerName(provider: LlmProvider): String =
        getString(if (provider == LlmProvider.OPENAI) R.string.api_keys_openai else R.string.api_keys_gemini)

    @StringRes
    private fun titleFor(task: LlmTask): Int = when (task) {
        LlmTask.WRITE_POST -> R.string.models_task_write_post
        LlmTask.CLASSIFY_COMMENT -> R.string.models_task_classify
        LlmTask.GENERATE_IMAGE -> R.string.models_task_image
    }

    @StringRes
    private fun descriptionFor(task: LlmTask): Int = when (task) {
        LlmTask.WRITE_POST -> R.string.models_task_write_post_description
        LlmTask.CLASSIFY_COMMENT -> R.string.models_task_classify_description
        LlmTask.GENERATE_IMAGE -> R.string.models_task_image_description
    }

    @StringRes
    private fun messageResource(message: ModelSelectionMessage): Int = when (message) {
        ModelSelectionMessage.SAVED -> R.string.models_msg_saved
        ModelSelectionMessage.SAVED_WITH_WARNING -> R.string.models_msg_saved_warning
        ModelSelectionMessage.RESET -> R.string.models_msg_reset
        ModelSelectionMessage.MISSING_API_KEY -> R.string.models_msg_missing_key
        ModelSelectionMessage.EMPTY -> R.string.models_msg_empty
        ModelSelectionMessage.TOO_LONG -> R.string.models_msg_too_long
        ModelSelectionMessage.INVALID_FORMAT -> R.string.models_msg_invalid_format
        ModelSelectionMessage.INCOMPATIBLE -> R.string.models_msg_incompatible
        ModelSelectionMessage.UNSUPPORTED_PROVIDER -> R.string.models_msg_unsupported_provider
        ModelSelectionMessage.ERROR -> R.string.models_msg_error
    }

    override fun onDestroyView() {
        sections = emptyMap()
        _binding = null
        super.onDestroyView()
    }
}
