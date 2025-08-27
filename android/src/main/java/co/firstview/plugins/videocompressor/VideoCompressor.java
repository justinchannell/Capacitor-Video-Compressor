package co.firstview.plugins.videocompressor;

import com.getcapacitor.Logger;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
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
import android.view.Surface;

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

            MediaCodec videoDecoder = null;
            MediaCodec videoEncoder = null;
            Surface encoderInputSurface = null;

            MediaCodec audioDecoder = null;   // NEW
            MediaCodec audioEncoder = null;   // NEW

            int outVideoTrackIndex = -1;
            int outAudioTrackIndex = -1;
            boolean muxerStarted = false;

            boolean hasAudio = false;

            try {
                Log.d(TAG, "Starting compression: src=" + sourcePath + ", dst=" + destinationPath + ", quality=" + quality);

                videoExtractor.setDataSource(sourcePath);
                audioExtractor.setDataSource(sourcePath);

                int videoTrackIndex = selectTrack(videoExtractor, "video/");
                int audioTrackIndex = selectTrack(audioExtractor, "audio/");
                hasAudio = audioTrackIndex != -1;
                Log.d(TAG, "Tracks selected -> video=" + videoTrackIndex + ", audio=" + audioTrackIndex + ", hasAudio=" + hasAudio);

                if (videoTrackIndex == -1) {
                    throw new IOException("No video track found in the source file.");
                }

                MediaFormat inputVideoFormat = videoExtractor.getTrackFormat(videoTrackIndex);
                String inVideoMime = inputVideoFormat.getString(MediaFormat.KEY_MIME);

                // Determine target video settings (assume you already compute compressedWidth/height and videoBitrate)
                int videoBitrate;
                int compressedWidth;
                int compressedHeight;

                switch (quality) {
                    case "low":
                        videoBitrate = 500000; // 0.5 Mbps
                        compressedWidth = 640;
                        compressedHeight = 480;
                        break;
                    case "medium":
                        videoBitrate = 1000000; // 1.0 Mbps
                        compressedWidth = 960;
                        compressedHeight = 540;
                        break;
                    case "high":
                    default:
                        videoBitrate = 2000000; // 2.0 Mbps
                        compressedWidth = 1280;
                        compressedHeight = 720;
                        break;
                }

                MediaFormat outVideoFormat = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC, compressedWidth, compressedHeight);
                outVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
                outVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
                outVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

                videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                videoEncoder.configure(outVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoderInputSurface = videoEncoder.createInputSurface();
                videoEncoder.start();

                videoDecoder = MediaCodec.createDecoderByType(inVideoMime);
                videoDecoder.configure(inputVideoFormat, encoderInputSurface, null, 0);
                videoDecoder.start();

                // Audio: setup decode → encode AAC
                MediaFormat inputAudioFormat = null;
                int audioSampleRate = 0;
                int audioChannelCount = 0;
                int audioBitrate;
                if ("low".equalsIgnoreCase(quality)) {
                    audioBitrate = 64_000;
                } else if ("medium".equalsIgnoreCase(quality)) {
                    audioBitrate = 96_000;
                } else {
                    audioBitrate = 128_000;
                }

                if (hasAudio) {
                    inputAudioFormat = audioExtractor.getTrackFormat(audioTrackIndex);
                    String inAudioMime = inputAudioFormat.getString(MediaFormat.KEY_MIME);
                    audioSampleRate = inputAudioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
                    audioChannelCount = inputAudioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

                    // Decoder for source audio
                    audioDecoder = MediaCodec.createDecoderByType(inAudioMime);
                    audioDecoder.configure(inputAudioFormat, null, null, 0);
                    audioDecoder.start();

                    // Encoder to AAC LC
                    MediaFormat outAudioFormat = MediaFormat.createAudioFormat(
                            MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, audioChannelCount);
                    outAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    outAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
                    // Some devices also like this:
                    // outAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

                    audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    audioEncoder.configure(outAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    audioEncoder.start();
                }

                mediaMuxer = new MediaMuxer(destinationPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                videoExtractor.selectTrack(videoTrackIndex);
                if (hasAudio) {
                    audioExtractor.selectTrack(audioTrackIndex);
                }

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
                callback.onProgress(0);
                lastProgress = 0;
                Log.d(TAG, "Progress: 0%");

                // State for loops
                MediaCodec.BufferInfo vDecInfo = new MediaCodec.BufferInfo();
                MediaCodec.BufferInfo vEncInfo = new MediaCodec.BufferInfo();
                MediaCodec.BufferInfo aDecInfo = new MediaCodec.BufferInfo();
                MediaCodec.BufferInfo aEncInfo = new MediaCodec.BufferInfo();

                boolean vInputDone = false, vDecDone = false, vEncDone = false;
                boolean aInputDone = !hasAudio, aDecDone = !hasAudio, aEncDone = !hasAudio;
                boolean signaledVideoEosToEncoder = false; // NEW

                // Bytes-per-sample for PCM16
                final int bytesPerSample = 2 * Math.max(1, hasAudio ? audioChannelCount : 1);

                while (!vEncDone || !aEncDone) {
                    // Feed video decoder
                    if (!vInputDone) {
                        int inIdx = videoDecoder.dequeueInputBuffer(0);
                        if (inIdx >= 0) {
                            ByteBuffer inBuf = videoDecoder.getInputBuffer(inIdx);
                            int size = videoExtractor.readSampleData(inBuf, 0);
                            if (size < 0) {
                                videoDecoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                vInputDone = true;
                            } else {
                                long pts = videoExtractor.getSampleTime();
                                int flags = videoExtractor.getSampleFlags();
                                if (flags < 0) flags = 0;
                                videoDecoder.queueInputBuffer(inIdx, 0, size, pts, flags);

                                long denom = videoDurationUs > 0 ? videoDurationUs : (totalDurationUs > 0 ? totalDurationUs : 1L);
                                int newProgress = (int) Math.min(99, Math.max(0, (pts * 100) / denom));
                                if (newProgress > lastProgress) {
                                    lastProgress = newProgress;
                                    callback.onProgress(newProgress);
                                }
                                videoExtractor.advance();
                            }
                        }
                    }

                    // Drain video decoder (renders to encoder surface)
                    if (!vDecDone) {
                        int outIdx = videoDecoder.dequeueOutputBuffer(vDecInfo, 0);
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // no-op
                        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            // no-op
                        } else if (outIdx >= 0) {
                            boolean render = vDecInfo.size != 0;
                            videoDecoder.releaseOutputBuffer(outIdx, render);
                            if ((vDecInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                vDecDone = true;
                                Log.d(TAG, "Video decoder EOS.");
                                if (!signaledVideoEosToEncoder) {
                                    try {
                                        videoEncoder.signalEndOfInputStream(); // IMPORTANT for Surface input
                                        signaledVideoEosToEncoder = true;
                                        Log.d(TAG, "Signaled encoder EOS via signalEndOfInputStream().");
                                    } catch (Exception e) {
                                        Log.w(TAG, "signalEndOfInputStream failed", e);
                                    }
                                }
                            }
                        }
                    }

                    // Drain video encoder
                    while (true) {
                        int outIdx = videoEncoder.dequeueOutputBuffer(vEncInfo, 0);
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            break;
                        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (outVideoTrackIndex == -1) {
                                MediaFormat newFmt = videoEncoder.getOutputFormat();
                                outVideoTrackIndex = mediaMuxer.addTrack(newFmt);
                                Log.d(TAG, "Video track added: " + newFmt);
                                if (outAudioTrackIndex != -1 || !hasAudio) {
                                    mediaMuxer.start();
                                    muxerStarted = true;
                                    Log.d(TAG, "MediaMuxer started (video ready " + (!hasAudio ? "no audio" : "audio already ready") + ").");
                                }
                            }
                        } else if (outIdx >= 0) {
                            if ((vEncInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                vEncInfo.size = 0;
                            }
                            if (vEncInfo.size > 0 && muxerStarted && outVideoTrackIndex != -1) {
                                ByteBuffer outBuf = videoEncoder.getOutputBuffer(outIdx);
                                if (outBuf != null) {
                                    outBuf.position(vEncInfo.offset);
                                    outBuf.limit(vEncInfo.offset + vEncInfo.size);
                                    mediaMuxer.writeSampleData(outVideoTrackIndex, outBuf, vEncInfo);
                                }
                            }
                            videoEncoder.releaseOutputBuffer(outIdx, false);
                            if ((vEncInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                vEncDone = true;
                                Log.d(TAG, "Video encoder EOS.");
                            }
                        }
                    }

                    // Audio pipeline
                    if (hasAudio) {
                        // Feed audio decoder
                        if (!aInputDone) {
                            int inIdx = audioDecoder.dequeueInputBuffer(0);
                            if (inIdx >= 0) {
                                ByteBuffer inBuf = audioDecoder.getInputBuffer(inIdx);
                                int size = audioExtractor.readSampleData(inBuf, 0);
                                if (size < 0) {
                                    audioDecoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    aInputDone = true;
                                } else {
                                    long pts = audioExtractor.getSampleTime();
                                    int flags = audioExtractor.getSampleFlags();
                                    if (flags < 0) flags = 0;
                                    audioDecoder.queueInputBuffer(inIdx, 0, size, pts, flags);

                                    // Optional: progress vs audio PTS
                                    if (totalDurationUs > 0) {
                                        int p = (int) Math.min(99, Math.max(lastProgress, (pts * 100) / totalDurationUs));
                                        if (p > lastProgress) {
                                            lastProgress = p;
                                            callback.onProgress(p);
                                        }
                                    }
                                    audioExtractor.advance();
                                }
                            }
                        }

                        // Drain audio decoder -> feed audio encoder
                        while (!aDecDone) {
                            int outIdx = audioDecoder.dequeueOutputBuffer(aDecInfo, 0);
                            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                break;
                            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                                // no-op
                            } else if (outIdx >= 0) {
                                ByteBuffer decOut = audioDecoder.getOutputBuffer(outIdx);
                                if (decOut != null && aDecInfo.size > 0) {
                                    decOut.position(aDecInfo.offset);
                                    decOut.limit(aDecInfo.offset + aDecInfo.size);

                                    int remaining = aDecInfo.size;
                                    long pts = aDecInfo.presentationTimeUs;
                                    int consumed = 0;

                                    while (remaining > 0) {
                                        int inIdx = audioEncoder.dequeueInputBuffer(10_000);
                                        if (inIdx < 0) {
                                            // Try again later
                                            continue;
                                        }
                                        ByteBuffer encIn = audioEncoder.getInputBuffer(inIdx);
                                        if (encIn == null) {
                                            audioEncoder.queueInputBuffer(inIdx, 0, 0, pts, 0);
                                            continue;
                                        }
                                        encIn.clear();
                                        int toCopy = Math.min(encIn.capacity(), remaining);
                                        // Copy chunk
                                        int oldLimit = decOut.limit();
                                        decOut.limit(decOut.position() + toCopy);
                                        encIn.put(decOut);
                                        decOut.limit(oldLimit);

                                        // Compute chunk PTS if we split
                                        long chunkPts = pts + (long)((consumed / (double)bytesPerSample) * 1_000_000d / Math.max(1, audioSampleRate));

                                        audioEncoder.queueInputBuffer(inIdx, 0, toCopy, chunkPts, 0);

                                        remaining -= toCopy;
                                        consumed += toCopy;
                                    }
                                }
                                boolean eos = (aDecInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                                audioDecoder.releaseOutputBuffer(outIdx, false);
                                if (eos) {
                                    aDecDone = true;
                                    Log.d(TAG, "Audio decoder EOS.");
                                    // Signal EOS to encoder
                                    int inIdx;
                                    while ((inIdx = audioEncoder.dequeueInputBuffer(10_000)) < 0) { /* wait */ }
                                    audioEncoder.queueInputBuffer(inIdx, 0, 0, aDecInfo.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                }
                            }
                        }

                        // Drain audio encoder -> write to muxer
                        while (true) {
                            int outIdx = audioEncoder.dequeueOutputBuffer(aEncInfo, 0);
                            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                break;
                            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                if (outAudioTrackIndex == -1) {
                                    MediaFormat newFmt = audioEncoder.getOutputFormat();
                                    outAudioTrackIndex = mediaMuxer.addTrack(newFmt);
                                    Log.d(TAG, "Audio track added (AAC): " + newFmt);
                                    if (outVideoTrackIndex != -1 && !muxerStarted) {
                                        mediaMuxer.start();
                                        muxerStarted = true;
                                        Log.d(TAG, "MediaMuxer started after audio+video ready.");
                                    }
                                }
                            } else if (outIdx >= 0) {
                                if ((aEncInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    aEncInfo.size = 0;
                                }
                                if (aEncInfo.size > 0 && muxerStarted && outAudioTrackIndex != -1) {
                                    ByteBuffer outBuf = audioEncoder.getOutputBuffer(outIdx);
                                    if (outBuf != null) {
                                        outBuf.position(aEncInfo.offset);
                                        outBuf.limit(aEncInfo.offset + aEncInfo.size);
                                        mediaMuxer.writeSampleData(outAudioTrackIndex, outBuf, aEncInfo);
                                    }
                                }
                                audioEncoder.releaseOutputBuffer(outIdx, false);
                                if ((aEncInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    aEncDone = true;
                                    Log.d(TAG, "Audio encoder EOS.");
                                }
                            }
                        }
                    }
                }

                if (!muxerStarted) {
                    throw new IllegalStateException("Muxer never started. Missing tracks?");
                }

                try { mediaMuxer.stop(); Log.d(TAG, "MediaMuxer stopped."); } catch (Exception ignore) {}
                try { mediaMuxer.release(); Log.d(TAG, "MediaMuxer released."); } catch (Exception ignore) {}
                mediaMuxer = null;

                try { if (videoDecoder != null) videoDecoder.stop(); } catch (Exception ignore) {}
                try { if (videoDecoder != null) videoDecoder.release(); } catch (Exception ignore) {}
                try { if (videoEncoder != null) videoEncoder.stop(); } catch (Exception ignore) {}
                try { if (videoEncoder != null) videoEncoder.release(); } catch (Exception ignore) {}
                try { if (audioDecoder != null) audioDecoder.stop(); } catch (Exception ignore) {}
                try { if (audioDecoder != null) audioDecoder.release(); } catch (Exception ignore) {}
                try { if (audioEncoder != null) audioEncoder.stop(); } catch (Exception ignore) {}
                try { if (audioEncoder != null) audioEncoder.release(); } catch (Exception ignore) {}

                videoExtractor.release();
                audioExtractor.release();

                File originalFile = new File(sourcePath);
                File compressedFile = new File(destinationPath);
                Log.d(TAG, "Pre-replace sizes (bytes) -> original=" + (originalFile.exists() ? originalFile.length() : -1) +
                        ", compressedTmp=" + (compressedFile.exists() ? compressedFile.length() : -1));
                if (originalFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    originalFile.delete();
                    Log.d(TAG, "Original file deleted.");
                }
                //noinspection ResultOfMethodCallIgnored
                compressedFile.renameTo(originalFile);
                Log.d(TAG, "Compressed file moved to original path.");

                if (lastProgress < 100) {
                    callback.onProgress(100);
                    Log.d(TAG, "Progress: 100%");
                }
                callback.onSuccess();

            } catch (Exception e) {
                Log.e(TAG, "Video compression failed", e);
                callback.onError(e);
            } finally {
                try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); } } catch (Exception ignore) {}
                try { if (audioDecoder != null) { audioDecoder.stop(); audioDecoder.release(); } } catch (Exception ignore) {}
                try { if (videoEncoder != null) { videoEncoder.stop(); videoEncoder.release(); } } catch (Exception ignore) {}
                try { if (videoDecoder != null) { videoDecoder.stop(); videoDecoder.release(); } } catch (Exception ignore) {}
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