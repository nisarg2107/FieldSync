package dev.fieldsync.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

@Composable
fun CameraScreen(onBack: () -> Unit, onCaptured: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    var error by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context) }
    val capture = remember { ImageCapture.Builder().setJpegQuality(85).build() }
    DisposableEffect(allowed) {
        val future = if (allowed) ProcessCameraProvider.getInstance(context) else null
        var disposed = false
        future?.addListener({
            if (!disposed) try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                provider.bindToLifecycle(lifecycle, selector, preview, capture)
            } catch (e: Exception) { error = e.message ?: "Camera unavailable" }
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; if (future?.isDone == true) runCatching { future.get().unbindAll() } }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Attach a photo", style = MaterialTheme.typography.headlineSmall)
        if (!allowed) {
            Text("Allow camera access to take a photo, or choose an existing image from the task screen.")
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        } else {
            AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
            Button(onClick = {
                capturing = true
                val file = File.createTempFile("photo-", ".jpg", context.cacheDir)
                capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) { capturing = false; onCaptured(Uri.fromFile(file)) }
                    override fun onError(exception: ImageCaptureException) { capturing = false; file.delete(); error = exception.message }
                })
            }, enabled = !capturing) { Text(if (capturing) "Saving…" else "Capture photo") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onBack, enabled = !capturing) { Text("Cancel") }
    }
}
