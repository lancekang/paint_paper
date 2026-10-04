package kr.dfluid.paint.document

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor

/**
 * 애니메이션 MP4(H.264) 쓰기. 프레임(ARGB, 흰 바탕에 합친 것)을 CPU에서 YUV로 바꿔 MediaCodec에 넣습니다.
 * 가로·세로는 짝수여야 합니다 (호출부가 맞춤). 프레임마다 [addFrame], 끝나면 [finish].
 */
class Mp4Encoder(fd: FileDescriptor, private val w: Int, private val h: Int, private val fps: Int) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxing = false
    private var frame = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.25f).toInt().coerceIn(1_000_000, 20_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            muxer.release()
            throw e
        }
    }

    /** argb = w*h 픽셀 (불투명이어야 함) */
    fun addFrame(argb: IntArray) {
        val idx = dequeueInput()
        val img = codec.getInputImage(idx) ?: throw IllegalStateException("인코더 입력을 얻지 못했습니다.")
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        for (row in 0 until h) {
            for (col in 0 until w) {
                val c = argb[row * w + col]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yb.put(row * y.rowStride + col * y.pixelStride, yy.coerceIn(0, 255).toByte())
                if (row % 2 == 0 && col % 2 == 0) {
                    val uu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    ub.put((row / 2) * u.rowStride + (col / 2) * u.pixelStride, uu.coerceIn(0, 255).toByte())
                    vb.put((row / 2) * v.rowStride + (col / 2) * v.pixelStride, vv.coerceIn(0, 255).toByte())
                }
            }
        }
        codec.queueInputBuffer(idx, 0, w * h * 3 / 2, frame * 1_000_000L / fps, 0)
        frame++
        drain(false)
    }

    fun finish() {
        val idx = dequeueInput()
        codec.queueInputBuffer(idx, 0, 0, frame * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        codec.stop()
        codec.release()
        if (muxing) muxer.stop()
        muxer.release()
    }

    fun abort() {
        try { codec.release() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
    }

    private fun dequeueInput(): Int {
        while (true) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) return i
            drain(false)
        }
    }

    private fun drain(end: Boolean) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)
                    if (buf != null && info.size > 0 && muxing && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
