package io.github.yuroyami.kiteaudio.verification

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView

/** One screen of text. The check starts once per process, when the activity first opens. */
class MainActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(32, 96, 32, 96)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(text) }
        setContentView(scroll)
        synchronized(Lines) { Lines.all.forEach(::append) }
        Lines.screen = this
        if (!Lines.started) {
            Lines.started = true
            Thread({ UnderrunCheck(Lines::add).run() }, "underrun-check").start()
        }
    }

    override fun onDestroy() {
        if (Lines.screen === this) Lines.screen = null
        super.onDestroy()
    }

    private fun append(line: String) {
        text.append(line + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** The lines of this process, so a new activity shows the lines of a run that is already going. */
    private object Lines {
        val all = mutableListOf<String>()
        @Volatile var screen: MainActivity? = null
        var started = false

        fun add(line: String) {
            synchronized(this) { all += line }
            screen?.let { activity -> activity.runOnUiThread { activity.append(line) } }
        }
    }
}
