package io.github.anastaci1a.localica.playback

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Android 8's MP3 decoder emits its final 529 samples after the stream-end timestamp. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal open class LegacyMp3AudioRenderer(
    context: Context,
    adapterFactory: MediaCodecAdapter.Factory,
    selector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    sink: AudioSink,
) : MediaCodecAudioRenderer(context, adapterFactory, selector, enableDecoderFallback, eventHandler, eventListener, sink) {
    private var transitionPending = false

    override fun onProcessedStreamChange() {
        if (Build.VERSION.SDK_INT == 26 && codecInfo?.name == "OMX.google.mp3.decoder") {
            // A discontinuity now drains the trim processor before its next configuration,
            // releasing encoder padding as if this were a seek. Keep the final PCM buffered.
            transitionPending = true
        } else super.onProcessedStreamChange()
    }

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        super.onOutputFormatChanged(format, mediaFormat)
        if (transitionPending) {
            transitionPending = false
            super.onProcessedStreamChange()
        }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        transitionPending = false
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
    }

    override fun onDisabled() {
        transitionPending = false
        super.onDisabled()
    }
}
