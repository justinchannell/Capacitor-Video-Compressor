package co.firstview.plugins.videocompressor;

import com.getcapacitor.Logger;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.content.Context;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoCompressor {

    private static final String TAG = "VideoCompressor";
    private static final long TIMEOUT_US = 10000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Context context;

    public VideoCompressor(Context context) {
        this.context = context;
    }

    public void compress(String sourcePath, String destinationPath, String quality, VideoCompressionCallback callback) {
        executor.execute(() -> {
            MediaExtractor videoExtractor = new MediaExtractor();
            MediaExtractor audioExtractor = new MediaExtractor();
            MediaMuxer mediaMuxer = null;
            MediaCodec videoEncoder = null;
            MediaCodec audioEncoder = null;

            boolean muxerStarted = false;
            int outVideoTrack = -1;
            int outAudioTrack = -1;

            try {
                // Ensure destination dir exists
                File destFile = new File(destinationPath);
                File parent = destFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                // Initialize MediaExtractor to read the source video and audio
                videoExtractor.setDataSource(sourcePath);
                audioExtractor.setDataSource(sourcePath);

                // Select video and audio tracks
                int videoTrackIndex = selectTrack(videoExtractor, "video/");
                int audioTrackIndex = selectTrack(audioExtractor, "audio/");
                final boolean hasAudio = audioTrackIndex != -1;

                if (videoTrackIndex == -1) {
                    throw new IOException("No video track found in the source file.");
                }

                // Get the video format from the source
                MediaFormat inputVideoFormat = videoExtractor.getTrackFormat(videoTrackIndex);
                
                // Determine compression settings based on quality preset
                int videoBitrate;
                int compressedWidth;
                int compressedHeight;
                int audioBitrate;

                switch (quality) {
                    case "low":
                        videoBitrate = 500000; // 0.5 Mbps
                        compressedWidth = 640;
                        compressedHeight = 480;
                        audioBitrate = 64000; // 64 kbps
                        break;
                    case "medium":
                        videoBitrate = 1000000; // 1.0 Mbps
                        compressedWidth = 960;
                        compressedHeight = 540;
                        audioBitrate = 128000; // 128 kbps
                        break;
                    case "high":
                    default:
                        videoBitrate = 2000000; // 2.0 Mbps
                        compressedWidth = 1280;
                        compressedHeight = 720;
                        audioBitrate = 192000; // 192 kbps
                        break;
                }

                // Define the output format for the compressed video
                MediaFormat outputVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, compressedWidth, compressedHeight);
                outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
                outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
                outputVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
                outputVideoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0); // Let MediaCodec determine this automatically

                // Configure and start the video encoder
                videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                videoEncoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                videoEncoder.start();

                // Initialize MediaMuxer to write the compressed video
                mediaMuxer = new MediaMuxer(destinationPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                // Select source tracks
                videoExtractor.selectTrack(videoTrackIndex);
                MediaCodec.BufferInfo videoBufferInfo = new MediaCodec.BufferInfo();
                long videoDurationUs = inputVideoFormat.containsKey(MediaFormat.KEY_DURATION)
                        ? inputVideoFormat.getLong(MediaFormat.KEY_DURATION) : 0L;

                // --- Video Compression Loop ---
                while (true) {
                    int inIndex = videoEncoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = videoEncoder.getInputBuffer(inIndex);
                        int sampleSize = videoExtractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            videoEncoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            long pts = videoExtractor.getSampleTime();
                            int flags = videoExtractor.getSampleFlags();
                            if (flags < 0) flags = 0;
                            videoEncoder.queueInputBuffer(inIndex, 0, sampleSize, pts, flags);

                            if (videoDurationUs > 0) {
                                int progress = (int) Math.min(99, Math.max(0, (pts * 100) / videoDurationUs));
                                callback.onProgress(progress);
                            }
                            videoExtractor.advance();
                        }
                    }

                    int outIndex = videoEncoder.dequeueOutputBuffer(videoBufferInfo, TIMEOUT_US);
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Add video track on format changed
                        MediaFormat newFormat = videoEncoder.getOutputFormat();
                        outVideoTrack = mediaMuxer.addTrack(newFormat);
                        if (!hasAudio) {
                            mediaMuxer.start();
                            muxerStarted = true;
                        } else if (outAudioTrack != -1 && !muxerStarted) {
                            mediaMuxer.start();
                            muxerStarted = true;
                        }
                    } else if (outIndex >= 0) {
                        if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // Ignore config buffers
                            videoBufferInfo.size = 0;
                        }
                        if (videoBufferInfo.size > 0 && muxerStarted && outVideoTrack != -1) {
                            ByteBuffer outBuf = videoEncoder.getOutputBuffer(outIndex);
                            if (outBuf != null) {
                                outBuf.position(videoBufferInfo.offset);
                                outBuf.limit(videoBufferInfo.offset + videoBufferInfo.size);
                                mediaMuxer.writeSampleData(outVideoTrack, outBuf, videoBufferInfo);
                            }
                        }
                        videoEncoder.releaseOutputBuffer(outIndex, false);

                        if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break;
                        }
                    }
                }

                // --- Audio Compression Loop (if audio track exists) ---
                if (hasAudio) {
                    MediaFormat inputAudioFormat = audioExtractor.getTrackFormat(audioTrackIndex);

                    MediaFormat outputAudioFormat = MediaFormat.createAudioFormat(
                            MediaFormat.MIMETYPE_AUDIO_AAC,
                            inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    );
                    outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);

                    audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    audioEncoder.configure(outputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    audioEncoder.start();

                    audioExtractor.selectTrack(audioTrackIndex);
                    MediaCodec.BufferInfo audioBufferInfo = new MediaCodec.BufferInfo();

                    while (true) {
                        int inIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
                        if (inIndex >= 0) {
                            ByteBuffer inBuf = audioEncoder.getInputBuffer(inIndex);
                            int sampleSize = audioExtractor.readSampleData(inBuf, 0);
                            if (sampleSize < 0) {
                                audioEncoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            } else {
                                long pts = audioExtractor.getSampleTime();
                                int flags = audioExtractor.getSampleFlags();
                                if (flags < 0) flags = 0;
                                audioEncoder.queueInputBuffer(inIndex, 0, sampleSize, pts, flags);
                                audioExtractor.advance();
                            }
                        }

                        int outIndex = audioEncoder.dequeueOutputBuffer(audioBufferInfo, TIMEOUT_US);
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat newFormat = audioEncoder.getOutputFormat();
                            outAudioTrack = mediaMuxer.addTrack(newFormat);
                            if (outVideoTrack == -1) {
                                // Video already added? If not, wait for it. If yes, start now.
                                // If video had no track (shouldn't), start when possible.
                            }
                            if (!muxerStarted && outVideoTrack != -1) {
                                mediaMuxer.start();
                                muxerStarted = true;
                            }
                        } else if (outIndex >= 0) {
                            if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                audioBufferInfo.size = 0;
                            }
                            if (audioBufferInfo.size > 0 && muxerStarted && outAudioTrack != -1) {
                                ByteBuffer outBuf = audioEncoder.getOutputBuffer(outIndex);
                                if