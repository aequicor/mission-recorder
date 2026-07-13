package io.aequicor.media.desktop.ffmpeg

import io.aequicor.capture.core.AudioFrame
import io.aequicor.capture.core.RecordingOutput
import io.aequicor.capture.core.RecordingSession
import io.aequicor.capture.core.VideoFrame
import io.aequicor.capture.core.audioOutputFormat
import io.aequicor.capture.core.estimateDroppedVideoFrames
import io.aequicor.replay.ReplayBufferException
import io.aequicor.replay.ReplayBufferStats
import io.aequicor.replay.ReplayMediaBuffer
import io.aequicor.replay.ReplaySaveResult
import io.aequicor.replay.ReplayStoragePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class FfmpegSegmentedReplayBuffer(
    private val storageRoot: Path,
    private val segmentDuration: Duration = DEFAULT_SEGMENT_DURATION,
) : ReplayMediaBuffer {
    private val frameMutex = Mutex()
    private val saveMutex = Mutex()
    private var context: ReplayContext? = null

    init {
        require(segmentDuration.isPositive()) { "Replay segment duration must be positive." }
    }

    override suspend fun open(session: RecordingSession, duration: Duration) = saveMutex.withLock {
        frameMutex.withLock {
            if (context != null) {
                throw ReplayBufferException("Replay buffer is already open.")
            }
            if (!duration.isPositive()) {
                throw ReplayBufferException("Replay duration must be positive.")
            }
            validateDesktopFfmpegSettings(session.settings)
            storageRoot.createDirectories()
            val workingDirectory = allocateDirectory("live-${session.id.value}")
            Files.createDirectory(workingDirectory)
            context = ReplayContext(
                session = session,
                duration = duration,
                workingDirectory = workingDirectory,
                maxSegments = maxSegmentCount(duration, segmentDuration),
                segmentScanIntervalNanoseconds = segmentDuration.inWholeNanoseconds,
            )
        }
    }

    override suspend fun writeVideoFrame(frame: VideoFrame): ReplayBufferStats = frameMutex.withLock {
        val current = context ?: throw ReplayBufferException("Replay buffer is not open.")
        try {
            val startedNow = current.recorder == null
            val recorder = current.recorder ?: startRecorder(current, frame).also { started ->
                current.recorder = started
            }
            if (frame.width != current.sourceWidth || frame.height != current.sourceHeight) {
                throw ReplayBufferException("Video frame dimensions changed while replay buffering was active.")
            }
            val timestampMicros = current.relativeTimestampMicros(frame.timestamp.nanoseconds)
            recorder.timestamp = timestampMicros
            recorder.record(
                requireNotNull(current.rgbaFrameBuffer).copyFrom(frame),
                frame.pixelFormat.toFfmpegPixelFormat(),
            )
            if (startedNow) {
                current.pendingAudioFrames.sortedBy { it.timestamp }.forEach { pending ->
                    recorder.recordPcmFrame(
                        frame = pending,
                        timestampMicros = current.relativeTimestampMicros(pending.timestamp.nanoseconds),
                    )
                }
                current.pendingAudioFrames.clear()
            }
            current.trackSegments(frame.timestamp.nanoseconds)
            current.trimStoredSegments()
            current.appendVideoTimestamp(frame.timestamp.nanoseconds)
            current.stats()
        } catch (throwable: Throwable) {
            throw throwable.asReplayBufferException("FFmpeg failed to buffer a video frame.")
        }
    }

    override suspend fun writeAudioFrame(frame: AudioFrame): ReplayBufferStats = frameMutex.withLock {
        val current = context ?: throw ReplayBufferException("Replay buffer is not open.")
        validateAudioFrame(frame)
        try {
            current.audioBatcher.append(frame).forEach(current::writeAudioFrame)
            current.appendAudioTimestamp(frame.timestamp.nanoseconds)
            current.stats()
        } catch (throwable: Throwable) {
            throw throwable.asReplayBufferException("FFmpeg failed to buffer an audio frame.")
        }
    }

    override suspend fun save(outputPath: String): ReplaySaveResult = saveMutex.withLock {
        val snapshot = frameMutex.withLock {
            val current = context ?: throw ReplayBufferException("Replay buffer is not open.")
            createSnapshot(current)
        }
        try {
            transcodeSnapshot(snapshot, Path.of(outputPath).toAbsolutePath().normalize())
        } finally {
            frameMutex.withLock {
                context?.releaseSnapshot(snapshot)
            }
        }
    }

    override suspend fun close() = saveMutex.withLock {
        frameMutex.withLock {
            val current = context ?: return@withLock
            context = null
            runCatching { stopRecorder(current) }
            runCatching { current.rgbaFrameBuffer?.close() }
            current.rgbaFrameBuffer = null
            current.directories.forEach(::deleteRecursively)
        }
    }

    private fun startRecorder(current: ReplayContext, firstFrame: VideoFrame): FFmpegFrameRecorder {
        firstFrame.validateVideoFrame()
        current.sourceWidth = firstFrame.width
        current.sourceHeight = firstFrame.height
        current.encodedWidth = firstFrame.width.roundUpToEven()
        current.encodedHeight = firstFrame.height.roundUpToEven()
        if (current.rgbaFrameBuffer == null) {
            current.rgbaFrameBuffer = RgbaFrameBuffer(
                current.encodedWidth,
                current.encodedHeight,
                firstFrame.pixelFormat,
            )
        }
        current.generationOriginNanoseconds = firstFrame.timestamp.nanoseconds
        val audioFormat = current.session.settings.audioOutputFormat()
        val pattern = current.workingDirectory.resolve(SEGMENT_FILE_PATTERN).toString()
        return FFmpegFrameRecorder(
            pattern,
            current.encodedWidth,
            current.encodedHeight,
            audioFormat?.channelCount ?: 0,
        ).apply {
            format = "segment"
            setOption("segment_format", "mpegts")
            setOption("segment_time", segmentDuration.inWholeMicroseconds.toSecondsString())
            setOption("segment_wrap", current.maxSegments.toString())
            setOption("segment_start_number", current.nextSegmentIndex.toString())
            setOption("reset_timestamps", "1")
            configureOpenH264BitrateControl()
            pixelFormat = AV_PIX_FMT_YUV420P
            frameRate = current.session.settings.frameRate.toDouble()
            videoBitrate = current.session.settings.encoder.videoBitrateBitsPerSecond
            gopSize = segmentFrameCount(current.session.settings.frameRate, segmentDuration)
            setInterleaved(true)
            if (audioFormat != null) {
                audioCodec = AV_CODEC_ID_AAC
                audioBitrate = current.session.settings.encoder.audioBitrateBitsPerSecond
                sampleRate = audioFormat.sampleRate
                audioChannels = audioFormat.channelCount
            }
            start()
        }
    }

    private fun createSnapshot(current: ReplayContext): ReplaySegmentSnapshot {
        if (current.recorder != null) {
            stopRecorder(current)
        }
        val orderedSegments = current.orderedSegments()
        if (orderedSegments.isEmpty()) {
            throw ReplayBufferException("Replay buffer does not contain finalized segments.")
        }
        current.pinSegments(orderedSegments)
        return try {
            val nextDirectory = allocateDirectory("live-${current.session.id.value}")
            Files.createDirectory(nextDirectory)
            current.rotateTo(nextDirectory)
            ReplaySegmentSnapshot(
                segments = orderedSegments,
                session = current.session,
                duration = current.duration,
                droppedFrames = current.retainedDroppedVideoFrames,
            )
        } catch (throwable: Throwable) {
            current.unpinSegments(orderedSegments)
            throw throwable.asReplayBufferException("Failed to snapshot replay segments.")
        }
    }

    private fun stopRecorder(current: ReplayContext) {
        val recorder = current.recorder ?: return
        try {
            current.audioBatcher.drain()?.let(current::writeAudioFrame)
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
            current.recorder = null
            current.generationOriginNanoseconds = null
            current.trackSegments(force = true)
            current.nextSegmentIndex = current.latestSegmentIndex()
                ?.let { (it + 1) % current.maxSegments }
                ?: 0
        }
    }

    private suspend fun transcodeSnapshot(snapshot: ReplaySegmentSnapshot, output: Path): ReplaySaveResult {
        if (output.extension.lowercase() != "mp4") {
            throw ReplayBufferException("Replay output must use the .mp4 extension.")
        }
        if (output.exists()) {
            throw ReplayBufferException("Replay output already exists: $output")
        }
        output.parent?.createDirectories()
        val temporary = output.resolveSibling("${output.name}.tmp-${snapshot.session.id.value}.mp4")
        if (temporary.exists()) {
            throw ReplayBufferException("Temporary replay output already exists: $temporary")
        }

        val probes = snapshot.segments.map(::probeSegment)
        val totalDurationMicros = probes.sumOf(SegmentProbe::durationMicros)
        val cutoffMicros = (totalDurationMicros - snapshot.duration.inWholeMicroseconds).coerceAtLeast(0)
        val firstVideoProbe = probes.firstOrNull { it.width > 0 && it.height > 0 }
            ?: throw ReplayBufferException("Replay segments do not contain a video stream.")
        val audioFormat = snapshot.session.settings.audioOutputFormat()
        val recorder = FFmpegFrameRecorder(
            temporary.toFile(),
            firstVideoProbe.width,
            firstVideoProbe.height,
            audioFormat?.channelCount ?: 0,
        ).apply {
            format = "mp4"
            configureOpenH264BitrateControl()
            pixelFormat = AV_PIX_FMT_YUV420P
            frameRate = snapshot.session.settings.frameRate.toDouble()
            videoBitrate = snapshot.session.settings.encoder.videoBitrateBitsPerSecond
            gopSize = snapshot.session.settings.frameRate * snapshot.session.settings.encoder.keyframeIntervalSeconds
            setInterleaved(true)
            if (audioFormat != null) {
                audioCodec = AV_CODEC_ID_AAC
                audioBitrate = snapshot.session.settings.encoder.audioBitrateBitsPerSecond
                sampleRate = audioFormat.sampleRate
                audioChannels = audioFormat.channelCount
            }
        }
        var videoFrames = 0L
        var audioFrames = 0L
        var segmentStartMicros = 0L
        var finalTimestampMicros = 0L
        try {
            recorder.start()
            probes.forEach { probe ->
                currentCoroutineContext().ensureActive()
                val segmentEndMicros = segmentStartMicros + probe.durationMicros
                if (segmentEndMicros > cutoffMicros) {
                    val grabber = FFmpegFrameGrabber(probe.path.toFile())
                    try {
                        grabber.start()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val frame = grabber.grab() ?: break
                            val sourceTimestamp = segmentStartMicros + grabber.timestamp.coerceAtLeast(0)
                            if (sourceTimestamp < cutoffMicros) {
                                continue
                            }
                            val outputTimestamp = (sourceTimestamp - cutoffMicros).coerceAtLeast(0)
                            recorder.timestamp = outputTimestamp
                            frame.timestamp = outputTimestamp
                            recorder.record(frame)
                            if (frame.image != null) {
                                videoFrames += 1
                            }
                            if (frame.samples != null) {
                                audioFrames += 1
                            }
                            finalTimestampMicros = maxOf(finalTimestampMicros, outputTimestamp)
                        }
                    } finally {
                        runCatching { grabber.stop() }
                        runCatching { grabber.release() }
                    }
                }
                segmentStartMicros = segmentEndMicros
            }
            if (videoFrames == 0L) {
                throw ReplayBufferException("Replay snapshot does not contain decodable video frames.")
            }
            recorder.stop()
            recorder.release()
            moveOutput(temporary, output)
            return ReplaySaveResult(
                output = RecordingOutput(output.toString()),
                videoFrames = videoFrames,
                audioFrames = audioFrames,
                duration = finalTimestampMicros.microseconds,
                droppedFrames = snapshot.droppedFrames,
            )
        } catch (throwable: Throwable) {
            runCatching { recorder.release() }
            Files.deleteIfExists(temporary)
            throw throwable.asReplayBufferException("FFmpeg failed to create the replay MP4.")
        }
    }

    private fun probeSegment(path: Path): SegmentProbe {
        val grabber = FFmpegFrameGrabber(path.toFile())
        return try {
            grabber.start()
            SegmentProbe(
                path = path,
                durationMicros = grabber.lengthInTime.takeIf { it > 0 }
                    ?: segmentDuration.inWholeMicroseconds,
                width = grabber.imageWidth,
                height = grabber.imageHeight,
            )
        } catch (throwable: Throwable) {
            throw throwable.asReplayBufferException("Failed to inspect replay segment: $path")
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    private fun allocateDirectory(prefix: String): Path {
        repeat(MAX_PATH_ATTEMPTS) { attempt ->
            val sanitizedPrefix = prefix.replace(UNSAFE_FILE_NAME_CHARACTERS, "-")
            val candidate = storageRoot.resolve("$sanitizedPrefix-${System.nanoTime()}-$attempt")
            if (!candidate.exists()) {
                return candidate
            }
        }
        throw ReplayBufferException("Could not allocate replay storage under $storageRoot")
    }
}

private data class ReplayContext(
    val session: RecordingSession,
    val duration: Duration,
    var workingDirectory: Path,
    val maxSegments: Int,
    val segmentScanIntervalNanoseconds: Long,
    val pendingAudioFrames: MutableList<AudioFrame> = mutableListOf(),
    val audioBatcher: PcmAudioFrameBatcher = PcmAudioFrameBatcher(),
    val videoTimestamps: ArrayDeque<Long> = ArrayDeque(),
    val droppedFramesBeforeVideoTimestamp: ArrayDeque<Long> = ArrayDeque(),
    val audioTimestamps: ArrayDeque<Long> = ArrayDeque(),
    val fingerprints: MutableMap<Path, SegmentFingerprint> = mutableMapOf(),
    val segmentOrder: MutableMap<Path, Long> = mutableMapOf(),
    val pinnedSegments: MutableMap<Path, Int> = mutableMapOf(),
    val directories: MutableSet<Path> = mutableSetOf(workingDirectory),
    var nextOrder: Long = 0,
    var recorder: FFmpegFrameRecorder? = null,
    var rgbaFrameBuffer: RgbaFrameBuffer? = null,
    var generationOriginNanoseconds: Long? = null,
    var sourceWidth: Int = 0,
    var sourceHeight: Int = 0,
    var encodedWidth: Int = 0,
    var encodedHeight: Int = 0,
    var nextSegmentIndex: Int = 0,
    var lastSegmentScanTimestampNanoseconds: Long? = null,
    var retainedDroppedVideoFrames: Long = 0,
) {
    fun writeAudioFrame(frame: AudioFrame) {
        val activeRecorder = recorder
        if (activeRecorder == null) {
            pendingAudioFrames += frame
        } else {
            activeRecorder.recordPcmFrame(
                frame = frame,
                timestampMicros = relativeTimestampMicros(frame.timestamp.nanoseconds),
            )
        }
    }

    fun relativeTimestampMicros(timestampNanoseconds: Long): Long {
        val origin = generationOriginNanoseconds ?: timestampNanoseconds
        return (timestampNanoseconds - origin).coerceAtLeast(0) / NANOS_PER_MICROSECOND
    }

    fun appendVideoTimestamp(timestampNanoseconds: Long) {
        val droppedFrames = estimateDroppedVideoFrames(
            previousTimestampNanoseconds = videoTimestamps.lastOrNull(),
            timestampNanoseconds = timestampNanoseconds,
            frameRate = session.settings.frameRate,
        )
        videoTimestamps.addLast(timestampNanoseconds)
        droppedFramesBeforeVideoTimestamp.addLast(droppedFrames)
        retainedDroppedVideoFrames += droppedFrames
        trimTimestamps(timestampNanoseconds)
    }

    fun appendAudioTimestamp(timestampNanoseconds: Long) {
        audioTimestamps.addLast(timestampNanoseconds)
        trimTimestamps(timestampNanoseconds)
    }

    fun stats(): ReplayBufferStats {
        val first = listOfNotNull(videoTimestamps.firstOrNull(), audioTimestamps.firstOrNull()).minOrNull()
        val last = listOfNotNull(videoTimestamps.lastOrNull(), audioTimestamps.lastOrNull()).maxOrNull()
        return ReplayBufferStats(
            videoFrameCount = videoTimestamps.size,
            audioFrameCount = audioTimestamps.size,
            retainedDuration = if (first == null || last == null) {
                Duration.ZERO
            } else {
                (last - first).coerceAtLeast(0).nanoseconds
            },
            storagePolicy = ReplayStoragePolicy.DiskSegments,
            droppedVideoFrameCount = retainedDroppedVideoFrames,
        )
    }

    fun trackSegments(timestampNanoseconds: Long? = null, force: Boolean = false) {
        val lastScan = lastSegmentScanTimestampNanoseconds
        if (!force && timestampNanoseconds != null && lastScan != null &&
            timestampNanoseconds - lastScan < segmentScanIntervalNanoseconds
        ) {
            return
        }
        val paths = Files.list(workingDirectory).use { stream ->
            stream.filter { it.extension.equals("ts", ignoreCase = true) }.toList()
        }
        paths.sortedBy(Path::toString).forEach { path ->
            val fingerprint = SegmentFingerprint(
                size = Files.size(path),
                lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
            )
            if (fingerprints[path] != fingerprint) {
                fingerprints[path] = fingerprint
                nextOrder += 1
                segmentOrder[path] = nextOrder
            }
        }
        if (timestampNanoseconds != null) {
            lastSegmentScanTimestampNanoseconds = timestampNanoseconds
        }
    }

    fun orderedSegments(): List<Path> {
        trackSegments(force = true)
        return segmentOrder.entries
            .filter { (path, _) -> path.exists() && Files.size(path) > 0 }
            .sortedBy(Map.Entry<Path, Long>::value)
            .map(Map.Entry<Path, Long>::key)
    }

    fun pinSegments(paths: List<Path>) {
        paths.forEach { path ->
            pinnedSegments[path] = (pinnedSegments[path] ?: 0) + 1
        }
    }

    fun unpinSegments(paths: List<Path>) {
        paths.forEach { path ->
            val remaining = (pinnedSegments[path] ?: 0) - 1
            if (remaining > 0) {
                pinnedSegments[path] = remaining
            } else {
                pinnedSegments.remove(path)
            }
        }
    }

    fun releaseSnapshot(snapshot: ReplaySegmentSnapshot) {
        unpinSegments(snapshot.segments)
        trimStoredSegments()
    }

    fun rotateTo(directory: Path) {
        workingDirectory = directory
        directories.add(directory)
        nextSegmentIndex = 0
        generationOriginNanoseconds = null
        lastSegmentScanTimestampNanoseconds = null
    }

    fun trimStoredSegments() {
        var excess = segmentOrder.keys.count { it.exists() } - maxSegments
        if (excess > 0) {
            segmentOrder.entries
                .filter { (path, _) -> path.exists() }
                .sortedBy(Map.Entry<Path, Long>::value)
                .map(Map.Entry<Path, Long>::key)
                .forEach { path ->
                if (excess > 0 && (pinnedSegments[path] ?: 0) == 0) {
                    Files.deleteIfExists(path)
                    fingerprints.remove(path)
                    segmentOrder.remove(path)
                    excess -= 1
                }
                }
        }
        directories.toList().forEach { directory ->
            if (directory != workingDirectory && directory.exists()) {
                val isEmpty = Files.list(directory).use { paths -> !paths.findAny().isPresent }
                if (isEmpty) {
                    Files.deleteIfExists(directory)
                    directories.remove(directory)
                }
            }
        }
    }

    fun latestSegmentIndex(): Int? = orderedSegments().lastOrNull()?.segmentIndex()

    private fun trimTimestamps(newestTimestampNanoseconds: Long) {
        val minimum = (newestTimestampNanoseconds - duration.inWholeNanoseconds).coerceAtLeast(0)
        while (videoTimestamps.firstOrNull()?.let { it < minimum } == true) {
            removeFirstVideoTimestamp()
        }
        while (audioTimestamps.firstOrNull()?.let { it < minimum } == true) {
            audioTimestamps.removeFirst()
        }
    }

    private fun removeFirstVideoTimestamp() {
        videoTimestamps.removeFirst()
        retainedDroppedVideoFrames -= droppedFramesBeforeVideoTimestamp.removeFirst()
        if (droppedFramesBeforeVideoTimestamp.isNotEmpty()) {
            retainedDroppedVideoFrames -= droppedFramesBeforeVideoTimestamp.removeFirst()
            droppedFramesBeforeVideoTimestamp.addFirst(0)
        }
    }
}

private data class ReplaySegmentSnapshot(
    val segments: List<Path>,
    val session: RecordingSession,
    val duration: Duration,
    val droppedFrames: Long,
)

private data class SegmentProbe(
    val path: Path,
    val durationMicros: Long,
    val width: Int,
    val height: Int,
)

private data class SegmentFingerprint(
    val size: Long,
    val lastModifiedMillis: Long,
)

private fun maxSegmentCount(duration: Duration, segmentDuration: Duration): Int =
    ceil(duration.inWholeMicroseconds.toDouble() / segmentDuration.inWholeMicroseconds)
        .toInt()
        .coerceAtLeast(1) + SEGMENT_SAFETY_MARGIN

private fun segmentFrameCount(frameRate: Int, duration: Duration): Int =
    ceil(frameRate * duration.inWholeMicroseconds / MICROSECONDS_PER_SECOND.toDouble())
        .toInt()
        .coerceAtLeast(1)

private fun Long.toSecondsString(): String =
    (this / MICROSECONDS_PER_SECOND.toDouble()).toString()

private fun Path.segmentIndex(): Int? =
    fileName.toString().removePrefix("segment-").removeSuffix(".ts").toIntOrNull()

private fun moveOutput(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}

private fun deleteRecursively(path: Path) {
    if (!path.exists()) {
        return
    }
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { candidate ->
            Files.deleteIfExists(candidate)
        }
    }
}

private fun Throwable.asReplayBufferException(fallbackMessage: String): ReplayBufferException {
    if (this is CancellationException) {
        throw this
    }
    return this as? ReplayBufferException ?: ReplayBufferException(message ?: fallbackMessage, this)
}

private val UNSAFE_FILE_NAME_CHARACTERS = Regex("[^A-Za-z0-9._-]")

private val DEFAULT_SEGMENT_DURATION = 2.seconds
private const val SEGMENT_FILE_PATTERN = "segment-%06d.ts"
private const val SEGMENT_SAFETY_MARGIN = 1
private const val MAX_PATH_ATTEMPTS = 10
private const val NANOS_PER_MICROSECOND = 1_000L
private const val MICROSECONDS_PER_SECOND = 1_000_000L
