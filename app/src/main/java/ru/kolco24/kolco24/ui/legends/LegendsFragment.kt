package ru.kolco24.kolco24.ui.legends

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import ru.kolco24.kolco24.AddTagActivity
import ru.kolco24.kolco24.DataDownloader
import ru.kolco24.kolco24.R
import ru.kolco24.kolco24.data.AppDatabase
import ru.kolco24.kolco24.data.entities.Checkpoint.PointExt
import ru.kolco24.kolco24.databinding.FragmentLegendsBinding
import ru.kolco24.kolco24.ui.legends.PointListAdapter.PointDiff


class LegendsFragment : Fragment(), MenuProvider {
    private lateinit var binding: FragmentLegendsBinding
    private var teamId = 0
    private lateinit var db: AppDatabase

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentLegendsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        db = AppDatabase.getDatabase(requireActivity().application)

        teamId = requireActivity().getSharedPreferences("team", Context.MODE_PRIVATE)
            .getInt("team_id", 0)

        db.photoDao().getCostSum(teamId).observe(
            viewLifecycleOwner
        ) { count: Int? ->
            if (count != null) {
                binding.takenPointsSum.text = String.format("Сумма баллов: %d", count)
            } else {
                binding.takenPointsSum.text = "Сумма баллов: 0"
            }
        }

        // Set up the RecyclerView all points
        val adapter = PointListAdapter(PointDiff())
        binding.pointsRecyclerView.adapter = adapter
        binding.pointsRecyclerView.layoutManager = LinearLayoutManager(root.context)

        db.pointDao().getPointsByTeam(teamId).observe(
            viewLifecycleOwner
        ) { points: List<PointExt?> ->
            adapter.submitList(points)
            if (points.isEmpty()) {
                binding.textNoLegends.visibility = View.VISIBLE
            } else {
                binding.textNoLegends.visibility = View.GONE
                binding.swipeToRefresh.isRefreshing = false
            }
        }

        //swipe to refresh
        val swipeRefreshLayout = binding.swipeToRefresh
        swipeRefreshLayout.isRefreshing = false
        swipeRefreshLayout.setOnRefreshListener {
            val dataDownloader = DataDownloader(
                requireActivity().application
            ) { swipeRefreshLayout.isRefreshing = false }
            dataDownloader.downloadPoints()
        }

        // Add the MenuProvider to handle menu creation
        requireActivity().addMenuProvider(this, viewLifecycleOwner)

        return root
    }

    override fun onResume() {
        super.onResume()
        teamId = requireActivity().getSharedPreferences("team", Context.MODE_PRIVATE)
            .getInt("team_id", 0)
    }


    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menu.clear()
        menuInflater.inflate(R.menu.legend_menu, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.action_update -> {
                DataDownloader(requireActivity().application).downloadPoints()
                true
            }

            R.id.action_local_update -> {
                DataDownloader(requireActivity().application).apply { setLocalDownload(true) }
                    .downloadPoints()
                true
            }


            R.id.action_add_tag -> {
                handleAddTagAction()
                true
            }

            else -> false
        }
    }

    /**
     * Handles the action to add a new tag.
     * This method starts the AddTagActivity.
     */
    private fun handleAddTagAction() {
        val intent = Intent(activity, AddTagActivity::class.java)
        startActivity(intent)
    }
}