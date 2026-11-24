package ru.cosmos.launcher

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.ListView
import android.widget.BaseAdapter
import android.view.Gravity
import android.text.TextWatcher
import android.text.Editable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

class MainActivity : Activity() {
    private var cosmosField: CosmosField? = null
    private lateinit var root: FrameLayout
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private var meteoredWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var meteoredWidgetView: AppWidgetHostView? = null
    private var pendingWidgetInfo: AppWidgetProviderInfo? = null
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val widgetHostId = 27427
    private val requestBindWidget = 27428
    private val requestConfigureWidget = 27429

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep Android status/navigation bars available for notifications,
        // gesture navigation, quick settings and system controls.
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.rgb(5, 12, 30)
        window.navigationBarColor = Color.rgb(5, 12, 30)
        root = FrameLayout(this)
        cosmosField = CosmosField()
        root.addView(cosmosField!!, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, widgetHostId)
        meteoredWidgetId = getSharedPreferences("cosmos_launcher", MODE_PRIVATE)
            .getInt("meteored_widget_id", AppWidgetManager.INVALID_APPWIDGET_ID)
        if (meteoredWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) attachSavedWidget()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        try { widgetHost.startListening() } catch (_: Exception) { }
        cosmosField?.refreshInstalledApps()
    }

    override fun onStop() {
        try { widgetHost.stopListening() } catch (_: Exception) { }
        super.onStop()
    }

    @Deprecated("Use system back dispatcher on newer Android versions")
    override fun onBackPressed() {
        if (cosmosField?.closeFolderOnBack() == true) return
        // As the HOME app, Back should not close the launcher or reveal an empty task.
    }

    private fun attachSavedWidget() {
        val info = try { widgetManager.getAppWidgetInfo(meteoredWidgetId) } catch (_: Exception) { null }
        if (info == null) {
            meteoredWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            getSharedPreferences("cosmos_launcher", MODE_PRIVATE).edit()
                .remove("meteored_widget_id").apply()
            return
        }
        showWidget(info)
    }

    private fun showWidget(info: AppWidgetProviderInfo) {
        val view = widgetHost.createView(this, meteoredWidgetId, info)
        view.setAppWidget(meteoredWidgetId, info)
        meteoredWidgetView?.let { oldView -> root.removeView(oldView) }
        meteoredWidgetView = view
        root.addView(view, FrameLayout.LayoutParams(1, 1))
        cosmosField?.invalidate()
    }

    private fun chooseMeteoredWidget() {
        val providers = widgetManager.installedProviders.filter {
            it.provider.packageName.contains("meteored", true) ||
                it.loadLabel(packageManager).toString().contains("meteored", true)
        }
        if (providers.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Виджет Meteored")
                .setMessage("Android не нашёл виджет Meteored. Проверь, что приложение Meteored установлено и предоставляет виджет.")
                .setPositiveButton("Открыть Meteored") { _, _ ->
                    val pkg = allInstalledPackageForMeteored()
                    val launchIntent = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
                    if (launchIntent != null) startActivity(launchIntent)
                    else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.meteored.com/")))
                }
                .setNegativeButton("Закрыть", null).show()
            return
        }
        val labels = providers.map { info ->
            val label = try { info.loadLabel(packageManager).toString() } catch (_: Exception) { "Meteored" }
            label + " — " + info.minWidth + "×" + info.minHeight
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выбери виджет Meteored")
            .setItems(labels) { _, index -> bindMeteoredWidget(providers[index]) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun allInstalledPackageForMeteored(): String? =
        packageManager.getInstalledPackages(0).firstOrNull {
            it.packageName.contains("meteored", true)
        }?.packageName

    private fun bindMeteoredWidget(info: AppWidgetProviderInfo) {
        pendingWidgetId = widgetHost.allocateAppWidgetId()
        pendingWidgetInfo = info
        val allowed = try {
            widgetManager.bindAppWidgetIdIfAllowed(pendingWidgetId, info.provider)
        } catch (_: Exception) { false }
        if (allowed) finishWidgetBinding()
        else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            }
            startActivityForResult(intent, requestBindWidget)
        }
    }

    private fun finishWidgetBinding() {
        val info = pendingWidgetInfo ?: return
        meteoredWidgetId = pendingWidgetId
        val configure = info.configure
        if (configure != null) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, meteoredWidgetId)
            }
            startActivityForResult(intent, requestConfigureWidget)
        } else saveBoundWidget(info)
    }

    private fun saveBoundWidget(info: AppWidgetProviderInfo) {
        getSharedPreferences("cosmos_launcher", MODE_PRIVATE).edit()
            .putInt("meteored_widget_id", meteoredWidgetId).apply()
        showWidget(info)
        pendingWidgetInfo = null
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    @Deprecated("Handled for Android widget binding and optional widget configuration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestBindWidget) {
            if (resultCode == RESULT_OK && pendingWidgetInfo != null) finishWidgetBinding()
            else {
                if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
                    widgetHost.deleteAppWidgetId(pendingWidgetId)
                pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                pendingWidgetInfo = null
            }
        } else if (requestCode == requestConfigureWidget) {
            val info = pendingWidgetInfo
            if (resultCode == RESULT_OK && info != null) saveBoundWidget(info)
            else {
                if (meteoredWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
                    widgetHost.deleteAppWidgetId(meteoredWidgetId)
                meteoredWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                pendingWidgetInfo = null
            }
        }
    }

    private data class AppEntry(val title: String, val packageName: String, val icon: Bitmap, val launchUrl: String? = null, val launchIntent: Intent? = null)
    private data class Orb(
        val apps: MutableList<AppEntry>,
        var x: Float = 0f,
        var y: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var page: Int = 0,
        var folderName: String = "Папка",
        var forceFolder: Boolean = false
    ) {
        val isFolder: Boolean get() = forceFolder || apps.size > 1
        val radius: Float get() = if (isFolder) 43f else 34f
    }

    private inner class CosmosField : View(this) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbs = mutableListOf<Orb>()
        private val allApps = mutableListOf<AppEntry>()
        private val dock = mutableListOf<AppEntry>()
        private var currentPage = 0
        private var openedFolder: Orb? = null
        private var folderScrollOffset = 0f
        private var folderDownY = 0f
        private var folderDownOffset = 0f
        private var folderTouchMoved = false
        private var folderTouchIndex = -1
        private var folderHeaderTapped = false
        private var folderItemLongPressed = false
        private var previousFrame = 0L
        private var selected: Orb? = null
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false
        private var dragging = false
        private var longPressTriggered = false
        private var edgeSwitchArmed = true
        private val touchHandler = Handler(Looper.getMainLooper())
        private var dragOffsetX = 0f
        private var dragOffsetY = 0f
        private var swipeHandled = false
        private var initialized = false
        private var layoutRestored = false
        private val deletePill = RectF()
        private val weatherWidgetRect = RectF()
        private val edgeSwitchZone get() = 34f * d
        private val longPressAction = Runnable {
            if (selected != null && !moved && openedFolder == null) {
                dragging = true
                longPressTriggered = true
                selected?.let { it.vx = 0f; it.vy = 0f }
                invalidate()
            }
        }
        private val folderItemLongPressAction = Runnable {
            val folder = openedFolder
            val index = folderTouchIndex
            if (folder != null && !folderTouchMoved && index in folder.apps.indices) {
                folderItemLongPressed = true
                showFolderAppActions(folder, index)
            }
        }

        init {
            isClickable = true
            loadApps()
        }

        private fun queryInstalledApps(): List<AppEntry> {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return packageManager.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { info ->
                    AppEntry(
                        info.loadLabel(packageManager).toString(),
                        info.activityInfo.packageName,
                        drawableToBitmap(info.loadIcon(packageManager))
                    )
                }
                .sortedBy { it.title.lowercase() }
        }

        private fun loadApps() {
            allApps.clear()
            allApps.addAll(queryInstalledApps())
            val used = mutableSetOf<String>()
            fun find(vararg words: String): AppEntry? = allApps.firstOrNull { app ->
                app.packageName !in used && words.any { word ->
                    app.title.contains(word, ignoreCase = true) ||
                        app.packageName.contains(word, ignoreCase = true)
                }
            }
            fun take(vararg words: String): AppEntry? = find(*words)?.also { used += it.packageName }
            fun addApp(page: Int, vararg words: String) {
                take(*words)?.let { orbs += Orb(mutableListOf(it), page = page) }
            }
            fun addFolder(name: String, page: Int, groups: List<List<String>>) {
                val members = mutableListOf<AppEntry>()
                groups.forEach { words ->
                    val match = allApps.firstOrNull { app ->
                        dock.none { it.packageName == app.packageName } &&
                            members.none { it.packageName == app.packageName } &&
                            words.any { word ->
                                app.title.contains(word, ignoreCase = true) ||
                                    app.packageName.contains(word, ignoreCase = true)
                            }
                    }
                    match?.let(members::add)
                }
                used.addAll(members.map { it.packageName })
                if (members.isNotEmpty()) orbs += Orb(members, page = page, folderName = name, forceFolder = true)
            }
            // Four fixed dock positions. Resolve system apps by launcher label first,
            // then by their Android standard intent, and always retain a visible slot.
            val dockPrefs = getSharedPreferences("cosmos_launcher", MODE_PRIVATE)
            val savedDock = dockPrefs.getString("dock_packages", null)?.split("|").orEmpty()
            fun normalized(value: String) = value.lowercase().replace(" ", "").replace("_", "")
            fun fallbackIcon(glyph: String): Bitmap {
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = Color.rgb(28, 72, 132)
                canvas.drawCircle(48f, 48f, 45f, p)
                p.color = Color.WHITE
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                p.textSize = if (glyph.length > 2) 24f else 42f
                canvas.drawText(glyph, 48f, 62f, p)
                return bitmap
            }
            fun resolveDockSlot(index: Int, title: String, glyph: String,
                                labelAliases: List<String>, packageAliases: List<String>,
                                fallbackIntent: Intent) {
                val labels = labelAliases.map(::normalized).toSet()
                val packages = packageAliases.toSet()
                val saved = savedDock.getOrNull(index)?.let { pkg ->
                    allApps.firstOrNull {
                        it.packageName == pkg &&
                            (it.packageName in packages || normalized(it.title) in labels)
                    }
                }
                val matched = saved ?: allApps.firstOrNull { app ->
                    app.packageName in packages || normalized(app.title) in labels
                }
                val resolvedInfo = if (matched == null) {
                    try { packageManager.resolveActivity(fallbackIntent, 0)?.activityInfo }
                    catch (_: Exception) { null }
                } else null
                val resolved = if (matched != null) matched else if (resolvedInfo != null) {
                    AppEntry(
                        resolvedInfo.loadLabel(packageManager).toString().ifBlank { title },
                        resolvedInfo.packageName,
                        drawableToBitmap(resolvedInfo.loadIcon(packageManager)),
                        launchIntent = Intent(fallbackIntent).setPackage(resolvedInfo.packageName)
                    )
                } else {
                    AppEntry(title, "cosmos:dock:$index", fallbackIcon(glyph), launchIntent = fallbackIntent)
                }
                val unique = if (dock.any { it.packageName == resolved.packageName }) {
                    AppEntry(title, "cosmos:dock:$index", fallbackIcon(glyph), launchIntent = fallbackIntent)
                } else resolved
                dock.add(unique)
            }
            resolveDockSlot(0, "Телефон", "☎",
                listOf("Телефон", "Phone", "Dialer", "Вызовы", "Звонки"),
                listOf("com.google.android.dialer", "com.android.dialer", "com.huawei.contacts",
                    "com.huawei.phone", "com.android.phone"),
                Intent(Intent.ACTION_DIAL))
            resolveDockSlot(1, "Сообщения", "SMS",
                listOf("Сообщения", "Messages", "SMS", "Message"),
                listOf("com.google.android.apps.messaging", "com.android.mms", "com.huawei.message"),
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING))
            resolveDockSlot(2, "Chrome", "C",
                listOf("Chrome"), listOf("com.android.chrome"),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).setPackage("com.android.chrome"))
            resolveDockSlot(3, "Галерея", "▧",
                listOf("Галерея", "Gallery", "Photos", "Фото", "Альбом"),
                listOf("com.huawei.photos", "com.android.gallery3d", "com.google.android.apps.photos"),
                Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_GALLERY"))
            dockPrefs.edit().putString("dock_packages", dock.joinToString("|") { it.packageName }).apply()

            // Desktop 1: the exact folder names and independent shortcuts from the reference.
            addApp(0, "cosmos notes", "cosmosnotes")
            addFolder("27", 0, listOf(
                listOf("youtube"), listOf("вконтакте", "vk"),
                listOf("viber"), listOf("telegram"),
                listOf("reader", "книги", "book"),
                listOf("cosmos connect", "cosmosconnect"),
                listOf("shazam"), listOf("whatsapp"),
                listOf("яндекс карты", "maps")
            ))
            addApp(0, "camera", "камера")
            addFolder("Google", 0, listOf(
                listOf("gmail"), listOf("google maps", "карты"),
                listOf("drive"), listOf("youtube"),
                listOf("google photos"), listOf("play store"),
                listOf("google"), listOf("google calendar", "календарь"),
                listOf("google keep", "keep")
            ))
            addFolder("Инструменты", 0, listOf(
                listOf("калькулятор", "calculator"), listOf("часы", "clock"),
                listOf("погода", "weather"), listOf("диктофон", "recorder"),
                listOf("компас", "compass"), listOf("заметки", "notes"),
                listOf("календарь", "calendar"), listOf("файлы", "files", "file manager"),
                listOf("контакты", "contacts")
            ))
            addApp(0, "настройки", "settings")

            // Desktop 2: named folders and apps from the second reference screen.
            addFolder("Игры", 1, listOf(
                listOf("game"), listOf("игр"), listOf("play games")
            ))
            orbs.firstOrNull { it.isFolder && it.folderName == "Игры" }?.let { gamesFolder ->
                allApps.filter { app ->
                    try {
                        packageManager.getApplicationInfo(app.packageName, 0).category ==
                            android.content.pm.ApplicationInfo.CATEGORY_GAME
                    } catch (_: Exception) { false }
                }.forEach { app ->
                    if (gamesFolder.apps.none { it.packageName == app.packageName }) {
                        gamesFolder.apps.add(app)
                    }
                }
            }
            addApp(1, "vpn наружу", "vpn")
            addFolder("ОФИС", 1, listOf(
                listOf("word"), listOf("excel"), listOf("powerpoint"),
                listOf("wps"), listOf("pdf"), listOf("office")
            ))
            addFolder("Хонор", 1, listOf(
                listOf("honor"), listOf("health"), listOf("тема", "themes"),
                listOf("device clone")
            ))
            addFolder("Разное", 1, listOf(
                listOf("zoom"), listOf("maxim"), listOf("qr"),
                listOf("go"), listOf("яндекс")
            ))
            addApp(1, "offradio")
            addFolder("Магазин", 1, listOf(
                listOf("wildberries", "wb"), listOf("ozon"),
                listOf("яндекс маркет"), listOf("магазин")
            ))
            addApp(1, "халва", "совкомбанк")
            addApp(1, "qwen")
            addApp(1, "яндекс браузер", "yandex browser")
            addApp(1, "obsidian")
            addApp(1, "гигачат", "gigachat")
            addApp(1, "chatgpt")
            addApp(1, "алиса ai", "alice")
            addApp(1, "capcut")
            addApp(1, "deepseek")

            // The reference "Разное" folder contains a full 3x3 set. Fill its remaining
            // slots with installed apps that have not been assigned to another desktop orb.
            orbs.firstOrNull { it.isFolder && it.folderName == "Разное" }?.let { miscFolder ->
                val alreadyPlaced = (dock.map { it.packageName } +
                    orbs.filter { it !== miscFolder }.flatMap { it.apps }.map { it.packageName }).toMutableSet()
                allApps.filter { it.packageName !in alreadyPlaced }
                    .take((9 - miscFolder.apps.size).coerceAtLeast(0))
                    .forEach { app ->
                        if (miscFolder.apps.none { it.packageName == app.packageName }) {
                            miscFolder.apps.add(app)
                            used.add(app.packageName)
                        }
                    }
            }

            // Desktop 3: one app, two browser bookmarks and a Meteored weather panel.
            addApp(2, "buddy")
            val iconSource = allApps.firstOrNull()?.icon
                ?: Bitmap.createBitmap((96*d).toInt(), (96*d).toInt(), Bitmap.Config.ARGB_8888)
            fun bookmark(title: String, url: String): AppEntry {
                val bitmap = Bitmap.createBitmap(iconSource.width, iconSource.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(bitmap)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = Color.rgb(25, 83, 150)
                c.drawCircle(bitmap.width/2f, bitmap.height/2f, bitmap.width*.47f, p)
                p.color = Color.WHITE
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                p.textSize = bitmap.width*.38f
                c.drawText("↗", bitmap.width/2f, bitmap.height*.64f, p)
                return AppEntry(title, "url:$url", bitmap, url)
            }
            orbs += Orb(mutableListOf(bookmark("Шантарж", "https://shahmaty-mistikov.tuqo.app/#steps")), page = 2)
            orbs += Orb(mutableListOf(bookmark("CosmosA27", "https://cosmos27.netlify.app/")), page = 2)
            // Unmatched installed apps stay available in the "+" app picker.
            // Never dump the entire unrecognized app list onto desktop 2.
        }
        // Called whenever the launcher returns to the foreground. Newly installed
        // apps become their own moving spheres; saved folders and positions are untouched.
        fun refreshInstalledApps() {
            if (!layoutRestored) return
            val fresh = queryInstalledApps()
            val freshByPackage = fresh.associateBy { it.packageName }
            allApps.clear()
            allApps.addAll(fresh)

            val known = getSharedPreferences("cosmos_launcher", MODE_PRIVATE)
                .getStringSet("known_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
            // Drop shortcuts for apps that were uninstalled; keep web bookmarks intact.
            orbs.forEach { orb ->
                orb.apps.removeAll { it.launchUrl == null && it.packageName !in freshByPackage }
            }
            orbs.removeAll { it.apps.isEmpty() }
            // Keep dock slots and their order even if an app is temporarily unavailable.
            // A newly installed app must never take over an existing dock position.
            known.retainAll(freshByPackage.keys)

            val placed = (dock.map { it.packageName } + orbs.flatMap { it.apps }.map { it.packageName }).toMutableSet()

            // Refresh labels/icons for apps already present without changing their positions.
            orbs.forEach { orb ->
                for (i in orb.apps.indices) {
                    val updated = freshByPackage[orb.apps[i].packageName]
                    if (updated != null && orb.apps[i].launchUrl == null) orb.apps[i] = updated
                }
            }
            for (i in dock.indices) freshByPackage[dock[i].packageName]?.let { dock[i] = it }

            val newlyInstalled = fresh.filter { it.packageName !in known && it.packageName !in placed }
            newlyInstalled.forEachIndexed { index, app ->
                val count = orbs.count { it.page == currentPage }
                val col = count % 4
                val row = count / 4
                val minY = 104f*d
                val maxY = (height - 126f*d).coerceAtLeast(minY + 60f*d)
                val x = (width * (0.13f + col * 0.245f)).coerceIn(34f*d, width - 34f*d)
                val y = (minY + row * 76f*d + index * 9f*d).coerceIn(minY, maxY)
                val angle = (app.packageName.hashCode() % 360) * (Math.PI / 180.0)
                val speed = (164f + (index % 4) * 14f) * d
                orbs += Orb(mutableListOf(app), x, y, cos(angle).toFloat()*speed,
                    sin(angle).toFloat()*speed, page = currentPage)
                placed += app.packageName
            }
            getSharedPreferences("cosmos_launcher", MODE_PRIVATE).edit()
                .putStringSet("known_packages", fresh.map { it.packageName }.toSet())
                .apply()
            if (newlyInstalled.isNotEmpty()) saveLayout()
            invalidate()
        }

        private fun saveKnownPackages() {
            getSharedPreferences("cosmos_launcher", MODE_PRIVATE).edit()
                .putStringSet("known_packages", allApps.map { it.packageName }.toSet())
                .apply()
        }

        private fun showAddAppDialog() {
            val placed = (dock.map { it.packageName } + orbs.flatMap { it.apps }.map { it.packageName }).toSet()
            val available = allApps.filter { it.packageName !in placed }.toMutableList()
            if (available.isEmpty()) {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Орбита заполнена")
                    .setMessage("Все установленные приложения уже размещены. Новые приложения появятся здесь автоматически после установки.")
                    .setPositiveButton("ПОНЯТНО", null)
                    .show()
                return
            }
            val density = d
            val panel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20*density).toInt(), (18*density).toInt(), (20*density).toInt(), (14*density).toInt())
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(22, 38, 70), Color.rgb(9, 18, 42), Color.rgb(18, 29, 58))
                ).apply {
                    cornerRadius = 28*density
                    setStroke((1*density).toInt(), Color.argb(105, 170, 215, 255))
                }
            }
            val heading = TextView(this@MainActivity).apply {
                text = "ДОБАВИТЬ НА ОРБИТУ"
                setTextColor(Color.WHITE)
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = .08f
                setPadding(2, 0, 0, (5*density).toInt())
            }
            val subtitle = TextView(this@MainActivity).apply {
                text = "Выбери приложение — оно появится отдельной сферой"
                setTextColor(Color.rgb(166, 194, 230))
                textSize = 12f
                setPadding(2, 0, 0, (14*density).toInt())
            }
            val search = EditText(this@MainActivity).apply {
                hint = "Найти приложение"
                setSingleLine(true)
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(145, 169, 204))
                setPadding((15*density).toInt(), 0, (15*density).toInt(), 0)
                background = GradientDrawable().apply {
                    setColor(Color.argb(105, 105, 145, 205))
                    cornerRadius = 18*density
                    setStroke((1*density).toInt(), Color.argb(90, 190, 220, 255))
                }
            }
            val list = ListView(this@MainActivity).apply {
                divider = ColorDrawable(Color.argb(35, 190, 220, 255))
                dividerHeight = (1*density).toInt()
                setPadding(0, (8*density).toInt(), 0, 0)
                isVerticalScrollBarEnabled = false
                setBackgroundColor(Color.TRANSPARENT)
            }
            val adapter = object : BaseAdapter() {
                private var shown = available.toList()
                fun filter(query: String) {
                    shown = available.filter { it.title.contains(query, true) || it.packageName.contains(query, true) }
                    notifyDataSetChanged()
                }
                fun item(position: Int) = shown[position]
                override fun getCount() = shown.size
                override fun getItem(position: Int) = shown[position]
                override fun getItemId(position: Int) = shown[position].packageName.hashCode().toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val app = shown[position]
                    val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding((8*density).toInt(), (9*density).toInt(), (8*density).toInt(), (9*density).toInt())
                    }
                    row.removeAllViews()
                    val icon = ImageView(this@MainActivity).apply {
                        setImageBitmap(app.icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    row.addView(icon, LinearLayout.LayoutParams((42*density).toInt(), (42*density).toInt()))
                    val name = TextView(this@MainActivity).apply {
                        text = app.title
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setPadding((14*density).toInt(), 0, 0, 0)
                    }
                    row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val plus = TextView(this@MainActivity).apply {
                        text = "+"
                        textSize = 25f
                        setTextColor(Color.rgb(132, 205, 255))
                        gravity = Gravity.CENTER
                    }
                    row.addView(plus, LinearLayout.LayoutParams((34*density).toInt(), (42*density).toInt()))
                    return row
                }
            }
            list.adapter = adapter
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { adapter.filter(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) {}
            })
            panel.addView(heading)
            panel.addView(subtitle)
            panel.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48*density).toInt()))
            panel.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (390*density).toInt()))
            val dialog = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert)
                .setView(panel)
                .setNegativeButton("ЗАКРЫТЬ", null)
                .create()
            list.setOnItemClickListener { _, _, position, _ ->
                val app = adapter.item(position)
                val count = orbs.count { it.page == currentPage }
                val col = count % 4
                val row = count / 4
                val minY = 104f*d
                val maxY = (height - 126f*d).coerceAtLeast(minY + 60f*d)
                val x = (width * (0.13f + col * 0.245f)).coerceIn(34f*d, width - 34f*d)
                val y = (minY + row * 76f*d).coerceIn(minY, maxY)
                val angle = (app.packageName.hashCode() % 360) * (Math.PI / 180.0)
                val speed = 172f*d
                orbs += Orb(mutableListOf(app), x, y, cos(angle).toFloat()*speed,
                    sin(angle).toFloat()*speed, page = currentPage)
                saveLayout()
                dialog.dismiss()
                invalidate()
            }
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.show()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            val size = (96 * d).toInt().coerceAtLeast(64)
            return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (initialized || w == 0 || h == 0) return
            initialized = true
            val usableBottom = h - 112f * d
            orbs.forEachIndexed { index, orb ->
                if (orb.x == 0f && orb.y == 0f) {
                    val inPage = orbs.take(index).count { it.page == orb.page }
                    val col = inPage % 4
                    val row = inPage / 4
                    orb.x = (w * (0.13f + col * 0.245f)).coerceIn(orb.radius * d + 4f, w - orb.radius * d - 4f)
                    orb.y = (usableBottom * (0.15f + row * 0.20f)).coerceIn(orb.radius * d + 88f * d, usableBottom - orb.radius * d)
                    val angle = index * 2.399f
                    // Calm, slow drift: each sphere keeps a gentle independent direction.
                    val speed = (132f + (index % 5) * 12f) * d
                    orb.vx = cos(angle) * speed
                    orb.vy = sin(angle) * speed
                }
            }
            if (!layoutRestored) {
                layoutRestored = true
                restoreLayout()
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(4, 8, 24))
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.rgb(8, 18, 43), Color.rgb(5, 10, 29), Color.rgb(12, 12, 38)),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            paint.setShadowLayer(55f*d, width*.78f, height*.24f, Color.argb(38, 75, 110, 255))
            paint.color = Color.argb(18, 80, 115, 255)
            canvas.drawCircle(width*.78f, height*.24f, width*.48f, paint)
            paint.clearShadowLayer()
            drawStars(canvas)
            syncMeteoredWidgetView()
            if (openedFolder != null) {
                drawOpenFolder(canvas, openedFolder!!)
            } else {
                drawSearchBar(canvas)
                if (currentPage == 2) drawWeatherWidget(canvas)
                val now = System.nanoTime()
                val dt = if (previousFrame == 0L) .016f else ((now - previousFrame) / 1e9f).coerceIn(0f, .03f)
                previousFrame = now
                updatePhysics(dt)
                orbs.filter { it.page == currentPage }.forEach { drawOrb(canvas, it) }
                drawPageIndicator(canvas)
                drawDock(canvas)
                if (dragging) drawDeletePill(canvas)
            }
            // The open-folder screen is static; redraw only after touch events.
            if (openedFolder == null) postInvalidateOnAnimation()
        }

        private fun drawStars(c: Canvas) {
            paint.style = Paint.Style.FILL
            for (i in 0 until 65) {
                val x = ((i * 173 + 37) % width.coerceAtLeast(1)).toFloat()
                val y = ((i * 97 + 19) % height.coerceAtLeast(1)).toFloat()
                paint.color = Color.argb(90 + (i % 5) * 25, 170, 195, 255)
                c.drawCircle(x, y, (i % 3 + 1) * .45f * d, paint)
            }
        }

        private fun drawOrb(c: Canvas, orb: Orb) {
            // Preserve the original app artwork, framed inside a glass sphere.
            val r = orb.radius * d
            val key = if (orb.isFolder) "cosmos-folder" else orb.apps.first().packageName
            val hue = (key.hashCode().toLong().and(0x7fffffff) % 360L).toFloat()
            val base = Color.HSVToColor(floatArrayOf(hue, .48f, .92f))
            val deep = Color.HSVToColor(floatArrayOf(hue, .72f, .38f))

            // A soft halo makes each app feel like a luminous glass object.
            paint.style = Paint.Style.FILL
            paint.setShadowLayer(15f*d, 0f, 0f, Color.argb(115, Color.red(base), Color.green(base), Color.blue(base)))
            paint.color = Color.argb(70, Color.red(base), Color.green(base), Color.blue(base))
            c.drawCircle(orb.x, orb.y, r+2f*d, paint)
            paint.clearShadowLayer()
            val spherePath = Path().apply {
                addCircle(orb.x, orb.y, r, Path.Direction.CW)
            }
            val save = c.save()
            c.clipPath(spherePath)

            // Soft colored glass tint behind and over the original icon.
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                orb.x - r, orb.y - r, orb.x + r, orb.y + r,
                intArrayOf(Color.argb(52, 255, 255, 255), Color.argb(12, Color.red(base), Color.green(base), Color.blue(base)), Color.argb(42, Color.red(deep), Color.green(deep), Color.blue(deep))),
                null, Shader.TileMode.CLAMP
            )
            c.drawRect(orb.x-r, orb.y-r, orb.x+r, orb.y+r, paint)
            paint.shader = null

            if (orb.isFolder) {
                val mini = r * .62f
                orb.apps.take(4).forEachIndexed { i, app ->
                    val col = i % 2
                    val row = i / 2
                    val cx = orb.x + (col - .5f) * mini * 1.05f
                    val cy = orb.y - r * .10f + (row - .5f) * mini * .82f
                    c.drawBitmap(app.icon, null, RectF(cx-mini*.58f, cy-mini*.58f, cx+mini*.58f, cy+mini*.58f),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 255; color = Color.WHITE })
                }
            } else {
                // Keep the original icon crisp, but leave a glass rim visible around it.
                val icon = orb.apps.first().icon
                val iconSize = r * .91f
                // Original app artwork at full color, without a milky overlay.
                c.drawBitmap(icon, null,
                    RectF(orb.x-iconSize, orb.y-iconSize, orb.x+iconSize, orb.y+iconSize),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }

            // App spheres need a dark label strip; folder previews keep every mini-icon unobscured.
            if (!orb.isFolder) {
                paint.color = Color.argb(118, 5, 10, 28)
                c.drawRect(orb.x-r, orb.y+r*.38f, orb.x+r, orb.y+r, paint)
            }

            // Subtle glass highlight.
            paint.shader = RadialGradient(
                orb.x - r * .34f, orb.y - r * .48f, r * .58f,
                intArrayOf(Color.argb(28, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(orb.x, orb.y, r*.96f, paint)
            paint.shader = null
            c.restoreToCount(save)

            // Bright neon outline with a soft outer glow.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 4.2f * d
            paint.color = if (orb.isFolder) Color.rgb(65, 205, 255) else Color.rgb(105, 225, 255)
            paint.setShadowLayer(13f*d, 0f, 0f, if (orb.isFolder) Color.rgb(40, 145, 255) else Color.rgb(35, 205, 255))
            c.drawCircle(orb.x, orb.y, r - 1.8f*d, paint)
            paint.clearShadowLayer()
            paint.strokeWidth = 1.3f*d
            paint.color = Color.argb(245, 225, 250, 255)
            c.drawCircle(orb.x, orb.y, r - 4.2f*d, paint)
            paint.style = Paint.Style.FILL
            // Small specular glint gives the sphere a polished glass finish.
            paint.shader = LinearGradient(orb.x-r*.55f, orb.y-r*.72f, orb.x+r*.12f, orb.y-r*.20f,
                intArrayOf(Color.argb(78,255,255,255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawOval(RectF(orb.x-r*.56f, orb.y-r*.76f, orb.x+r*.18f, orb.y-r*.42f), paint)
            paint.shader = null

            val label = if (orb.isFolder) orb.folderName else orb.apps.first().title
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.setShadowLayer(2f*d, 0f, 1f*d, Color.BLACK)
            var textSize = 8.5f*d
            val maxWidth = r*1.72f
            paint.textSize = textSize
            while (paint.measureText(label) > maxWidth && textSize > 6f*d) {
                textSize -= .5f*d
                paint.textSize = textSize
            }
            val fitted = if (paint.measureText(label) > maxWidth) {
                val available = paint.breakText(label, true, maxWidth-4f*d, null)
                label.take(available.coerceAtLeast(1)).trimEnd() + "…"
            } else label
            c.drawText(fitted, orb.x, orb.y+r*.78f, paint)
            paint.clearShadowLayer()
            paint.typeface = Typeface.DEFAULT
        }

        private fun drawSearchBar(c: Canvas) {
            val now = System.nanoTime() / 1_000_000_000.0
            val pulse = ((sin(now * 1.15) + 1.0) / 2.0).toFloat()
            val alpha = (145f + pulse * 55f).toInt()
            val left = 22f * d
            val right = width - 22f * d
            val top = 20f * d
            val bottom = top + 48f * d
            val oval = RectF(left, top, right, bottom)

            paint.style = Paint.Style.FILL
            paint.setShadowLayer((5f + pulse * 5f) * d, 0f, 0f, Color.argb((35f + pulse * 45f).toInt(), 95, 175, 255))
            paint.shader = LinearGradient(
                left, top, right, bottom,
                intArrayOf(
                    Color.argb(112, 150, 195, 255),
                    Color.argb(48, 55, 90, 145),
                    Color.argb(92, 120, 170, 235)
                ),
                null, Shader.TileMode.CLAMP
            )
            c.drawRoundRect(oval, 26f * d, 26f * d, paint)
            paint.shader = null
            paint.clearShadowLayer()

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (1f + pulse * .35f) * d
            paint.color = Color.argb(alpha, 210, 232, 255)
            c.drawRoundRect(oval, 26f * d, 26f * d, paint)
            paint.style = Paint.Style.FILL

            // Soft moving glass highlight and a quiet search hint.
            paint.shader = LinearGradient(
                left + 8f * d, top, left + width * .42f, bottom,
                intArrayOf(Color.argb((28f + pulse * 32f).toInt(), 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            c.drawRoundRect(oval, 26f * d, 26f * d, paint)
            paint.shader = null

            val iconX = left + 23f * d
            val iconY = (top + bottom) / 2f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f * d
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.argb(210, 225, 239, 255)
            c.drawCircle(iconX, iconY - 1f * d, 6f * d, paint)
            c.drawLine(iconX + 4.5f * d, iconY + 3.5f * d, iconX + 9f * d, iconY + 8f * d, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(190, 225, 239, 255)
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 13f * d
            c.drawText("Поиск Google", left + 43f * d, iconY + 4.5f * d, paint)

            // A small plus opens the installed-app picker.
            val plusX = right - 25f*d
            paint.color = Color.argb(225, 235, 246, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f*d
            paint.strokeCap = Paint.Cap.ROUND
            c.drawCircle(plusX, iconY, 12f*d, paint)
            c.drawLine(plusX-5f*d, iconY, plusX+5f*d, iconY, paint)
            c.drawLine(plusX, iconY-5f*d, plusX, iconY+5f*d, paint)
            paint.style = Paint.Style.FILL
        }

        private fun syncMeteoredWidgetView() {
            val widget = meteoredWidgetView ?: return
            val rect = weatherWidgetRect
            val visible = currentPage == 2 && openedFolder == null && rect.width() > 0f
            widget.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) {
                val params = widget.layoutParams as? FrameLayout.LayoutParams
                    ?: FrameLayout.LayoutParams(1, 1)
                val left = rect.left.toInt()
                val top = rect.top.toInt()
                val w = rect.width().toInt().coerceAtLeast(1)
                val h = rect.height().toInt().coerceAtLeast(1)
                if (params.width != w || params.height != h || params.leftMargin != left || params.topMargin != top) {
                    params.width = w
                    params.height = h
                    params.leftMargin = left
                    params.topMargin = top
                    widget.layoutParams = params
                }
                widget.bringToFront()
            }
        }

        private fun drawWeatherWidget(c: Canvas) {
            val left = 24f*d
            val top = height*.39f
            val right = width-24f*d
            val bottom = top+112f*d
            weatherWidgetRect.set(left,top,right,bottom)
            paint.style = Paint.Style.FILL
            paint.setShadowLayer(18f*d, 0f, 5f*d, Color.argb(90, 65, 145, 255))
            paint.shader = LinearGradient(left,top,right,bottom,
                intArrayOf(Color.argb(218,52,91,145),Color.argb(185,12,27,58),Color.argb(205,27,45,83)),
                null,Shader.TileMode.CLAMP)
            c.drawRoundRect(weatherWidgetRect,26f*d,26f*d,paint)
            paint.shader = null
            paint.clearShadowLayer()
            paint.shader = LinearGradient(left,top,left,bottom,
                intArrayOf(Color.argb(45,255,255,255),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            c.drawRoundRect(weatherWidgetRect,26f*d,26f*d,paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.1f*d
            paint.color = Color.argb(170,195,225,255)
            c.drawRoundRect(weatherWidgetRect,26f*d,26f*d,paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(160,140,190,245)
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 9f*d
            paint.typeface = Typeface.create("sans-serif-medium",Typeface.BOLD)
            c.drawText("ПРОГНОЗ ПОГОДЫ",left+18f*d,top+22f*d,paint)
            paint.color = Color.WHITE
            paint.textSize = 19f*d
            c.drawText("Meteored",left+18f*d,top+49f*d,paint)
            paint.textSize = 11f*d
            paint.typeface = Typeface.DEFAULT
            paint.color = Color.argb(220,220,235,255)
            c.drawText("Подключи виджет приложения",left+18f*d,top+76f*d,paint)
            paint.color = Color.argb(185,200,220,245)
            paint.textSize = 10f*d
            c.drawText("Нажми, чтобы выбрать виджет",left+18f*d,top+94f*d,paint)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 34f*d
            paint.color = Color.rgb(255,214,125)
            c.drawText("☼",right-43f*d,top+72f*d,paint)
        }

        private fun drawDeletePill(c: Canvas) {
            // The trash target stays above the dock for the entire drag gesture.
            val w = 174f*d
            val h = 50f*d
            deletePill.set((width-w)/2f, height-190f*d, (width+w)/2f, height-190f*d+h)
            val hovered = selected?.let { deletePill.contains(it.x, it.y) } == true
            paint.style = Paint.Style.FILL
            paint.setShadowLayer(if (hovered) 26f*d else 14f*d, 0f, 3f*d,
                if (hovered) Color.argb(230, 255, 45, 75) else Color.argb(125, 255, 65, 105))
            paint.shader = LinearGradient(deletePill.left, deletePill.top, deletePill.right, deletePill.bottom,
                if (hovered)
                    intArrayOf(Color.rgb(255, 72, 92), Color.rgb(175, 20, 55))
                else
                    intArrayOf(Color.argb(238, 190, 55, 91), Color.argb(220, 105, 28, 66)),
                null, Shader.TileMode.CLAMP)
            c.drawRoundRect(deletePill, 25f*d, 25f*d, paint)
            paint.shader = null
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (hovered) 2.4f*d else 1.1f*d
            paint.color = Color.argb(235, 255, 225, 232)
            c.drawRoundRect(deletePill, 25f*d, 25f*d, paint)
            paint.style = Paint.Style.FILL
            val cx = deletePill.centerX()
            val cy = deletePill.centerY()
            paint.color = Color.WHITE
            paint.strokeWidth = 2f*d
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = Paint.Style.STROKE
            c.drawLine(cx-48f*d, cy-7f*d, cx-36f*d, cy-7f*d, paint)
            c.drawLine(cx-46f*d, cy-4f*d, cx-44f*d, cy+9f*d, paint)
            c.drawLine(cx-38f*d, cy-4f*d, cx-40f*d, cy+9f*d, paint)
            c.drawLine(cx-44f*d, cy+9f*d, cx-40f*d, cy+9f*d, paint)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = 12f*d
            c.drawText(if (hovered) "ОТПУСТИ, ЧТОБЫ УДАЛИТЬ" else "ПЕРЕТАЩИ СЮДА", cx+18f*d, cy+4.5f*d, paint)
            paint.typeface = Typeface.DEFAULT
        }

        private fun drawPageIndicator(c: Canvas) {
            val cy = height - 91f*d
            for (i in 0..2) {
                paint.color = if (i == currentPage) Color.WHITE else Color.argb(95, 180, 195, 225)
                c.drawCircle(width / 2f + (i - 1) * 13f*d, cy, if (i == currentPage) 3.4f*d else 2.5f*d, paint)
            }
        }

        private fun drawDock(c: Canvas) {
            val top = height - 76f*d
            val dockRect = RectF(12f*d, top, width-12f*d, height-9f*d)
            paint.style = Paint.Style.FILL
            paint.setShadowLayer(16f*d, 0f, 3f*d, Color.argb(90, 75, 145, 255))
            paint.shader = LinearGradient(dockRect.left, dockRect.top, dockRect.right, dockRect.bottom,
                intArrayOf(Color.argb(172, 70, 100, 150), Color.argb(132, 18, 31, 61), Color.argb(160, 42, 65, 105)),
                null, Shader.TileMode.CLAMP)
            c.drawRoundRect(dockRect, 26f*d, 26f*d, paint)
            paint.shader = null
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f*d
            paint.color = Color.argb(125, 190, 220, 255)
            c.drawRoundRect(dockRect, 26f*d, 26f*d, paint)
            paint.style = Paint.Style.FILL
            val count = 4
            dock.forEachIndexed { i, app ->
                val size = 43f*d
                val radius = size / 2f
                val cx = width * (i + .5f) / count
                val cy = top + 32f*d
                val hue = (app.packageName.hashCode().toLong().and(0x7fffffff) % 360L).toFloat()
                val tint = Color.HSVToColor(floatArrayOf(hue, .48f, .92f))
                val deep = Color.HSVToColor(floatArrayOf(hue, .72f, .38f))
                val clipSave = c.save()
                c.clipPath(Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) })
                c.drawBitmap(app.icon, null, RectF(cx-radius, cy-radius, cx+radius, cy+radius),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 255; color = Color.WHITE })
                // Keep the launcher-provided dock icon colors untouched.
                paint.shader = null
                c.restoreToCount(clipSave)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f*d
                paint.color = Color.argb(175, 220, 240, 255)
                c.drawCircle(cx, cy, radius-.5f*d, paint)
                paint.style = Paint.Style.FILL
            }
        }

        private fun drawOpenFolder(c: Canvas, folder: Orb) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(205, 2, 6, 22)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            val box = RectF(18f*d, height*.17f, width-18f*d, height*.80f)
            paint.setShadowLayer(28f*d, 0f, 8f*d, Color.argb(115, 65, 145, 255))
            paint.shader = LinearGradient(box.left, box.top, box.right, box.bottom,
                intArrayOf(Color.argb(242, 34, 57, 96), Color.argb(238, 10, 22, 49), Color.argb(245, 24, 39, 76)),
                null, Shader.TileMode.CLAMP)
            c.drawRoundRect(box, 30f*d, 30f*d, paint)
            paint.shader = null
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f*d
            paint.color = Color.argb(155, 190, 225, 255)
            c.drawRoundRect(box, 30f*d, 30f*d, paint)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(box.left, box.top, box.right, box.top+100f*d,
                intArrayOf(Color.argb(48,255,255,255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(box, 30f*d, 30f*d, paint)
            paint.shader = null

            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = 19f*d
            c.drawText(folder.folderName, width/2f, box.top+39f*d, paint)
            paint.color = Color.argb(190, 170, 205, 245)
            paint.textSize = 9f*d
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            c.drawText("${folder.apps.size} ПРИЛОЖЕНИЙ", width/2f, box.top+57f*d, paint)
            paint.textAlign=Paint.Align.RIGHT
            paint.textSize=16f*d
            paint.color=Color.argb(225,205,230,255)
            c.drawText("✎",box.right-22f*d,box.top+39f*d,paint)
            paint.textAlign=Paint.Align.CENTER
            val cellW = (box.width()-28f*d)/3f
            val contentClip = c.save()
            c.clipRect(box.left+8f*d, box.top+72f*d, box.right-8f*d, box.bottom-38f*d)
            folder.apps.forEachIndexed { i, app ->
                val col=i%3; val row=i/3
                val cx=box.left+14f*d+cellW*(col+.5f)
                val cy=box.top+105f*d+row*94f*d-folderScrollOffset
                if (cy > box.top+66f*d && cy < box.bottom-32f*d) {
                    val tile = RectF(cx-31f*d, cy-31f*d, cx+31f*d, cy+31f*d)
                    paint.color = Color.argb(72, 150, 190, 245)
                    c.drawRoundRect(tile, 17f*d, 17f*d, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = .8f*d
                    paint.color = Color.argb(95, 205, 230, 255)
                    c.drawRoundRect(tile, 17f*d, 17f*d, paint)
                    paint.style = Paint.Style.FILL
                    val size=43f*d
                    c.drawBitmap(app.icon,null,RectF(cx-size/2,cy-size/2,cx+size/2,cy+size/2),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 255; color = Color.WHITE })
                    paint.color=Color.WHITE
                    paint.textSize=10f*d
                    c.drawText(app.title.take(15),cx,cy+size/2+16f*d,paint)
                }
            }
            c.restoreToCount(contentClip)
            paint.color=Color.argb(170,175,205,240)
            paint.textSize=10f*d
            c.drawText("Смахни вверх или нажми снаружи",width/2f,box.bottom-18f*d,paint)
        }

        private fun updatePhysics(dt: Float) {
            val edge = 6f*d
            val bottom = height - 112f*d
            val active = orbs.filter { it.page == currentPage }
            active.forEach { orb ->
                if (orb === selected) return@forEach
                val r=orb.radius*d
                orb.x += orb.vx*dt
                orb.y += orb.vy*dt
                if (orb.x < edge+r || orb.x > width-edge-r) {
                    orb.vx=-orb.vx*.96f; orb.x=orb.x.coerceIn(edge+r,width-edge-r)
                }
                if (orb.y < 88f*d+r || orb.y > bottom-r) {
                    orb.vy=-orb.vy*.96f; orb.y=orb.y.coerceIn(88f*d+r,bottom-r)
                }
            }
            for (i in active.indices) for (j in i+1 until active.size) {
                val a=active[i]; val b=active[j]
                val dx=b.x-a.x; val dy=b.y-a.y; val dist=hypot(dx,dy)
                val min=(a.radius+b.radius)*d
                if (dist > .1f && dist < min) {
                    val nx=dx/dist; val ny=dy/dist; val overlap=min-dist
                    a.x-=nx*overlap*.5f; a.y-=ny*overlap*.5f
                    b.x+=nx*overlap*.5f; b.y+=ny*overlap*.5f
                    val rel=(b.vx-a.vx)*nx+(b.vy-a.vy)*ny
                    if (rel<0f) {
                        val impulse=rel*.65f
                        a.vx+=impulse*nx; a.vy+=impulse*ny
                        b.vx-=impulse*nx; b.vy-=impulse*ny
                    }
                }
            }
            // Keep collisions from gradually making the calm drift too fast.
            val maxSpeed = 276f*d
            active.forEach { orb ->
                val speed = hypot(orb.vx, orb.vy)
                if (speed > maxSpeed) {
                    val scale = maxSpeed / speed
                    orb.vx *= scale
                    orb.vy *= scale
                }
            }
        }

        private fun launch(app: AppEntry) {
            if (app.launchUrl != null) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.launchUrl)))
            } else if (app.launchIntent != null) {
                try {
                    startActivity(Intent(app.launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
            } else {
                packageManager.getLaunchIntentForPackage(app.packageName)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(intent)
                }
            }
        }

        fun closeFolderOnBack(): Boolean {
            if (openedFolder == null) return false
            openedFolder = null
            touchHandler.removeCallbacks(folderItemLongPressAction)
            folderScrollOffset = 0f
            invalidate()
            return true
        }

        private fun renameFolder(folder: Orb) {
            val input = EditText(this@MainActivity).apply {
                setText(folder.folderName)
                hint = "Название папки"
                setSingleLine(true)
                textSize = 16f
                setPadding((16f*d).toInt(), (12f*d).toInt(), (16f*d).toInt(), (12f*d).toInt())
            }
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Название папки")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create()
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = "Введите название"
                } else {
                    folder.folderName = name.take(24)
                    saveLayout()
                    dialog.dismiss()
                    invalidate()
                }
            }
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            input.requestFocus()
        }

        private fun showFolderAppActions(folder: Orb, index: Int) {
            if (index !in folder.apps.indices) return
            val app = folder.apps[index]
            AlertDialog.Builder(this@MainActivity)
                .setTitle(app.title)
                .setItems(arrayOf("Переместить на рабочий стол", "Убрать из Cosmos", "Отмена")) { dialog, which ->
                    if (which == 0 || which == 1) {
                        val prefs = getSharedPreferences("cosmos_launcher", MODE_PRIVATE)
                        val customized = prefs.getStringSet("customized_folders", emptySet())
                            ?.toMutableSet() ?: mutableSetOf()
                        customized.add(folder.folderName)
                        customized.add(folder.folderName.lowercase().replace(" ", "").replace("_", ""))
                        prefs.edit().putStringSet("customized_folders", customized).apply()

                        folder.apps.removeAll { it.packageName == app.packageName }
                        if (which == 0) {
                            val minX = (folder.radius * d + 8f*d)
                            val maxX = (width - folder.radius*d - 8f*d).coerceAtLeast(minX)
                            val x = (folder.x + folder.radius*d*2.1f).coerceIn(minX, maxX)
                            val minY = 92f*d + 36f*d
                            val maxY = (height - 124f*d).coerceAtLeast(minY)
                            val y = folder.y.coerceIn(minY, maxY)
                            val angle = (app.packageName.hashCode() % 360) * (Math.PI / 180.0)
                            orbs.add(Orb(mutableListOf(app), x, y,
                                cos(angle).toFloat()*150f*d, sin(angle).toFloat()*150f*d,
                                page = folder.page))
                        }
                        if (folder.apps.isEmpty()) orbs.remove(folder)
                        saveLayout()
                        invalidate()
                    }
                }
                .show()
        }

        private fun openGoogleSearch() {
            val panel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((22*d).toInt(), (20*d).toInt(), (22*d).toInt(), (12*d).toInt())
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(24, 43, 78), Color.rgb(8, 17, 40))
                ).apply {
                    cornerRadius = 26*d
                    setStroke((1*d).toInt(), Color.argb(115, 170, 215, 255))
                }
            }
            val title = TextView(this@MainActivity).apply {
                text = "ПОИСК ПО ВСЕЛЕННОЙ"
                setTextColor(Color.WHITE)
                textSize = 16f
                letterSpacing = .07f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, 0, (15*d).toInt())
            }
            val input = EditText(this@MainActivity).apply {
                hint = "Что будем искать?"
                setSingleLine(true)
                textSize = 15f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(155, 181, 218))
                setPadding((16*d).toInt(), 0, (16*d).toInt(), 0)
                background = GradientDrawable().apply {
                    setColor(Color.argb(110, 105, 145, 205))
                    cornerRadius = 17*d
                    setStroke((1*d).toInt(), Color.argb(95, 190, 220, 255))
                }
                imeOptions = EditorInfo.IME_ACTION_SEARCH
            }
            panel.addView(title)
            panel.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52*d).toInt()))
            val dialog = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert)
                .setView(panel)
                .setNegativeButton("ОТМЕНА", null)
                .setPositiveButton("ИСКАТЬ", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(120, 195, 255))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(165, 190, 225))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val query = input.text.toString().trim()
                    if (query.isNotEmpty()) {
                        val googleAppIntent = Intent(Intent.ACTION_WEB_SEARCH)
                            .setPackage("com.google.android.googlequicksearchbox")
                            .putExtra("query", query)
                        try {
                            startActivity(googleAppIntent)
                        } catch (_: Exception) {
                            startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
                        }
                        dialog.dismiss()
                    }
                }
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                input.requestFocus()
            }
            dialog.show()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX=e.x; downY=e.y; lastX=e.x; lastY=e.y
                    moved=false; dragging=false; longPressTriggered=false; edgeSwitchArmed=true; swipeHandled=false
                    touchHandler.removeCallbacks(longPressAction)
                    if (openedFolder != null) {
                        val folder=openedFolder!!
                        val box=RectF(18f*d,height*.17f,width-18f*d,height*.80f)
                        if (!box.contains(e.x,e.y)) {
                            openedFolder=null
                            touchHandler.removeCallbacks(folderItemLongPressAction)
                            folderScrollOffset=0f
                            invalidate()
                            return true
                        }
                        folderDownY=e.y
                        folderDownOffset=folderScrollOffset
                        folderTouchMoved=false
                        folderHeaderTapped = e.y < box.top + 72f*d
                        val cellW=(box.width()-28f*d)/3f
                        val col=((e.x-box.left-14f*d)/cellW).toInt()
                        val row=((e.y-box.top-58f*d+folderScrollOffset)/(94f*d)).toInt()
                        folderTouchIndex=if (!folderHeaderTapped && col in 0..2 && row>=0) row*3+col else -1
                        folderItemLongPressed = false
                        touchHandler.removeCallbacks(folderItemLongPressAction)
                        if (folderTouchIndex in folder.apps.indices) {
                            touchHandler.postDelayed(folderItemLongPressAction, 520L)
                        }
                        return true
                    }
                    if (currentPage == 2 && meteoredWidgetView == null && weatherWidgetRect.contains(e.x,e.y)) {
                        chooseMeteoredWidget()
                        return true
                    }
                    if (e.y in 20f*d..68f*d && e.x in 22f*d..(width-22f*d)) {
                        if (e.x >= width - 58f*d) showAddAppDialog() else openGoogleSearch()
                        return true
                    }
                    val dockTop=height-76f*d
                    if (e.y>=dockTop && dock.isNotEmpty()) {
                        val index=(e.x/(width.toFloat()/dock.size)).toInt().coerceIn(0,dock.size-1)
                        launch(dock[index]); return true
                    }
                    selected=orbs.asReversed().firstOrNull {
                        it.page==currentPage && hypot(e.x-it.x,e.y-it.y)<it.radius*d+14f*d
                    }
                    selected?.let {
                        dragOffsetX = it.x - e.x
                        dragOffsetY = it.y - e.y
                        touchHandler.postDelayed(longPressAction, 450L)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Page swipes have priority over orb selection. This allows
                    // switching desktops even when the finger starts on a moving sphere.
                    if (openedFolder == null && selected == null && !dragging && !swipeHandled &&
                        abs(e.x - downX) > 55f*d && abs(e.x - downX) > abs(e.y - downY)) {
                        touchHandler.removeCallbacks(longPressAction)
                        selected = null
                        dragging = false
                        moved = true
                        currentPage = (currentPage + if (e.x < downX) 1 else -1).coerceIn(0, 2)
                        getSharedPreferences("cosmos_launcher", MODE_PRIVATE).edit()
                            .putInt("current_page", currentPage).apply()
                        swipeHandled = true
                        lastX = e.x
                        lastY = e.y
                        invalidate()
                        return true
                    }
                    if (openedFolder != null) {
                        if (abs(e.y-folderDownY)>8f*d) {
                            touchHandler.removeCallbacks(folderItemLongPressAction)
                            folderTouchMoved=true
                            val boxHeight=height*.63f
                            val rows=ceil(openedFolder!!.apps.size/3.0).toInt()
                            val visibleHeight=(boxHeight-96f*d).coerceAtLeast(0f)
                            val maxScroll=(rows*94f*d-visibleHeight).coerceAtLeast(0f)
                            folderScrollOffset=(folderDownOffset+folderDownY-e.y).coerceIn(0f,maxScroll)
                        }
                        invalidate()
                        return true
                    }
                    if (selected != null) {
                        val orb=selected!!
                        if (!dragging && hypot(e.x-downX,e.y-downY)>12f*d) {
                            touchHandler.removeCallbacks(longPressAction)
                            moved=true
                            // A quick finger sweep transfers momentum to the held sphere
                            // and nearby spheres, like pushing physical objects on a table.
                            val pushX = (e.x-lastX) * 8.0f
                            val pushY = (e.y-lastY) * 8.0f
                            orb.vx = (orb.vx + pushX).coerceIn(-276f*d, 276f*d)
                            orb.vy = (orb.vy + pushY).coerceIn(-276f*d, 276f*d)
                            orbs.filter { it !== orb && it.page == currentPage }.forEach { other ->
                                val dx = other.x - orb.x
                                val dy = other.y - orb.y
                                val distance = hypot(dx, dy)
                                val reach = (orb.radius + other.radius) * d * 2.15f
                                if (distance > 1f && distance < reach) {
                                    val falloff = 1f - distance / reach
                                    other.vx = (other.vx + pushX * falloff * .72f).coerceIn(-276f*d, 276f*d)
                                    other.vy = (other.vy + pushY * falloff * .72f).coerceIn(-276f*d, 276f*d)
                                }
                            }
                        }
                        if (dragging) {
                            moved=true
                            orb.x=e.x+dragOffsetX
                            orb.y=e.y+dragOffsetY
                            orb.vx=0f; orb.vy=0f
                            // Dragging through a screen edge carries the orb to the adjacent desktop.
                            if (e.x > edgeSwitchZone*2f && e.x < width-edgeSwitchZone*2f) edgeSwitchArmed=true
                            if (edgeSwitchArmed && e.x < edgeSwitchZone && currentPage > 0 && orb.page == currentPage) {
                                edgeSwitchArmed=false
                                currentPage--
                                orb.page=currentPage
                                orb.x=width-orb.radius*d-edgeSwitchZone
                                getSharedPreferences("cosmos_launcher", MODE_PRIVATE).edit().putInt("current_page",currentPage).apply()
                            } else if (edgeSwitchArmed && e.x > width-edgeSwitchZone && currentPage < 2 && orb.page == currentPage) {
                                edgeSwitchArmed=false
                                currentPage++
                                orb.page=currentPage
                                orb.x=orb.radius*d+edgeSwitchZone
                                getSharedPreferences("cosmos_launcher", MODE_PRIVATE).edit().putInt("current_page",currentPage).apply()
                            }
                            orb.x=orb.x.coerceIn(orb.radius*d+4f*d,width-orb.radius*d-4f*d)
                            orb.y=orb.y.coerceIn(88f*d+orb.radius*d,height-112f*d-orb.radius*d)
                        }
                    }
                    lastX=e.x; lastY=e.y
                    invalidate(); return true
                }
                MotionEvent.ACTION_UP -> {
                    touchHandler.removeCallbacks(longPressAction)
                    if (openedFolder != null) {
                        touchHandler.removeCallbacks(folderItemLongPressAction)
                        val folder = openedFolder!!
                        if (folderItemLongPressed) {
                            folderTouchIndex = -1
                            folderHeaderTapped = false
                            invalidate()
                            return true
                        }
                        if (!folderTouchMoved && folderHeaderTapped) {
                            renameFolder(folder)
                        } else if (!folderTouchMoved && folderTouchIndex in folder.apps.indices) {
                            launch(folder.apps[folderTouchIndex])
                        }
                        folderTouchIndex=-1
                        folderHeaderTapped=false
                        invalidate()
                        return true
                    }
                    selected?.let { dragged ->
                        if (dragging && deletePill.contains(dragged.x, dragged.y)) {
                            // Dropping the orb itself onto the trash removes it from this desktop.
                            orbs.remove(dragged)
                            saveLayout()
                        } else if (dragging) {
                            val target=orbs.firstOrNull {
                                it!==dragged && it.page==currentPage &&
                                hypot(it.x-dragged.x,it.y-dragged.y)<(it.radius+dragged.radius)*d*.9f
                            }
                            if (target != null) {
                                if (target.isFolder) target.apps.addAll(dragged.apps)
                                else if (!dragged.isFolder) {
                                    target.apps.addAll(dragged.apps)
                                } else {
                                    target.apps.addAll(dragged.apps)
                                }
                                orbs.remove(dragged)
                                target.vx*=.65f; target.vy*=.65f
                            } else {
                                // A small release impulse avoids sudden fast launches.
                                dragged.vx=((e.x-downX)*.10f).coerceIn(-60f*d,60f*d)
                                dragged.vy=((e.y-downY)*.10f).coerceIn(-30f*d,30f*d)
                            }
                            saveLayout()
                        } else if (!moved && dragged.isFolder) {
                            openedFolder=dragged
                        } else if (!moved) launch(dragged.apps.first())
                    }
                    selected=null
                    dragging=false
                    longPressTriggered=false
                    invalidate()
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchHandler.removeCallbacks(longPressAction)
                    touchHandler.removeCallbacks(folderItemLongPressAction)
                    if (openedFolder != null) {
                        folderTouchIndex=-1
                        folderTouchMoved=false
                        folderItemLongPressed=false
                        return true
                    }
                    selected=null
                    dragging=false
                    moved=false
                    invalidate()
                    return true
                }
            }
            return true
        }

        private fun saveLayout() {
            val array=JSONArray()
            orbs.forEach { orb ->
                val item=JSONObject()
                val members=JSONArray()
                orb.apps.forEach { members.put(it.launchUrl?.let { url -> "url:$url" } ?: it.packageName) }
                item.put("apps",members)
                item.put("folderName", orb.folderName)
                item.put("isFolder", orb.isFolder)
                item.put("x",if(width>0) orb.x/width else 0.5f)
                item.put("y",if(height>0) orb.y/height else 0.3f)
                item.put("page",orb.page)
                array.put(item)
            }
            getSharedPreferences("cosmos_launcher", MODE_PRIVATE).edit()
                .putString("layout",array.toString())
                .putInt("layout_version", 3)
                .putInt("current_page", currentPage)
                .apply()
        }

        private fun restoreLayout() {
            val prefs = getSharedPreferences("cosmos_launcher", MODE_PRIVATE)
            val savedVersion = prefs.getInt("layout_version", 0)
            currentPage = if (savedVersion < 3) 0 else prefs.getInt("current_page", 0).coerceIn(0, 2)
            val raw = prefs.getString("layout", null)
            // Version 3 rebuilds the reference desktops once: earlier builds could
            // scatter every unmatched installed app across desktop 2.
            if (savedVersion < 3 || raw == null) {
                saveLayout()
                saveKnownPackages()
                return
            }
            try {
                val saved=JSONArray(raw)
                val byPackage=(allApps + orbs.flatMap { it.apps }.filter { it.launchUrl != null }).associateBy { it.packageName }
                val restored=mutableListOf<Orb>()
                for (i in 0 until saved.length()) {
                    val item=saved.getJSONObject(i)
                    val members=item.getJSONArray("apps")
                    val entries=mutableListOf<AppEntry>()
                    for (j in 0 until members.length()) {
                        val packageName = members.getString(j)
                        // Migrate older saved layouts: pinned dock apps must not reappear on desktops.
                        if (packageName.startsWith("url:")) {
                            val url = packageName.removePrefix("url:")
                            val title = if (url.contains("shahmaty-mistikov")) "Шантарж" else "CosmosA27"
                            val icon = allApps.firstOrNull()?.icon ?: Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                            entries += AppEntry(title, packageName, icon, url)
                        } else if (dock.none { it.packageName == packageName }) {
                            byPackage[packageName]?.let(entries::add)
                        }
                    }
                    val savedFolderName = item.optString("folderName","Папка")
                    // Restore the complete predefined contents of every reference folder.
                    // Older builds could persist only a partial member list; match by folder
                    // name so each folder receives only its own apps, never another folder's.
                    val customizedFolders = prefs.getStringSet("customized_folders", emptySet()).orEmpty()
                    if (item.optBoolean("isFolder", entries.size > 1) &&
                        savedFolderName !in customizedFolders &&
                        savedFolderName.lowercase().replace(" ", "").replace("_", "") !in customizedFolders) {
                        val normalizedSavedName = savedFolderName.lowercase().replace(" ", "").replace("_", "")
                        val referenceFolder = orbs.firstOrNull {
                            it.isFolder &&
                                it.folderName.lowercase().replace(" ", "").replace("_", "") == normalizedSavedName
                        }
                        referenceFolder?.apps?.forEach { app ->
                            if (entries.none { it.packageName == app.packageName } &&
                                dock.none { it.packageName == app.packageName }) {
                                entries.add(app)
                            }
                        }
                    }
                    if (entries.isNotEmpty()) restored += Orb(
                        entries,
                        item.optDouble("x",.5).toFloat()*width,
                        item.optDouble("y",.3).toFloat()*height,
                        page=item.optInt("page",0).coerceIn(0,2),
                        folderName=savedFolderName,
                        forceFolder=item.optBoolean("isFolder", entries.size > 1)
                    )
                }
                // An explicitly saved empty array means the user removed every desktop orb.
                // Restore it as empty instead of recreating the default app layout.
                orbs.clear()
                orbs.addAll(restored)
                // Keep the four explicitly selected dock apps; never replace them alphabetically.
                saveKnownPackages()
            } catch (_: Exception) { }
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
