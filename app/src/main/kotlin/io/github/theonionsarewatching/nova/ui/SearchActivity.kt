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
        binding.btnSearchGo.setOnClickListener {
            run(binding.searchInput.text?.toString()?.trim().orEmpty())
        }
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

        ThemeUtils.applyFocusHighlightRound(binding.btnBack, binding.btnSearchGo)
        ThemeUtils.applyFocusHighlight(binding.searchInput)

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
            adapter.submit(combined, titles, query)
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
        private var query: String = ""

        fun submit(list: List<SearchRow>, t: Map<Long, String>, q: String) {
            items = list
            titles = t
            query = q
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemSuggestionBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.root.isFocusable = true
            b.root.background = ThemeUtils.focusFill(parent.context)
            b.root.foreground = ThemeUtils.focusStroke(parent.context)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            val title = titles[r.convoId] ?: ""
            holder.b.suggestionName.text = android.text.SpannableStringBuilder()
                .append(boldMatches(title, query))
                .append("  \u00B7  ")
                .append(Formatters.listStamp(r.date))
            // show a window of the body around the match so the bolded term is
            // visible even in a long message, not just the first 120 chars
            holder.b.suggestionNumber.text = boldMatches(snippetAround(r.body, query), query)
            holder.itemView.setOnClickListener { onOpen(r) }
        }

        /** A ~120-char window of the body centered on the first match. */
        private fun snippetAround(body: String, q: String): String {
            if (q.isBlank() || body.length <= 120) return body.take(120)
            val idx = body.indexOf(q, ignoreCase = true)
            if (idx < 0) return body.take(120)
            val start = (idx - 40).coerceAtLeast(0)
            val end = (start + 120).coerceAtMost(body.length)
            val prefix = if (start > 0) "\u2026" else ""
            val suffix = if (end < body.length) "\u2026" else ""
            return prefix + body.substring(start, end) + suffix
        }

        /** Bold every case-insensitive occurrence of the query in the text. */
        private fun boldMatches(text: String, q: String): CharSequence {
            if (q.isBlank()) return text
            val sp = android.text.SpannableString(text)
            val lower = text.lowercase()
            val needle = q.lowercase()
            var from = 0
            while (true) {
                val i = lower.indexOf(needle, from)
                if (i < 0) break
                sp.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    i, i + needle.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                from = i + needle.length
            }
            return sp
        }
    }
}
