package com.mobeen.launcher

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color

class SettingsActivity : Activity() {

    private val rows = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Launcher settings"

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(Color.BLACK)
        val pad = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(pad, pad, pad, pad)

        val heading = TextView(this)
        heading.text = "Launcher settings"
        heading.setTextColor(Color.WHITE)
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        if (Build.VERSION.SDK_INT >= 28) heading.isAccessibilityHeading = true
        layout.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        val list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> onRow(pos) }
        layout.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        rows.clear()
        if (LauncherUtil.isDefaultLauncher(this)) {
            rows.add("Default launcher: Yes, this app is your home screen")
        } else {
            rows.add("Set as default launcher (currently not the default)")
        }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        rows.add("Version $version")
        adapter.notifyDataSetChanged()
    }

    private fun onRow(pos: Int) {
        if (pos == 0) {
            if (LauncherUtil.isDefaultLauncher(this)) {
                Toast.makeText(this, "Already the default launcher", Toast.LENGTH_SHORT).show()
            } else {
                LauncherUtil.requestDefaultLauncher(this)
            }
        }
    }
}
