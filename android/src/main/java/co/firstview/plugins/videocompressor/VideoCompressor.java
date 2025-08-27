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

            try {
                // Initialize MediaExtractor to read the source video and audio
                videoExtractor.setDataSource(sourcePath);
                audioExtractor.setDataSource(sourcePath);

                // Select video and audio tracks
                int videoTrackIndex = selectTrack(videoExtractor, "video/");
                int audioTrackIndex = selectTrack(audioExtractor, "audio/");

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
                int outputVideoTrackIndex = -1;
                boolean outputVideoFormatAdded = false;
                long videoDurationUs = inputVideoFormat.getLong(MediaFormat.KEY_DURATION);
                
                // --- Video Compression Loop ---
                while (true) {
                    int inputBufferIndex = videoEncoder.dequeueInputBuffer(-1);
                    if (inputBufferIndex >= 0) {
                        int sampleSize = videoExtractor.readSampleData(videoEncoder.getInputBuffer(inputBufferIndex), 0);
                        if (sampleSize < 0) {
                            videoEncoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            break;
                        }
                        
                        long presentationTimeUs = videoExtractor.getSampleTime();
                        videoEncoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, videoExtractor.getSampleFlags());
                        
                        // Emit progress update
                        int progress = (int) ((presentationTimeUs * 100) / videoDurationUs);
                        callback.onProgress(progress);

                        videoExtractor.advance();
                    }
                    
                    int outputBufferIndex = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 0);
                    while (outputBufferIndex >= 0) {
                        if (!outputVideoFormatAdded) {
                            outputVideoTrackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
                            mediaMuxer.start(); // Start muxer only after both tracks are added
                            outputVideoFormatAdded = true;
                        }
                        ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                        mediaMuxer.writeSampleData(outputVideoTrackIndex, outputBuffer, videoBufferInfo);
                        videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                        outputBufferIndex = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 0);
                    }
                }
                
                // --- Audio Compression Loop (if audio track exists) ---
                if (audioTrackIndex != -1) {
                    MediaFormat inputAudioFormat = audioExtractor.getTrackFormat(audioTrackIndex);
                    
                    // Create and configure audio encoder
                    MediaFormat outputAudioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
                    
                    audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    audioEncoder.configure(outputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    audioEncoder.start();
                    
                    audioExtractor.selectTrack(audioTrackIndex);
                    MediaCodec.BufferInfo audioBufferInfo = new MediaCodec.BufferInfo();
                    int outputAudioTrackIndex = -1;

                    while (true) {
                        int inputBufferIndex = audioEncoder.dequeueInputBuffer(-1);
                        if (inputBufferIndex >= 0) {
                            int sampleSize = audioExtractor.readSampleData(audioEncoder.getInputBuffer(inputBufferIndex), 0);
                            if (sampleSize < 0) {
                                audioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                break;
                            }
                            
                            long presentationTimeUs = audioExtractor.getSampleTime();
                            audioEncoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, audioExtractor.getSampleFlags());
                            audioExtractor.advance();
                        }
                        
                        int outputBufferIndex = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 0);
                        while (outputBufferIndex >= 0) {
                            if (outputAudioTrackIndex == -1) {
                                outputAudioTrackIndex = mediaMuxer.addTrack(audioEncoder.getOutputFormat());
                            }
                            ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(outputBufferIndex);
                            mediaMuxer.writeSampleData(outputAudioTrackIndex, outputBuffer, audioBufferInfo);
                            audioEncoder.releaseOutputBuffer(outputBufferIndex, false);
                            outputBufferIndex = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 0);
                        }
                    }
                    audioEncoder.stop();
                    audioEncoder.release();
                }

                // Cleanup and finalize
                mediaMuxer.stop();
                mediaMuxer.release();
                videoEncoder.stop();
                videoEncoder.release();
                videoExtractor.release();
                audioExtractor.release();

                File originalFile = new File(sourcePath);
                File compressedFile = new File(destinationPath);
                if (originalFile.exists()) {
                    originalFile.delete();
                }
                compressedFile.renameTo(originalFile);

                callback.onSuccess();

            } catch (Exception e) {
                Log.e(TAG, "Video compression failed", e);
                callback.onError(e);
            } finally {
                if (mediaMuxer != null) {
                    mediaMuxer.release();
                }
                if (videoEncoder != null) {
                    videoEncoder.release();
                }
                if (audioEncoder != null) {
                    audioEncoder.release();
                }
                videoExtractor.release();
                audioExtractor.release();
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