package io.github.theonionsarewatching.nova.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.data.SearchRow
import io.github.theonionsarewatching.nova.databinding.ActivitySearchBinding
import io.github.theonionsarewatching.nova.databinding.ItemSuggestionBinding
import io.github.theonionsarewatching.nova.util.Formatters
import kotlinx.coroutines.launch

class SearchActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var repo: Repo
    private lateinit var adapter: ResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = Repo.get(this)

        adapter = ResultAdapter { open(it) }
        binding.resultList.layoutManager = LinearLayoutManager(this)
        binding.resultList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.searchInput.setOnEditorActionListener { v, _, _ ->
            run(v.text?.toString()?.trim().orEmpty())
            true
        }

        softkeys = Softkeys(this, binding.softkeyBar).also {
            it.set(
                getString(R.string.back), null, getString(R.string.menu_search),
                onLeft = { finish() },
                onRight = { run(binding.searchInput.text?.toString()?.trim().orEmpty()) }
            )
        }

        ThemeUtils.applyFocusHighlight(binding.btnBack, binding.searchInput)

        val initial = intent.getStringExtra("query").orEmpty()
        if (initial.isNotBlank()) {
            binding.searchInput.setText(initial)
            run(initial)
        } else {
            binding.searchInput.requestFocus()
        }
    }

    private fun run(query: String) {
        if (query.isBlank()) return
        lifecycleScope.launch {
            val titles = HashMap<Long, String>()
            repo.db.conversations().all().forEach { titles[it.id] = it.displayTitle() }
            // body matches (FTS, prefix), guarded against FTS operator syntax errors
            val body = try {
                repo.db.messages().search(sanitize(query))
            } catch (_: Exception) { emptyList() }
            // conversations whose title matches
            val convoHits = titles.filterValues { it.contains(query, ignoreCase = true) }.keys
            val convoRows = convoHits.mapNotNull { id ->
                repo.db.messages().newest(id)?.let {
                    SearchRow(it.id, id, it.body, it.date, it.isMine)
                }
            }
            val combined = (convoRows + body).distinctBy { it.id }
            adapter.submit(combined, titles)
            binding.emptyLabel.visibility =
                if (combined.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun sanitize(q: String): String =
        q.replace("\"", " ").trim().split(Regex("\\s+")).joinToString(" ") { "$it*" }

    private fun open(row: SearchRow) {
        startActivity(Intent(this, ThreadActivity::class.java).apply {
            putExtra(ThreadActivity.EXTRA_CONVO_ID, row.convoId)
            putExtra(ThreadActivity.EXTRA_TARGET_MESSAGE_ID, row.id)
        })
    }

    inner class ResultAdapter(
        private val onOpen: (SearchRow) -> Unit
    ) : RecyclerView.Adapter<ResultAdapter.VH>() {

        private var items: List<SearchRow> = emptyList()
        private var titles: Map<Long, String> = emptyMap()

        fun submit(list: List<SearchRow>, t: Map<Long, String>) {
            items = list
            titles = t
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemSuggestionBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.root.isFocusable = true
            b.root.foreground = ThemeUtils.focusForeground(parent.context)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.b.suggestionName.text =
                (titles[r.convoId] ?: "") + "  \u00B7  " + Formatters.listStamp(r.date)
            holder.b.suggestionNumber.text = r.body.take(120)
            holder.itemView.setOnClickListener { onOpen(r) }
        }
    }
}
