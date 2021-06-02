package com.jm.media_pipe_proto

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.TextView
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
    private val OUTPUT_RIGHT_STREAM_NAME = "right_iris_depth_mm"
    private val OUTPUT_LEFT_STREAM_NAME = "left_iris_depth_mm"
    private val FOCAL_LENGTH_STREAM_NAME = "focal_length_pixel"
    private val CAMERA_FACING = CameraHelper.CameraFacing.FRONT
    private val FLIP_FRAMES_VERTICALLY = true
    private val CONVERTER_NUM_BUFFERS = 2

    private var haveAddedSidePackets = false;


    companion object {
        init {
            // Load all native libraries needed by the app.
            System.loadLibrary("mediapipe_jni")
            System.loadLibrary("opencv_java3")
        }
    }

    private var previewFrameTexture: SurfaceTexture? = null
    private lateinit var previewDisplayView: SurfaceView
    private lateinit var inputDisplayView: TextureView
    private lateinit var eglManager: EglManager
    private lateinit var processor: FrameProcessor
    private lateinit var converter: ExternalTextureConverter
    private lateinit var cameraHelper: CameraXPreviewHelper
    private lateinit var tvLeftEye : TextView
    private lateinit var tvRightEye : TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewDisplayView = SurfaceView(this)
        inputDisplayView = TextureView(this)
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
//        processor.addPacketCallback(
//            OUTPUT_LANDMARKS_STREAM_NAME
//        ) { packet: Packet ->
//            Log.d(TAG, "Received face landmarks packet.")
//            val landmarksRaw = PacketGetter.getProtoBytes(packet)
//            try {
//                val faceLandmarksWithIris = LandmarkProto.NormalizedLandmarkList.parseFrom(landmarksRaw)
//                    if (faceLandmarksWithIris == null) {
//                        Log.v(TAG, "[TS:${packet.timestamp}] No landmarks.")
//                        return@addPacketCallback
//                    }
//                Log.d(TAG, "[TS:${packet.timestamp}] #Landmarks for face (including iris): ${faceLandmarksWithIris.landmarkCount}")
////                Log.d(TAG, getFaceLandmarksDebugString(faceLandmarksWithIris))
//            } catch (e : InvalidProtocolBufferException) {
//                Log.e(TAG, "Couldn't Exception received - $e")
//                return@addPacketCallback
//            }
//        }
        tvLeftEye = findViewById<TextView>(R.id.tvLeftEye)
        tvRightEye = findViewById<TextView>(R.id.tvRightEye)

        processor.addPacketCallback(
            OUTPUT_LEFT_STREAM_NAME
        ) { packet: Packet ->
            val distance = PacketGetter.getFloat32(packet).toString()
            runOnUiThread {
                tvLeftEye.text = distance
            }
//            val raw = PacketGetter.getFloat32(packet)
//            Log.d(TAG, raw.toString())
        }

        processor.addPacketCallback(
            OUTPUT_RIGHT_STREAM_NAME
        ) { packet: Packet ->
            val distance = PacketGetter.getFloat32(packet).toString()
            runOnUiThread {
                tvRightEye.text = distance
            }
//            val raw = PacketGetter.getFloat32(packet)
//            Log.d(TAG, raw.toString())
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

    private fun startCamera() {
        cameraHelper = CameraXPreviewHelper()
        cameraHelper.setOnCameraStartedListener { surfaceTexture: SurfaceTexture? ->
            onCameraStarted(surfaceTexture)
        }
        cameraHelper.startCamera(this, CAMERA_FACING,  /*surfaceTexture=*/null)
    }

    private fun onCameraStarted(surfaceTexture : SurfaceTexture?) {
        previewFrameTexture = surfaceTexture
        previewDisplayView.visibility = View.VISIBLE
//        setupInputDisplayView()
        if (!haveAddedSidePackets) {
            val focalLength = cameraHelper.focalLengthPixels
            if (focalLength != Float.MIN_VALUE) {
                val focalLengthSidePacket = processor.packetCreator.createFloat32(focalLength)
                val inputSidePackets = mutableMapOf<String, Packet>()
                inputSidePackets[FOCAL_LENGTH_STREAM_NAME] = focalLengthSidePacket
                processor.setInputSidePackets(inputSidePackets)
            }
            haveAddedSidePackets = true
        }
    }

    private fun onPreviewDisplaySurfaceChanged(width : Int, height : Int) {
        val viewSize = Size(width, height)
        val displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize)
        val isCameraRotated = cameraHelper.isCameraRotated

        converter.setSurfaceTextureAndAttachToGLContext(previewFrameTexture,
            if (isCameraRotated) displaySize.height else displaySize.width,
            if (isCameraRotated) displaySize.width else displaySize.height)
        Log.e(TAG, "[JM] onPreviewDisplaySurfaceChanged")

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
                        onPreviewDisplaySurfaceChanged(width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        processor.videoSurfaceOutput.setSurface(null)
                    }
                })
    }

    private fun setupInputDisplayView() {
        inputDisplayView.visibility = View.VISIBLE
        val viewGroup = findViewById<ViewGroup>(R.id.preview_display_layout)
        viewGroup.addView(inputDisplayView)
        inputDisplayView.surfaceTextureListener = object : TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                onPreviewDisplaySurfaceChanged(width, height)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
        previewFrameTexture?.let {
            inputDisplayView.setSurfaceTexture(it)
        }
    }

    private fun getFaceLandmarksDebugString(landmarks: LandmarkProto.NormalizedLandmarkList): String {
        var landmarksString = ""
        for (landmarkIndex in 468..477) {
            val landmark = landmarks.landmarkList[landmarkIndex]
            landmarksString += "\t\tLandmark[${landmarkIndex}]: (${landmark.x}, ${landmark.y}, ${landmark.z})\n"
        }
        return landmarksString;
    }
}