package ru.kolco24.kolco24.ui.teams

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import ru.kolco24.kolco24.DataDownloader
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.databinding.ActivityTeamSelectionBinding

class TeamSelectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTeamSelectionBinding
    private lateinit var teamViewModel: TeamViewModel

    private var categoryCode: Int = 0
    private var categoryName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeamSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        categoryCode = intent.getIntExtra(EXTRA_CATEGORY_CODE, 0)
        categoryName = intent.getStringExtra(EXTRA_CATEGORY_NAME).orEmpty()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (categoryName.isNotBlank()) {
            getString(R.string.select_team_title, categoryName)
        } else {
            getString(R.string.select_team_title_generic)
        }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val adapter = TeamListAdapter(TeamListAdapter.TeamDiff())
        binding.recyclerTeams.layoutManager = LinearLayoutManager(this)
        binding.recyclerTeams.adapter = adapter

        teamViewModel = ViewModelProvider(this)[TeamViewModel::class.java]

        teamViewModel.getTeamsByCategory(categoryCode).observe(this) { teams ->
            adapter.submitList(teams)
            if (teams.isNullOrEmpty()) {
                binding.textNoTeams.visibility = View.VISIBLE
            } else {
                binding.textNoTeams.visibility = View.GONE
            }
            binding.swipeToRefresh.isRefreshing = false
        }

        teamViewModel.toastMessage.observe(this) { message ->
            if (!message.isNullOrEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                teamViewModel.clearToastMessage()
            }
        }

        teamViewModel.isLoading().observe(this) { isLoading ->
            binding.swipeToRefresh.isRefreshing = isLoading == true
        }

        binding.swipeToRefresh.setOnRefreshListener {
            val dataDownloader = DataDownloader(application) {
                binding.swipeToRefresh.isRefreshing = false
            }
            dataDownloader.downloadTeams(categoryCode)
        }
    }

    companion object {
        const val EXTRA_CATEGORY_CODE = "extra_category_code"
        const val EXTRA_CATEGORY_NAME = "extra_category_name"
    }
}
