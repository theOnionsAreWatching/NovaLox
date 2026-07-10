package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.theonionsarewatching.nova.R

object Dialogs {
    /** Dialog title with a hairline underneath, separating it from the list. */
    fun title(activity: Activity, text: String): View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(activity).apply {
            setText(text)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(18), dp(22), dp(12))
        }
        val line = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(ContextCompat.getColor(activity, R.color.divider))
        }
        box.addView(title)
        box.addView(line)
        return box
    }
}
