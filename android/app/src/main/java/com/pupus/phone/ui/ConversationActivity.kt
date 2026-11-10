package com.pupus.phone.ui

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pupus.phone.R
import com.pupus.phone.voice.ConversationHistory
import com.pupus.phone.voice.ConversationTurn
import com.pupus.phone.voice.VoiceBus

/**
 * Full text of the current conversation: user turns on the right, complete
 * assistant replies on the left, selectable and copyable. Updates live while
 * open and follows the newest text unless the user scrolled up to read.
 */
class ConversationActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView
    private val shown = ArrayList<TurnViews>()
    private var isFirstRender = true

    private val historyListener = VoiceBus.HistoryListener { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation)
        scroll = findViewById(R.id.conversationScroll)
        container = findViewById(R.id.turnsContainer)
        emptyText = findViewById(R.id.emptyText)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        AppForeground.onResume()
        VoiceBus.addHistoryListener(historyListener)
    }

    override fun onPause() {
        AppForeground.onPause()
        VoiceBus.removeHistoryListener(historyListener)
        super.onPause()
    }

    private fun render(history: ConversationHistory) {
        val shouldFollow = isFirstRender || isNearBottom()
        isFirstRender = false
        emptyText.visibility = if (history.isEmpty) View.VISIBLE else View.GONE
        // Turns are only appended or dropped from the head (cap): rebuild when the head moved.
        val isSamePrefix = shown.size <= history.turns.size &&
            shown.indices.all { shown[it].id == history.turns[it].id }
        if (!isSamePrefix) {
            container.removeAllViews()
            shown.clear()
        }
        history.turns.forEachIndexed { index, turn ->
            val views = shown.getOrNull(index) ?: addTurn(turn.id)
            views.bind(turn)
        }
        if (shouldFollow) scroll.post { scroll.scrollTo(0, container.bottom) }
    }

    private fun isNearBottom(): Boolean {
        val distance = container.bottom - (scroll.scrollY + scroll.height - scroll.paddingBottom)
        return distance <= dp(NEAR_BOTTOM_DP)
    }

    private fun addTurn(id: String): TurnViews {
        val user = bodyText().apply {
            setBackgroundResource(R.drawable.bg_bubble_user)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            maxWidth = (resources.displayMetrics.widthPixels * USER_BUBBLE_WIDTH).toInt()
        }
        val statuses = TextView(this).apply {
            setTextColor(getColor(R.color.text_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, STATUS_SP)
            setTextIsSelectable(true)
        }
        val reply = bodyText().apply { setLineSpacing(0f, REPLY_LINE_SPACING) }
        container.addView(user, params(Gravity.END, top = dp(20)))
        container.addView(statuses, params(Gravity.START, top = dp(8)))
        container.addView(reply, params(Gravity.START, top = dp(8)))
        return TurnViews(id, user, statuses, reply).also { shown.add(it) }
    }

    private fun bodyText() = TextView(this).apply {
        setTextColor(getColor(R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
        setTextIsSelectable(true)
    }

    private fun params(gravity: Int, top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        this.gravity = gravity
        topMargin = top
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Views of one turn; text is only reset when it changed, so a selection survives updates. */
    private inner class TurnViews(
        val id: String,
        private val user: TextView,
        private val statuses: TextView,
        private val reply: TextView,
    ) {
        fun bind(turn: ConversationTurn) {
            setIfChanged(user, turn.userText)
            val statusText = turn.statuses.joinToString("\n") { "· $it" }
            setIfChanged(statuses, statusText)
            statuses.visibility = if (statusText.isEmpty()) View.GONE else View.VISIBLE
            val hasReply = turn.reply.isNotEmpty()
            setIfChanged(reply, if (hasReply) turn.reply else getString(R.string.conversation_waiting))
            reply.setTextColor(getColor(if (hasReply) R.color.text_primary else R.color.text_tertiary))
        }

        private fun setIfChanged(view: TextView, text: String) {
            if (view.text.toString() != text) view.text = text
        }
    }

    private companion object {
        const val BODY_SP = 20f
        const val STATUS_SP = 14f
        const val REPLY_LINE_SPACING = 1.2f
        const val USER_BUBBLE_WIDTH = 0.8f
        const val NEAR_BOTTOM_DP = 48
    }
}
