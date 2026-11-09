package com.fitalarm.app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.fitalarm.app.core.PhotoQuality
import com.fitalarm.app.core.PhotoSize
import com.fitalarm.app.core.QualityOption
import com.fitalarm.app.core.QualityTier
import com.fitalarm.app.systemService
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * 눈바디 촬영용 Camera2: 미리보기 + 정지 사진(JPEG). 미션용 PoseCamera 와 달리 폰이 지원하는
 * 최대 해상도까지 찍음 (미리보기는 화면에 맞는 작은 크기).
 *
 * 스레드: start/stop/capture 는 메인, 카메라 콜백과 파일 쓰기는 start 마다 새로 만드는 HandlerThread.
 * 늦게 도착한 이전 세션의 콜백은 generation 으로 무시 (PoseCamera 와 같은 방식).
 */
class StillCamera(
    private val activity: Activity,
    private val textureView: TextureView,
    private val listener: Listener,
) {
    interface Listener {
        /** 미리보기 비율(세운 기준)과 이 카메라에서 고를 수 있는 화질 */
        fun onCameraReady(uprightWidth: Int, uprightHeight: Int, front: Boolean, options: List<QualityOption>, chosen: QualityOption)
        fun onCameraError(message: String)
        /** 찍은 JPEG 를 file 에 썼음 (메인 스레드) */
        fun onCaptured(file: File, front: Boolean)
        fun onCaptureFailed(message: String)
    }

    private val cameraManager: CameraManager = activity.systemService()
    private val main = Handler(Looper.getMainLooper())

    /** 다음 start() 부터 적용 */
    var front = false
    var tier = QualityTier.MAX

    private val lock = Any()
    private var generation = 0
    private var running = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var cameraRequested = false
    private var released = false

    @Volatile private var active = false
    @Volatile private var previewSize = Size(1440, 1080)
    @Volatile private var sensorOrientation = 90
    @Volatile private var isFront = false
    @Volatile private var afContinuous = false
    @Volatile private var captureTarget: File? = null

    val isCapturing: Boolean get() = captureTarget != null

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            if (active && !cameraRequested) openCamera()
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = configureTransform(width, height)
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    init {
        textureView.surfaceTextureListener = surfaceListener
    }

    fun hasLens(wantFront: Boolean): Boolean = findCamera(cameraManager, wantFront, exact = true) != null

    fun start() {
        if (active || released) return
        synchronized(lock) {
            generation++
            running = true
        }
        active = true
        cameraRequested = false
        captureTarget = null
        val t = HandlerThread("StillCamera").also { it.start() }
        bgThread = t
        bgHandler = Handler(t.looper)
        if (textureView.isAvailable) openCamera()
    }

    fun stop() {
        if (!active) return
        active = false
        cameraRequested = false
        captureTarget = null
        var oldSession: CameraCaptureSession?
        var oldDevice: CameraDevice?
        var oldReader: ImageReader?
        var oldSurface: Surface?
        synchronized(lock) {
            running = false
            generation++
            oldSession = session
            oldDevice = device
            oldReader = reader
            oldSurface = previewSurface
            session = null
            device = null
            reader = null
            previewSurface = null
        }
        val h = bgHandler
        val t = bgThread
        bgHandler = null
        bgThread = null
        val closeAll = Runnable {
            try {
                oldSession?.close()
            } catch (e: Exception) {
                // 무시
            }
            oldDevice?.close()
            oldReader?.close()
            oldSurface?.release()
        }
        if (h != null) h.post(closeAll) else closeAll.run()
        t?.quitSafely()
    }

    fun restart() {
        stop()
        start()
    }

    fun release() {
        stop()
        released = true
    }

    // ------------------------------------------------------------------ 열기

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val handler = bgHandler ?: return
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onCameraError("카메라 권한이 없습니다")
            return
        }
        val st = textureView.surfaceTexture ?: return
        cameraRequested = true
        val gen = synchronized(lock) { generation }
        try {
            val id = findCamera(cameraManager, front, exact = false) ?: run {
                listener.onCameraError("사용할 수 있는 카메라가 없습니다")
                return
            }
            val chars = cameraManager.getCameraCharacteristics(id)
            val facingFront = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
                listener.onCameraError("카메라 설정을 읽을 수 없습니다")
                return
            }
            val options = qualityOptions(map)
            val chosen = PhotoQuality.pick(options, tier) ?: run {
                listener.onCameraError("이 카메라로는 사진을 찍을 수 없습니다")
                return
            }
            isFront = facingFront
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            afContinuous = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
            val jpeg = Size(chosen.size.width, chosen.size.height)
            previewSize = choosePreview(map, jpeg)

            val upright = uprightDegrees()
            val (uw, uh) = if (upright % 180 == 90) previewSize.height to previewSize.width else previewSize.width to previewSize.height
            listener.onCameraReady(uw, uh, facingFront, options, chosen)
            configureTransform(textureView.width, textureView.height)
            cameraManager.openCamera(id, DeviceCallback(gen, st, jpeg, handler), handler)
        } catch (e: CameraAccessException) {
            listener.onCameraError("카메라를 열 수 없습니다 (${e.reason})")
        } catch (e: SecurityException) {
            listener.onCameraError("카메라 권한이 없습니다")
        } catch (e: IllegalArgumentException) {
            listener.onCameraError("카메라를 열 수 없습니다")
        }
    }

    /** 미리보기: 찍을 사진과 같은 비율 중 화면에 충분한 크기 (너무 크면 버벅임) */
    private fun choosePreview(map: StreamConfigurationMap, jpeg: Size): Size {
        val all = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val ratio = jpeg.width.toFloat() / jpeg.height
        val same = all.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f }
        val bounded = same.filter { it.width <= 1920 && it.height <= 1440 }
        return bounded.maxByOrNull { it.width.toLong() * it.height }
            ?: same.minByOrNull { it.width.toLong() * it.height }
            ?: all.minByOrNull { abs(it.width.toFloat() / it.height - ratio) * 10_000 + abs(it.width * it.height - 1440 * 1080) / 1000f }
            ?: Size(1440, 1080)
    }

    private fun displayRotationDegrees(): Int {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
        }
        return when (r) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** 센서 영상을 화면 방향으로 세우는 각도 = JPEG 방향 */
    private fun uprightDegrees(): Int {
        val d = displayRotationDegrees()
        return if (isFront) (sensorOrientation + d) % 360 else (sensorOrientation - d + 360) % 360
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
        }
        val size = previewSize
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(viewHeight.toFloat() / size.height, viewWidth.toFloat() / size.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        textureView.setTransform(matrix)
    }

    // ------------------------------------------------------------------ 장치·세션 (bg)

    private inner class DeviceCallback(
        private val gen: Int,
        private val texture: SurfaceTexture,
        private val jpeg: Size,
        private val handler: Handler,
    ) : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            val accepted = synchronized(lock) {
                if (running && gen == generation) {
                    device = camera
                    true
                } else false
            }
            if (!accepted) {
                camera.close()
                return
            }
            createSession(camera, gen, texture, jpeg, handler)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            synchronized(lock) { if (device === camera) device = null }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            synchronized(lock) { if (device === camera) device = null }
            postError(gen, "카메라 오류가 발생했습니다 (코드 $error)")
        }
    }

    private fun createSession(camera: CameraDevice, gen: Int, texture: SurfaceTexture, jpeg: Size, handler: Handler) {
        val p = previewSize
        texture.setDefaultBufferSize(p.width, p.height)
        val surface = Surface(texture)
        val r = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
        val accepted = synchronized(lock) {
            if (running && gen == generation) {
                previewSurface = surface
                reader = r
                true
            } else false
        }
        if (!accepted) {
            surface.release()
            r.close()
            return
        }
        r.setOnImageAvailableListener({ onJpeg(it, gen) }, handler)
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    val ok = synchronized(lock) {
                        if (running && gen == generation && device === camera) {
                            session = s
                            true
                        } else false
                    }
                    if (!ok) {
                        s.close()
                        return
                    }
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            if (afContinuous) set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        s.setRepeatingRequest(req, null, handler)
                    } catch (e: CameraAccessException) {
                        postError(gen, "카메라를 시작할 수 없습니다")
                    } catch (e: IllegalStateException) {
                        // 세션이 이미 닫힘
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    postError(gen, "카메라 세션을 구성할 수 없습니다")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            postError(gen, "카메라 세션을 만들 수 없습니다")
        } catch (e: IllegalStateException) {
            // 장치가 이미 닫힘
        }
    }

    /** 사진 한 장 찍기 (메인). 이미 찍는 중이거나 준비 전이면 false */
    fun capture(out: File): Boolean {
        if (!active || captureTarget != null) return false
        val (s, d, r) = synchronized(lock) { Triple(session, device, reader) }
        if (s == null || d == null || r == null) return false
        val h = bgHandler ?: return false
        val gen = synchronized(lock) { generation }
        captureTarget = out
        return try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (afContinuous) set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, uprightDegrees())
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }.build()
            s.capture(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    captureTarget = null
                    main.post { if (isCurrent(gen)) listener.onCaptureFailed("사진을 찍지 못했어요. 다시 눌러 주세요") }
                }
            }, h)
            true
        } catch (e: Exception) {
            captureTarget = null
            false
        }
    }

    private fun onJpeg(r: ImageReader, gen: Int) {
        val image = try {
            r.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        val target = captureTarget
        try {
            if (target == null || !isCurrent(gen)) return
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { it.write(bytes) }
            val f = isFront
            main.post {
                captureTarget = null
                if (isCurrent(gen)) listener.onCaptured(target, f)
            }
        } catch (e: Exception) {
            main.post {
                captureTarget = null
                if (isCurrent(gen)) listener.onCaptureFailed("사진을 저장하지 못했어요")
            }
        } finally {
            image.close()
        }
    }

    private fun isCurrent(gen: Int): Boolean = synchronized(lock) { running && gen == generation }

    private fun postError(gen: Int, message: String) {
        main.post { if (isCurrent(gen)) listener.onCameraError(message) }
    }

    companion object {
        /** wantFront 에 맞는 카메라. exact=false 면 없을 때 다른 쪽이라도 */
        fun findCamera(cm: CameraManager, wantFront: Boolean, exact: Boolean): String? {
            val want = if (wantFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val ids = try {
                cm.cameraIdList.toList()
            } catch (e: Exception) {
                emptyList()
            }
            fun facing(id: String): Int? = try {
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            } catch (e: Exception) {
                null
            }
            return ids.firstOrNull { facing(it) == want } ?: if (exact) null else ids.firstOrNull()
        }

        private fun qualityOptions(map: StreamConfigurationMap): List<QualityOption> =
            PhotoQuality.options(map.getOutputSizes(ImageFormat.JPEG)?.map { PhotoSize(it.width, it.height) }.orEmpty())

        /** 설정 화면용: 카메라를 열지 않고 이 폰의 화질 목록만 */
        fun options(ctx: Context, front: Boolean): List<QualityOption> = try {
            val cm = ctx.systemService<CameraManager>()
            val id = findCamera(cm, front, exact = false)
            val map = id?.let { cm.getCameraCharacteristics(it).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }
            if (map == null) emptyList() else qualityOptions(map)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
