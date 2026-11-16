package io.github.theonionsarewatching.nova.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SIM storage only. Never deletes the app database or the phone SMS inbox. */
class SimMessagesActivity : BaseActivity() {
    private var rows = emptyList<SimRecord>()
    private var target: SimTarget? = null
    private val reader by lazy { SimMessageReader(this) }
    private lateinit var list: ListView
    private lateinit var status: TextView
    private var busy = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "SIM messages"; textSize = 16f; gravity = android.view.Gravity.CENTER
            setTextColor(ThemeUtils.accentColor(this@SimMessagesActivity)); setPadding(8, 8, 8, 8)
        })
        status = TextView(this).apply { textSize = 13f; setPadding(8, 4, 8, 4) }
        root.addView(status)
        list = ListView(this).apply { divider = null }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val keys = io.github.theonionsarewatching.nova.databinding.ViewSoftkeyBarBinding.inflate(layoutInflater)
        root.addView(keys.root); setContentView(root)
        softkeys = Softkeys(this, keys).also { it.set("Back", "Read", "Reload",
            onLeft = { finish() }, onCenter = { if (!busy) rows.getOrNull(list.selectedItemPosition)?.let { read(it) } },
            onRight = { if (!busy) chooseSim() }) }
        list.setOnItemClickListener { _, _, pos, _ -> if (!busy) rows.getOrNull(pos)?.let { read(it) } }
        chooseSim()
    }
    private fun failure(code: SimFailure) = when (code) {
        SimFailure.ROLE -> "Set D-SMS as the default SMS app first."
        SimFailure.PHONE_PERMISSION -> "Phone permission missing. Check app permissions."
        SimFailure.SMS_PERMISSION -> "SMS permission missing. Check app permissions."
        SimFailure.NO_ACTIVE -> "No active SIM subscription reported. Check SIM settings."
        SimFailure.NO_LIST -> "SIM returned no list after retry. It may be empty, or the read failed. Reload to retry."
        SimFailure.DENIED -> "SIM access denied by the phone. Check SMS/Phone permissions. System access may be required."
        SimFailure.UNSUPPORTED -> "This phone rejected the SIM reading route."
        SimFailure.SCHEMA -> "SIM provider returned an unsupported message format."
        SimFailure.STALE -> "SIM selection changed or did not match. Reload and choose again."
        SimFailure.PROVIDER -> "SIM provider could not complete the read. Reload to retry."
    } + "\nCode: ${code.name}"

    private fun chooseSim() {
        if (busy) return
        rows = emptyList(); list.adapter = null; target = null
        try {
            reader.gate()?.let { status.text = failure(it); return }
            val sims = reader.active()
            if (sims.isEmpty()) { status.text = failure(SimFailure.NO_ACTIVE); return }
            fun select(pos: Int) {
                try {
                    reader.gate()?.let { status.text = failure(it); return }
                    val chosen = sims.getOrNull(pos) ?: return
                    if (!SimReadPolicy.matches(chosen, reader.active())) { status.text = failure(SimFailure.STALE); return }
                    target = chosen
                    reload(chosen)
                } catch (_: SecurityException) { status.text = failure(SimFailure.DENIED) }
                catch (_: Exception) { status.text = failure(SimFailure.PROVIDER) }
            }
            if (sims.size == 1) select(0) else {
                status.text = "Choose the SIM to read."
                DsmsAlertBuilder(this).setTitle("Choose SIM")
                    .setItems(sims.map { "SIM ${it.slot + 1}" }.toTypedArray()) { _, pos -> select(pos) }.show()
            }
        } catch (_: SecurityException) { status.text = failure(SimFailure.DENIED) }
        catch (_: Exception) { status.text = failure(SimFailure.PROVIDER) }
    }
    private fun reload(chosen: SimTarget? = target) {
        if (chosen == null) return
        busy = true; rows = emptyList(); list.adapter = null; status.text = "Reading SIM ${chosen.slot + 1}…"
        lifecycleScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { reader.read(chosen) }) {
                    is SimReadResult.Failure -> status.text = failure(result.code)
                    is SimReadResult.Records -> {
                        rows = result.rows
                        status.text = if (rows.isEmpty()) "No messages stored on SIM ${chosen.slot + 1}."
                            else "${rows.size} messages on SIM ${chosen.slot + 1}" +
                                if (rows.any { !deletable(it) }) " • some records read-only" else ""
                        list.adapter = object : ArrayAdapter<SimRecord>(this@SimMessagesActivity, android.R.layout.simple_list_item_2, android.R.id.text1, rows) {
                            override fun getView(pos: Int, old: View?, parent: android.view.ViewGroup): View {
                                val v = super.getView(pos, old, parent)
                                v.findViewById<TextView>(android.R.id.text1).apply { text = rows[pos].address.ifBlank { "Unknown sender" }; textSize = 16f }
                                v.findViewById<TextView>(android.R.id.text2).apply { text = rows[pos].body; textSize = 13f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
                                v.background = ThemeUtils.dialogRowBackground(this@SimMessagesActivity)
                                return v
                            }
                        }
                        list.requestFocus()
                    }
                }
            } finally { busy = false }
        }
    }
    private fun deletable(row: SimRecord) = row.target == target && row.target.cardId >= 0 &&
        (row.index ?: 0) > 0 && rows.count { it.index == row.index } == 1

    private fun read(row: SimRecord) {
        val dialog = DsmsAlertBuilder(this).setTitle(row.address.ifBlank { "SIM message" })
            .setView(Dialogs.scrollableMessageText(this, row.body))
            .setPositiveButton(android.R.string.ok, null)
        if (deletable(row)) dialog.setNeutralButton("Delete from SIM") { _, _ ->
            DsmsAlertBuilder(this).setTitle("Delete from SIM ${row.target.slot + 1}?")
                .setMessage("Permanently delete this message from the selected SIM? This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> deleteConfirmed(row) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        dialog.show()
    }
    private fun deleteConfirmed(row: SimRecord) {
        if (busy) return
        val selected = target
        busy = true
        lifecycleScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { reader.delete(row, selected) }
                DsmsToast.makeText(this@SimMessagesActivity, message, Toast.LENGTH_LONG).show()
            } finally { busy = false; reload() }
        }
    }
}
