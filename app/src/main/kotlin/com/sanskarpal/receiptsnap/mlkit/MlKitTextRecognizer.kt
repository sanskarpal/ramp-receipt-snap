package com.sanskarpal.receiptsnap.mlkit

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sanskarpal.receiptsnap.core.data.repo.TextRecognizer
import com.sanskarpal.receiptsnap.core.model.TextLine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device ML Kit Text Recognition v2 (Latin, bundled model: works offline
 * on first launch). Converts recognizer lines into page-normalized [TextLine]s
 * for the pure-Kotlin parser.
 */
@Singleton
class MlKitTextRecognizer @Inject constructor(@ApplicationContext private val context: Context) : TextRecognizer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(imagePath: String): List<TextLine> {
        val file = File(imagePath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(imagePath, it) }
        val pageHeight = bounds.outHeight.coerceAtLeast(1).toFloat()
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            TextLine(line.text, box.top / pageHeight, box.height() / pageHeight)
        }
    }
}
