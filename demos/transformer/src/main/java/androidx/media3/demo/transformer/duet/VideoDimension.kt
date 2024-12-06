package androidx.media3.demo.transformer.duet

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import kotlin.text.toInt
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class VideoDimension(
    private val width: Int,
    private val height: Int,
    val rotation: Int
) {

    /**
     * The raw size of the video, without considering rotation
     */
    val rawSize: Size by lazy { Size(width, height) }

    /**
     * The size of the video, considering rotation
     */
    val size: Size by lazy {
        if (rotation == 90 || rotation == 270) {
            Size(height, width)
        } else {
            Size(width, height)
        }
    }

    val aspectRatio: Size by lazy {
        val gcd = generateSequence(size.width to size.height) { (a, b) -> b to a % b }
            .first { it.second == 0 }
            .first
        Size(width / gcd, height / gcd)
    }
}

internal fun MediaMetadataRetriever.getVideoDimension(): VideoDimension {
    val retriever = this
    val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
    val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
    val rotationStr = retriever.extractMetadata(
        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
    )

    checkNotNull(widthStr) { "Failed to get video width from file" }
    checkNotNull(heightStr) { "Failed to get video height from file" }

    val width = widthStr.toInt()
    val height = heightStr.toInt()

    require(!(width <= 0 || height <= 0)) { "Transformer:: Invalid video dimension: $width x $height" }

    val rotation = if (rotationStr != null) rotationStr.toInt() else 0

    return VideoDimension(width, height, rotation)
}

internal fun MediaMetadataRetriever.getDuration(): Duration {
    val retriever = this
    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
    checkNotNull(durationStr) { "Failed to get video duration from file" }
    return durationStr.toLong().toDuration(DurationUnit.MILLISECONDS)
}

internal object MediaMetadataRetrieverUtils {
    fun toMetaDataRetriever(context: Context, uri: Uri): MediaMetadataRetriever {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        return retriever
    }
}
