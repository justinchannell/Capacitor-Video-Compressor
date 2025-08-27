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

            // Track/muxer state
            int outVideoTrackIndex = -1;
            int outAudioTrackIndex = -1;
            boolean hasAudio = false;
            boolean muxerStarted = false;

            try {
                // Initialize MediaExtractor to read the source video and audio
                videoExtractor.setDataSource(sourcePath);
                audioExtractor.setDataSource(sourcePath);

                // Select video and audio tracks
                int videoTrackIndex = selectTrack(videoExtractor, "video/");
                int audioTrackIndex = selectTrack(audioExtractor, "audio/");
                hasAudio = audioTrackIndex != -1;

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
                
                // Add video track to muxer
                videoExtractor.selectTrack(videoTrackIndex);
                MediaCodec.BufferInfo videoBufferInfo = new MediaCodec.BufferInfo();

                // Pre-compute durations for progress (use the longest of video/audio)
                long videoDurationUs = inputVideoFormat.containsKey(MediaFormat.KEY_DURATION)
                        ? inputVideoFormat.getLong(MediaFormat.KEY_DURATION) : 0L;
                long audioDurationUs = 0L;
                if (hasAudio) {
                    MediaFormat audioFmtForDur = audioExtractor.getTrackFormat(audioTrackIndex);
                    if (audioFmtForDur.containsKey(MediaFormat.KEY_DURATION)) {
                        audioDurationUs = audioFmtForDur.getLong(MediaFormat.KEY_DURATION);
                    }
                }
                final long totalDurationUs = Math.max(videoDurationUs, audioDurationUs);
                int lastProgress = -1;
                // Emit initial progress = 0
                callback.onProgress(0);
                lastProgress = 0;

                // --- Video Compression Loop ---
                while (true) {
                    int inputBufferIndex = videoEncoder.dequeueInputBuffer(-1);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inBuf = videoEncoder.getInputBuffer(inputBufferIndex);
                        int sampleSize = videoExtractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            videoEncoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            long presentationTimeUs = videoExtractor.getSampleTime();
                            int flags = videoExtractor.getSampleFlags();
                            if (flags < 0) flags = 0;
                            videoEncoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, flags);

                            // Progress: compute against the longest track duration and emit only when it increases
                            if (totalDurationUs > 0) {
                                int newProgress = (int) Math.min(99, Math.max(0, (presentationTimeUs * 100) / totalDurationUs));
                                if (newProgress > lastProgress) {
                                    lastProgress = newProgress;
                                    callback.onProgress(newProgress);
                                }
                            }
                            videoExtractor.advance();
                        }
                    }

                    int outputBufferIndex = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 0);
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Add video track when encoder output format becomes available
                        if (outVideoTrackIndex == -1) {
                            MediaFormat newFormat = videoEncoder.getOutputFormat();
                            outVideoTrackIndex = mediaMuxer.addTrack(newFormat);
                            // If there's no audio, we can start now
                            if (!hasAudio && !muxerStarted) {
                                mediaMuxer.start();
                                muxerStarted = true;
                            } else if (hasAudio && outAudioTrackIndex != -1 && !muxerStarted) {
                                // Start when both tracks added
                                mediaMuxer.start();
                                muxerStarted = true;
                            }
                        }
                    } else if (outputBufferIndex >= 0) {
                        if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            videoBufferInfo.size = 0;
                        }
                        if (videoBufferInfo.size > 0 && muxerStarted && outVideoTrackIndex != -1) {
                            ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                            if (outputBuffer != null) {
                                outputBuffer.position(videoBufferInfo.offset);
                                outputBuffer.limit(videoBufferInfo.offset + videoBufferInfo.size);
                                mediaMuxer.writeSampleData(outVideoTrackIndex, outputBuffer, videoBufferInfo);
                            }
                        }
                        videoEncoder.releaseOutputBuffer(outputBufferIndex, false);

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
                        int inIdx = audioEncoder.dequeueInputBuffer(-1);
                        if (inIdx >= 0) {
                            ByteBuffer inBuf = audioEncoder.getInputBuffer(inIdx);
                            int sampleSize = audioExtractor.readSampleData(inBuf, 0);
                            if (sampleSize < 0) {
                                audioEncoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            } else {
                                long pts = audioExtractor.getSampleTime();
                                int flags = audioExtractor.getSampleFlags();
                                if (flags < 0) flags = 0;
                                audioEncoder.queueInputBuffer(inIdx, 0, sampleSize, pts, flags);
                                audioExtractor.advance();
                            }
                        }

                        int outIdx = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 0);
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (outAudioTrackIndex == -1) {
                                MediaFormat newFormat = audioEncoder.getOutputFormat();
                                outAudioTrackIndex = mediaMuxer.addTrack(newFormat);
                                if (!muxerStarted && outVideoTrackIndex != -1) {
                                    mediaMuxer.start();
                                    muxerStarted = true;
                                }
                            }
                        } else if (outIdx >= 0) {
                            if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                audioBufferInfo.size = 0;
                            }
                            if (audioBufferInfo.size > 0 && muxerStarted && outAudioTrackIndex != -1) {
                                ByteBuffer outBuf = audioEncoder.getOutputBuffer(outIdx);
                                if (outBuf != null) {
                                    outBuf.position(audioBufferInfo.offset);
                                    outBuf.limit(audioBufferInfo.offset + audioBufferInfo.size);
                                    mediaMuxer.writeSampleData(outAudioTrackIndex, outBuf, audioBufferInfo);
                                }
                            }
                            audioEncoder.releaseOutputBuffer(outIdx, false);

                            if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break;
                            }
                        }
                    }
                    // Stop/release audio encoder
                    try { audioEncoder.stop(); } catch (Exception ignore) {}
                    try { audioEncoder.release(); } catch (Exception ignore) {}
                    audioEncoder = null;
                }

                // Cleanup and finalize
                if (muxerStarted) {
                    try { mediaMuxer.stop(); } catch (Exception ignore) {}
                }
                try { mediaMuxer.release(); } catch (Exception ignore) {}
                mediaMuxer = null;

                try { videoEncoder.stop(); } catch (Exception ignore) {}
                try { videoEncoder.release(); } catch (Exception ignore) {}
                videoEncoder = null;

                videoExtractor.release();
                audioExtractor.release();

                File originalFile = new File(sourcePath);
                File compressedFile = new File(destinationPath);
                if (originalFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    originalFile.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                compressedFile.renameTo(originalFile);

                // Final “100%” just before success
                callback.onProgress(100);
                callback.onSuccess();

            } catch (Exception e) {
                Log.e(TAG, "Video compression failed", e);
                callback.onError(e);
            } finally {
                // Defensive cleanup
                try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); } } catch (Exception ignore) {}
                try { if (videoEncoder != null) { videoEncoder.stop(); videoEncoder.release(); } } catch (Exception ignore) {}
                try { if (mediaMuxer != null) { mediaMuxer.release(); } } catch (Exception ignore) {}
                try { videoExtractor.release(); } catch (Exception ignore) {}
                try { audioExtractor.release(); } catch (Exception ignore) {}
            }
        });
    }

    private int selectTrack(MediaExtractor extractor, String mimeTypePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimeTypePrefix)) {
                return i;
            }
        }
        return -1;
    }

    public interface VideoCompressionCallback {
        void onSuccess();
        void onError(Exception e);
        void onProgress(int progress);
    }
}