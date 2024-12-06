package androidx.media3.demo.transformer.duet

import android.Manifest
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.transformer.TransformerActivity
import androidx.media3.exoplayer.ExoPlayer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class DuetCameraActivity : AppCompatActivity() {

    sealed interface ScreenState {
        object PickFile : ScreenState
        object ReadyToRecord : ScreenState
        object Recording : ScreenState
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        )
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(
                    baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                startCamera()
            }
        }

    private val videoLocalFilePickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data?.data
            if (data != null) {
                if (directToTransformer) {
                    directToTransformer = false
                    navigateToTransformer(
                        firstUri = data,
                        secondUri = data
                    )
                    return@registerForActivityResult
                }

                localFileUri = data

                val inputPlayer = ExoPlayer.Builder(this).build().apply {
                    playWhenReady = false
                    setMediaItem(MediaItem.fromUri(data))
                    prepare()
                }
                viewBinding.inputPlayerView.player = inputPlayer
                viewBinding.inputPlayerView.useController = false
                setState(ScreenState.ReadyToRecord)

            } else {
                Toast.makeText(applicationContext, "Failed to get file", Toast.LENGTH_SHORT).show()
            }

            // If cancelled or anything, reset the state
            directToTransformer = false
        }

    private var localFileUri: Uri? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var directToTransformer = false

    private val viewBinding by lazy { DuetViewBinding(this) }

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewBinding.inflate()

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Set preview height
        val width = resources.displayMetrics.widthPixels.toFloat()
        val previewHeight = ((width / 2) * 4 / 3).toInt()
        viewBinding.viewFinder.layoutParams.height = previewHeight
        viewBinding.inputPlayerView.layoutParams.height = previewHeight

        cameraExecutor = Executors.newSingleThreadExecutor()

        renderScreen()
    }

    private var screenState: ScreenState = ScreenState.PickFile

    private fun setState(screenState: ScreenState) {
        this.screenState = screenState
        renderScreen()
    }

    private fun renderScreen() {
        when (screenState) {
            ScreenState.PickFile -> {
                viewBinding.actionButton.apply {
                    text = "Pick File"
                    setOnClickListener {
                        selectLocalFile(
                            videoLocalFilePickerLauncher,
                            arrayOf("video/*")
                        )
                    }
                }

                viewBinding.pickButton.visibility = VISIBLE
                viewBinding.pickButton.setOnClickListener {
                    directToTransformer = true
                    selectLocalFile(
                        videoLocalFilePickerLauncher,
                        arrayOf("video/*")
                    )
                }
            }

            ScreenState.ReadyToRecord -> {
                viewBinding.pickButton.visibility = GONE
                viewBinding.actionButton.apply {
                    text = "Start Capture"
                    setOnClickListener { captureVideo() }
                }
            }

            ScreenState.Recording -> {
                viewBinding.pickButton.visibility = GONE
                viewBinding.actionButton.apply {
                    text = "Stop Capture"
                    setOnClickListener { captureVideo() }
                }
            }
        }
    }

    private fun captureVideo() {
        val player = viewBinding.inputPlayerView.player
            ?: error("Player is not ready. Please pick file first.")

        val videoCapture = this.videoCapture ?: return
        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(
                        this@DuetCameraActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        setState(ScreenState.Recording)

                        val endListener = object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == ExoPlayer.STATE_ENDED) {
                                    recording?.stop()
                                    player.removeListener(this)
                                }
                            }
                        }

                        player.addListener(endListener)
                        player.play()
                    }

                    is VideoRecordEvent.Status -> {
                        Log.d(
                            "DuetCameraActivity",
                            "Recorded: ${recordEvent.recordingStats.recordedDurationNanos}"
                        )
                    }

                    is VideoRecordEvent.Finalize -> {
                        player.stop()

                        if (!recordEvent.hasError()) {
                            val msg =
                                "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, msg)

                            navigateToTransformer(
                                firstUri = recordEvent.outputResults.outputUri,
                                secondUri = requireNotNull(localFileUri)
                            )

                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                        setState(ScreenState.PickFile)
                    }
                }
            }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Set preview scale type
            viewBinding.viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER

            // Preview
            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = viewBinding.viewFinder.surfaceProvider }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Video capture
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()

                videoCapture = VideoCapture.withOutput(recorder)

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun selectLocalFile(
        localFilePickerLauncher: ActivityResultLauncher<Intent>,
        mimeTypes: Array<String>,
    ) {
        val permission = if (SDK_INT >= 33) READ_MEDIA_VIDEO else READ_EXTERNAL_STORAGE
        if (ActivityCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchLocalFilePicker(localFilePickerLauncher, mimeTypes)
        }
    }

    private fun launchLocalFilePicker(
        localFilePickerLauncher: ActivityResultLauncher<Intent>,
        mimeTypes: Array<String>
    ) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        localFilePickerLauncher.launch(intent);
    }

    private fun navigateToTransformer(
        firstUri: Uri,
        secondUri: Uri,
    ) {
        val intent = Intent(
            this@DuetCameraActivity,
            TransformerActivity::class.java
        ).apply {
            data = firstUri
            putExtra(
                BUNDLE_DUET_DATA,
                arrayOf(firstUri.toString(), secondUri.toString())
            )
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
            ).apply {
                if (SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                if (SDK_INT >= 33) {
                    add(READ_MEDIA_VIDEO)
                } else {
                    READ_EXTERNAL_STORAGE
                }
            }.toTypedArray()

        const val BUNDLE_DUET_DATA = "duet_data"
    }
}
