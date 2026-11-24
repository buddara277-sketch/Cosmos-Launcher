package ru.cosmos.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(CosmosField())
    }

    private data class AppEntry(val title: String, val packageName: String, val icon: Bitmap)
    private data class Orb(
        val apps: MutableList<AppEntry>,
        var x: Float = 0f,
        var y: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var page: Int = 0
    ) {
        val isFolder: Boolean get() = apps.size > 1
        val radius: Float get() = 29f
    }

    private inner class CosmosField : View(this) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbs = mutableListOf<Orb>()
        private val dock = mutableListOf<AppEntry>()
        private var currentPage = 0
        private var openedFolder: Orb? = null
        private var previousFrame = 0L
        private var selected: Orb? = null
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false
        private var swipeHandled = false
        private var initialized = false
        private var layoutRestored = false

        init {
            isClickable = true
            loadApps()
        }

        private fun loadApps() {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
                .take(45)
            apps.forEach { info ->
                val entry = AppEntry(
                    info.loadLabel(packageManager).toString(),
                    info.activityInfo.packageName,
                    drawableToBitmap(info.loadIcon(packageManager))
                )
                orbs += Orb(mutableListOf(entry), page = (orbs.size / 12).coerceAtMost(2))
            }
            dock.addAll(orbs.take(5).map { it.apps.first() })
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
                    orb.y = (usableBottom * (0.15f + row * 0.20f)).coerceIn(orb.radius * d + 35f * d, usableBottom - orb.radius * d)
                    val angle = index * 2.399f
                    // Calm, slow drift: each sphere keeps a gentle independent direction.
                    val speed = (15f + (index % 5) * 1.5f) * d
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
            if (openedFolder != null) {
                drawOpenFolder(canvas, openedFolder!!)
            } else {
                val now = System.nanoTime()
                val dt = if (previousFrame == 0L) .016f else ((now - previousFrame) / 1e9f).coerceIn(0f, .03f)
                previousFrame = now
                updatePhysics(dt)
                orbs.filter { it.page == currentPage }.forEach { drawOrb(canvas, it) }
                drawPageIndicator(canvas)
                drawDock(canvas)
            }
            postInvalidateOnAnimation()
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

            val label = if (orb.isFolder) "Папка" else orb.apps.first().title
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
            c.drawText("Папка", width/2f, box.top+36f*d, paint)
            val cellW = (box.width()-28f*d)/3f
            folder.apps.forEachIndexed { i, app ->
                val col=i%3; val row=i/3
                val cx=box.left+14f*d+cellW*(col+.5f)
                val cy=box.top+92f*d+row*91f*d
                val size=43f*d
                c.drawBitmap(app.icon,null,RectF(cx-size/2,cy-size/2,cx+size/2,cy+size/2),paint)
                paint.color=Color.WHITE; paint.textSize=10f*d
                c.drawText(app.title.take(15),cx,cy+size/2+14f*d,paint)
            }
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
                    orb.vx=-orb.vx; orb.x=orb.x.coerceIn(edge+r,width-edge-r)
                }
                if (orb.y < 42f*d+r || orb.y > bottom-r) {
                    orb.vy=-orb.vy; orb.y=orb.y.coerceIn(42f*d+r,bottom-r)
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
        }

        private fun launch(app: AppEntry) {
            packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX=e.x; downY=e.y; lastX=e.x; lastY=e.y
                    moved=false; swipeHandled=false
                    if (openedFolder != null) {
                        val folder=openedFolder!!
                        val box=RectF(22f*d,height*.19f,width-22f*d,height*.78f)
                        if (!box.contains(e.x,e.y)) { openedFolder=null; invalidate(); return true }
                        val cellW=(box.width()-28f*d)/3f
                        val col=((e.x-box.left-14f*d)/cellW).toInt()
                        val row=((e.y-box.top-55f*d)/(91f*d)).toInt()
                        val index=row*3+col
                        if (col in 0..2 && index in folder.apps.indices) launch(folder.apps[index])
                        return true
                    }
                    val dockTop=height-76f*d
                    if (e.y>=dockTop) {
                        val index=(e.x/(width.toFloat()/dock.size.coerceAtLeast(1))).toInt().coerceIn(0,dock.size-1)
                        launch(dock[index]); return true
                    }
                    selected=orbs.asReversed().firstOrNull {
                        it.page==currentPage && hypot(e.x-it.x,e.y-it.y)<it.radius*d+14f*d
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (selected != null) {
                        val orb=selected!!
                        if (hypot(e.x-downX,e.y-downY)>8f*d) moved=true
                        orb.x=e.x; orb.y=e.y; orb.vx=0f; orb.vy=0f
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
                    selected?.let { dragged ->
                        if (moved) {
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
                                dragged.vx=((e.x-downX)*.16f).coerceIn(-42f*d,42f*d)
                                dragged.vy=((e.y-downY)*.16f).coerceIn(-42f*d,42f*d)
                            }
                            saveLayout()
                        } else if (dragged.isFolder) {
                            openedFolder=dragged
                        } else launch(dragged.apps.first())
                    }
                    selected=null
                    performClick()
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
                orb.apps.forEach { members.put(it.packageName) }
                item.put("apps",members)
                item.put("x",if(width>0) orb.x/width else 0.5f)
                item.put("y",if(height>0) orb.y/height else 0.3f)
                item.put("page",orb.page)
                array.put(item)
            }
            getSharedPreferences("cosmos_launcher", MODE_PRIVATE).edit()
                .putString("layout",array.toString())
                .putInt("current_page", currentPage)
                .apply()
        }

        private fun restoreLayout() {
            val prefs = getSharedPreferences("cosmos_launcher", MODE_PRIVATE)
            currentPage = prefs.getInt("current_page", 0).coerceIn(0, 2)
            val raw = prefs.getString("layout", null) ?: return
            try {
                val saved=JSONArray(raw)
                val byPackage=orbs.flatMap { it.apps }.associateBy { it.packageName }
                val restored=mutableListOf<Orb>()
                for (i in 0 until saved.length()) {
                    val item=saved.getJSONObject(i)
                    val members=item.getJSONArray("apps")
                    val entries=mutableListOf<AppEntry>()
                    for (j in 0 until members.length()) byPackage[members.getString(j)]?.let(entries::add)
                    if (entries.isNotEmpty()) restored += Orb(entries, item.optDouble("x",.5).toFloat()*width,
                        item.optDouble("y",.3).toFloat()*height, page=item.optInt("page",0).coerceIn(0,2))
                }
                if (restored.isNotEmpty()) {
                    orbs.clear(); orbs.addAll(restored)
                    dock.clear(); dock.addAll(orbs.flatMap { it.apps }.distinctBy { it.packageName }.take(5))
                }
            } catch (_: Exception) { }
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
