package com.fitalarm.app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.fitalarm.app.core.Lm
import com.fitalarm.app.core.PoseFrame
import com.fitalarm.app.core.Pt
import com.fitalarm.app.systemService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.abs
import kotlin.math.max

/**
 * Camera2 미리보기 + ImageReader 프레임 → ML Kit 포즈 인식.
 *
 * 스레드 모델:
 *  - start()/stop()/release(): 메인 스레드
 *  - 카메라 콜백·프레임 수신: start() 마다 새로 만드는 HandlerThread
 *  - ML Kit 결과 콜백: 메인 스레드
 *
 * 화면을 빠르게 껐다 켜면 이전 세션의 콜백이 늦게 도착할 수 있으므로,
 * start() 마다 generation 을 올리고 모든 콜백이 자기 generation 과 비교한 뒤에만
 * 공유 필드(device/session/reader)를 만지도록 lock 으로 보호합니다.
 * 추론 중에는 새 프레임을 즉시 버려(busy) 지연이 쌓이지 않습니다.
 */
class PoseCamera(
    private val activity: Activity,
    private val textureView: TextureView,
    private val listener: Listener,
) {
    interface Listener {
        /** 프레임 크기 확정 (세로로 세운 기준 너비/높이, 전면 카메라면 mirrored=true) */
        fun onCameraReady(uprightWidth: Int, uprightHeight: Int, mirrored: Boolean)
        fun onPose(frame: PoseFrame?, uprightWidth: Int, uprightHeight: Int, mirrored: Boolean)
        fun onCameraError(message: String)
    }

    private val cameraManager: CameraManager = activity.systemService()
    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )
    private val main = Handler(Looper.getMainLooper())

    // ---- lock 으로 보호 ----
    private val lock = Any()
    private var generation = 0
    private var running = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    // ---- 메인 스레드 전용 ----
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var cameraRequested = false
    private var released = false
    private val readersPendingClose = ArrayList<ImageReader>()

    // ---- 여러 스레드에서 읽기 ----
    @Volatile private var busy = false
    @Volatile private var active = false
    @Volatile private var bufferSize = Size(640, 480)
    @Volatile private var rotationDegrees = 0
    @Volatile private var uprightW = 480
    @Volatile private var uprightH = 640
    @Volatile private var mirrored = true
    @Volatile private var fpsRange: Range<Int>? = null

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            if (active && !cameraRequested) openCamera()
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    init {
        textureView.surfaceTextureListener = surfaceListener
    }

    /** onResume 에서 호출 */
    fun start() {
        if (active || released) return
        synchronized(lock) {
            generation++
            running = true
        }
        active = true
        cameraRequested = false
        val t = HandlerThread("PoseCamera").also { it.start() }
        bgThread = t
        bgHandler = Handler(t.looper)
        if (textureView.isAvailable) openCamera()
    }

    /** onPause 에서 호출 */
    fun stop() {
        if (!active) return
        active = false
        cameraRequested = false
        var oldSession: CameraCaptureSession?
        var oldDevice: CameraDevice?
        var oldReader: ImageReader?
        var oldSurface: Surface?
        synchronized(lock) {
            running = false
            generation++   // 늦게 도착하는 이전 콜백을 모두 무효화
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
            oldSurface?.release()
        }
        if (h != null) h.post(closeAll) else closeAll.run()
        t?.quitSafely()
        oldReader?.let { closeReaderWhenIdle(it) }
    }

    /** onDestroy 에서 호출 */
    fun release() {
        stop()
        released = true
        detector.close()
    }

    /** ML Kit 이 아직 프레임을 읽는 중일 수 있으니 추론이 끝난 뒤 닫음 (메인 스레드) */
    private fun closeReaderWhenIdle(r: ImageReader) {
        if (busy) readersPendingClose.add(r) else r.close()
    }

    private fun isCurrent(gen: Int): Boolean = synchronized(lock) { running && gen == generation }

    // ------------------------------------------------------------------ 열기 (메인)

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
            val id = chooseCamera() ?: run {
                listener.onCameraError("사용할 수 있는 카메라가 없습니다")
                return
            }
            val chars = cameraManager.getCameraCharacteristics(id)
            val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
                listener.onCameraError("카메라 설정을 읽을 수 없습니다")
                return
            }
            val size = chooseSize(map)
            val displayDeg = displayRotationDegrees()
            // ML Kit 가이드의 회전 보정 공식
            val rotation = if (front) (sensorOrientation + displayDeg) % 360
            else (sensorOrientation - displayDeg + 360) % 360

            bufferSize = size
            rotationDegrees = rotation
            mirrored = front
            if (rotation % 180 == 90) {
                uprightW = size.height
                uprightH = size.width
            } else {
                uprightW = size.width
                uprightH = size.height
            }
            fpsRange = chooseFpsRange(chars)

            listener.onCameraReady(uprightW, uprightH, mirrored)
            configureTransform(textureView.width, textureView.height)
            cameraManager.openCamera(id, DeviceCallback(gen, st, handler), handler)
        } catch (e: CameraAccessException) {
            listener.onCameraError("카메라를 열 수 없습니다 (${e.reason})")
        } catch (e: SecurityException) {
            listener.onCameraError("카메라 권한이 없습니다")
        } catch (e: IllegalArgumentException) {
            listener.onCameraError("카메라를 열 수 없습니다")
        }
    }

    /** 전면 카메라 우선, 없으면 후면 */
    private fun chooseCamera(): String? {
        val ids = cameraManager.cameraIdList
        return ids.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: ids.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
            ?: ids.firstOrNull()
    }

    private fun facing(id: String): Int? =
        try {
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        } catch (e: Exception) {
            null
        }

    /** 미리보기와 분석 모두 지원하는 4:3, 640x480 근처 크기 (포즈 인식은 이 정도면 충분하고 가벼움) */
    private fun chooseSize(map: StreamConfigurationMap): Size {
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val tex = map.getOutputSizes(SurfaceTexture::class.java)?.toSet().orEmpty()
        val common = yuv.filter { it in tex }.ifEmpty { yuv }
        val bounded = common.filter { it.width <= 1280 && it.height <= 1280 }.ifEmpty { common }
        val target = 640 * 480
        return bounded.minWithOrNull(
            compareBy<Size>(
                { if (abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f) 0 else 1 },
                { abs(it.width * it.height - target) },
            )
        ) ?: Size(640, 480)
    }

    /** 최대 30fps 범위 중 하한이 가장 낮은 것 (어두운 아침 방에서 노출 확보) */
    private fun chooseFpsRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.filter { it.upper <= 30 }
            .maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { -it.lower }))
    }

    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
        }

    private fun displayRotationDegrees(): Int = when (displayRotation()) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /** 화면이 가로일 때 미리보기 회전/비율 보정 (세로 고정 화면에서는 단위 행렬) */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val rotation = displayRotation()
        val size = bufferSize
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

    // ------------------------------------------------------------------ 장치/세션 (bg)

    private inner class DeviceCallback(
        private val gen: Int,
        private val texture: SurfaceTexture,
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
            createSession(camera, gen, texture, handler)
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

    private fun createSession(camera: CameraDevice, gen: Int, texture: SurfaceTexture, handler: Handler) {
        val size = bufferSize
        texture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(texture)
        // maxImages 3: 추론 중 1장 보유 + acquireLatestImage 가 최신 프레임을 고를 여유
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        val accepted = synchronized(lock) {
            if (running && gen == generation) {
                previewSurface = surface
                reader = r
                true
            } else false
        }
        if (!accepted) {
            surface.release()
            main.post { closeReaderWhenIdle(r) }
            return
        }
        r.setOnImageAvailableListener(imageListener, handler)

        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    val accepted = synchronized(lock) {
                        if (running && gen == generation && device === camera) {
                            session = s
                            true
                        } else false
                    }
                    if (!accepted) {
                        s.close()
                        return
                    }
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            addTarget(r.surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }.build()
                        s.setRepeatingRequest(req, null, handler)
                    } catch (e: CameraAccessException) {
                        postError(gen, "카메라를 시작할 수 없습니다")
                    } catch (e: IllegalStateException) {
                        // 세션이 이미 닫힘 (화면 전환 중) → 무시
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

    private val imageListener = ImageReader.OnImageAvailableListener { r ->
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return@OnImageAvailableListener

        if (busy || !active) {
            image.close()
            return@OnImageAvailableListener
        }
        busy = true
        val input = try {
            InputImage.fromMediaImage(image, rotationDegrees)
        } catch (e: Exception) {
            image.close()
            busy = false
            return@OnImageAvailableListener
        }
        val w = uprightW
        val h = uprightH
        val m = mirrored
        detector.process(input)
            .addOnSuccessListener { pose -> if (active) listener.onPose(toFrame(pose), w, h, m) }
            .addOnFailureListener { /* 이 프레임은 건너뜀 */ }
            .addOnCompleteListener {
                image.close()
                busy = false
                if (readersPendingClose.isNotEmpty()) {
                    readersPendingClose.forEach { it.close() }
                    readersPendingClose.clear()
                }
            }
    }

    private fun postError(gen: Int, message: String) {
        main.post { if (isCurrent(gen)) listener.onCameraError(message) }
    }

    companion object {
        /** ML Kit Pose → 앱 내부 PoseFrame (ML Kit 의존성을 core 패키지 밖에 가둠) */
        fun toFrame(pose: Pose): PoseFrame? {
            val all = pose.allPoseLandmarks
            if (all.isEmpty()) return null
            val pts = arrayOfNulls<Pt>(Lm.COUNT)
            for (lm in all) {
                val type = lm.landmarkType
                if (type in 0 until Lm.COUNT) {
                    pts[type] = Pt(lm.position.x, lm.position.y, lm.inFrameLikelihood)
                }
            }
            return PoseFrame(pts)
        }
    }
}
