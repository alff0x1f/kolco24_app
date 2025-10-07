package ru.kolco24.kolco24.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.core.view.MenuProvider
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.data.CategoryConfig
import ru.kolco24.kolco24.data.SettingsPreferences
import ru.kolco24.kolco24.data.entities.Team
import ru.kolco24.kolco24.databinding.FragmentSettingsBinding
import ru.kolco24.kolco24.ui.teams.TeamViewModel
import ru.kolco24.kolco24.DataDownloader

private data class CompetitionOption(val id: Int, val title: String)

class SettingsFragment : Fragment(), MenuProvider {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val teamViewModel: TeamViewModel by viewModels()

    private var competitionOptions: List<CompetitionOption> = emptyList()
    private var teamsLiveData: LiveData<List<Team>>? = null
    private var teams: List<Team> = emptyList()
    private lateinit var teamAdapter: ArrayAdapter<String>
    private var ignoreTeamSelection = false
    private var selectedCategoryCode: Int = SettingsPreferences.DEFAULT_CATEGORY_CODE
    private var selectedTeamId: Int = 0
    private var selectedCompetitionPosition: Int = 0
    private var isLocalServerSelected = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner)
        selectedCategoryCode = SettingsPreferences.getSelectedCategory(requireContext())
        selectedTeamId = SettingsPreferences.getSelectedTeamId(requireContext())
        restoreTeamSummaryFromPreferences()
        setupCompetitionSpinner()
        setupServerSelector()
        setupCategorySpinner()
        setupTeamSpinner()
        observeTeams(selectedCategoryCode)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        teamsLiveData = null
        _binding = null
    }

    private fun setupCompetitionSpinner() {
        competitionOptions = buildCompetitionOptions()
        val competitionTitles = competitionOptions.map { it.title }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            competitionTitles
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.competitionSpinner.adapter = adapter
        binding.competitionSpinner.isEnabled = competitionOptions.isNotEmpty()

        if (competitionOptions.isNotEmpty()) {
            val storedRaceId = SettingsPreferences.getRaceId(requireContext())
            val index = competitionOptions.indexOfFirst { it.id == storedRaceId }
                .takeIf { it >= 0 } ?: 0
            selectedCompetitionPosition = index
            binding.competitionSpinner.setSelection(index)
            val selectedOption = competitionOptions[index]
            if (selectedOption.id != storedRaceId) {
                SettingsPreferences.setRaceId(requireContext(), selectedOption.id)
            }
        }

        binding.competitionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (competitionOptions.isEmpty()) return
                if (position == selectedCompetitionPosition) return
                selectedCompetitionPosition = position
                val selectedRace = competitionOptions[position]
                SettingsPreferences.setRaceId(requireContext(), selectedRace.id)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // no-op
            }
        }
    }

    private fun setupCategorySpinner() {
        val categoryNames = CategoryConfig.categories.map { it.name }
        val categoryAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            categoryNames
        )
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.categorySpinner.adapter = categoryAdapter

        val initialIndex = CategoryConfig.findPositionByCode(selectedCategoryCode)
        binding.categorySpinner.setSelection(initialIndex)

        binding.categorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val categoryCode = CategoryConfig.getCode(position)
                if (categoryCode == selectedCategoryCode) {
                    return
                }
                selectedCategoryCode = categoryCode
                SettingsPreferences.setSelectedCategory(requireContext(), categoryCode)
                observeTeams(categoryCode)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // no-op
            }
        }
    }

    private fun setupTeamSpinner() {
        ignoreTeamSelection = true
        teamAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            mutableListOf(getString(R.string.settings_team_spinner_placeholder))
        )
        teamAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.teamSpinner.adapter = teamAdapter

        binding.teamSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (ignoreTeamSelection) return
                if (position == 0) {
                    if (selectedTeamId != 0) {
                        SettingsPreferences.clearTeamSelection(requireContext())
                        selectedTeamId = 0
                        updateTeamSummary(null)
                    }
                    return
                }
                val team = teams.getOrNull(position - 1) ?: return
                selectedTeamId = team.id
                SettingsPreferences.persistTeamSelection(
                    requireContext(),
                    team.id,
                    team.teamname,
                    team.startNumber
                )
                updateTeamSummary(team)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // no-op
            }
        }
    }

    private fun observeTeams(categoryCode: Int) {
        teamsLiveData?.removeObservers(viewLifecycleOwner)
        teamsLiveData = teamViewModel.getTeamsByCategory(categoryCode)
        teamsLiveData?.observe(viewLifecycleOwner) { items ->
            updateTeams(items ?: emptyList())
        }
    }

    private fun updateTeams(newTeams: List<Team>) {
        teams = newTeams
        val displayItems = mutableListOf(getString(R.string.settings_team_spinner_placeholder))
        displayItems.addAll(newTeams.map { formatTeam(it) })
        teamAdapter.clear()
        teamAdapter.addAll(displayItems)
        teamAdapter.notifyDataSetChanged()

        if (newTeams.isEmpty()) {
            ignoreTeamSelection = true
            binding.teamSpinner.setSelection(0)
            ignoreTeamSelection = false
            binding.teamSpinner.isEnabled = false
            binding.noTeamsText.visibility = View.VISIBLE
            updateTeamSummary(null)
            return
        }

        binding.teamSpinner.isEnabled = true
        binding.noTeamsText.visibility = View.GONE
        val storedTeamId = SettingsPreferences.getSelectedTeamId(requireContext())
        selectedTeamId = storedTeamId
        val index = newTeams.indexOfFirst { it.id == storedTeamId }
        ignoreTeamSelection = true
        binding.teamSpinner.setSelection(if (index >= 0) index + 1 else 0)
        ignoreTeamSelection = false
        updateTeamSummary(newTeams.getOrNull(index))
    }

    private fun restoreTeamSummaryFromPreferences() {
        if (selectedTeamId == 0) {
            applyTeamSummary(null, null)
            return
        }
        val teamName = SettingsPreferences.getSelectedTeamName(requireContext())
        val teamNumber = SettingsPreferences.getSelectedTeamNumber(requireContext())
        if (teamName.isNullOrBlank()) {
            applyTeamSummary(null, null)
        } else {
            applyTeamSummary(teamNumber, teamName)
        }
    }

    private fun updateTeamSummary(team: Team?) {
        if (team == null) {
            applyTeamSummary(null, null)
        } else {
            applyTeamSummary(team.startNumber, team.teamname)
        }
    }

    private fun applyTeamSummary(startNumber: String?, teamName: String?) {
        binding.teamSummary.text = when {
            teamName.isNullOrBlank() -> getString(R.string.settings_team_not_selected)
            startNumber.isNullOrBlank() -> getString(R.string.settings_team_summary_name_only, teamName)
            else -> getString(R.string.settings_team_summary, startNumber, teamName)
        }
    }

    private fun formatTeam(team: Team): String {
        val startNumber = team.startNumber
        return if (startNumber != null && startNumber.isNotBlank()) {
            getString(R.string.settings_team_item_format, startNumber, team.teamname)
        } else {
            team.teamname
        }
    }

    private fun buildCompetitionOptions(): List<CompetitionOption> {
        val names = resources.getStringArray(R.array.competition_names)
        val ids = resources.getIntArray(R.array.competition_ids)
        val count = minOf(names.size, ids.size)
        val options = mutableListOf<CompetitionOption>()
        for (index in 0 until count) {
            options.add(CompetitionOption(ids[index], names[index]))
        }
        return options
    }

    private fun setupServerSelector() {
        isLocalServerSelected = SettingsPreferences.shouldUseLocalServer(requireContext())
        val group = binding.serverModeGroup
        group.setOnCheckedChangeListener(null)
        group.check(
            if (isLocalServerSelected) R.id.serverLocalButton else R.id.serverInternetButton
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            val useLocal = checkedId == R.id.serverLocalButton
            if (useLocal == isLocalServerSelected) {
                return@setOnCheckedChangeListener
            }
            isLocalServerSelected = useLocal
            SettingsPreferences.setUseLocalServer(requireContext(), useLocal)
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menu.clear()
        menuInflater.inflate(R.menu.settings_menu, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.action_teams_update -> {
                handleTeamsUpdateAction()
                true
            }
            R.id.action_member_tag_update -> {
                handleMemberTagUpdateAction()
                true
            }
            else -> false
        }
    }

    private fun handleTeamsUpdateAction() {
        val dataDownloader = DataDownloader(requireActivity().application)
        dataDownloader.downloadTeams(null)
    }

    private fun handleMemberTagUpdateAction() {
        val dataDownloader = DataDownloader(requireActivity().application)
        dataDownloader.downloadMemberTags()
    }
}
