package com.mobeen.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
data class HomeItem(val pkg: String, val col: Int, val row: Int)

class MainActivity : Activity() {

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val COLS = 4
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var homeLayer: LinearLayout
    private lateinit var homeArea: FrameLayout
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
    private var homeItems: MutableList<HomeItem> = ArrayList()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        homeItems = loadHomeItems()
        buildUi()
        setContentView(root)
        loadApps()
        applyFilter()
        refreshFavorites()
        homeArea.post { renderHome() }

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
        renderHome()
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
            (c.tag as? AppEntry)?.let { showAppMenu(it) }
            true
        }
        attachAppActions(c) { c.tag as? AppEntry }
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // Home layer: free area (home screen apps) + one row at the bottom.
        // Empty places contain no view at all, so TalkBack stays silent there.
        homeLayer = LinearLayout(this)
        homeLayer.orientation = LinearLayout.VERTICAL
        homeLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(homeLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        homeArea = FrameLayout(this)
        homeArea.addOnLayoutChangeListener { _, l, t, r, b, oL, oT, oR, oB ->
            if ((r - l) != (oR - oL) || (b - t) != (oB - oT)) homeArea.post { renderHome() }
        }
        homeLayer.addView(homeArea, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Row is left-to-right: favorite 1, favorite 2, APPS, favorite 3, favorite 4
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = View.LAYOUT_DIRECTION_LTR
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
        // TalkBack swipe order = left to right
        for (k in 1 until cells.size) {
            cells[k].accessibilityTraversalAfter = cells[k - 1].id
        }
        homeLayer.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

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
            shown.getOrNull(pos)?.let { showAppMenu(it) }
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

    // ---------------------------------------------------------------- long-press menu

    private fun canUninstall(app: AppEntry): Boolean {
        return try {
            val ai = packageManager.getApplicationInfo(app.pkg, 0)
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystem || isUpdatedSystem
        } catch (e: Exception) {
            false
        }
    }

    private fun openAppInfo(app: AppEntry) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        } catch (e: Exception) {
            announce("Cannot open app info for ${app.label}")
        }
    }

    private fun uninstallApp(app: AppEntry) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
        } catch (e: Exception) {
            announce("Cannot uninstall ${app.label}")
        }
    }

    private fun showAppMenu(app: AppEntry) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        labels.add("App info")
        actions.add { openAppInfo(app) }

        if (canUninstall(app)) {
            labels.add("Uninstall")
            actions.add { uninstallApp(app) }
        }

        if (isOnHome(app)) {
            labels.add("Remove from home screen")
            actions.add { removeFromHome(app) }
        } else {
            labels.add("Add to home screen")
            actions.add { addToHome(app) }
        }

        labels.add("Edit app favorite position")
        actions.add { showPositionDialog(app) }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Cancel", null)
            .show()
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

    // ---------------------------------------------------------------- home screen apps

    private fun loadHomeItems(): MutableList<HomeItem> {
        val out = ArrayList<HomeItem>()
        val raw = prefs.getString("home_items", "") ?: ""
        for (line in raw.split("\n")) {
            val p = line.split("|")
            if (p.size == 3) {
                val c = p[1].toIntOrNull()
                val r = p[2].toIntOrNull()
                if (c != null && r != null) out.add(HomeItem(p[0], c, r))
            }
        }
        return out
    }

    private fun saveHome() {
        val raw = homeItems.joinToString("\n") { "${it.pkg}|${it.col}|${it.row}" }
        prefs.edit().putString("home_items", raw).apply()
    }

    private fun isOnHome(app: AppEntry): Boolean = homeItems.any { it.pkg == app.pkg }

    private fun cellHeight(): Int = dp(76)

    private fun homeRows(): Int {
        val h = homeArea.height
        return if (h <= 0) 6 else maxOf(1, h / cellHeight())
    }

    private fun renderHome() {
        if (allApps.isNotEmpty()) {
            val before = homeItems.size
            homeItems.removeAll { h -> allApps.none { it.pkg == h.pkg } }
            if (homeItems.size != before) saveHome()
        }
        homeArea.removeAllViews()
        val w = homeArea.width
        val h = homeArea.height
        if (w <= 0 || h <= 0) return
        val cellW = w / COLS
        val cellH = cellHeight()
        val rows = homeRows()
        for (item in homeItems) {
            val app = allApps.firstOrNull { it.pkg == item.pkg } ?: continue
            if (item.row >= rows || item.col >= COLS) continue
            val b = makeButton()
            b.text = app.label
            b.tag = app
            b.setOnClickListener { launch(app) }
            b.setOnLongClickListener {
                showAppMenu(app)
                true
            }
            attachAppActions(b) { app }
            val lp = FrameLayout.LayoutParams(cellW - dp(6), cellH - dp(6))
            lp.leftMargin = item.col * cellW + dp(3)
            lp.topMargin = item.row * cellH + dp(3)
            homeArea.addView(b, lp)
        }
    }

    private fun addToHome(app: AppEntry) {
        if (isOnHome(app)) {
            announce("${app.label} is already on the home screen")
            return
        }
        val rows = homeRows()
        for (r in 0 until rows) {
            for (c in 0 until COLS) {
                if (homeItems.none { it.col == c && it.row == r }) {
                    homeItems.add(HomeItem(app.pkg, c, r))
                    saveHome()
                    renderHome()
                    adapter.notifyDataSetChanged()
                    val msg = "${app.label} added to home screen, row ${r + 1}, column ${c + 1}"
                    handler.postDelayed({ announce(msg) }, 400)
                    return
                }
            }
        }
        handler.postDelayed({ announce("Home screen is full") }, 400)
    }

    private fun removeFromHome(app: AppEntry) {
        homeItems.removeAll { it.pkg == app.pkg }
        saveHome()
        renderHome()
        adapter.notifyDataSetChanged()
        handler.postDelayed({ announce("${app.label} removed from home screen") }, 400)
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

    // The same menu as long press, but as direct TalkBack actions
    private fun attachAppActions(v: View, provider: () -> AppEntry?) {
        v.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val app = provider() ?: return
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_app_info, "App info"))
                if (canUninstall(app)) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_uninstall, "Uninstall"))
                }
                val homeLabel = if (isOnHome(app)) "Remove from home screen" else "Add to home screen"
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_home, homeLabel))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_fav, "Edit app favorite position"))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val app = provider()
                if (app != null) {
                    if (action == R.id.action_app_info) {
                        openAppInfo(app)
                        return true
                    }
                    if (action == R.id.action_uninstall) {
                        uninstallApp(app)
                        return true
                    }
                    if (action == R.id.action_home) {
                        if (isOnHome(app)) removeFromHome(app) else addToHome(app)
                        return true
                    }
                    if (action == R.id.action_fav) {
                        showPositionDialog(app)
                        return true
                    }
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
            val parts = ArrayList<String>()
            val fav = (1..4).firstOrNull { favPkg(it) == app.pkg }
            if (fav != null) parts.add("favorite position $fav")
            if (isOnHome(app)) parts.add("on home screen")
            tv.contentDescription = if (parts.isEmpty()) null else app.label + ", " + parts.joinToString(", ")
            attachAppActions(tv) { app }
            return tv
        }
    }
}
