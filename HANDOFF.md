# Mobin Launcher — handoff notes (read this first in a new chat)

**हिंदी सार:** यह फ़ाइल नई चैट के लिए है। इसमें लॉन्चर का अब तक का काम, आपके फ़ैसले और आगे की सूची है। पहले `talkback-master` रेपो की `HANDOFF.md` में "user and how to work with him" वाला हिस्सा पढ़िए, वही यहाँ भी लागू है।

## 1. What it is
- A simple, accessible **Android home launcher** (Kotlin, no external libraries), package `com.mobeen.launcher`, `minSdk 24`, `targetSdk 34`. Built from Mobeen's old Lua script (an accessibility overlay) but as a **real launcher** (activity with HOME category).
- Text-only buttons, plain black screen, white text. **No theme, wallpaper or colours wanted.**
- Repo: `mobinsayyea-netizen/Mobin-Launcher` (public). Direct download of the latest build: https://github.com/mobinsayyea-netizen/Mobin-Launcher/releases/latest/download/MobinLauncher.apk
- **Updates install over the old app:** same package, same signing key (repo secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`=`launcher`, `KEY_PASSWORD`; certificate SHA-256 starts with `5a8b080bf5a8c3de`), `versionCode = GITHUB_RUN_NUMBER`. Never change these.
- CI: `.github/workflows/build.yml` runs `gradle assembleRelease`, publishes a release `build-N` with `MobinLauncher.apk`. Compile errors show up as GitHub annotations. `[skip ci]` in a commit message skips the build.
- Tokens: never store a token in a file. Ask him for a new one in the new chat (the old one was pasted into the old chat and should be deleted).

## 2. Done (latest release: see current build number in Releases)
- **Home screen:** favorites row directly above the phone's Back/Home/Overview buttons, left to right: `[fav1] [fav2] [APPS] [fav3] [fav4]`. Empty favorite slots are invisible to TalkBack (skipped silently); empty screen area is silent. The area above the row (home screen apps) is separate from the row, with a gap, so they never touch.
- **APPS list:** A–Z, working search, "Launcher settings" button, announcements ("App list open", counts). Back or Home closes it.
- **Long-press menu** on any app (list, favorites, home screen), also as TalkBack custom actions. Order: **Edit app favorite position** (first, so it's reached without swiping past everything else), Add/Remove from home, **Move to a new spot** (drag), Uninstall (hidden for system apps), Select, App info, Edit Icon. **Widgets: skipped on purpose** (too big).
- **Select** mode: tick several apps, then Add to home / Uninstall / Cancel selection.
- **Add to home:** auto-placed in the first free place; with **Rows and columns** on, it asks row then column; if the place is taken and **Folders** is on, a popup "Create a folder" shows both app names, a name field, Create and Cancel. Folders open as a list; rename/remove inside.
- **Move to a new spot (drag):** long-pressing any app (list, favorites, or home screen) directly starts a system drag (`View.startDragAndDrop`), the same as Google Now Launcher and other launchers — for sighted and TalkBack users alike. While the finger stays down and moves over the home area, announces the row/column and what's there each time it crosses into a new cell; lifting on an empty cell places it there; lifting on an occupied cell follows the same folder rules as "Add to home". If the finger is lifted without moving (a plain long-press, no drag), the app's menu opens instead, same as before this feature existed. The menu also keeps a "Move to a new spot" entry to restart the drag. **Not yet tried on his phone with real TalkBack gestures.**
- **Launcher settings** (categories): Default launcher, Updates, Home screen layout (APPS button on/off; Rows and columns off by default with column/row count; Folders off by default), Reset (home screen, favorites, renamed names).
- **APPS button off:** open the list by swiping up (with TalkBack: two-finger swipe up via scroll actions) — **untested**.
- **In-app updater** (`Updater.kt`): checks GitHub Releases, downloads the APK, installs with PackageInstaller (he must allow "install unknown apps" once). Auto-check when the launcher opens (at most every 6 hours, prompts once per new version).
- First launch asks to become the default home app (RoleManager).

## 3. Behaviour he specified (keep)
- Every user action gets a spoken announcement (open list, app name on long-press, "Edit app favorite position", added/removed messages…).
- Favorite 1 is on the **left**, favorite 4 on the **right** (he corrected the order once).
- Rows/columns and folders, the APPS-button option and everything else about layout live in the category **"Home screen layout"**.
- The user can also long-press an app and **place it anywhere on the screen**; while dragging, announce the position; on lifting the finger, announce success. (**Done as "Move to a new spot" — see section 2 — needs testing on his phone with real TalkBack gestures; exact TalkBack drag-gesture behaviour can vary by TalkBack version.**)

## 4. TODO
1. **Verify "Move to a new spot" on his phone**: does the double-tap-and-hold-then-drag TalkBack gesture actually reach `homeArea`'s `OnDragListener`? Do the row/column announcements fire as expected while moving? If TalkBack on his phone doesn't forward the drag this way, the fallback is the existing row/column-picker dialog (already works, reachable via "Add to home").
2. **Assistant:** long-press on Home should offer "select assistant"; the chosen assistant becomes permanent; "Select assistant" in Launcher settings changes it. Android handles the Home long-press itself, so the workable route is to register the launcher as the **default assistant** (VoiceInteractionService/ACTION_ASSIST) and forward to the chosen assistant. Test on his phone first (Moto G45).
3. **Verify on his phone** (nothing below was tried yet): TalkBack swipe order and silence on empty slots; swipe-up with APPS button off; in-app update install; folder creation; select mode; Edit Icon.
4. **Customization ideas he did not ask for** are NOT wanted (no theme/wallpaper/colours).
5. Widgets remain skipped unless he asks again.

## 5. Code map (`app/src/main/java/com/mobeen/launcher/`)
- `MainActivity.kt` — whole home screen: favorites, home area, APPS panel, menus, selection, folders, drag-to-move, settings hooks.
- `SettingsActivity.kt` — categorized settings (one screen per category via an intent extra).
- `Updater.kt` (+ `InstallReceiver`) — in-app update.
- `LauncherUtil.kt` — default-launcher check/request.
- Data is stored in SharedPreferences `launcher` (keys: `fav_1..4`, `home_items` as `kind|id|col|row` lines, `folders`, `label_<pkg>`, `apps_button`, `grid_on`, `grid_cols`, `grid_rows`, `folders_on`, `auto_update`, `last_check`, `notified_build`).
