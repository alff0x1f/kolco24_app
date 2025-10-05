package ru.kolco24.kolco24.ui.teams

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.data.entities.Team
import ru.kolco24.kolco24.databinding.FragmentTeamsCategoryBinding

class TeamsCategoryFragment : Fragment() {
    private var binding: FragmentTeamsCategoryBinding? = null
    private lateinit var teamViewModel: TeamViewModel
    private var currentTeamLiveData: LiveData<Team?>? = null

    private var categoryName: String = ""
    private var categoryCode: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            categoryName = args.getString(CATEGORY_NAME).orEmpty()
            categoryCode = args.getInt(CATEGORY_CODE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentTeamsCategoryBinding.inflate(inflater, container, false)
        teamViewModel = ViewModelProvider(this)[TeamViewModel::class.java]

        binding?.apply {
            textCategoryTitle.text = if (categoryName.isNotBlank()) {
                getString(R.string.team_category_title_format, categoryName)
            } else {
                getString(R.string.select_team_title_generic)
            }
            buttonChangeTeam.setOnClickListener {
                val intent = Intent(requireContext(), TeamSelectionActivity::class.java).apply {
                    putExtra(TeamSelectionActivity.EXTRA_CATEGORY_CODE, categoryCode)
                    putExtra(TeamSelectionActivity.EXTRA_CATEGORY_NAME, categoryName)
                }
                startActivity(intent)
            }
        }

        updateCurrentTeam()

        return binding!!.root
    }

    override fun onResume() {
        super.onResume()
        updateCurrentTeam()
        (activity as? AppCompatActivity)?.supportActionBar?.title =
            if (categoryName.isNotBlank()) {
                getString(R.string.team_category_title_format, categoryName)
            } else {
                getString(R.string.select_team_title_generic)
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        currentTeamLiveData?.removeObservers(viewLifecycleOwner)
        binding = null
    }

    private fun updateCurrentTeam() {
        if (!isAdded) {
            return
        }
        val sharedPreferences = requireContext().getSharedPreferences("team", Context.MODE_PRIVATE)
        val teamId = sharedPreferences.getInt("team_id", 0)
        currentTeamLiveData?.removeObservers(viewLifecycleOwner)

        if (teamId == 0) {
            showNoTeam(getString(R.string.team_not_selected_message))
            return
        }

        currentTeamLiveData = teamViewModel.getTeam(teamId)
        currentTeamLiveData?.observe(viewLifecycleOwner) { team ->
            if (team == null) {
                showNoTeam(getString(R.string.team_not_found_message))
                return@observe
            }
            if (team.category != categoryCode) {
                showNoTeam(getString(R.string.team_category_mismatch_message))
                return@observe
            }
            showTeam(team)
        }
    }

    private fun showTeam(team: Team) {
        binding?.apply {
            containerTeamInfo.visibility = View.VISIBLE
            textNoTeam.visibility = View.GONE
            textTeamName.text = team.teamname
            textTeamNumber.text = getString(R.string.team_number_format, team.startNumber)
            textTeamCategory.text =
                getString(R.string.team_category_format, categoryName.ifBlank { team.dist })
            textTeamCity.text = getString(
                R.string.team_city_format,
                team.city.ifBlank { getString(R.string.team_info_not_specified) },
            )
            textTeamOrganization.text = getString(
                R.string.team_organization_format,
                team.organization.ifBlank { getString(R.string.team_info_not_specified) },
            )
            textTeamMembers.text = getString(R.string.team_members_format, team.ucount)
        }
    }

    private fun showNoTeam(message: String) {
        binding?.apply {
            containerTeamInfo.visibility = View.GONE
            textNoTeam.text = message
            textNoTeam.visibility = View.VISIBLE
        }
    }

    companion object {
        const val CATEGORY_NAME: String = "6ч"
        const val CATEGORY_CODE: String = "6h"

        fun newInstance(param1: String?, param2: String?): TeamsCategoryFragment {
            val fragment = TeamsCategoryFragment()
            val args = Bundle()
            args.putString(CATEGORY_NAME, param1)
            args.putString(CATEGORY_CODE, param2)
            fragment.arguments = args
            return fragment
        }
    }
}
