package ru.kolco24.kolco24.ui.photo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import android.view.Surface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import ru.kolco24.kolco24.Kolco24App
import ru.kolco24.kolco24.data.ScanFeedbackPlayer
import ru.kolco24.kolco24.data.marks.PhotoStorage
import ru.kolco24.kolco24.data.time.TimeSample

private const val TAG = "PhotoCaptureScreen"

/**
 * Full-screen CameraX photo-capture overlay (the photo-mark fallback for a КП that can't be NFC-read).
 * Follows the app's overlay convention: a `rememberSaveable` host flag renders this after the `Scaffold`,
 * dismissed via [BackHandler].
 *
 * [markId] is minted by the entry point **before** the camera opens (an `AttachTo` reuses the recent NFC
 * take's id; an `AskNumber` mints a fresh UUID) so every captured frame is written under
 * `marks/<markId>/` under the same id the row will carry — fixing the chicken-and-egg between the file
 * paths and the row. Frames survive rotation ([rememberSaveable] strip of relative paths); the row write
 * is the host's job on `applicationScope` via [onCommit] (so it outlives this overlay).
 *
 * @param cpNumber КП number for the header.
 * @param onChangeCheckpoint non-null only for the `AttachTo` path (shows «изменить» to drop into the
 *   number picker); null hides it.
 * @param sampleProvider trusted-clock sampler, called in the first `takePicture` success callback (the
 *   best presence proxy — mirrors NFC sampling at the physical tap).
 * @param onCommit invoked with the captured relative paths and the first-frame [TimeSample] when «Готово»
 *   is pressed; the host persists (attach vs create) on `applicationScope` and then closes.
 * @param onClose close without persisting (back with no frames, or after a discard-confirm cleanup).
 */
@Composable
fun PhotoCaptureScreen(
    markId: String,
    cpNumber: Int,
    scanFeedback: ScanFeedbackPlayer,
    sampleProvider: () -> TimeSample,
    onChangeCheckpoint: (() -> Unit)?,
    onCommit: (paths: List<String>, firstSample: TimeSample) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val filesDir = context.filesDir
    val applicationScope = remember { (context.applicationContext as Kolco24App).container.applicationScope }
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentSampleProvider by rememberUpdatedState(sampleProvider)

    // Relative paths of frames captured this session; rememberSaveable so a rotation keeps the strip.
    val frames = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }

    // First-frame time sample (presence proxy). Not saveable (TimeSample is not Parcelable); a rotation
    // mid-session falls back to a fresh sample at commit — an acceptable rare inaccuracy.
    var firstSample by remember { mutableStateOf<TimeSample?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    // True while writeDownscaledJpeg is running; blocks back/close, Готово, and «изменить» to prevent
    // the race where scope cancellation drops an in-flight IO write from the committed frame list.
    var isCapturing by remember { mutableStateOf(false) }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val imageCapture = remember { ImageCapture.Builder().build() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var isFrontCamera by rememberSaveable { mutableStateOf(false) }
    var hasFrontCamera by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    // Bind CameraX once permission is granted (and re-bind on grant or on a front/back toggle).
    // ImageCapture (unlike CameraController) never mirrors the saved JPEG on its own — only
    // PreviewView auto-mirrors the live preview — so selfies save right-reading (КП numbers legible)
    // without any extra flip code here.
    LaunchedEffect(permissionGranted, lifecycleOwner, isFrontCamera) {
        if (!permissionGranted) return@LaunchedEffect
        val provider = try {
            context.awaitCameraProvider()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera provider", e)
            return@LaunchedEffect
        }
        cameraProvider = provider
        hasFrontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val selector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageCapture,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    // Release the camera when this overlay leaves the Compose tree.
    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    // Keep the torch in sync with the toggle whenever the camera (re)binds.
    LaunchedEffect(camera, torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    fun discardAndClose() {
        // Delete only THIS session's frames (per-path), never the whole dir — an AttachTo target may
        // already hold previously-committed frames belonging to the NFC take.
        // applicationScope so the delete outlives the overlay (rememberCoroutineScope is cancelled on
        // recomposition after currentOnClose() removes this composable from the tree).
        val toDelete = frames.toList()
        applicationScope.launch(Dispatchers.IO) {
            toDelete.forEach { PhotoStorage.deletePhoto(filesDir, it) }
        }
        frames.clear()
        currentOnClose()
    }

    fun handleBack() {
        if (isCapturing) return
        if (frames.isEmpty()) currentOnClose() else showDiscardConfirm = true
    }

    fun capture() {
        if (isCapturing) return
        isCapturing = true
        scanFeedback.shutter()
        imageCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // Sample at the first successful shot — the best photo-mark presence proxy.
                    val sample = currentSampleProvider()
                    if (firstSample == null) firstSample = sample
                    val rotation = image.imageInfo.rotationDegrees
                    val bytes = try { image.toJpegBytes() } finally { image.close() }
                    scope.launch(Dispatchers.IO) {
                        try {
                            val rel = PhotoStorage.writeDownscaledJpeg(filesDir, markId, bytes, rotation)
                            withContext(Dispatchers.Main) {
                                if (rel != null) {
                                    frames.add(rel)
                                    scanFeedback.photoCaptureConfirm()
                                } else {
                                    scanFeedback.failure()
                                }
                            }
                        } finally {
                            withContext(Dispatchers.Main) { isCapturing = false }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exception)
                    isCapturing = false
                    scanFeedback.failure()
                }
            },
        )
    }

    BackHandler { handleBack() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (permissionGranted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else if (permissionDenied) {
            PermissionRationale(onClose = currentOnClose)
        }

        // Top bar: close · «КП NN» (+ optional «изменить») · torch.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { handleBack() }) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
            }
            Text(
                text = "КП ${cpNumber.toString().padStart(2, '0')}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(start = 4.dp),
            )
            if (onChangeCheckpoint != null) {
                TextButton(
                    enabled = !isCapturing,
                    onClick = {
                        val toDelete = frames.toList()
                        applicationScope.launch(Dispatchers.IO) {
                            toDelete.forEach { PhotoStorage.deletePhoto(filesDir, it) }
                        }
                        frames.clear()
                        onChangeCheckpoint()
                    },
                ) {
                    Text("изменить", color = Color.White)
                }
            }
            Box(modifier = Modifier.weight(1f))
            if (permissionGranted && !isFrontCamera) {
                IconButton(onClick = { torchOn = !torchOn }) {
                    Icon(
                        imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = if (torchOn) "Выключить фонарик" else "Включить фонарик",
                        tint = Color.White,
                    )
                }
            }
        }

        if (permissionGranted) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (frames.isNotEmpty()) {
                    ThumbnailStrip(
                        filesDir = filesDir,
                        frames = frames,
                        onDelete = { rel ->
                            frames.remove(rel)
                            scope.launch(Dispatchers.IO) { PhotoStorage.deletePhoto(filesDir, rel) }
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                        // left slot balances the «Готово» button; the switch icon lives here when available
                        if (hasFrontCamera) {
                            IconButton(onClick = { isFrontCamera = !isFrontCamera }, enabled = !isCapturing) {
                                Icon(
                                    imageVector = Icons.Filled.Cameraswitch,
                                    contentDescription = if (isFrontCamera) "Переключить на тыловую камеру" else "Переключить на фронтальную камеру",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                    ShutterButton(onClick = { capture() }, enabled = !isCapturing)
                    Button(
                        onClick = {
                            val sample = firstSample ?: currentSampleProvider()
                            currentOnCommit(frames.toList(), sample)
                        },
                        enabled = frames.isNotEmpty() && !isCapturing,
                    ) {
                        Text("Готово (${frames.size})")
                    }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Удалить снимки?") },
            text = { Text("Снятые кадры (${frames.size}) не будут сохранены.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    discardAndClose()
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun ThumbnailStrip(
    filesDir: File,
    frames: List<String>,
    onDelete: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(frames, key = { it }) { rel ->
            Box(modifier = Modifier.size(72.dp)) {
                AsyncImage(
                    model = File(filesDir, rel),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.DarkGray, RoundedCornerShape(8.dp)),
                )
                IconButton(
                    onClick = { onDelete(rel) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Удалить кадр",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(if (enabled) Color.White else Color.Gray, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(64.dp)) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun PermissionRationale(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Нужен доступ к камере",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Чтобы сфотографировать КП, разрешите доступ к камере в настройках приложения.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onClose, modifier = Modifier.padding(top = 24.dp)) {
            Text("Закрыть")
        }
    }
}

/** Copy the JPEG bytes out of a captured [ImageProxy] (ImageCapture's default output is single-plane JPEG). */
private fun ImageProxy.toJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

/** Suspend-await the [ProcessCameraProvider] (the ListenableFuture resolves on the main executor). */
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try { cont.resume(future.get()) } catch (e: Exception) { cont.cancel(e) }
        }, ContextCompat.getMainExecutor(this))
        cont.invokeOnCancellation { future.cancel(true) }
    }
