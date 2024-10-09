package ru.kolco24.kolco24.ui.teams

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import ru.kolco24.kolco24.DataDownloader
import ru.kolco24.kolco24.data.entities.Team
import ru.kolco24.kolco24.databinding.FragmentTeamsCategoryBinding
import ru.kolco24.kolco24.ui.teams.TeamListAdapter.TeamDiff

/**
 * A simple [Fragment] subclass.
 * Use the [TeamsCategoryFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class TeamsCategoryFragment : Fragment() {
    private var binding: FragmentTeamsCategoryBinding? = null
    private var teamViewModel: TeamViewModel? = null

    // TODO: Rename and change types of parameters
    private var categoryName: String? = null
    private var categoryCode: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (arguments != null) {
            categoryName = requireArguments().getString(CATEGORY_NAME)
            categoryCode = requireArguments().getInt(CATEGORY_CODE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentTeamsCategoryBinding.inflate(inflater, container, false)
        val root: View = binding!!.root
        // recycler view
        val recyclerTeams = binding!!.recyclerTeams
        val adapter = TeamListAdapter(TeamDiff())
        recyclerTeams.adapter = adapter
        recyclerTeams.layoutManager = LinearLayoutManager(context)

        teamViewModel = ViewModelProvider(this).get(TeamViewModel::class.java)
        teamViewModel!!.getTeamsByCategory(categoryCode).observe(
            viewLifecycleOwner
        ) { list: List<Team?> -> adapter.submitList(list) }
        teamViewModel!!.getTeamsByCategory(categoryCode).observe(
            viewLifecycleOwner
        ) { teams: List<Team?> ->
            if (teams.size == 0) {
                binding!!.textNoTeams.visibility = View.VISIBLE
                //                teamViewModel.downloadTeams("https://kolco24.ru/api/v1/teams");
            } else {
                binding!!.textNoTeams.visibility = View.GONE
                binding!!.swipeToRefresh.isRefreshing = false
            }
        }
        binding!!.swipeToRefresh.setOnRefreshListener {
            val dataDownloader = DataDownloader(
                requireActivity().application
            ) { binding!!.swipeToRefresh.isRefreshing = false }
            dataDownloader.downloadTeams(categoryCode)
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //        TextView textView = view.findViewById(R.id.text);
//        textView.setText(mParam1);
        //toast observer
        teamViewModel!!.toastMessage.observe(
            viewLifecycleOwner
        ) { s: String? ->
            println("toast message: $s")
            if (s != null) {
                Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
                teamViewModel!!.clearToastMessage()
            }
        }
        teamViewModel!!.isLoading().observe(
            viewLifecycleOwner
        ) { aBoolean: Boolean? ->
            binding!!.swipeToRefresh.isRefreshing = aBoolean!!
        }
        //
    }

    override fun onResume() {
        super.onResume()
        (activity as AppCompatActivity).supportActionBar!!.title = "Команды $categoryName"
    }

    companion object {
        // TODO: Rename parameter arguments, choose names that match
        // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
        const val CATEGORY_NAME: String = "6ч"
        const val CATEGORY_CODE: String = "6h"

        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment DemoObjectFragment.
         */
        // TODO: Rename and change types and number of parameters
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