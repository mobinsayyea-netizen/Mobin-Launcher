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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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

data class AppEntry(val label: String, val pkg: String, val orig: String)
data class HomeItem(val kind: String, val id: String, val col: Int, val row: Int)
class Folder(val id: String, var name: String, val pkgs: MutableList<String>)

class MainActivity : Activity() {

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REQ_UNINSTALL = 2001
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var homeLayer: LinearLayout
    private lateinit var homeArea: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var listView: ListView
    private lateinit var selectBar: LinearLayout
    private lateinit var selectCount: TextView
    private lateinit var appsBtn: Button
    private lateinit var gestureDetector: GestureDetector
    private val favCells = arrayOfNulls<Button>(4)
    private val rowCells = ArrayList<Button>()
    private val adapter = AppAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var announceRunnable: Runnable? = null
    private var programmatic = false
    private var allApps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var homeItems: MutableList<HomeItem> = ArrayList()
    private val folders = LinkedHashMap<String, Folder>()
    private var selectionMode = false
    private val selected = LinkedHashSet<String>()
    private val uninstallQueue = ArrayDeque<String>()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || e2 == null) return false
                val dy = e1.y - e2.y
                val dx = Math.abs(e1.x - e2.x)
                if (dy > dp(100) && dy > dx * 1.5f && velocityY < -500f) {
                    openPanel(true)
                    return true
                }
                return false
            }
        })

        loadFolders()
        homeItems = loadHomeItems()
        buildUi()
        setContentView(root)
        loadApps()
        applyFilter()
        refreshFavorites()
        applyAppsButtonSetting()
        homeArea.post { renderHome() }

        if (!LauncherUtil.isDefaultLauncher(this) && !prefs.getBoolean("asked_default", false)) {
            prefs.edit().putBoolean("asked_default", true).apply()
            handler.postDelayed({ LauncherUtil.requestDefaultLauncher(this) }, 700)
        }
        maybeAutoCheckUpdate()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        applyFilter()
        refreshFavorites()
        applyAppsButtonSetting()
        renderHome()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while the app list is open -> back to the home screen
        if (panel.visibility == View.VISIBLE) closePanel(true)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // A launcher never closes itself; Back only closes selection / the app list.
        if (selectionMode) {
            endSelection(true)
            return
        }
        if (panel.visibility == View.VISIBLE) closePanel(true)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!prefs.getBoolean("apps_button", true) && panel.visibility != View.VISIBLE) {
            gestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNINSTALL) uninstallNext()
    }

    private fun maybeAutoCheckUpdate() {
        if (!prefs.getBoolean("auto_update", true)) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < 6L * 60L * 60L * 1000L) return
        prefs.edit().putLong("last_check", now).apply()
        Updater.check(this, false)
    }

    // ---------------------------------------------------------------- UI building

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

        // Home layer: free area for home screen apps + the favorites row at the bottom.
        // The area and the row are separate, with a gap, so they never touch.
        homeLayer = LinearLayout(this)
        homeLayer.orientation = LinearLayout.VERTICAL
        homeLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(homeLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        homeArea = FrameLayout(this)
        homeArea.addOnLayoutChangeListener { _, l, t, r, b, oL, oT, oR, oB ->
            if ((r - l) != (oR - oL) || (b - t) != (oB - oT)) homeArea.post { renderHome() }
        }
        val alp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        alp.bottomMargin = dp(12)
        homeLayer.addView(homeArea, alp)

        // Favorites row, left to right: favorite 1, favorite 2, APPS, favorite 3, favorite 4
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
        appsBtn = makeCell()
        appsBtn.text = "APPS"
        appsBtn.contentDescription = "Apps"
        appsBtn.setOnClickListener { openPanel(true) }
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
            rowCells.add(c)
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

        // Selection bar (only visible in selection mode)
        selectBar = LinearLayout(this)
        selectBar.orientation = LinearLayout.VERTICAL
        selectBar.visibility = View.GONE
        selectCount = TextView(this)
        selectCount.setTextColor(Color.WHITE)
        selectCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        selectCount.setPadding(dp(4), dp(4), dp(4), dp(4))
        selectBar.addView(selectCount, LinearLayout.LayoutParams(MATCH, WRAP))
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        val bAdd = makeButton()
        bAdd.text = "Add to home"
        bAdd.setOnClickListener { selectionAddToHome() }
        val bDel = makeButton()
        bDel.text = "Uninstall"
        bDel.setOnClickListener { selectionUninstall() }
        val bCancel = makeButton()
        bCancel.text = "Cancel selection"
        bCancel.setOnClickListener { endSelection(true) }
        for (b in listOf(bAdd, bDel, bCancel)) {
            val lp = LinearLayout.LayoutParams(0, dp(52), 1f)
            lp.setMargins(dp(3), 0, dp(3), dp(6))
            btnRow.addView(b, lp)
        }
        selectBar.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(selectBar, LinearLayout.LayoutParams(MATCH, WRAP))

        listView = ListView(this)
        listView.adapter = adapter
        listView.divider = ColorDrawable(Color.DKGRAY)
        listView.dividerHeight = 1
        listView.setOnItemClickListener { _, _, pos, _ ->
            val app = shown.getOrNull(pos)
            if (app != null) {
                if (selectionMode) toggleSelect(app) else launch(app)
            }
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { showAppMenu(it) }
            true
        }
        panel.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------------------------------------------------------------- settings applied to the home screen

    private val scrollDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.isScrollable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ) {
                openPanel(true)
                return true
            }
            return super.performAccessibilityAction(host, action, args)
        }
    }

    private fun applyAppsButtonSetting() {
        val on = prefs.getBoolean("apps_button", true)
        appsBtn.visibility = if (on) View.VISIBLE else View.GONE
        var prev: View? = null
        for (c in rowCells) {
            if (c.visibility != View.GONE) {
                c.accessibilityTraversalAfter = prev?.id ?: View.NO_ID
                prev = c
            }
        }
        if (on) {
            homeLayer.setAccessibilityDelegate(null)
        } else {
            homeLayer.setAccessibilityDelegate(scrollDelegate)
        }
        updateHomeA11y()
    }

    private fun updateHomeA11y() {
        val appsOn = prefs.getBoolean("apps_button", true)
        homeLayer.importantForAccessibility = when {
            panel.visibility == View.VISIBLE -> View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            !appsOn -> View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else -> View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    // ---------------------------------------------------------------- app list panel

    private fun openPanel(announceOpen: Boolean) {
        loadApps()
        programmatic = true
        searchBox.setText("")
        programmatic = false
        applyFilter()
        listView.setSelection(0)
        panel.visibility = View.VISIBLE
        updateHomeA11y()
        if (announceOpen) {
            announce("App list open")
            handler.postDelayed({
                searchBox.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }, 400)
        }
    }

    private fun closePanel(withAnnouncement: Boolean) {
        hideKeyboard(searchBox)
        endSelection(false)
        panel.visibility = View.GONE
        updateHomeA11y()
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
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val orig = ri.loadLabel(packageManager).toString()
                val custom = prefs.getString("label_$pkg", null)
                AppEntry(if (custom.isNullOrBlank()) orig else custom, pkg, orig)
            }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedWith(Comparator { a, b -> a.label.compareTo(b.label, ignoreCase = true) })
    }

    private fun applyFilter() {
        val q = searchBox.text.toString().trim()
        shown = if (q.isEmpty()) allApps else allApps.filter {
            it.label.contains(q, ignoreCase = true) || it.orig.contains(q, ignoreCase = true)
        }
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

    // ---------------------------------------------------------------- selection mode

    private fun updateSelectCount() {
        selectCount.text = "${selected.size} selected"
    }

    private fun startSelection(app: AppEntry) {
        if (panel.visibility != View.VISIBLE) openPanel(false)
        selectionMode = true
        selected.clear()
        selected.add(app.pkg)
        selectBar.visibility = View.VISIBLE
        updateSelectCount()
        adapter.notifyDataSetChanged()
        handler.postDelayed({ announce("Selection mode. ${app.label} selected") }, 400)
    }

    private fun endSelection(withAnnouncement: Boolean) {
        if (!selectionMode) return
        selectionMode = false
        selected.clear()
        selectBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
        if (withAnnouncement) announce("Selection cancelled")
    }

    private fun toggleSelect(app: AppEntry) {
        val nowSelected: Boolean
        if (selected.contains(app.pkg)) {
            selected.remove(app.pkg)
            nowSelected = false
        } else {
            selected.add(app.pkg)
            nowSelected = true
        }
        updateSelectCount()
        adapter.notifyDataSetChanged()
        announce("${app.label} " + (if (nowSelected) "selected" else "unselected") + ", ${selected.size} selected")
    }

    private fun selectionAddToHome() {
        var added = 0
        for (pkg in selected.toList()) {
            val app = allApps.firstOrNull { it.pkg == pkg } ?: continue
            if (isOnHome(app)) continue
            if (autoPlace(app) != null) added++ else break
        }
        endSelection(false)
        val msg = if (added > 0) "$added apps added to home" else "No apps added to home"
        handler.postDelayed({ announce(msg) }, 400)
    }

    private fun selectionUninstall() {
        for (pkg in selected.toList()) {
            val app = allApps.firstOrNull { it.pkg == pkg } ?: continue
            if (canUninstall(app)) uninstallQueue.addLast(pkg)
        }
        endSelection(false)
        if (uninstallQueue.isEmpty()) {
            handler.postDelayed({ announce("None of the selected apps can be uninstalled") }, 400)
        } else {
            uninstallNext()
        }
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
        uninstallQueue.addLast(app.pkg)
        uninstallNext()
    }

    @Suppress("DEPRECATION")
    private fun uninstallNext() {
        val pkg = uninstallQueue.removeFirstOrNull() ?: return
        try {
            startActivityForResult(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")), REQ_UNINSTALL)
        } catch (e: Exception) {
            announce("Cannot uninstall $pkg")
            uninstallNext()
        }
    }

    private fun showAppMenu(app: AppEntry) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (isOnHome(app)) {
            labels.add("Remove from home")
            actions.add { removeFromHome(app) }
        } else {
            labels.add("Add to home")
            actions.add { addToHome(app) }
        }
        if (canUninstall(app)) {
            labels.add("Uninstall")
            actions.add { uninstallApp(app) }
        }
        labels.add("Select")
        actions.add { startSelection(app) }
        labels.add("App info")
        actions.add { openAppInfo(app) }
        labels.add("Edit Icon")
        actions.add { showEditIcon(app) }
        labels.add("Edit app favorite position")
        actions.add { showPositionDialog(app) }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun textDialog(title: String, message: String?, initial: String, positive: String, onOk: (String) -> Unit) {
        val input = EditText(this)
        input.setText(initial)
        input.setSelectAllOnFocus(true)
        input.isSingleLine = true
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
        val b = AlertDialog.Builder(this).setTitle(title)
        if (message != null) b.setMessage(message)
        b.setView(box)
        b.setPositiveButton(positive) { _, _ -> onOk(input.text.toString().trim()) }
        b.setNegativeButton("Cancel", null)
        b.show()
    }

    private fun showEditIcon(app: AppEntry) {
        textDialog(
            "Edit icon: ${app.orig}",
            "Type a new name. Leave it empty to bring back the original name.",
            app.label,
            "Save"
        ) { n ->
            val e = prefs.edit()
            if (n.isEmpty() || n == app.orig) e.remove("label_${app.pkg}") else e.putString("label_${app.pkg}", n)
            e.apply()
            loadApps()
            applyFilter()
            refreshFavorites()
            renderHome()
            val shownName = if (n.isEmpty()) app.orig else n
            handler.postDelayed({ announce("Name changed to $shownName") }, 400)
        }
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

    // ---------------------------------------------------------------- home screen data

    private fun loadHomeItems(): MutableList<HomeItem> {
        val out = ArrayList<HomeItem>()
        val raw = prefs.getString("home_items", "") ?: ""
        for (line in raw.split("\n")) {
            val p = line.split("|")
            if (p.size == 4) {
                val c = p[2].toIntOrNull()
                val r = p[3].toIntOrNull()
                if (c != null && r != null) out.add(HomeItem(p[0], p[1], c, r))
            } else if (p.size == 3) {
                // older format: pkg|col|row
                val c = p[1].toIntOrNull()
                val r = p[2].toIntOrNull()
                if (c != null && r != null) out.add(HomeItem("A", p[0], c, r))
            }
        }
        return out
    }

    private fun saveHome() {
        val raw = homeItems.joinToString("\n") { "${it.kind}|${it.id}|${it.col}|${it.row}" }
        prefs.edit().putString("home_items", raw).apply()
    }

    private fun cleanName(name: String): String {
        val s = name.replace(Regex("[|,\n]"), " ").trim()
        return if (s.isEmpty()) "Folder" else s
    }

    private fun loadFolders() {
        folders.clear()
        val raw = prefs.getString("folders", "") ?: ""
        for (line in raw.split("\n")) {
            val p = line.split("|")
            if (p.size == 3 && p[0].isNotEmpty()) {
                val pk: MutableList<String> = if (p[2].isEmpty()) ArrayList() else ArrayList(p[2].split(","))
                folders[p[0]] = Folder(p[0], p[1], pk)
            }
        }
    }

    private fun saveFolders() {
        val raw = folders.values.joinToString("\n") {
            "${it.id}|${cleanName(it.name)}|${it.pkgs.joinToString(",")}"
        }
        prefs.edit().putString("folders", raw).apply()
    }

    private fun gridOn(): Boolean = prefs.getBoolean("grid_on", false)
    private fun foldersOn(): Boolean = prefs.getBoolean("folders_on", false)

    private fun gridCols(): Int = if (gridOn()) prefs.getInt("grid_cols", 4).coerceIn(2, 6) else 4

    private fun gridRows(): Int {
        if (gridOn()) return prefs.getInt("grid_rows", 5).coerceIn(2, 8)
        val h = homeArea.height
        return if (h <= 0) 6 else maxOf(1, h / dp(76))
    }

    private fun cellHeightPx(rows: Int): Int {
        val h = homeArea.height
        return if (gridOn() && h > 0) h / rows else dp(76)
    }

    private fun folderMembers(f: Folder): List<AppEntry> =
        f.pkgs.mapNotNull { p -> allApps.firstOrNull { it.pkg == p } }

    private fun isOnHome(app: AppEntry): Boolean =
        homeItems.any { it.kind == "A" && it.id == app.pkg } || folders.values.any { it.pkgs.contains(app.pkg) }

    private fun itemAt(col: Int, row: Int): HomeItem? =
        homeItems.firstOrNull { it.col == col && it.row == row }

    private fun itemName(item: HomeItem): String =
        if (item.kind == "F") (folders[item.id]?.name ?: "a") + " folder"
        else (allApps.firstOrNull { it.pkg == item.id }?.label ?: "an app")

    private fun pruneHome() {
        if (allApps.isEmpty()) return
        var changed = false
        val installed = allApps.map { it.pkg }.toSet()
        if (homeItems.removeAll { it.kind == "A" && !installed.contains(it.id) }) changed = true
        for (f in folders.values) {
            if (f.pkgs.removeAll { !installed.contains(it) }) changed = true
        }
        val emptyIds = folders.values.filter { it.pkgs.isEmpty() }.map { it.id }
        for (id in emptyIds) {
            folders.remove(id)
            homeItems.removeAll { it.kind == "F" && it.id == id }
            changed = true
        }
        if (changed) {
            saveHome()
            saveFolders()
        }
    }

    // ---------------------------------------------------------------- home screen drawing

    private fun renderHome() {
        pruneHome()
        homeArea.removeAllViews()
        val w = homeArea.width
        val h = homeArea.height
        if (w <= 0 || h <= 0) return
        val cols = gridCols()
        val rows = gridRows()
        val cellW = w / cols
        val cellH = cellHeightPx(rows)
        for (item in homeItems) {
            if (item.col >= cols || item.row >= rows) continue
            val b = makeButton()
            if (item.kind == "F") {
                val f = folders[item.id] ?: continue
                val n = folderMembers(f).size
                b.text = f.name
                b.contentDescription = "${f.name}, folder, $n apps"
                b.setOnClickListener { openFolder(f) }
                b.setOnLongClickListener {
                    showFolderMenu(f)
                    true
                }
            } else {
                val app = allApps.firstOrNull { it.pkg == item.id } ?: continue
                b.text = app.label
                b.tag = app
                b.setOnClickListener { launch(app) }
                b.setOnLongClickListener {
                    showAppMenu(app)
                    true
                }
                attachAppActions(b) { app }
            }
            val lp = FrameLayout.LayoutParams(cellW - dp(6), cellH - dp(6))
            lp.leftMargin = item.col * cellW + dp(3)
            lp.topMargin = item.row * cellH + dp(3)
            homeArea.addView(b, lp)
        }
    }

    private fun autoPlace(app: AppEntry): Pair<Int, Int>? {
        val cols = gridCols()
        val rows = gridRows()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (itemAt(c, r) == null) {
                    homeItems.add(HomeItem("A", app.pkg, c, r))
                    saveHome()
                    renderHome()
                    adapter.notifyDataSetChanged()
                    return Pair(c, r)
                }
            }
        }
        return null
    }

    private fun addToHome(app: AppEntry) {
        if (isOnHome(app)) {
            announce("${app.label} is already on the home screen")
            return
        }
        if (gridOn()) {
            chooseRow(app)
            return
        }
        val p = autoPlace(app)
        val msg = if (p != null) {
            "${app.label} added to home, row ${p.second + 1}, column ${p.first + 1}"
        } else {
            "Home screen is full"
        }
        handler.postDelayed({ announce(msg) }, 400)
    }

    private fun chooseRow(app: AppEntry) {
        val rows = gridRows()
        val items = Array(rows) { r -> "Row ${r + 1}" }
        AlertDialog.Builder(this)
            .setTitle("${app.label}: choose row")
            .setItems(items) { _, r -> chooseColumn(app, r) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseColumn(app: AppEntry, row: Int) {
        val cols = gridCols()
        val items = Array(cols) { c ->
            val occ = itemAt(c, row)
            "Column ${c + 1}" + (if (occ != null) ", now ${itemName(occ)}" else ", empty")
        }
        AlertDialog.Builder(this)
            .setTitle("${app.label}: row ${row + 1}, choose column")
            .setItems(items) { _, c -> placeAt(app, c, row) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun placeAt(app: AppEntry, col: Int, row: Int) {
        val occ = itemAt(col, row)
        if (occ == null) {
            homeItems.add(HomeItem("A", app.pkg, col, row))
            saveHome()
            renderHome()
            adapter.notifyDataSetChanged()
            handler.postDelayed({ announce("${app.label} added to home, row ${row + 1}, column ${col + 1}") }, 400)
            return
        }
        if (!foldersOn()) {
            handler.postDelayed({
                announce("Row ${row + 1}, column ${col + 1} is used by ${itemName(occ)}. Choose another place.")
            }, 400)
            return
        }
        if (occ.kind == "F") {
            val f = folders[occ.id]
            if (f != null) {
                f.pkgs.add(app.pkg)
                saveFolders()
                renderHome()
                adapter.notifyDataSetChanged()
                handler.postDelayed({ announce("${app.label} added to folder ${f.name}") }, 400)
            }
            return
        }
        val other = allApps.firstOrNull { it.pkg == occ.id }
        if (other == null) {
            homeItems.remove(occ)
            placeAt(app, col, row)
            return
        }
        textDialog("Create a folder", "Apps: ${other.label} and ${app.label}", "Folder", "Create") { name ->
            createFolder(name, other, app, col, row)
        }
    }

    private fun createFolder(name: String, a: AppEntry, b: AppEntry, col: Int, row: Int) {
        val id = "f" + System.currentTimeMillis()
        val f = Folder(id, cleanName(name), arrayListOf(a.pkg, b.pkg))
        folders[id] = f
        homeItems.removeAll { it.kind == "A" && it.id == a.pkg }
        homeItems.add(HomeItem("F", id, col, row))
        saveHome()
        saveFolders()
        renderHome()
        adapter.notifyDataSetChanged()
        handler.postDelayed({ announce("Folder ${f.name} created with ${a.label} and ${b.label}") }, 400)
    }

    private fun removeFromHome(app: AppEntry) {
        homeItems.removeAll { it.kind == "A" && it.id == app.pkg }
        for (f in folders.values) f.pkgs.remove(app.pkg)
        val emptyIds = folders.values.filter { it.pkgs.isEmpty() }.map { it.id }
        for (id in emptyIds) {
            folders.remove(id)
            homeItems.removeAll { it.kind == "F" && it.id == id }
        }
        saveHome()
        saveFolders()
        renderHome()
        adapter.notifyDataSetChanged()
        handler.postDelayed({ announce("${app.label} removed from home") }, 400)
    }

    // ---------------------------------------------------------------- folders

    private fun openFolder(f: Folder) {
        val members = folderMembers(f)
        if (members.isEmpty()) {
            announce("${f.name} folder is empty")
            return
        }
        val labels = members.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${f.name}, folder")
            .setItems(labels) { _, i -> launch(members[i]) }
            .setNeutralButton("Edit folder") { _, _ -> showFolderMenu(f) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFolderMenu(f: Folder) {
        val items = arrayOf("Rename folder", "Remove an app from folder", "Remove folder")
        AlertDialog.Builder(this)
            .setTitle("${f.name}, folder")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renameFolder(f)
                    1 -> removeAppFromFolder(f)
                    else -> removeFolder(f)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameFolder(f: Folder) {
        textDialog("Rename folder", null, f.name, "Save") { n ->
            f.name = cleanName(n)
            saveFolders()
            renderHome()
            handler.postDelayed({ announce("Folder renamed to ${f.name}") }, 400)
        }
    }

    private fun removeAppFromFolder(f: Folder) {
        val members = folderMembers(f)
        if (members.isEmpty()) return
        val labels = members.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Remove which app from ${f.name}?")
            .setItems(labels) { _, i ->
                val app = members[i]
                f.pkgs.remove(app.pkg)
                if (f.pkgs.isEmpty()) {
                    folders.remove(f.id)
                    homeItems.removeAll { it.kind == "F" && it.id == f.id }
                    saveHome()
                }
                saveFolders()
                renderHome()
                adapter.notifyDataSetChanged()
                handler.postDelayed({ announce("${app.label} removed from folder ${f.name}") }, 400)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeFolder(f: Folder) {
        folders.remove(f.id)
        homeItems.removeAll { it.kind == "F" && it.id == f.id }
        saveHome()
        saveFolders()
        renderHome()
        adapter.notifyDataSetChanged()
        handler.postDelayed({ announce("Folder ${f.name} removed") }, 400)
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

    // The long-press menu, also offered as direct TalkBack actions
    private fun attachAppActions(v: View, provider: () -> AppEntry?) {
        v.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val app = provider() ?: return
                val homeLabel = if (isOnHome(app)) "Remove from home" else "Add to home"
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_home, homeLabel))
                if (canUninstall(app)) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_uninstall, "Uninstall"))
                }
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_select, "Select"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_app_info, "App info"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_edit_icon, "Edit Icon"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_fav, "Edit app favorite position"))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val app = provider()
                if (app != null) {
                    if (action == R.id.action_home) {
                        if (isOnHome(app)) removeFromHome(app) else addToHome(app)
                        return true
                    }
                    if (action == R.id.action_uninstall) {
                        uninstallApp(app)
                        return true
                    }
                    if (action == R.id.action_select) {
                        startSelection(app)
                        return true
                    }
                    if (action == R.id.action_app_info) {
                        openAppInfo(app)
                        return true
                    }
                    if (action == R.id.action_edit_icon) {
                        showEditIcon(app)
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
            val isSel = selectionMode && selected.contains(app.pkg)
            tv.text = if (selectionMode) (if (isSel) "[x] " else "[ ] ") + app.label else app.label
            val parts = ArrayList<String>()
            if (selectionMode) parts.add(if (isSel) "selected" else "not selected")
            val fav = (1..4).firstOrNull { favPkg(it) == app.pkg }
            if (fav != null) parts.add("favorite position $fav")
            if (isOnHome(app)) parts.add("on home screen")
            tv.contentDescription = if (parts.isEmpty()) null else app.label + ", " + parts.joinToString(", ")
            attachAppActions(tv) { app }
            return tv
        }
    }
}
