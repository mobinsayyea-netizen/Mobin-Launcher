# Mobin Launcher — handoff notes (read this first in a new chat)

**हिंदी सार:** यह फ़ाइल नई चैट के लिए है। इसमें लॉन्चर का अब तक का काम, आपके फ़ैसले और आगे की सूची है। पहले `talkback-master` रेपो की `HANDOFF.md` में "user and how to work with him" वाला हिस्सा पढ़िए, वही यहाँ भी लागू है।

## 1. What it is
- A simple, accessible **Android home launcher** (Kotlin, no external libraries), package `com.mobeen.launcher`, `minSdk 24`, `targetSdk 34`. Built from Mobeen's old Lua script (an accessibility overlay) but as a **real launcher** (activity with HOME category).
- Text-only buttons, plain black screen, white text. **No theme, wallpaper or colours wanted.**
- Repo: `mobinsayyea-netizen/Mobin-Launcher` (public). Direct download of the latest build: https://github.com/mobinsayyea-netizen/Mobin-Launcher/releases/latest/download/MobinLauncher.apk
- **Updates install over the old app:** same package, same signing key (repo secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`=`launcher`, `KEY_PASSWORD`; certificate SHA-256 starts with `5a8b080bf5a8c3de`), `versionCode = GITHUB_RUN_NUMBER`. Never change these.
- CI: `.github/workflows/build.yml` runs `gradle assembleRelease`, publishes a release `build-N` with `MobinLauncher.apk`. Compile errors show up as GitHub annotations. `[skip ci]` in a commit message skips the build.
- Tokens: never store a token in a file. Ask him for a new one in the new chat (the old one was pasted into the old chat and should be deleted).

## 2. Done (latest release: 1.0.4, build 4)
- **Home screen:** favorites row directly above the phone's Back/Home/Overview buttons, left to right: `[fav1] [fav2] [APPS] [fav3] [fav4]`. Empty favorite slots are invisible to TalkBack (skipped silently); empty screen area is silent. The area above the row (home screen apps) is separate from the row, with a gap, so they never touch.
- **APPS list:** A–Z, working search, "Launcher settings" button, announcements ("App list open", counts). Back or Home closes it.
- **Long-press menu** on any app (list, favorites, home screen), also as TalkBack custom actions: Add to home / Remove from home, Uninstall (hidden for system apps), Select, App info, Edit Icon (rename the label), Edit app favorite position. **Widgets: skipped on purpose** (too big).
- **Select** mode: tick several apps, then Add to home / Uninstall / Cancel selection.
- **Add to home:** auto-placed in the first free place; with **Rows and columns** on, it asks row then column; if the place is taken and **Folders** is on, a popup "Create a folder" shows both app names, a name field, Create and Cancel. Folders open as a list; rename/remove inside.
- **Launcher settings** (categories): Default launcher, Updates, Home screen layout (APPS button on/off; Rows and columns off by default with column/row count; Folders off by default), Reset (home screen, favorites, renamed names).
- **APPS button off:** open the list by swiping up (with TalkBack: two-finger swipe up via scroll actions) — **untested**.
- **In-app updater** (`Updater.kt`): checks GitHub Releases, downloads the APK, installs with PackageInstaller (he must allow "install unknown apps" once). Auto-check when the launcher opens (at most every 6 hours, prompts once per new version).
- First launch asks to become the default home app (RoleManager).

## 3. Behaviour he specified (keep)
- Every user action gets a spoken announcement (open list, app name on long-press, "Edit app favorite position", added/removed messages…).
- Favorite 1 is on the **left**, favorite 4 on the **right** (he corrected the order once).
- Rows/columns and folders, the APPS-button option and everything else about layout live in the category **"Home screen layout"**.
- The user can also long-press an app and **place it anywhere on the screen**; while dragging, announce the position; on lifting the finger, announce success. (**Not done — see TODO.**)

## 4. TODO
1. **Move/drag placement** of an app anywhere on the home screen, with announcements while the finger moves and a success message on release (and a non-drag way for TalkBack users). Then folders can also be made by dropping one app on another.
2. **Assistant:** long-press on Home should offer "select assistant"; the chosen assistant becomes permanent; "Select assistant" in Launcher settings changes it. Android handles the Home long-press itself, so the workable route is to register the launcher as the **default assistant** (VoiceInteractionService/ACTION_ASSIST) and forward to the chosen assistant. Test on his phone first (Moto G45).
3. **Verify on his phone** (nothing below was tried yet): TalkBack swipe order and silence on empty slots; swipe-up with APPS button off; in-app update install; folder creation; select mode; Edit Icon.
4. **Customization ideas he did not ask for** are NOT wanted (no theme/wallpaper/colours).
5. Widgets remain skipped unless he asks again.

## 5. Code map (`app/src/main/java/com/mobeen/launcher/`)
- `MainActivity.kt` — whole home screen: favorites, home area, APPS panel, menus, selection, folders, settings hooks.
- `SettingsActivity.kt` — categorized settings (one screen per category via an intent extra).
- `Updater.kt` (+ `InstallReceiver`) — in-app update.
- `LauncherUtil.kt` — default-launcher check/request.
- Data is stored in SharedPreferences `launcher` (keys: `fav_1..4`, `home_items` as `kind|id|col|row` lines, `folders`, `label_<pkg>`, `apps_button`, `grid_on`, `grid_cols`, `grid_rows`, `folders_on`, `auto_update`, `last_check`, `notified_build`).
