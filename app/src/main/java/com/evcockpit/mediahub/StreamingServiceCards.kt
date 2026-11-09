package com.evcockpit.mediahub

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Original cinema-style source cards, adapted to the user's MediaEngine screenshot:
 * large artwork, dark title section and a circular launch action.
 *
 * All drawings below are local vector artwork, not third-party thumbnails.
 * This is the phone dashboard; Android Auto's moving-driver UI remains audio-only.
 */
object StreamingServiceCards {
    private enum class Platform(
        val title: String, val subtitle: String,
        val tint: Int, val dark: Int
    ) {
        YOUTUBE("YouTube", "Katalog · Ara · Videolar", 0xfff23a57.toInt(), 0xff270d1b.toInt()),
        NETFLIX("Netflix", "Katalog · Ara · Son içerikler", 0xffdf333d.toInt(), 0xff1f101d.toInt()),
        PRIME("Prime Video", "Katalog · Ara · Son içerikler", 0xff34b9e9.toInt(), 0xff092536.toInt())
    }

    private fun dp(activity: Activity, n: Int) =
        (n * activity.resources.displayMetrics.density).toInt()

    private fun gradient(activity: Activity, a: Int, b: Int, radius: Int, border: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply {
            cornerRadius = dp(activity, radius).toFloat()
            setStroke(dp(activity, 1), border)
        }

    fun render(
        activity: Activity,
        youtube: () -> Unit,
        netflix: () -> Unit,
        prime: () -> Unit
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        listOf(
            Platform.YOUTUBE to youtube,
            Platform.NETFLIX to netflix,
            Platform.PRIME to prime
        ).forEach { (platform, launch) ->
            val card = createCard(activity, platform, launch)
            addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(activity, 13)
            })
        }
    }

    /** A single service card, used to preserve the video demo's TV → YouTube order. */
    fun youtube(activity: Activity, launch: () -> Unit): LinearLayout =
        createCard(activity, Platform.YOUTUBE, launch)

    fun subscriptions(activity: Activity, netflix: () -> Unit,
                      prime: () -> Unit): LinearLayout = LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL
        addView(createCard(activity,Platform.NETFLIX,netflix),
            LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(activity,12) })
        addView(createCard(activity,Platform.PRIME,prime))
    }

    private fun createCard(
        activity: Activity, platform: Platform, launch: () -> Unit
    ): LinearLayout {
        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient(activity, 0xff17232f.toInt(), platform.dark, 23, platform.tint)
            clipToOutline = true
            isClickable = true
            isFocusable = true
            elevation = dp(activity, 3).toFloat()
            contentDescription = platform.title + ". " + platform.subtitle + ". Aç"
            setOnClickListener { launch() }
        }

        shell.addView(PosterArtwork(activity, platform),
            LinearLayout.LayoutParams(-1, dp(activity, 164)))

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 17), dp(activity, 14))
        }
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        labels.addView(TextView(activity).apply {
            text = platform.title
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
        labels.addView(TextView(activity).apply {
            text = platform.subtitle
            textSize = 12.5f
            setTextColor(0xffaebbc7.toInt())
            maxLines = 2
            setPadding(0, dp(activity, 3), 0, 0)
        })
        footer.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(TextView(activity).apply {
            text = "›"
            textSize = 35f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = gradient(activity, platform.dark, platform.tint, 40, platform.tint)
            isClickable = true
            isFocusable = true
            contentDescription = platform.title + " aç"
            setOnClickListener { launch() }
        }, LinearLayout.LayoutParams(dp(activity, 54), dp(activity, 54)))
        shell.addView(footer, LinearLayout.LayoutParams(-1, dp(activity, 98)))
        return shell
    }

    private class PosterArtwork(activity: Activity, private val platform: Platform) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bold = Typeface.create("sans-serif-black", Typeface.BOLD)
        private val rect = RectF()

        private fun fill(color: Int) {
            paint.shader = null
            paint.color = color
            paint.alpha = Color.alpha(color)
            paint.style = Paint.Style.FILL
        }

        private fun grad(canvas: Canvas, a: Int, b: Int) {
            paint.shader = LinearGradient(0f, 0f, 360f, 172f, a, b, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 360f, 172f, paint)
            paint.shader = null
        }

        private fun round(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, c: Int) {
            fill(c)
            rect.set(x, y, x + w, y + h)
            canvas.drawRoundRect(rect, r, r, paint)
        }

        private fun lettering(canvas: Canvas, value: String, x: Float, y: Float,
                              size: Float, color: Int, font: Typeface = bold) {
            fill(color)
            paint.typeface = font
            paint.textSize = size
            canvas.drawText(value, x, y, paint)
        }

        // Imaginary film frames, deliberately not scraped/copyrighted service artwork.
        private fun filmFrame(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                              top: Int, bottom: Int, id: Int) {
            paint.shader = LinearGradient(x,y,x+w,y+h,top,bottom,Shader.TileMode.CLAMP)
            rect.set(x,y,x+w,y+h)
            canvas.drawRoundRect(rect, 5f, 5f, paint)
            paint.shader = null
            fill(0x66ffcfb3)
            canvas.drawCircle(x+w*(0.22f+0.18f*(id%3)),y+h*0.28f, h*0.17f,paint)
            val path = Path().apply {
                moveTo(x,y+h*0.94f)
                lineTo(x+w*0.28f,y+h*0.55f)
                lineTo(x+w*0.49f,y+h*0.8f)
                lineTo(x+w*0.73f,y+h*0.43f)
                lineTo(x+w,y+h*0.86f)
                lineTo(x+w,y+h)
                lineTo(x,y+h)
                close()
            }
            fill(0x99050d19.toInt())
            canvas.drawPath(path,paint)
            round(canvas,x+6,y+h-9,w*0.50f,2f,1f,0x88ffffff.toInt())
        }

        private fun youtube(canvas: Canvas) {
            grad(canvas,0xff240c18.toInt(),0xff101720.toInt())
            val colors = arrayOf(
                0xff764265.toInt() to 0xff1d3153.toInt(),
                0xff6b3f29.toInt() to 0xff412237.toInt(),
                0xff33495d.toInt() to 0xff2d244e.toInt(),
                0xff604259.toInt() to 0xff252448.toInt()
            )
            for (i in 0..8) {
                val x=165f+(i%3)*62f
                val y=10f+(i/3)*54f
                val c=colors[i%colors.size]
                filmFrame(canvas,x,y,57f,48f,c.first,c.second,i)
            }
            round(canvas,28f,48f,124f,80f,21f,0xfff10c20.toInt())
            val triangle = Path().apply {
                moveTo(78f,69f);lineTo(78f,108f);lineTo(111f,89f);close()
            }
            fill(Color.WHITE)
            canvas.drawPath(triangle,paint)
            round(canvas,0f,146f,360f,26f,0f,0x4406000a)
        }

        private fun netflix(canvas: Canvas) {
            grad(canvas,0xff20111e.toInt(),0xff050809.toInt())
            val colors=arrayOf(
                0xff382950.toInt() to 0xff112036.toInt(),
                0xff7d2f3e.toInt() to 0xff30142e.toInt(),
                0xff2a4c54.toInt() to 0xff1a1c35.toInt()
            )
            for (i in 0..5) {
                val x=170f+(i%3)*61f
                val y=12f+(i/3)*76f
                val p=colors[i%3]
                filmFrame(canvas,x,y,55f,69f,p.first,p.second,i+2)
            }
            // Subtle theatrical light under the large N.
            paint.shader = LinearGradient(25f,0f,161f,172f,
                0xff450d1f.toInt(),0xff08080d.toInt(),Shader.TileMode.CLAMP)
            canvas.drawRect(15f,0f,162f,172f,paint)
            paint.shader=null
            lettering(canvas,"N",42f,132f,119f,0xffe50914.toInt())
            lettering(canvas,"NETFLIX",45f,158f,14f,0xfff9ebed.toInt())
        }

        private fun prime(canvas: Canvas) {
            grad(canvas,0xff073956.toInt(),0xff051527.toInt())
            val colors=arrayOf(
                0xff24567b.toInt() to 0xff131e3e.toInt(),
                0xff335b55.toInt() to 0xff152d3d.toInt(),
                0xff54508a.toInt() to 0xff1c2545.toInt()
            )
            for (i in 0..5) {
                val x=181f+(i%3)*57f
                val y=12f+(i/3)*76f
                val p=colors[i%3]
                filmFrame(canvas,x,y,52f,69f,p.first,p.second,i+6)
            }
            round(canvas,14f,20f,153f,130f,16f,0xbb04243c.toInt())
            lettering(canvas,"prime",23f,81f,43f,Color.WHITE)
            lettering(canvas,"video",26f,114f,31f,0xff4ad0fa.toInt())
            fill(0xff4ad0fa.toInt())
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=4.5f
            paint.strokeCap=Paint.Cap.ROUND
            canvas.drawArc(29f,110f,133f,140f,7f,155f,false,paint)
            paint.style=Paint.Style.FILL
            val arrow=Path().apply{
                moveTo(132f,131f);lineTo(124f,125f);lineTo(122f,136f);close()
            }
            canvas.drawPath(arrow,paint)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width<=0 || height<=0) return
            canvas.save()
            canvas.scale(width/360f,height/172f)
            when(platform) {
                Platform.YOUTUBE -> youtube(canvas)
                Platform.NETFLIX -> netflix(canvas)
                Platform.PRIME -> prime(canvas)
            }
            canvas.restore()
        }
    }
}
