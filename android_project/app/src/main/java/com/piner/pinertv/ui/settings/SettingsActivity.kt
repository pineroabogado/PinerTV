package com.piner.pinertv.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.piner.pinertv.R
import com.piner.pinertv.data.HomeLayoutPrefs
import com.piner.pinertv.data.SecurePrefs
import com.piner.pinertv.data.SubtitleStylePrefs
import com.piner.pinertv.ui.library.LibraryReportActivity

class SettingsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = SecurePrefs(this)
        val home = HomeLayoutPrefs(this)
        val subs = SubtitleStylePrefs(this)

        val editUrl = findViewById<EditText>(R.id.editUrl)
        val editHost = findViewById<EditText>(R.id.editHost)
        val editUser = findViewById<EditText>(R.id.editUser)
        val editPass = findViewById<EditText>(R.id.editPass)
        val editMac = findViewById<EditText>(R.id.editMac)
        val editBroadcast = findViewById<EditText>(R.id.editBroadcast)

        editUrl.setText(prefs.nasUrl)
        editHost.setText(prefs.nasHost)
        editUser.setText(prefs.nasUser)
        editPass.setText(prefs.nasPass)
        editMac.setText(prefs.nasMac)
        editBroadcast.setText(prefs.nasBroadcast)
        editUrl.requestFocus()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.nasUrl = editUrl.text.toString().trim()
            prefs.nasHost = editHost.text.toString().trim()
            prefs.nasUser = editUser.text.toString().trim()
            prefs.nasPass = editPass.text.toString()
            prefs.nasMac = editMac.text.toString().trim()
            prefs.nasBroadcast = editBroadcast.text.toString().trim()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_LONG).show()
            finish()
        }

        findViewById<Button>(R.id.btnLibraryReport).setOnClickListener {
            startActivity(Intent(this, LibraryReportActivity::class.java))
        }

        bindHomeLayout(home)
        bindSubtitleStyle(subs)
    }

    private fun bindHomeLayout(home: HomeLayoutPrefs) {
        val container = findViewById<LinearLayout>(R.id.rowHomeLayout)
        fun refresh() {
            container.removeAllViews()
            home.order().forEach { row ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                }
                val label = TextView(this).apply {
                    text = when (row) {
                        HomeLayoutPrefs.RowId.CONTINUE -> getString(R.string.row_continue)
                        HomeLayoutPrefs.RowId.MOVIES -> getString(R.string.row_movies)
                        HomeLayoutPrefs.RowId.SERIES -> getString(R.string.row_series)
                    } + " · " + if (home.isVisible(row)) getString(R.string.show_row) else getString(R.string.hide_row)
                    setTextColor(getColor(R.color.emby_text))
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnVis = Button(this).apply {
                    text = if (home.isVisible(row)) "On" else "Off"
                    isFocusable = true
                    setOnClickListener {
                        home.setVisible(row, !home.isVisible(row))
                        refresh()
                    }
                }
                val btnUp = Button(this).apply {
                    text = "↑"
                    isFocusable = true
                    setOnClickListener {
                        home.moveUp(row)
                        refresh()
                    }
                }
                val btnDown = Button(this).apply {
                    text = "↓"
                    isFocusable = true
                    setOnClickListener {
                        home.moveDown(row)
                        refresh()
                    }
                }
                line.addView(label)
                line.addView(btnVis)
                line.addView(btnUp)
                line.addView(btnDown)
                container.addView(line)
            }
        }
        refresh()
    }

    private fun bindSubtitleStyle(subs: SubtitleStylePrefs) {
        val lbl = findViewById<TextView>(R.id.lblSubSize)
        fun paintSize() {
            lbl.text = getString(R.string.sub_size) + ": ${subs.sizePercent}%"
        }
        paintSize()
        findViewById<Button>(R.id.btnSubSizeDown).setOnClickListener {
            subs.sizePercent = subs.sizePercent - 10
            paintSize()
        }
        findViewById<Button>(R.id.btnSubSizeUp).setOnClickListener {
            subs.sizePercent = subs.sizePercent + 10
            paintSize()
        }
        findViewById<Button>(R.id.btnSubWhite).setOnClickListener { subs.textColorKey = "white" }
        findViewById<Button>(R.id.btnSubYellow).setOnClickListener { subs.textColorKey = "yellow" }
        findViewById<Button>(R.id.btnSubCyan).setOnClickListener { subs.textColorKey = "cyan" }
        findViewById<Button>(R.id.btnSubBgNone).setOnClickListener { subs.backgroundKey = "none" }
        findViewById<Button>(R.id.btnSubBgSemi).setOnClickListener { subs.backgroundKey = "semi" }
        findViewById<Button>(R.id.btnSubBgSolid).setOnClickListener { subs.backgroundKey = "solid" }
    }
}
