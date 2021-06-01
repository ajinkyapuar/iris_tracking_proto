package com.jm.media_pipe_proto

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.components.*
import com.google.mediapipe.formats.proto.LandmarkProto
import com.google.mediapipe.framework.AndroidAssetUtil
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.framework.PacketGetter
import com.google.mediapipe.glutil.EglManager

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val BINARY_GRAPH_NAME = "iris_tracking_gpu.binarypb"
    private val INPUT_VIDEO_STREAM_NAME = "input_video"
    private val OUTPUT_VIDEO_STREAM_NAME = "output_video"
    private val OUTPUT_LANDMARKS_STREAM_NAME = "face_landmarks_with_iris"
    private val CAMERA_FACING = CameraHelper.CameraFacing.FRONT
    private val FLIP_FRAMES_VERTICALLY = true
    private val CONVERTER_NUM_BUFFERS = 2

    companion object {
        init {
            // Load all native libraries needed by the app.
            System.loadLibrary("mediapipe_jni")
            System.loadLibrary("opencv_java3")
        }
    }

    private var previewFrameTexture: SurfaceTexture? = null
    private lateinit var previewDisplayView: SurfaceView
    private lateinit var eglManager: EglManager
    private lateinit var processor: FrameProcessor
    private lateinit var converter: ExternalTextureConverter
    private lateinit var cameraHelper: CameraXPreviewHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewDisplayView = SurfaceView(this)
        setupPreviewDisplayView()
        // Initialize asset manager so that MediaPipe native libraries can access the app assets,
        // e.g., binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(this)
        eglManager = EglManager(null)
        processor = FrameProcessor(
            this,
            eglManager.nativeContext,
            BINARY_GRAPH_NAME,
            INPUT_VIDEO_STREAM_NAME,
            OUTPUT_VIDEO_STREAM_NAME
        )
        processor.videoSurfaceOutput.setFlipY(FLIP_FRAMES_VERTICALLY)
        processor.addPacketCallback(
            OUTPUT_LANDMARKS_STREAM_NAME
        ) { packet: Packet ->
            Log.d(TAG, "Received face landmarks packet.")
            val faceLandmarksWithIris =
                PacketGetter.getProtoVector(
                    packet,
                    LandmarkProto.NormalizedLandmarkList.parser()
                )
            Log.d(
                TAG,
                "[TS:"
                        + packet.timestamp
                        + "] "
                        + getFaceLandmarksDebugString(faceLandmarksWithIris)
            )
        }
        PermissionHelper.checkAndRequestCameraPermissions(this)
    }

    override fun onResume() {
        super.onResume()
        converter = ExternalTextureConverter(eglManager.context, CONVERTER_NUM_BUFFERS)
        converter.setFlipY(FLIP_FRAMES_VERTICALLY)
        converter.setConsumer(processor)
        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        converter.close()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun onCameraStarted(surfaceTexture : SurfaceTexture?) {
        previewFrameTexture = surfaceTexture
        previewDisplayView.visibility = View.VISIBLE
    }

    private fun startCamera() {
        cameraHelper = CameraXPreviewHelper()
        cameraHelper.setOnCameraStartedListener { surfaceTexture: SurfaceTexture? ->
            onCameraStarted(surfaceTexture)
        }
        cameraHelper.startCamera(this, CAMERA_FACING,  /*surfaceTexture=*/null)
    }

    private fun onPreviewDisplaySurfaceChanged(holder: SurfaceHolder, format: Int, width : Int, height : Int) {
        val viewSize = Size(width, height)
        val displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize)
        val isCameraRotated = cameraHelper.isCameraRotated

        converter.setSurfaceTextureAndAttachToGLContext(previewFrameTexture,
            if (isCameraRotated) displaySize.height else displaySize.width,
            if (isCameraRotated) displaySize.width else displaySize.height)
    }

    private fun setupPreviewDisplayView() {
        previewDisplayView.visibility = View.GONE
        val viewGroup = findViewById<ViewGroup>(R.id.preview_display_layout)
        viewGroup.addView(previewDisplayView)
        previewDisplayView
            .holder
            .addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        processor.videoSurfaceOutput.setSurface(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        onPreviewDisplaySurfaceChanged(holder, format, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        processor.videoSurfaceOutput.setSurface(null)
                    }
                })
    }

    private fun getFaceLandmarksDebugString(faceLandmarksWithIris: List<LandmarkProto.NormalizedLandmarkList>): String {
            if (faceLandmarksWithIris.isEmpty()) {
                return "No face landmarks"
            }
            var faceLandmarksWithIrisStr =
                "Number of face detected: " + faceLandmarksWithIris.size + "\n"
            for ((handIndex, landmarks) in faceLandmarksWithIris.withIndex()) {
                faceLandmarksWithIrisStr +=
                    "\t#face landmarks for face[" + handIndex + "]: " + landmarks.landmarkCount + "\n"
                for ((landmarkIndex, landmark) in landmarks.landmarkList.withIndex()) {
                    faceLandmarksWithIrisStr += ("\t\tLandmark ["
                            + landmarkIndex
                            + "]: ("
                            + landmark.x
                            + ", "
                            + landmark.y
                            + ", "
                            + landmark.z
                            + ")\n")
                }
            }
            return faceLandmarksWithIrisStr
    }
}