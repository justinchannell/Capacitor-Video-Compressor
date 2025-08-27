package co.firstview.plugins.videocompressor;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;

@CapacitorPlugin(name = "VideoCompressor")
public class VideoCompressorPlugin extends Plugin {

    private VideoCompressor videoCompressor;

    @Override
    public void load() {
        super.load();
        videoCompressor = new VideoCompressor(getContext());
    }

    @PluginMethod
    public void compressVideo(PluginCall call) {
        String path = call.getString("path");
        String quality = call.getString("quality", "high");

        if (path == null) {
            call.reject("Must provide a 'path' to the video file.");
            return;
        }

        File originalFile = new File(path);
        if (!originalFile.exists()) {
            call.reject("Original file does not exist at path: " + path);
            return;
        }

        // Create a temporary destination path for the compressed file
        String tempOutputPath = originalFile.getParent() + "/" + "temp_compressed_" + originalFile.getName();

        videoCompressor.compress(path, tempOutputPath, quality, new VideoCompressor.VideoCompressionCallback() {
            @Override
            public void onSuccess() {
                // Send final 100% for UIs relying on the event
                JSObject ret = new JSObject();
                ret.put("progress", 100);
                getActivity().runOnUiThread(() -> notifyListeners("videoProgress", ret));

                call.resolve(new JSObject());
            }

            @Override
            public void onError(Exception e) {
                call.reject("Video compression failed", e);
            }
            
            @Override
            public void onProgress(int progress) {
                JSObject ret = new JSObject();
                ret.put("progress", progress);
                getActivity().runOnUiThread(() -> notifyListeners("videoProgress", ret));
            }
        });
    }
}
