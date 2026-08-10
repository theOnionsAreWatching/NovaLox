package io.github.theonionsarewatching.nova.ui

import android.widget.EditText
import android.widget.GridView
import androidx.appcompat.app.AlertDialog
import io.github.theonionsarewatching.nova.R

/**
 * D-pad friendly emoji picker: categories first, then a grid; BACK from a
 * grid returns to the categories (user-specified flow). Selecting an emoji
 * types it at the cursor and closes everything.
 *
 * Curated set — every common emoji by category, minus the bikini (U+1F459),
 * excluded per instruction.
 */
object EmojiPicker {

    private val categories: List<Pair<String, List<String>>> = listOf(
        "Faces" to listOf(
            "\uD83D\uDE00","\uD83D\uDE01","\uD83D\uDE02","\uD83E\uDD23","\uD83D\uDE03","\uD83D\uDE04","\uD83D\uDE05","\uD83D\uDE06","\uD83D\uDE09","\uD83D\uDE0A","\uD83D\uDE0B","\uD83D\uDE0E","\uD83D\uDE0D","\uD83D\uDE18","\uD83D\uDE17","\uD83D\uDE19","\uD83D\uDE1A","\uD83D\uDE42","\uD83E\uDD17","\uD83E\uDD14","\uD83D\uDE10","\uD83D\uDE11","\uD83D\uDE36","\uD83D\uDE44","\uD83D\uDE0F","\uD83D\uDE23","\uD83D\uDE25","\uD83D\uDE2E","\uD83D\uDE10","\uD83D\uDE34","\uD83D\uDE31","\uD83D\uDE22","\uD83D\uDE2D","\uD83D\uDE24","\uD83D\uDE20","\uD83D\uDE21","\uD83E\uDD2C","\uD83E\uDD12","\uD83E\uDD15","\uD83E\uDD22","\uD83E\uDD27","\uD83D\uDE07","\uD83E\uDD20","\uD83E\uDD21","\uD83D\uDE48","\uD83D\uDE49","\uD83D\uDE4A"
        ),
        "Gestures & people" to listOf(
            "\uD83D\uDC4D","\uD83D\uDC4E","\uD83D\uDC4C","\u270C\uFE0F","\uD83E\uDD1E","\uD83E\uDD1F","\uD83E\uDD18","\uD83D\uDC4A","\u270A","\uD83D\uDC4F","\uD83D\uDE4C","\uD83D\uDC50","\uD83E\uDD32","\uD83E\uDD1D","\uD83D\uDE4F","\u270D\uFE0F","\uD83D\uDCAA","\uD83D\uDC4B","\uD83E\uDD19","\uD83D\uDD90\uFE0F","\uD83D\uDC66","\uD83D\uDC67","\uD83D\uDC68","\uD83D\uDC69","\uD83D\uDC74","\uD83D\uDC75","\uD83D\uDC76","\uD83E\uDDD1","\uD83D\uDC71","\uD83E\uDDD4","\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC66","\uD83D\uDC83","\uD83D\uDD7A","\uD83D\uDEB6","\uD83C\uDFC3"
        ),
        "Animals & nature" to listOf(
            "\uD83D\uDC36","\uD83D\uDC31","\uD83D\uDC2D","\uD83D\uDC39","\uD83D\uDC30","\uD83E\uDD8A","\uD83D\uDC3B","\uD83D\uDC3C","\uD83D\uDC28","\uD83D\uDC2F","\uD83E\uDD81","\uD83D\uDC2E","\uD83D\uDC37","\uD83D\uDC38","\uD83D\uDC35","\uD83D\uDC14","\uD83D\uDC27","\uD83D\uDC26","\uD83E\uDD86","\uD83E\uDD85","\uD83E\uDD89","\uD83E\uDD8B","\uD83D\uDC1D","\uD83D\uDC1E","\uD83D\uDC22","\uD83D\uDC0D","\uD83D\uDC19","\uD83D\uDC20","\uD83D\uDC2C","\uD83D\uDC33","\uD83D\uDC0B","\uD83D\uDC06","\uD83D\uDC18","\uD83E\uDD92","\uD83D\uDC2A","\uD83D\uDC0E","\uD83C\uDF32","\uD83C\uDF33","\uD83C\uDF34","\uD83C\uDF35","\uD83C\uDF3B","\uD83C\uDF39","\uD83C\uDF37","\uD83C\uDF41","\uD83C\uDF42","\u2600\uFE0F","\uD83C\uDF19","\u2B50","\uD83C\uDF08","\u2601\uFE0F","\u26C8\uFE0F","\u2744\uFE0F","\uD83D\uDD25","\uD83D\uDCA7","\uD83C\uDF0A"
        ),
        "Food & drink" to listOf(
            "\uD83C\uDF4E","\uD83C\uDF4C","\uD83C\uDF47","\uD83C\uDF53","\uD83C\uDF52","\uD83C\uDF51","\uD83C\uDF4D","\uD83E\uDD5D","\uD83C\uDF45","\uD83E\uDD51","\uD83C\uDF3D","\uD83E\uDD55","\uD83C\uDF5E","\uD83E\uDDC0","\uD83C\uDF57","\uD83C\uDF54","\uD83C\uDF5F","\uD83C\uDF55","\uD83C\uDF2D","\uD83C\uDF2E","\uD83C\uDF5C","\uD83C\uDF5D","\uD83C\uDF63","\uD83C\uDF66","\uD83C\uDF70","\uD83C\uDF6A","\uD83C\uDF6B","\uD83C\uDF7F","\u2615","\uD83E\uDD64","\uD83C\uDF75","\uD83E\uDDC3"
        ),
        "Activities & sports" to listOf(
            "\u26BD","\uD83C\uDFC0","\uD83C\uDFC8","\u26BE","\uD83C\uDFBE","\uD83C\uDFD0","\uD83C\uDFB3","\uD83C\uDFD3","\uD83E\uDD45","\u26F3","\uD83C\uDFA3","\uD83C\uDFBD","\uD83D\uDEB4","\uD83C\uDFCA","\uD83C\uDFC6","\uD83E\uDD47","\uD83E\uDD48","\uD83E\uDD49","\uD83C\uDFAF","\uD83C\uDFAE","\uD83C\uDFB2","\uD83C\uDFAD","\uD83C\uDFA8","\uD83C\uDFAC","\uD83C\uDFA4","\uD83C\uDFA7","\uD83C\uDFB8","\uD83C\uDFB9","\uD83C\uDFBA","\uD83E\uDD41","\uD83C\uDFC1"
        ),
        "Travel & places" to listOf(
            "\uD83D\uDE97","\uD83D\uDE95","\uD83D\uDE8C","\uD83D\uDE91","\uD83D\uDE92","\uD83D\uDE93","\uD83D\uDE9A","\uD83D\uDE9C","\uD83D\uDEB2","\uD83C\uDFCD\uFE0F","\u2708\uFE0F","\uD83D\uDE80","\uD83D\uDEA2","\u26F5","\uD83D\uDE82","\uD83D\uDE87","\uD83C\uDFE0","\uD83C\uDFE5","\uD83C\uDFEB","\uD83C\uDFE6","\u26EA","\uD83D\uDD4D","\uD83C\uDFD4\uFE0F","\uD83C\uDFD6\uFE0F","\uD83C\uDF06","\uD83C\uDF09","\uD83C\uDFAA"
        ),
        "Objects" to listOf(
            "\uD83D\uDCF1","\u260E\uFE0F","\uD83D\uDCBB","\u231A","\u23F0","\uD83D\uDCF7","\uD83D\uDCFA","\uD83D\uDCA1","\uD83D\uDD26","\uD83D\uDD0B","\uD83D\uDD0C","\uD83D\uDCB0","\uD83D\uDCB3","\uD83D\uDCE6","\u2709\uFE0F","\uD83D\uDCDD","\uD83D\uDCDA","\uD83D\uDCD6","\u2702\uFE0F","\uD83D\uDD11","\uD83D\uDD12","\uD83D\uDD28","\uD83E\uDE9B","\uD83D\uDEE0\uFE0F","\u2699\uFE0F","\uD83E\uDDF9","\uD83E\uDDFA","\uD83E\uDDFB","\uD83D\uDECD\uFE0F","\uD83C\uDF81","\uD83C\uDF88","\uD83C\uDF89"
        ),
        "Hearts & symbols" to listOf(
            "\u2764\uFE0F","\uD83E\uDDE1","\uD83D\uDC9B","\uD83D\uDC9A","\uD83D\uDC99","\uD83D\uDC9C","\uD83D\uDDA4","\uD83E\uDD0D","\uD83E\uDD0E","\uD83D\uDC94","\u2763\uFE0F","\uD83D\uDC95","\uD83D\uDC9E","\uD83D\uDC93","\uD83D\uDC97","\uD83D\uDC96","\uD83D\uDC98","\uD83D\uDC9D","\u2705","\u274C","\u2757","\u2753","\u26A0\uFE0F","\uD83D\uDEAB","\uD83D\uDCAF","\uD83D\uDD1E","\u267B\uFE0F","\u2B55","\uD83D\uDD14","\uD83C\uDF89","\uD83C\uDFB5","\uD83C\uDFB6","\uD83D\uDCA4","\uD83D\uDCA8","\uD83D\uDCA5","\uD83D\uDCAB","\uD83D\uDCA6"
        )
    )

    fun show(activity: BaseActivity, target: EditText) {
        showCategories(activity, target)
    }

    private fun showCategories(activity: BaseActivity, target: EditText) {
        // each category leads with a representative emoji (user request)
        val labels = categories.map { (name, emojis) -> emojis.first() + "  " + name }
        AlertDialog.Builder(activity)
            .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.attach_menu_emoji)))
            .setItems(labels.toTypedArray()) { _, which ->
                showGrid(activity, target, which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGrid(activity: BaseActivity, target: EditText, index: Int) {
        val (name, emojis) = categories[index]
        val grid = GridView(activity).apply {
            // adapt to the screen instead of a fixed 6 columns — narrow
            // QVGA displays clipped the last column (user report)
            val dm = activity.resources.displayMetrics
            numColumns = GridView.AUTO_FIT
            columnWidth = (44 * dm.density).toInt()
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            val pad = (6 * dm.density).toInt()
            setPadding(pad, pad, pad, pad)
            adapter = android.widget.ArrayAdapter(
                activity, R.layout.item_emoji, emojis
            )
        }
        val dialog = AlertDialog.Builder(activity)
            .setCustomTitle(Dialogs.title(activity, name))
            .setView(grid)
            .create()
        grid.setOnItemClickListener { _, _, pos, _ ->
            val e = emojis[pos]
            val at = target.selectionStart.coerceAtLeast(0)
            target.text.insert(at, e)
            dialog.dismiss()
        }
        // BACK from a grid returns to the categories (user-specified flow)
        dialog.setOnCancelListener { showCategories(activity, target) }
        dialog.show()
    }
}
