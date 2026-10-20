package com.fitalarm.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import com.fitalarm.app.core.Lm
import com.fitalarm.app.core.PoseFrame

/** 자식(카메라 미리보기·골격 오버레이)을 지정한 가로세로비로 부모 안에 맞춤 */
class AspectRatioFrameLayout(context: Context) : FrameLayout(context) {
    private var ratio = 3f / 4f   // width / height

    fun setAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val r = width.toFloat() / height
        if (r != ratio) {
            ratio = r
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        if (maxW == 0 || maxH == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        var w = maxW
        var h = (w / ratio).toInt()
        if (h > maxH) {
            h = maxH
            w = (h * ratio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}

/** 인식된 관절을 미리보기 위에 선과 점으로 그림 */
class SkeletonOverlayView(context: Context) : View(context) {
    private var frame: PoseFrame? = null
    private var imgW = 1
    private var imgH = 1
    private var mirrored = false
    private var tracking = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = context.dpf(4)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setPose(frame: PoseFrame?, imageWidth: Int, imageHeight: Int, mirrored: Boolean, tracking: Boolean) {
        this.frame = frame
        this.imgW = imageWidth.coerceAtLeast(1)
        this.imgH = imageHeight.coerceAtLeast(1)
        this.mirrored = mirrored
        this.tracking = tracking
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame ?: return
        val sx = width.toFloat() / imgW
        val sy = height.toFloat() / imgH
        // 전면 카메라 미리보기는 거울처럼 좌우 반전되어 보이므로 좌표도 반전
        fun x(px: Float) = (if (mirrored) imgW - px else px) * sx
        fun y(py: Float) = py * sy

        linePaint.color = if (tracking) 0xE6FFFFFF.toInt() else 0x80FFFFFF.toInt()
        jointPaint.color = if (tracking) Palette.ACCENT else Palette.TEXT_DIM

        for ((a, b) in CONNECTIONS) {
            val pa = f.visible(a) ?: continue
            val pb = f.visible(b) ?: continue
            canvas.drawLine(x(pa.x), y(pa.y), x(pb.x), y(pb.y), linePaint)
        }
        val r = context.dpf(5)
        for (i in JOINTS) {
            val p = f.visible(i) ?: continue
            canvas.drawCircle(x(p.x), y(p.y), r, jointPaint)
        }
    }

    private companion object {
        val CONNECTIONS = listOf(
            Lm.LEFT_SHOULDER to Lm.RIGHT_SHOULDER,
            Lm.LEFT_SHOULDER to Lm.LEFT_ELBOW, Lm.LEFT_ELBOW to Lm.LEFT_WRIST,
            Lm.RIGHT_SHOULDER to Lm.RIGHT_ELBOW, Lm.RIGHT_ELBOW to Lm.RIGHT_WRIST,
            Lm.LEFT_SHOULDER to Lm.LEFT_HIP, Lm.RIGHT_SHOULDER to Lm.RIGHT_HIP,
            Lm.LEFT_HIP to Lm.RIGHT_HIP,
            Lm.LEFT_HIP to Lm.LEFT_KNEE, Lm.LEFT_KNEE to Lm.LEFT_ANKLE,
            Lm.RIGHT_HIP to Lm.RIGHT_KNEE, Lm.RIGHT_KNEE to Lm.RIGHT_ANKLE,
        )
        val JOINTS = intArrayOf(
            Lm.NOSE,
            Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW,
            Lm.LEFT_WRIST, Lm.RIGHT_WRIST, Lm.LEFT_HIP, Lm.RIGHT_HIP,
            Lm.LEFT_KNEE, Lm.RIGHT_KNEE, Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE,
        )
    }
}

/** 둥근 진행 막대 (0..1) */
class ProgressBarView(context: Context) : View(context) {
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var trackColor: Int = 0x33FFFFFF
    var fillColor: Int = Palette.ACCENT

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val r = h / 2f
        paint.color = trackColor
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, paint)
        if (progress > 0f) {
            paint.color = fillColor
            rect.set(0f, 0f, (width * progress).coerceAtLeast(h), h)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}
