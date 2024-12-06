package androidx.media3.demo.transformer

import android.content.Context
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.demo.transformer.DuetComposition.P2PPreset
import androidx.media3.demo.transformer.DuetComposition.SideBySide
import androidx.media3.demo.transformer.duet.MediaMetadataRetrieverUtils
import androidx.media3.demo.transformer.duet.getDuration
import androidx.media3.demo.transformer.duet.getVideoDimension
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import java.util.ArrayList

internal object DuetComposition {

    @JvmStatic
    fun createP2PComposition(preset: P2PPreset): Composition {
        return if (preset is SideBySide) {
            createSideBySideComposition(preset)
        } else {
            createPictureInPictureComposition(preset.firstMediaItem)
        }
    }

    private fun createSideBySideComposition(preset: SideBySide): Composition {
        val sideBySideVideoCompositorSettings: VideoCompositorSettings =
            object : VideoCompositorSettings {
                override fun getOutputSize(inputSizes: MutableList<Size>): Size {
                    val inputSize = inputSizes[0]
                    return Size(inputSize.width * 2, inputSize.height)
                }

                override fun getOverlaySettings(
                    inputId: Int,
                    presentationTimeUs: Long
                ): OverlaySettings {
                    return if (inputId == 0) {
                        OverlaySettings.Builder()
                            .setScale(1f, 1f)
                            .setOverlayFrameAnchor(-1f, -1f)
                            .setBackgroundFrameAnchor(-1f, -1f)
                            .build()
                    } else {
                        OverlaySettings.Builder()
                            .setScale(1f, 1f)
                            .setOverlayFrameAnchor(1f, -1f)
                            .setBackgroundFrameAnchor(1f, -1f)
                            .build()
                    }
                }
            }

        val localConfig = requireNotNull(preset.firstMediaItem.localConfiguration) {
            "First media item must have local configuration. Please pick a local media item."
        }
        val metadata = MediaMetadataRetrieverUtils.toMetaDataRetriever(
            context = preset.context,
            uri = localConfig.uri
        )
        val dimension = metadata.getVideoDimension()

        val presentation = Presentation.createForWidthAndHeight(
            dimension.size.width * 2,
            dimension.size.height,
            Presentation.LAYOUT_SCALE_TO_FIT
        )

        val compositionSequences: MutableList<EditedMediaItemSequence> =
            ArrayList<EditedMediaItemSequence>()

        val duration = metadata.getDuration()
        val clippedVideo2 = preset.secondMediaItem.buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(0)
                    .setEndPositionUs(duration.inWholeNanoseconds)
                    .build()
            ).build()

        val video1 = EditedMediaItem.Builder(preset.firstMediaItem).build()
        val video2 = EditedMediaItem.Builder(clippedVideo2).build()

        compositionSequences.add(sequenceOf(video1))
        compositionSequences.add(sequenceOf(video2))

        logMediaItem(preset.context, preset.firstMediaItem)
        logMediaItem(preset.context, clippedVideo2)

        return Composition.Builder(compositionSequences)
            .setEffects(
                Effects(
                    ImmutableList.of<AudioProcessor>(),
                    ImmutableList.of<Effect>(presentation)
                )
            )
            .setVideoCompositorSettings(sideBySideVideoCompositorSettings)
            .build()
    }

    private fun createPictureInPictureComposition(mediaItem: MediaItem): Composition {
        val video1 = EditedMediaItem.Builder(mediaItem).build()
        val pictureInPictureVideoCompositorSettings: VideoCompositorSettings =
            object : VideoCompositorSettings {
                override fun getOutputSize(inputSizes: MutableList<Size>): Size {
                    return inputSizes.get(0)
                }

                override fun getOverlaySettings(
                    inputId: Int,
                    presentationTimeUs: Long
                ): OverlaySettings {
                    return if (inputId == 0) {
                        // This tests all OverlaySettings builder variables.
                        OverlaySettings.Builder()
                            .setScale(0.35f, 0.35f)
                            .setOverlayFrameAnchor(1f, -1f)
                            .setBackgroundFrameAnchor(0.9f, -0.3f)
                            .build()
                    } else {
                        OverlaySettings.Builder().build()
                    }
                }
            }

        val compositionSequences: MutableList<EditedMediaItemSequence> =
            ArrayList<EditedMediaItemSequence>()
        compositionSequences.add(sequenceOf(video1))
        compositionSequences.add(sequenceOf(video1))

        return Composition.Builder(compositionSequences)
            .setEffects(
                Effects(
                    ImmutableList.of<AudioProcessor>(),
                    ImmutableList.of<Effect>(
                        Presentation.createForWidthAndHeight(
                            360, 240, Presentation.LAYOUT_SCALE_TO_FIT
                        )
                    )
                )
            )
            .setVideoCompositorSettings(pictureInPictureVideoCompositorSettings)
            .build()
    }


    private fun sequenceOf(item: EditedMediaItem): EditedMediaItemSequence {
        return EditedMediaItemSequence.Builder(ImmutableList.of<EditedMediaItem>(item)).build()
    }

    private fun logMediaItem(context: Context, item: MediaItem) {
        val localConfiguration = item.localConfiguration
        if (localConfiguration == null) {
            Log.d("DuetComposition", "MediaItem has no local configuration")
            return
        }

        val uri = localConfiguration.uri
        val dimension = MediaMetadataRetrieverUtils.toMetaDataRetriever(
            context = context,
            uri = uri
        ).getVideoDimension()

        val duration = MediaMetadataRetrieverUtils.toMetaDataRetriever(
            context = context,
            uri = uri
        ).getDuration()

        Log.d(
            "DuetComposition", """
            item: $uri
            Dimension: ${dimension.size}
            Aspect Ratio: ${dimension.aspectRatio}
            Duration: $duration
        """.trimIndent()
        )
    }

    sealed interface P2PPreset {
        val firstMediaItem: MediaItem
        val secondMediaItem: MediaItem
    }

    data class SideBySide(
        val context: Context,
        override val firstMediaItem: MediaItem,
        override val secondMediaItem: MediaItem,
    ) : P2PPreset

    data class PictureInPicture(
        override val firstMediaItem: MediaItem,
        override val secondMediaItem: MediaItem
    ) : P2PPreset
}
