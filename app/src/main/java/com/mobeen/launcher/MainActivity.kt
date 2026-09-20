package com.mobeen.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

data class AppEntry(val label: String, val pkg: String)

class MainActivity : Activity() {

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var homeLayer: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var listView: ListView
    private val favCells = arrayOfNulls<Button>(4)
    private val adapter = AppAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var announceRunnable: Runnable? = null
    private var programmatic = false
    private var allApps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        buildUi()
        setContentView(root)
        loadApps()
        applyFilter()
        refreshFavorites()

        if (!LauncherUtil.isDefaultLauncher(this) && !prefs.getBoolean("asked_default", false)) {
            prefs.edit().putBoolean("asked_default", true).apply()
            handler.postDelayed({ LauncherUtil.requestDefaultLauncher(this) }, 700)
        }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        applyFilter()
        refreshFavorites()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while the app list is open -> back to the home screen
        if (panel.visibility == View.VISIBLE) closePanel(true)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // A launcher never closes itself; Back only closes the app list.
        if (panel.visibility == View.VISIBLE) closePanel(true)
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun makeButton(): Button {
        val b = Button(this)
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        b.minWidth = 0
        b.minHeight = 0
        b.stateListAnimator = null
        b.maxLines = 2
        b.ellipsize = TextUtils.TruncateAt.END
        b.setPadding(dp(2), dp(2), dp(2), dp(2))
        val bg = GradientDrawable()
        bg.setColor(Color.TRANSPARENT)
        bg.setStroke(dp(2), Color.WHITE)
        bg.cornerRadius = dp(8).toFloat()
        b.background = bg
        return b
    }

    private fun makeCell(): Button {
        val c = makeButton()
        val lp = LinearLayout.LayoutParams(0, dp(64), 1f)
        lp.setMargins(dp(3), 0, dp(3), 0)
        c.layoutParams = lp
        return c
    }

    private fun setupFavCell(c: Button) {
        c.setOnClickListener { (c.tag as? AppEntry)?.let { launch(it) } }
        c.setOnLongClickListener {
            (c.tag as? AppEntry)?.let { showPositionDialog(it) }
            true
        }
        attachEditAction(c) { c.tag as? AppEntry }
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // Home layer: empty screen + one row at the bottom. Empty area stays silent for TalkBack.
        homeLayer = FrameLayout(this)
        homeLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(homeLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        // Row is right-to-left: first child is rightmost.
        // Order of children: favorite 1, favorite 2, APPS, favorite 3, favorite 4
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        row.setPadding(dp(4), dp(4), dp(4), dp(8))

        val cells = ArrayList<Button>()
        for (n in 1..2) {
            val c = makeCell()
            setupFavCell(c)
            favCells[n - 1] = c
            cells.add(c)
        }
        val appsBtn = makeCell()
        appsBtn.text = "APPS"
        appsBtn.contentDescription = "Apps"
        appsBtn.setOnClickListener { openPanel() }
        cells.add(appsBtn)
        for (n in 3..4) {
            val c = makeCell()
            setupFavCell(c)
            favCells[n - 1] = c
            cells.add(c)
        }
        for (c in cells) {
            c.id = View.generateViewId()
            row.addView(c)
        }
        // TalkBack swipe order = same as the child order (right to left)
        for (k in 1 until cells.size) {
            cells[k].accessibilityTraversalAfter = cells[k - 1].id
        }
        homeLayer.addView(row, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // App list panel
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.BLACK)
        panel.isClickable = true
        panel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        panel.visibility = View.GONE
        panel.setPadding(dp(8), dp(8), dp(8), dp(8))

        searchBox = EditText(this)
        searchBox.hint = "Search apps"
        searchBox.setHintTextColor(Color.LTGRAY)
        searchBox.setTextColor(Color.WHITE)
        searchBox.isSingleLine = true
        searchBox.inputType = InputType.TYPE_CLASS_TEXT
        searchBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
                if (!programmatic) scheduleCountAnnouncement()
            }
        })
        searchBox.setOnEditorActionListener { v, _, _ ->
            hideKeyboard(v)
            true
        }
        panel.addView(searchBox, LinearLayout.LayoutParams(MATCH, WRAP))

        val settingsBtn = makeButton()
        settingsBtn.text = "Launcher settings"
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        val slp = LinearLayout.LayoutParams(MATCH, dp(52))
        slp.setMargins(0, dp(6), 0, dp(6))
        panel.addView(settingsBtn, slp)

        listView = ListView(this)
        listView.adapter = adapter
        listView.divider = ColorDrawable(Color.DKGRAY)
        listView.dividerHeight = 1
        listView.setOnItemClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { launch(it) }
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { showPositionDialog(it) }
            true
        }
        panel.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------------------------------------------------------------- app list panel

    private fun openPanel() {
        loadApps()
        programmatic = true
        searchBox.setText("")
        programmatic = false
        applyFilter()
        listView.setSelection(0)
        panel.visibility = View.VISIBLE
        homeLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        announce("App list open")
        handler.postDelayed({
            searchBox.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }, 400)
    }

    private fun closePanel(withAnnouncement: Boolean) {
        hideKeyboard(searchBox)
        panel.visibility = View.GONE
        homeLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (withAnnouncement) announce("Home screen")
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = packageManager.queryIntentActivities(i, 0)
        allApps = found
            .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedWith(Comparator { a, b -> a.label.compareTo(b.label, ignoreCase = true) })
    }

    private fun applyFilter() {
        val q = searchBox.text.toString().trim()
        shown = if (q.isEmpty()) allApps else allApps.filter { it.label.contains(q, ignoreCase = true) }
        adapter.notifyDataSetChanged()
    }

    private fun scheduleCountAnnouncement() {
        announceRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            announce(if (shown.isEmpty()) "No apps found" else "${shown.size} apps")
        }
        announceRunnable = r
        handler.postDelayed(r, 900)
    }

    // ---------------------------------------------------------------- favorites

    private fun favPkg(n: Int): String? = prefs.getString("fav_$n", null)

    private fun setFav(n: Int, pkg: String?) {
        val e = prefs.edit()
        if (pkg == null) e.remove("fav_$n") else e.putString("fav_$n", pkg)
        e.apply()
    }

    private fun labelOf(pkg: String?): String? =
        if (pkg == null) null else allApps.firstOrNull { it.pkg == pkg }?.label

    private fun refreshFavorites() {
        for (n in 1..4) {
            val cell = favCells[n - 1] ?: continue
            val app = favPkg(n)?.let { p -> allApps.firstOrNull { it.pkg == p } }
            if (app == null) {
                cell.tag = null
                cell.text = ""
                cell.contentDescription = null
                cell.visibility = View.INVISIBLE   // keeps the slot, invisible to TalkBack and touch
            } else {
                cell.tag = app
                cell.text = app.label
                cell.visibility = View.VISIBLE
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun showPositionDialog(app: AppEntry) {
        val current = (1..4).firstOrNull { favPkg(it) == app.pkg }
        val items = ArrayList<String>()
        for (n in 1..4) {
            val occupant = labelOf(favPkg(n))
            var s = "Favorite position $n"
            s += if (occupant != null) ", now $occupant" else ", empty"
            if (current == n) s += ", current"
            items.add(s)
        }
        if (current != null) items.add("Remove from favorites")

        AlertDialog.Builder(this)
            .setTitle("${app.label}: Edit app favorite position")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < 4) assignFavorite(app, which + 1) else removeFavorite(app)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun assignFavorite(app: AppEntry, pos: Int) {
        for (n in 1..4) if (favPkg(n) == app.pkg) setFav(n, null)
        val old = labelOf(favPkg(pos))
        setFav(pos, app.pkg)
        refreshFavorites()
        var msg = "${app.label} added to favorite position $pos"
        if (old != null && old != app.label) msg += ", replacing $old"
        handler.postDelayed({ announce(msg) }, 400)
    }

    private fun removeFavorite(app: AppEntry) {
        for (n in 1..4) if (favPkg(n) == app.pkg) setFav(n, null)
        refreshFavorites()
        handler.postDelayed({ announce("${app.label} removed from favorites") }, 400)
    }

    // ---------------------------------------------------------------- helpers

    private fun launch(app: AppEntry) {
        val intent = packageManager.getLaunchIntentForPackage(app.pkg)
        if (intent == null) {
            announce("Cannot open ${app.label}")
            return
        }
        if (panel.visibility == View.VISIBLE) closePanel(false)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun announce(msg: String) {
        root.announceForAccessibility(msg)
    }

    // TalkBack actions menu entry (same thing as long press)
    private fun attachEditAction(v: View, provider: () -> AppEntry?) {
        v.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(R.id.action_edit_fav, "Edit app favorite position")
                )
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == R.id.action_edit_fav) {
                    provider()?.let { showPositionDialog(it) }
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        })
    }

    // ---------------------------------------------------------------- list adapter

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).also {
                it.setTextColor(Color.WHITE)
                it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                it.setPadding(dp(16), dp(14), dp(16), dp(14))
                it.minHeight = dp(56)
                it.gravity = Gravity.CENTER_VERTICAL
            }
            val app = shown[position]
            tv.text = app.label
            val fav = (1..4).firstOrNull { favPkg(it) == app.pkg }
            tv.contentDescription = if (fav != null) "${app.label}, favorite position $fav" else null
            attachEditAction(tv) { app }
            return tv
        }
    }
}
