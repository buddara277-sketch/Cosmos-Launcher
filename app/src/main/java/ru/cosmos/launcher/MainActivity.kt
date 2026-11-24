package ru.cosmos.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(CosmosField())
    }

    private data class AppEntry(val title: String, val packageName: String, val icon: Bitmap)
    private data class Orb(var x: Float, var y: Float, var vx: Float, var vy: Float, val entry: AppEntry, val radius: Float)

    private inner class CosmosField : View(this) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbs = mutableListOf<Orb>()
        private var previous = 0L
        private var selected: Orb? = null
        private var downX = 0f
        private var downY = 0f
        private var dragged = false

        init { loadApps(); isClickable = true }

        private fun loadApps() {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
                .take(36)
                .forEachIndexed { i, info ->
                    val icon = toBitmap(info.loadIcon(packageManager))
                    val entry = AppEntry(info.loadLabel(packageManager).toString(), info.activityInfo.packageName, icon)
                    val a = i * 2.399f
                    orbs += Orb(0f, 0f, cos(a) * 42f * d, sin(a) * 42f * d, entry, 29f * d)
                }
        }

        private fun toBitmap(drawable: Drawable): Bitmap {
            val size = (96 * d).toInt().coerceAtLeast(64)
            return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it); drawable.setBounds(0, 0, size, size); drawable.draw(c)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            orbs.forEachIndexed { i, o ->
                if (o.x == 0f && o.y == 0f) {
                    o.x = (w * (0.12f + (i % 5) * 0.18f)).coerceIn(o.radius, w - o.radius)
                    o.y = (h * (0.12f + (i / 5) * 0.105f)).coerceIn(o.radius, h - o.radius)
                }
            }
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(5, 9, 27))
            val now = System.nanoTime()
            val dt = if (previous == 0L) .016f else ((now - previous) / 1e9f).coerceIn(0f, .03f)
            previous = now
            physics(dt)
            orbs.forEach { o ->
                p.color = Color.argb(55, 60, 145, 255)
                c.drawCircle(o.x, o.y, o.radius + 7f * d, p)
                c.drawBitmap(o.entry.icon, null, RectF(o.x-o.radius, o.y-o.radius, o.x+o.radius, o.y+o.radius), p)
                p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = 10f*d
                p.setShadowLayer(4f*d, 0f, 1f*d, Color.BLACK)
                c.drawText(o.entry.title.take(17), o.x, o.y+o.radius+15f*d, p)
                p.clearShadowLayer()
            }
            postInvalidateOnAnimation()
        }

        private fun physics(dt: Float) {
            val left = 5f*d; val right = width-5f*d
            val top = 8f*d; val bottom = height-22f*d
            orbs.forEach { o ->
                if (o === selected) return@forEach
                o.x += o.vx*dt; o.y += o.vy*dt
                if (o.x < left+o.radius || o.x > right-o.radius) { o.vx = -o.vx; o.x = o.x.coerceIn(left+o.radius, right-o.radius) }
                if (o.y < top+o.radius || o.y > bottom-o.radius) { o.vy = -o.vy; o.y = o.y.coerceIn(top+o.radius, bottom-o.radius) }
            }
            for (i in orbs.indices) for (j in i+1 until orbs.size) {
                val a=orbs[i]; val b=orbs[j]; val dx=b.x-a.x; val dy=b.y-a.y
                val dist=hypot(dx,dy); val min=a.radius+b.radius
                if (dist > .01f && dist < min) {
                    val nx=dx/dist; val ny=dy/dist; val overlap=min-dist
                    a.x-=nx*overlap*.5f; a.y-=ny*overlap*.5f; b.x+=nx*overlap*.5f; b.y+=ny*overlap*.5f
                    val rel=(b.vx-a.vx)*nx+(b.vy-a.vy)*ny
                    if (rel<0f) { val impulse=rel*.9f; a.vx+=impulse*nx; a.vy+=impulse*ny; b.vx-=impulse*nx; b.vy-=impulse*ny }
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX=e.x; downY=e.y; dragged=false
                    selected=orbs.asReversed().firstOrNull { hypot(e.x-it.x,e.y-it.y)<it.radius+12f*d }
                    return true
                }
                MotionEvent.ACTION_MOVE -> selected?.let {
                    if (hypot(e.x-downX,e.y-downY)>9f*d) dragged=true
                    it.x=e.x; it.y=e.y; it.vx=0f; it.vy=0f; invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    selected?.let {
                        if (!dragged) packageManager.getLaunchIntentForPackage(it.entry.packageName)?.let(::startActivity)
                        else { it.vx=((e.x-downX)*2f).coerceIn(-500f*d,500f*d); it.vy=((e.y-downY)*2f).coerceIn(-500f*d,500f*d) }
                    }
                    selected=null; performClick(); return true
                }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
