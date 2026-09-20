package com.mobeen.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private class Row(val text: String, val action: (() -> Unit)?)

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: ArrayAdapter<String>
    private var rows: List<Row> = emptyList()
    private var screen = "main"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        screen = intent.getStringExtra("screen") ?: "main"
        val headingText = when (screen) {
            "default" -> "Default launcher"
            "updates" -> "Updates"
            "layout" -> "Home screen layout"
            "reset" -> "Reset"
            else -> "Launcher settings"
        }
        title = headingText

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(Color.BLACK)
        val pad = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(pad, pad, pad, pad)

        val heading = TextView(this)
        heading.text = headingText
        heading.setTextColor(Color.WHITE)
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        if (Build.VERSION.SDK_INT >= 28) heading.isAccessibilityHeading = true
        layout.addView(
            heading,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.action?.invoke() }
        layout.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        rows = buildRows()
        adapter.clear()
        adapter.addAll(rows.map { it.text })
        adapter.notifyDataSetChanged()
    }

    private fun open(target: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("screen", target))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setFlag(key: String, value: Boolean, message: String) {
        prefs.edit().putBoolean(key, value).apply()
        toast(message)
        refresh()
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Are you sure?")
            .setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun onOff(v: Boolean): String = if (v) "On. Tap to turn off" else "Off. Tap to turn on"

    private fun buildRows(): List<Row> {
        val list = ArrayList<Row>()
        when (screen) {
            "default" -> {
                if (LauncherUtil.isDefaultLauncher(this)) {
                    list.add(Row("Default launcher: Yes. This app is your home screen.", null))
                } else {
                    list.add(Row("Set as default launcher. Currently it is not the default.", { LauncherUtil.requestDefaultLauncher(this) }))
                }
            }
            "updates" -> {
                list.add(Row("Current version: ${Updater.installedName(this)}", null))
                list.add(Row("Check for updates", { Updater.check(this, true) }))
                val auto = prefs.getBoolean("auto_update", true)
                list.add(Row("Automatic check when the launcher opens: ${onOff(auto)}", {
                    setFlag("auto_update", !auto, "Automatic update check turned " + (if (!auto) "on" else "off"))
                }))
            }
            "layout" -> layoutRows(list)
            "reset" -> resetRows(list)
            else -> {
                list.add(Row("Default launcher", { open("default") }))
                list.add(Row("Updates", { open("updates") }))
                list.add(Row("Home screen layout", { open("layout") }))
                list.add(Row("Reset", { open("reset") }))
            }
        }
        return list
    }

    private fun layoutRows(list: MutableList<Row>) {
        val appsOn = prefs.getBoolean("apps_button", true)
        list.add(Row("APPS button: ${onOff(appsOn)}", {
            prefs.edit().putBoolean("apps_button", !appsOn).apply()
            if (appsOn) {
                AlertDialog.Builder(this)
                    .setTitle("APPS button turned off")
                    .setMessage("Open the app list by swiping up on the home screen. With TalkBack, swipe up with two fingers.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                toast("APPS button turned on")
            }
            refresh()
        }))

        val gridOn = prefs.getBoolean("grid_on", false)
        list.add(Row("Rows and columns: ${onOff(gridOn)}", {
            setFlag("grid_on", !gridOn, "Rows and columns turned " + (if (!gridOn) "on" else "off"))
        }))
        if (gridOn) {
            val cols = prefs.getInt("grid_cols", 4)
            val rowsN = prefs.getInt("grid_rows", 5)
            list.add(Row("Columns: $cols. Tap to change", { pickNumber("Columns", "grid_cols", 2, 6, cols) }))
            list.add(Row("Rows: $rowsN. Tap to change", { pickNumber("Rows", "grid_rows", 2, 8, rowsN) }))
        }

        val foldersOn = prefs.getBoolean("folders_on", false)
        list.add(Row("Folders: ${onOff(foldersOn)}", {
            if (!foldersOn && !prefs.getBoolean("grid_on", false)) {
                prefs.edit().putBoolean("grid_on", true).apply()
                setFlag("folders_on", true, "Folders turned on. Rows and columns were also turned on, because folders need them.")
            } else {
                setFlag("folders_on", !foldersOn, "Folders turned " + (if (!foldersOn) "on" else "off"))
            }
        }))
    }

    private fun pickNumber(title: String, key: String, min: Int, max: Int, current: Int) {
        val labels = ArrayList<String>()
        for (n in min..max) labels.add(if (n == current) "$n (current)" else "$n")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                prefs.edit().putInt(key, min + which).apply()
                Toast.makeText(
                    this,
                    "$title set to ${min + which}. Apps outside the grid stay hidden until it is big enough.",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetRows(list: MutableList<Row>) {
        list.add(Row("Remove all apps from home screen", {
            confirm("All apps and folders will be removed from the home screen.") {
                prefs.edit().remove("home_items").remove("folders").apply()
                toast("Home screen cleared")
            }
        }))
        list.add(Row("Remove all favorites", {
            confirm("All four favorite places will be emptied.") {
                val e = prefs.edit()
                for (n in 1..4) e.remove("fav_$n")
                e.apply()
                toast("Favorites cleared")
            }
        }))
        list.add(Row("Restore original app names", {
            confirm("All names you changed with Edit Icon will be restored.") {
                val e = prefs.edit()
                for (k in prefs.all.keys.toList()) {
                    if (k.startsWith("label_")) e.remove(k)
                }
                e.apply()
                toast("Original names restored")
            }
        }))
    }
}
