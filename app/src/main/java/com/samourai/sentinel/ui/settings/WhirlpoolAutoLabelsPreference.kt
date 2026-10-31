package com.samourai.sentinel.ui.settings

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceViewHolder
import com.samourai.sentinel.R

/**
 * Whirlpool auto-labels row. The XML title stays the plain fallback
 * ("Whirlpool auto-labels"). The bound title adds a smaller "experimental"
 * tag and stays on one line.
 */
class WhirlpoolAutoLabelsPreference(context: Context, attrs: AttributeSet?) :
    CheckBoxPreference(context, attrs) {

    init {
        setSingleLineTitle(true)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val titleView = holder.findViewById(android.R.id.title) as? TextView ?: return
        titleView.setSingleLine(true)
        titleView.setHorizontallyScrolling(false)
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.text = styledTitle(context)
    }

    companion object {
        fun styledTitle(context: Context): CharSequence {
            val base = context.getString(R.string.whirlpool_auto_labels_title)
            val tag = context.getString(R.string.whirlpool_auto_labels_experimental)
            val text = "$base $tag"
            val start = base.length + 1
            return SpannableString(text).apply {
                setSpan(
                    RelativeSizeSpan(0.75f),
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(context, R.color.v3_textColorSecondary),
                    ),
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }
}
