package com.example.rk_multimedia_demo;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class RK4KRawFrameDecoder {
    private static final String TAG = "RK4KRawFrameDecoder";
    private static final int TIMEOUT_US = 1000;

    private int mVideoWidth;
    private int mVideoHeight;
    private int mFrameRate;
    private long mFrameDurationUs;
    private int mYuvFrameSize;
    private long mTotalFrameCount;
    private long mDecodedHalfFrameCount;
    private byte[] mSingle4KFrameBuffer;
    // 新增：记录解码输出的YUV格式（关键）
    private int mOutputColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;

    public interface OnYuvFrameCallback {
        boolean onFrameReceived(byte[] yuvData, int width, int height, long pts, int colorFormat);
    }

    // 修改回调参数，增加colorFormat
    public String decode4KRawFrames(Context context, String videoPath, OnYuvFrameCallback callback, boolean decodeHalf) {
        if (callback == null) {
            return "❌ 必须设置帧回调接口";
        }
        if (!isExternalStorageAvailable()) {
            return "❌ 外部存储不可用";
        }

        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        StringBuilder result = new StringBuilder();
        int decodedFrameCount = 0;
        long targetFrameCount = 0;

        try {
            extractor = new MediaExtractor();
            if (videoPath.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(videoPath), null);
            } else {
                extractor.setDataSource(videoPath);
            }

            int videoTrackIndex = -1;
            MediaFormat videoFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    videoFormat = fmt;
                    break;
                }
            }
            if (videoTrackIndex < 0) {
                return "❌ 未找到视频轨道";
            }
            extractor.selectTrack(videoTrackIndex);

            mVideoWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
            mVideoHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
            mFrameRate = videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
            mFrameDurationUs = 1000000 / mFrameRate;
            mYuvFrameSize = mVideoWidth * mVideoHeight * 3 / 2;

            long videoDurationUs = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            mTotalFrameCount = videoDurationUs / mFrameDurationUs;
            if (decodeHalf) {
                targetFrameCount = mTotalFrameCount / 2;
                result.append("✅ 视频总帧数：").append(mTotalFrameCount).append("，本次解码：").append(targetFrameCount).append("帧（半帧）\n");
            } else {
                targetFrameCount = mTotalFrameCount;
                result.append("✅ 视频总帧数：").append(mTotalFrameCount).append("，本次解码全部帧\n");
            }

            mSingle4KFrameBuffer = new byte[mYuvFrameSize];

            result.append("✅ 4K 原始参数（不缩放）：\n");
            result.append("分辨率：").append(mVideoWidth).append("×").append(mVideoHeight).append("\n");
            result.append("单帧 YUV 大小：").append(mYuvFrameSize / 1024 / 1024).append("MB\n");
            result.append("帧率：").append(mFrameRate).append("fps\n");
            result.append("🔄 开始解码 4K 原始帧...\n");

            String videoMime = videoFormat.getString(MediaFormat.KEY_MIME);
            MediaCodecInfo decoderInfo = findRK4KHardwareCodec(videoMime, false);
            if (decoderInfo == null) {
                return "❌ RK 平台不支持 " + videoMime + " 4K 硬件解码";
            }
            result.append("✅ 使用 RK 4K 解码器：").append(decoderInfo.getName()).append("\n");

            decoder = MediaCodec.createByCodecName(decoderInfo.getName());
            videoFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, mFrameRate);
            videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024);
            decoder.configure(videoFormat, null, null, 0);
            decoder.start();

            // 新增：获取解码器输出的颜色格式（关键）
            MediaCodecInfo.CodecCapabilities capabilities = decoder.getCodecInfo().getCapabilitiesForType(videoMime);
            for (int format : capabilities.colorFormats) {
                if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                        || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                    mOutputColorFormat = format;
                    result.append("✅ 解码器输出YUV格式：").append(getColorFormatName(format)).append("\n");
                    break;
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean isEOS = false;

            while (!isEOS) {
                if (decodeHalf && decodedFrameCount >= targetFrameCount) {
                    result.append("✅ 已解码 ").append(decodedFrameCount).append(" 帧（达到半帧目标），停止解码\n");
                    mDecodedHalfFrameCount = decodedFrameCount;
                    isEOS = true;
                    break;
                }

                if (!isEOS) {
                    int inputBufferId = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            long pts = extractor.getSampleTime();

                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufferId, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                isEOS = true;
                            } else {
                                decoder.queueInputBuffer(inputBufferId, 0, sampleSize, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                while (outputBufferId >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoder.releaseOutputBuffer(outputBufferId, false);
                        outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 0);
                        isEOS = true;
                        break;
                    }

                    ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufferId);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // 修复：移除Math.min，确保完整拷贝（关键）
                        if (bufferInfo.size == mYuvFrameSize) {
                            outputBuffer.get(mSingle4KFrameBuffer, 0, bufferInfo.size);
                        } else {
                            Log.w(TAG, "YUV帧大小不匹配：期望" + mYuvFrameSize + "，实际" + bufferInfo.size);
                            decoder.releaseOutputBuffer(outputBufferId, false);
                            outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 0);
                            continue;
                        }

                        // 回调时传入颜色格式
                        boolean continueDecode = callback.onFrameReceived(
                                mSingle4KFrameBuffer,
                                mVideoWidth,
                                mVideoHeight,
                                bufferInfo.presentationTimeUs,
                                mOutputColorFormat
                        );

                        decodedFrameCount++;

                        if (!continueDecode) {
                            isEOS = true;
                            break;
                        }

                        System.gc();
                    }

                    decoder.releaseOutputBuffer(outputBufferId, false);
                    outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 0);
                }
            }

            result.append("✅ 4K 原始帧解码完成：\n");
            result.append("实际解码帧数：").append(decodedFrameCount).append("帧\n");
            result.append("内存占用峰值：≈").append(mYuvFrameSize / 1024 / 1024).append("MB（仅 1 帧）");
            mDecodedHalfFrameCount = decodedFrameCount;

        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "4K 解码 OOM", oom);
            result.append("❌ 4K 解码内存不足：\n");
            result.append("当前设备单应用可用内存 < ").append(mYuvFrameSize / 1024 / 1024).append("MB\n");
            result.append("建议：检查 RK 板子的内存配置，或降低解码帧率");
        } catch (Exception e) {
            Log.e(TAG, "4K 解码异常", e);
            result.append("❌ 4K 解码失败：").append(e.getMessage());
        } finally {
            try {
                if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                }
            } catch (Exception ignored) {}
            try {
                if (extractor != null) {
                    extractor.release();
                }
            } catch (Exception ignored) {}

            mSingle4KFrameBuffer = null;
            System.gc();
        }

        return result.toString();
    }

    // 辅助方法：格式化颜色格式名称
    private String getColorFormatName(int format) {
        switch (format) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return "YUV420P(I420)";
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return "NV12";
            default:
                return "UNKNOWN(" + format + ")";
        }
    }

    private MediaCodecInfo findRK4KHardwareCodec(String mime, boolean isEncoder) {
        int codecCount = MediaCodecList.getCodecCount();
        for (int i = 0; i < codecCount; i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly()) {
                continue;
            }
            if (info.isEncoder() != isEncoder) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime) && info.getName().contains("rk")) {
                    return info;
                }
            }
        }
        return null;
    }

    private boolean isExternalStorageAvailable() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    public int getVideoWidth() { return mVideoWidth; }
    public int getVideoHeight() { return mVideoHeight; }
    public int getFrameRate() { return mFrameRate; }
    public long getFrameDurationUs() { return mFrameDurationUs; }
    public int getYuvFrameSize() { return mYuvFrameSize; }
    public long getDecodedHalfFrameCount() { return mDecodedHalfFrameCount; }
    // 新增：对外暴露解码输出格式
    public int getOutputColorFormat() { return mOutputColorFormat; }
}