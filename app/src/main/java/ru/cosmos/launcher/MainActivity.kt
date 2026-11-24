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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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

    private data class AppEntry(val title: String, val packageName: String, val icon: Bitmap, val launchUrl: String? = null)
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
        val radius: Float get() = if (isFolder) 37f else 29f
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
                groups.forEach { words -> take(*words.toTypedArray())?.let(members::add) }
                if (members.isNotEmpty()) orbs += Orb(members, page = page, folderName = name, forceFolder = true)
            }
            fun pin(vararg words: String) {
                take(*words)?.let(dock::add)
            }

            // Fixed dock order from the reference screen.
            pin("телефон", "phone", "dialer")
            pin("сообщения", "messages", "messaging")
            pin("chrome")
            pin("галерея", "gallery", "photos")

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
                listOf("google")
            ))
            addFolder("Инструменты", 0, listOf(
                listOf("калькулятор", "calculator"), listOf("часы", "clock"),
                listOf("погода", "weather"), listOf("диктофон", "recorder"),
                listOf("компас", "compass"), listOf("заметки", "notes")
            ))
            addApp(0, "настройки", "settings")

            // Desktop 2: named folders and apps from the second reference screen.
            addFolder("Игры", 1, listOf(
                listOf("game"), listOf("игр"), listOf("play games")
            ))
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
            // Keep any apps not recognized by the reference layout available rather than hiding them.
            allApps.filter { it.packageName !in used }.forEach { app ->
                if (app.packageName !in dock.map { it.packageName }) {
                    orbs += Orb(mutableListOf(app), page = 1)
                    used += app.packageName
                }
            }
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
            dock.removeAll { it.packageName !in freshByPackage }
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
                val speed = (38f + (index % 4) * 4f) * d
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
            val available = allApps.filter { it.packageName !in placed }
            if (available.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Добавить приложение")
                    .setMessage("Все установленные приложения уже размещены. Новые появятся здесь автоматически после установки.")
                    .setPositiveButton("Понятно", null)
                    .show()
                return
            }
            val labels = available.map { it.title }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Добавить шар на экран")
                .setItems(labels) { _, which ->
                    val app = available[which]
                    val count = orbs.count { it.page == currentPage }
                    val col = count % 4
                    val row = count / 4
                    val minY = 104f*d
                    val maxY = (height - 126f*d).coerceAtLeast(minY + 60f*d)
                    val x = (width * (0.13f + col * 0.245f)).coerceIn(34f*d, width - 34f*d)
                    val y = (minY + row * 76f*d).coerceIn(minY, maxY)
                    val angle = (app.packageName.hashCode() % 360) * (Math.PI / 180.0)
                    val speed = 42f*d
                    orbs += Orb(mutableListOf(app), x, y, cos(angle).toFloat()*speed,
                        sin(angle).toFloat()*speed, page = currentPage)
                    saveLayout()
                    invalidate()
                }
                .setNegativeButton("Отмена", null)
                .show()
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
                    val speed = (34f + (index % 5) * 3.5f) * d
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
            canvas.drawColor(Color.rgb(5, 9, 27))
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

            val spherePath = Path().apply {
                addCircle(orb.x, orb.y, r, Path.Direction.CW)
            }
            val save = c.save()
            c.clipPath(spherePath)

            // Soft colored glass tint behind and over the original icon.
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                orb.x - r, orb.y - r, orb.x + r, orb.y + r,
                intArrayOf(Color.argb(105, 255, 255, 255), Color.argb(28, Color.red(base), Color.green(base), Color.blue(base)), Color.argb(105, Color.red(deep), Color.green(deep), Color.blue(deep))),
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
                    c.drawBitmap(app.icon, null, RectF(cx-mini*.48f, cy-mini*.48f, cx+mini*.48f, cy+mini*.48f), paint)
                }
            } else {
                val icon = orb.apps.first().icon
                val iconRect = RectF(orb.x-r, orb.y-r, orb.x+r, orb.y+r)
                c.drawBitmap(icon, null, iconRect, paint)
            }

            // Dark translucent label strip keeps the app artwork visible and text readable.
            paint.color = Color.argb(118, 5, 10, 28)
            c.drawRect(orb.x-r, orb.y+r*.38f, orb.x+r, orb.y+r, paint)

            // Subtle glass highlight.
            paint.shader = RadialGradient(
                orb.x - r * .34f, orb.y - r * .48f, r * .58f,
                intArrayOf(Color.argb(105, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(orb.x, orb.y, r*.96f, paint)
            paint.shader = null
            c.restoreToCount(save)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.15f * d
            paint.color = Color.argb(190, 220, 240, 255)
            c.drawCircle(orb.x, orb.y, r - .7f*d, paint)
            paint.style = Paint.Style.FILL

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
            paint.shader = LinearGradient(left,top,right,bottom,
                intArrayOf(Color.argb(205,30,75,125),Color.argb(175,12,29,62)),
                null,Shader.TileMode.CLAMP)
            c.drawRoundRect(weatherWidgetRect,22f*d,22f*d,paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f*d
            paint.color = Color.argb(190,190,225,255)
            c.drawRoundRect(weatherWidgetRect,22f*d,22f*d,paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.create("sans-serif-medium",Typeface.BOLD)
            paint.textSize = 17f*d
            c.drawText("Meteored",left+18f*d,top+31f*d,paint)
            paint.textSize = 12f*d
            paint.typeface = Typeface.DEFAULT
            paint.color = Color.argb(220,220,235,255)
            c.drawText("Погодный виджет",left+18f*d,top+57f*d,paint)
            paint.textSize = 11f*d
            paint.color = Color.argb(185,210,225,245)
            c.drawText("Нажми, чтобы открыть прогноз",left+18f*d,top+82f*d,paint)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 35f*d
            paint.color = Color.rgb(255,214,105)
            c.drawText("☀",right-42f*d,top+68f*d,paint)
        }

        private fun drawDeletePill(c: Canvas) {
            val w = 124f*d
            val h = 38f*d
            deletePill.set((width-w)/2f, 18f*d, (width+w)/2f, 18f*d+h)
            paint.color = Color.argb(235, 155, 42, 58)
            c.drawRoundRect(deletePill, 22f*d, 22f*d, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = 14f*d
            c.drawText("Удалить", width/2f, deletePill.centerY()+5f*d, paint)
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
            paint.color = Color.argb(125, 32, 47, 76)
            c.drawRoundRect(12f*d, top, width-12f*d, height-9f*d, 24f*d, 24f*d, paint)
            val count = dock.size.coerceAtLeast(1)
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
                c.drawBitmap(app.icon, null, RectF(cx-radius, cy-radius, cx+radius, cy+radius), paint)
                paint.shader = LinearGradient(cx-radius, cy-radius, cx+radius, cy+radius,
                    intArrayOf(Color.argb(58, 255, 255, 255),
                        Color.argb(18, Color.red(tint), Color.green(tint), Color.blue(tint)),
                        Color.argb(78, Color.red(deep), Color.green(deep), Color.blue(deep))),
                    null, Shader.TileMode.CLAMP)
                c.drawRect(cx-radius, cy-radius, cx+radius, cy+radius, paint)
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
            paint.color = Color.argb(210, 4, 8, 24)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = Color.argb(245, 25, 38, 68)
            val box = RectF(22f*d, height*.19f, width-22f*d, height*.78f)
            c.drawRoundRect(box, 25f*d, 25f*d, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 19f*d
            c.drawText(folder.folderName, width/2f, box.top+36f*d, paint)
            paint.textAlign=Paint.Align.RIGHT
            paint.textSize=12f*d
            paint.color=Color.argb(210,190,215,255)
            c.drawText("✎",box.right-18f*d,box.top+35f*d,paint)
            paint.textAlign=Paint.Align.CENTER
            val cellW = (box.width()-28f*d)/3f
            val contentClip = c.save()
            c.clipRect(box.left+8f*d, box.top+62f*d, box.right-8f*d, box.bottom-34f*d)
            folder.apps.forEachIndexed { i, app ->
                val col=i%3; val row=i/3
                val cx=box.left+14f*d+cellW*(col+.5f)
                val cy=box.top+92f*d+row*91f*d-folderScrollOffset
                if (cy > box.top+55f*d && cy < box.bottom-30f*d) {
                    val size=43f*d
                    c.drawBitmap(app.icon,null,RectF(cx-size/2,cy-size/2,cx+size/2,cy+size/2),paint)
                    paint.color=Color.WHITE; paint.textSize=10f*d
                    c.drawText(app.title.take(15),cx,cy+size/2+14f*d,paint)
                }
            }
            c.restoreToCount(contentClip)
            paint.color=Color.LTGRAY; paint.textSize=12f*d
            c.drawText("Нажми вне окна, чтобы закрыть",width/2f,box.bottom-18f*d,paint)
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
            val maxSpeed = 78f*d
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
            } else {
                packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
            }
        }

        fun closeFolderOnBack(): Boolean {
            if (openedFolder == null) return false
            openedFolder = null
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

        private fun openGoogleSearch() {
            val input = EditText(this@MainActivity).apply {
                hint = "Что ищем в Google?"
                setSingleLine(true)
                textSize = 16f
                setPadding((16f*d).toInt(), (12f*d).toInt(), (16f*d).toInt(), (12f*d).toInt())
                imeOptions = EditorInfo.IME_ACTION_SEARCH
            }
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Google")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Найти") { _, _ ->
                    val query = input.text.toString().trim()
                    if (query.isNotEmpty()) {
                        val googleAppIntent = Intent(Intent.ACTION_WEB_SEARCH)
                            .setPackage("com.google.android.googlequicksearchbox")
                            .putExtra("query", query)
                        try {
                            startActivity(googleAppIntent)
                        } catch (_: Exception) {
                            val browserIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
                            )
                            startActivity(browserIntent)
                        }
                    }
                }
                .create()
            dialog.setOnShowListener {
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                input.requestFocus()
            }
            dialog.show()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX=e.x; downY=e.y; lastX=e.x; lastY=e.y
                    moved=false; dragging=false; longPressTriggered=false; edgeSwitchArmed=true; swipeHandled=false
                    touchHandler.removeCallbacks(longPressAction)
                    if (openedFolder != null) {
                        val folder=openedFolder!!
                        val box=RectF(22f*d,height*.19f,width-22f*d,height*.78f)
                        if (!box.contains(e.x,e.y)) {
                            openedFolder=null
                            folderScrollOffset=0f
                            invalidate()
                            return true
                        }
                        folderDownY=e.y
                        folderDownOffset=folderScrollOffset
                        folderTouchMoved=false
                        folderHeaderTapped = e.y < box.top + 62f*d
                        val cellW=(box.width()-28f*d)/3f
                        val col=((e.x-box.left-14f*d)/cellW).toInt()
                        val row=((e.y-box.top-55f*d+folderScrollOffset)/(91f*d)).toInt()
                        folderTouchIndex=if (!folderHeaderTapped && col in 0..2 && row>=0) row*3+col else -1
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
                    if (openedFolder != null) {
                        if (abs(e.y-folderDownY)>8f*d) {
                            folderTouchMoved=true
                            val boxHeight=height*.59f
                            val rows=ceil(openedFolder!!.apps.size/3.0).toInt()
                            val visibleHeight=(boxHeight-96f*d).coerceAtLeast(0f)
                            val maxScroll=(rows*91f*d-visibleHeight).coerceAtLeast(0f)
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
                    } else if (!swipeHandled && abs(e.x-downX)>55f*d && abs(e.x-downX)>abs(e.y-downY)) {
                        currentPage=(currentPage + if (e.x<downX) 1 else -1).coerceIn(0,2)
                        getSharedPreferences("cosmos_launcher", MODE_PRIVATE).edit()
                            .putInt("current_page", currentPage).apply()
                        swipeHandled=true
                    }
                    lastX=e.x; lastY=e.y
                    invalidate(); return true
                }
                MotionEvent.ACTION_UP -> {
                    touchHandler.removeCallbacks(longPressAction)
                    if (openedFolder != null) {
                        val folder = openedFolder!!
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
                        if (dragging && deletePill.contains(e.x,e.y)) {
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
                                dragged.vx=((e.x-downX)*.10f).coerceIn(-30f*d,30f*d)
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
                    if (openedFolder != null) {
                        folderTouchIndex=-1
                        folderTouchMoved=false
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
                .putInt("layout_version", 2)
                .putInt("current_page", currentPage)
                .apply()
        }

        private fun restoreLayout() {
            val prefs = getSharedPreferences("cosmos_launcher", MODE_PRIVATE)
            currentPage = prefs.getInt("current_page", 0).coerceIn(0, 2)
            val raw = prefs.getString("layout", null)
            // Rebuild the first reference-based layout once when upgrading from the old
            // auto-sorted launcher. After this, the user's own folder edits are preserved.
            if (prefs.getInt("layout_version", 0) < 2 || raw == null) {
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
                    if (entries.isNotEmpty()) restored += Orb(
                        entries,
                        item.optDouble("x",.5).toFloat()*width,
                        item.optDouble("y",.3).toFloat()*height,
                        page=item.optInt("page",0).coerceIn(0,2),
                        folderName=item.optString("folderName","Папка"),
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
