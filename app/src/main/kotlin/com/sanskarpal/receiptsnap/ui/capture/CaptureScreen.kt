package com.sanskarpal.receiptsnap.ui.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanskarpal.receiptsnap.core.data.repo.ReceiptRepository
import com.sanskarpal.receiptsnap.ui.theme.Lime
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val receipts: ReceiptRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    /** The id of the receipt just captured; the screen navigates to its review when set. */
    val captured = MutableStateFlow<String?>(null)
    val samples: List<String> = context.assets.list(SAMPLES)?.sorted().orEmpty()

    fun fromFile(file: File) = viewModelScope.launch { captured.value = receipts.capture(file).also { file.delete() } }

    fun fromSample(name: String) = viewModelScope.launch { captured.value = receipts.capture(context.assets.open("$SAMPLES/$name").use { it.readBytes() }) }

    fun fromUri(uri: android.net.Uri) = viewModelScope.launch {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
        captured.value = receipts.capture(bytes)
    }

    fun sampleBitmap(name: String) = context.assets.open("$SAMPLES/$name").use { BitmapFactory.decodeStream(it) }

    companion object {
        const val SAMPLES = "sample_receipts"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(onBack: () -> Unit, onCaptured: (String) -> Unit, vm: CaptureViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val captured by vm.captured.collectAsState()
    LaunchedEffect(captured) { captured?.let(onCaptured) }

    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::fromUri) }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Snap a receipt", color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) } },
                actions = {
                    IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Filled.PhotoLibrary, "Choose from gallery", tint = Color.White)
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (hasCamera) {
                    CameraPreview(imageCapture)
                    // simple edge guide: keep the receipt inside the frame
                    Box(Modifier.fillMaxWidth(0.72f).aspectRatio(0.6f).border(BorderStroke(2.dp, Lime.copy(alpha = 0.8f)), RoundedCornerShape(12.dp)))
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text("Camera permission is off. You can still pick a photo or a sample receipt below.", color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera", color = Color.White) }
                    }
                }
            }
            if (hasCamera) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .background(Color.White, CircleShape)
                            .padding(4.dp)
                            .border(3.dp, Color.Black, CircleShape)
                            .semantics { contentDescription = "Take photo" }
                            .clickable { takePhoto(context, imageCapture, vm::fromFile) },
                    )
                }
            }
            SampleStrip(vm)
        }
    }
}

@Composable
private fun CameraPreview(imageCapture: ImageCapture) {
    val lifecycle = LocalLifecycleOwner.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
    )
}

private fun takePhoto(context: Context, capture: ImageCapture, onSaved: (File) -> Unit) {
    val file = File(context.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
    capture.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved(file)
            override fun onError(exception: ImageCaptureException) = Unit
        },
    )
}

/** Synthetic receipts bundled for demos on an emulator, where the camera sees a virtual room. */
@Composable
private fun SampleStrip(vm: CaptureViewModel) {
    if (vm.samples.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(Color(0xFF111111)).navigationBarsPadding().padding(vertical = 12.dp)) {
        Text("Or try a sample receipt", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            items(vm.samples) { name ->
                val bitmap = remember(name) { vm.sampleBitmap(name) }
                Column(Modifier.width(84.dp).clickable { vm.fromSample(name) }.semantics { contentDescription = "Sample receipt ${name.substringBefore('.')}" }) {
                    Image(
                        bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(110.dp).background(Color.White, RoundedCornerShape(6.dp)),
                    )
                    Text(name.substringBefore('.').replace('_', ' '), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}
