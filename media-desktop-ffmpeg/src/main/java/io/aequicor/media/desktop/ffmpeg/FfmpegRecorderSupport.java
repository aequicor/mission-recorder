package io.aequicor.media.desktop.ffmpeg;

import java.nio.Buffer;
import org.bytedeco.javacv.FFmpegFrameRecorder;

final class FfmpegRecorderSupport {
    private FfmpegRecorderSupport() {}

    static boolean flushAudio(FFmpegFrameRecorder recorder) throws FFmpegFrameRecorder.Exception {
        return recorder.recordSamples(0, 0, (Buffer[]) null);
    }
}
