package io.legado.app.ui.book.search

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.App
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.lib.theme.ATH
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.widget.LoadMoreView
import io.legado.app.utils.*
import kotlinx.android.synthetic.main.activity_book_search.*
import kotlinx.android.synthetic.main.view_search.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.anko.sdk27.listeners.onClick
import org.jetbrains.anko.startActivity


class SearchActivity : VMBaseActivity<SearchViewModel>(R.layout.activity_book_search),
    SearchViewModel.CallBack,
    BookAdapter.CallBack,
    HistoryKeyAdapter.CallBack,
    SearchAdapter.CallBack {

    override val viewModel: SearchViewModel
        get() = getViewModel(SearchViewModel::class.java)

    override lateinit var adapter: SearchAdapter
    private lateinit var bookAdapter: BookAdapter
    private lateinit var historyKeyAdapter: HistoryKeyAdapter
    private lateinit var loadMoreView: LoadMoreView
    private var historyData: LiveData<List<SearchKeyword>>? = null
    private var bookData: LiveData<List<Book>>? = null
    private var menu: Menu? = null
    private var precisionSearchMenuItem: MenuItem? = null
    private var groups = hashSetOf<String>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.callBack = this
        initRecyclerView()
        initSearchView()
        initOtherView()
        initData()
        initIntent()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_search, menu)
        precisionSearchMenuItem = menu.findItem(R.id.menu_precision_search)
        precisionSearchMenuItem?.isChecked = getPrefBoolean(PreferKey.precisionSearch)
        this.menu = menu
        upGroupMenu()
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_precision_search -> {
                putPrefBoolean(
                    PreferKey.precisionSearch,
                    !getPrefBoolean(PreferKey.precisionSearch)
                )
                precisionSearchMenuItem?.isChecked = getPrefBoolean(PreferKey.precisionSearch)
                search_view.query?.toString()?.trim()?.let {
                    search_view.setQuery(it, true)
                }
            }
            R.id.menu_source_manage -> startActivity<BookSourceActivity>()
            else -> if (item.groupId == R.id.source_group) {
                item.isChecked = true
                if (item.title.toString() == getString(R.string.all_source)) {
                    putPrefString("searchGroup", "")
                } else {
                    putPrefString("searchGroup", item.title.toString())
                }
                search_view.query?.toString()?.trim()?.let {
                    search_view.setQuery(it, true)
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initSearchView() {
        ATH.setTint(search_view, primaryTextColor)
        search_view.onActionViewExpanded()
        search_view.isSubmitButtonEnabled = true
        search_view.queryHint = getString(R.string.search_book_key)
        search_view.clearFocus()
        search_view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                search_view.clearFocus()
                query?.let {
                    viewModel.saveSearchKey(query)
                    viewModel.search(it)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrBlank()) viewModel.stop()
                upHistory(newText)
                return false
            }
        })
        search_view.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (!hasFocus && search_view.query.toString().trim().isEmpty()) {
                finish()
            } else {
                openOrCloseHistory(hasFocus)
            }
        }
    }

    private fun initRecyclerView() {
        ATH.applyEdgeEffectColor(recycler_view)
        ATH.applyEdgeEffectColor(rv_bookshelf_search)
        ATH.applyEdgeEffectColor(rv_history_key)
        bookAdapter = BookAdapter(this, this)
        rv_bookshelf_search.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rv_bookshelf_search.adapter = bookAdapter
        historyKeyAdapter = HistoryKeyAdapter(this, this)
        rv_history_key.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rv_history_key.adapter = historyKeyAdapter
        adapter = SearchAdapter(this, this)
        recycler_view.layoutManager = LinearLayoutManager(this)
        recycler_view.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    recycler_view.scrollToPosition(0)
                }
            }
        })
        loadMoreView = LoadMoreView(this)
        recycler_view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                }
            }
        })
    }

    private fun initOtherView() {
        tv_clear_history.onClick { viewModel.clearHistory() }
        fb_stop.onClick {
            viewModel.stop()
            refresh_progress_bar.isAutoLoading = false
        }
    }

    private fun initData() {
        App.db.bookSourceDao().liveGroupEnabled().observe(this, Observer {
            groups.clear()
            it.map { group ->
                groups.addAll(group.splitNotBlank(",", ";"))
            }
            upGroupMenu()
        })
    }

    private fun initIntent() {
        intent.getStringExtra("key")?.let {
            search_view.setQuery(it, true)
        } ?: let {
            search_view.requestFocus()
        }
    }

    private fun scrollToBottom() {
        if (!viewModel.isLoading && viewModel.searchKey.isNotEmpty() && loadMoreView.hasMore) {
            viewModel.search("")
        }
    }

    private fun openOrCloseHistory(open: Boolean) {
        if (open) {
            upHistory(search_view.query.toString())
            ll_history.visibility = VISIBLE
        } else {
            ll_history.visibility = GONE
        }
    }

    private fun upGroupMenu() {
        val selectedGroup = getPrefString("searchGroup") ?: ""
        menu?.removeGroup(R.id.source_group)
        var item = menu?.add(R.id.source_group, Menu.NONE, Menu.NONE, R.string.all_source)
        if (selectedGroup == "") {
            item?.isChecked = true
        }
        groups.map {
            item = menu?.add(R.id.source_group, Menu.NONE, Menu.NONE, it)
            if (it == selectedGroup) {
                item?.isChecked = true
            }
        }
        menu?.setGroupCheckable(R.id.source_group, true, true)
    }

    private fun upHistory(key: String? = null) {
        bookData?.removeObservers(this)
        if (key.isNullOrBlank()) {
            tv_book_show.gone()
            rv_bookshelf_search.gone()
        } else {
            bookData = App.db.bookDao().liveDataSearch(key)
            bookData?.observe(this, Observer {
                if (it.isEmpty()) {
                    tv_book_show.gone()
                    rv_bookshelf_search.gone()
                } else {
                    tv_book_show.visible()
                    rv_bookshelf_search.visible()
                }
                bookAdapter.setItems(it)
            })
        }
        historyData?.removeObservers(this)
        historyData =
            if (key.isNullOrBlank()) {
                App.db.searchKeywordDao().liveDataByUsage()
            } else {
                App.db.searchKeywordDao().liveDataSearch(key)
            }
        historyData?.observe(this, Observer { historyKeyAdapter.setItems(it)
            if (it.isEmpty()) {
                tv_clear_history.invisible()
            } else {
                tv_clear_history.visible()
            }
        })
    }

    override fun startSearch() {
        refresh_progress_bar.isAutoLoading = true
        initData()
        fb_stop.visible()
    }

    override fun searchFinally() {
        refresh_progress_bar.isAutoLoading = false
        loadMoreView.startLoad()
        fb_stop.invisible()
    }

    override fun showBookInfo(name: String, author: String) {
        viewModel.getSearchBook(name, author) { searchBook ->
            searchBook?.let {
                startActivity<BookInfoActivity>(Pair("bookUrl", it.bookUrl))
            }
        }
    }

    override fun showBookInfo(book: Book) {
        startActivity<BookInfoActivity>(
            Pair("bookUrl", book.bookUrl)
        )
    }

    override fun searchHistory(key: String) {
        launch {
            if (withContext(IO) { App.db.bookDao().findByName(key).isEmpty() }) {
                search_view.setQuery(key, true)
            } else {
                search_view.setQuery(key, false)
            }
        }
    }
}