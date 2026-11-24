package com.lttbdd.socialagent.feature.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import com.lttbdd.socialagent.core.ui.components.AppSnackbar
import com.lttbdd.socialagent.databinding.FragmentComposeBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Màn hình nhập topic, mục tiêu, đối tượng, giọng văn, ngôn ngữ và kênh đăng (F3).
 */
@AndroidEntryPoint
class ComposeFragment : Fragment() {

    private var _binding: FragmentComposeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ComposeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentComposeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToneDropdown()
        setupListeners()
        observeUiState()
    }

    private fun setupToneDropdown() {
        val toneLabels = ToneOption.ALL.map { getString(it.labelRes) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, toneLabels)
        binding.actvTone.setAdapter(adapter)
        binding.actvTone.setOnItemClickListener { _, _, position, _ ->
            val selectedOption = ToneOption.ALL.getOrNull(position) ?: return@setOnItemClickListener
            viewModel.onToneChanged(selectedOption.key)
        }
    }

    private fun setupListeners() {
        binding.etTopic.doAfterTextChanged {
            viewModel.onTopicChanged(it?.toString().orEmpty())
        }
        binding.etTopic.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.onTopicFocusLost()
        }

        binding.etGoal.doAfterTextChanged {
            viewModel.onGoalChanged(it?.toString().orEmpty())
        }
        binding.etGoal.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.onGoalFocusLost()
        }

        binding.etAudience.doAfterTextChanged {
            viewModel.onAudienceChanged(it?.toString().orEmpty())
        }
        binding.etAudience.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.onAudienceFocusLost()
        }

        binding.actvTone.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.onToneFocusLost()
        }

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val language = if (checkedId == R.id.rbLanguageEn) ContentLanguage.EN else ContentLanguage.VI
            viewModel.onLanguageChanged(language)
        }

        binding.cbChannelFacebook.setOnCheckedChangeListener { _, isChecked ->
            viewModel.onChannelToggled(ChannelType.FACEBOOK, isChecked)
        }

        binding.cbChannelLinkedIn.setOnCheckedChangeListener { _, isChecked ->
            viewModel.onChannelToggled(ChannelType.LINKEDIN, isChecked)
        }

        binding.btnContinue.setOnClickListener {
            viewModel.onSubmit()
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUiState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ComposeEvent.BriefSubmitted -> {
                                AppSnackbar.show(binding.root, event.messageRes)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderUiState(state: ComposeUiState) {
        val form = state.formState

        // Cập nhật text input khi có sự khác biệt (tránh con trỏ bị nhảy khi nhập)
        if (binding.etTopic.text?.toString() != form.topic) {
            binding.etTopic.setText(form.topic)
        }
        if (binding.etGoal.text?.toString() != form.goal) {
            binding.etGoal.setText(form.goal)
        }
        if (binding.etAudience.text?.toString() != form.audience) {
            binding.etAudience.setText(form.audience)
        }

        // Khôi phục text hiển thị của ô giọng văn khi màn hình được tạo lại (vd: đổi ngôn ngữ ở Cài đặt)
        val expectedToneLabel = ToneOption.ALL
            .firstOrNull { it.key == form.tone }
            ?.let { getString(it.labelRes) }
            .orEmpty()
        if (binding.actvTone.text?.toString() != expectedToneLabel) {
            binding.actvTone.setText(expectedToneLabel, false)
        }

        // Cập nhật lỗi
        binding.layoutTopic.error = form.topicError?.let { getString(it) }
        binding.layoutGoal.error = form.goalError?.let { getString(it) }
        binding.layoutAudience.error = form.audienceError?.let { getString(it) }
        binding.layoutTone.error = form.toneError?.let { getString(it) }

        if (form.channelsError != null) {
            binding.tvChannelsError.setText(form.channelsError)
            binding.tvChannelsError.visibility = View.VISIBLE
        } else {
            binding.tvChannelsError.visibility = View.GONE
        }

        // Cập nhật kênh đã chọn
        val fbChecked = form.selectedChannels.contains(ChannelType.FACEBOOK)
        if (binding.cbChannelFacebook.isChecked != fbChecked) {
            binding.cbChannelFacebook.isChecked = fbChecked
        }
        val liChecked = form.selectedChannels.contains(ChannelType.LINKEDIN)
        if (binding.cbChannelLinkedIn.isChecked != liChecked) {
            binding.cbChannelLinkedIn.isChecked = liChecked
        }

        // Cập nhật ngôn ngữ
        val targetRadioId = if (form.language == ContentLanguage.EN) R.id.rbLanguageEn else R.id.rbLanguageVi
        if (binding.rgLanguage.checkedRadioButtonId != targetRadioId) {
            binding.rgLanguage.check(targetRadioId)
        }

        // Cập nhật nút Tiếp tục
        binding.btnContinue.isEnabled = form.isFormValid
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
