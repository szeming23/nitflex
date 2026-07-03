package com.nitflex.app.fragments.my_list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.nitflex.app.R
import com.nitflex.app.adapters.AppAdapter
import com.nitflex.app.database.AppDatabase
import com.nitflex.app.databinding.FragmentMyListMobileBinding
import com.nitflex.app.models.Category
import com.nitflex.app.models.Movie
import com.nitflex.app.models.TvShow
import com.nitflex.app.ui.SpacingItemDecoration
import com.nitflex.app.utils.UserPreferences
import com.nitflex.app.utils.dp
import kotlinx.coroutines.launch

class MyListMobileFragment : Fragment() {

    private var _binding: FragmentMyListMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MyListViewModel by lazy {
        val providerKey = UserPreferences.currentProvider?.name ?: "default"
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MyListViewModel(AppDatabase.getInstance(requireContext())) as T
            }
        }
        ViewModelProvider(this, factory).get(providerKey, MyListViewModel::class.java)
    }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMyListMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvMyList.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(SpacingItemDecoration(20.dp(requireContext())))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MyListViewModel.State.Loading -> {}
                    is MyListViewModel.State.Success -> displayMyList(state.categories)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reflect changes made on detail/player screens since the list was built.
        viewModel.load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.rvMyList)
        _binding = null
    }

    private fun localizedName(category: Category): String = when (category.name) {
        Category.MY_LIST_WATCHLIST -> getString(R.string.my_list_watchlist)
        Category.MY_LIST_CONTINUE -> getString(R.string.my_list_continue)
        Category.MY_LIST_REVIEWED -> getString(R.string.my_list_reviewed)
        else -> category.name
    }

    private fun displayMyList(categories: List<Category>) {
        val nonEmpty = categories.filter { it.list.isNotEmpty() }

        binding.tvMyListEmpty.visibility = if (nonEmpty.isEmpty()) View.VISIBLE else View.GONE
        binding.rvMyList.visibility = if (nonEmpty.isEmpty()) View.GONE else View.VISIBLE

        appAdapter.submitList(
            nonEmpty.onEach { category ->
                category.list.onEach { item ->
                    when (item) {
                        is Movie -> item.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                        is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    }
                }
                category.name = localizedName(category)
                category.itemSpacing = 10.dp(requireContext())
                category.itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
            }
        )
    }
}
