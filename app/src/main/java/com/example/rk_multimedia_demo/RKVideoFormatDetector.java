package com.example.rk_multimedia_demo;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * RK Android 14 视频格式检测工具
 * 解析视频文件的编码格式、宽高、帧率、比特率等核心参数
 */
public class RKVideoFormatDetector {
    private static final String TAG = "RKVideoFormatDetector";
    private MediaExtractor mediaExtractor;

    /**
     * 检测视频文件的核心格式信息（修复：添加Context参数，支持content:// URI）
     * @param context 上下文（必须传入，用于处理content:// URI）
     * @param videoPath 视频文件路径（如/storage/emulated/0/test.mp4 或 content:// URI）
     * @return 视频格式信息（null=检测失败）
     */
    // ========== 修复点1：添加Context参数 ==========
    public VideoFormatInfo detectVideoFormat(Context context, String videoPath) {
        if (context == null) {
            Log.e(TAG, "Context为空，无法处理content:// URI");
            return null;
        }
        if (videoPath == null || (!new File(videoPath).exists() && !videoPath.startsWith("content://"))) {
            Log.e(TAG, "视频文件不存在或路径无效：" + videoPath);
            return null;
        }

        mediaExtractor = new MediaExtractor();
        VideoFormatInfo formatInfo = new VideoFormatInfo();

        try {
            // Android 14适配：支持文件路径和Content URI（修复：移除ContextHolder）
            if (videoPath.startsWith("content://")) {
                // ========== 修复点2：改用公开的Context+Uri API ==========
                mediaExtractor.setDataSource(context, Uri.parse(videoPath), null);
            } else {
                mediaExtractor.setDataSource(videoPath);
            }

            // 遍历所有轨道，找到视频轨道
            int videoTrackIndex = -1;
            int trackCount = mediaExtractor.getTrackCount();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat trackFormat = mediaExtractor.getTrackFormat(i);
                String mimeType = trackFormat.getString(MediaFormat.KEY_MIME);

                if (mimeType != null && mimeType.startsWith("video/")) {
                    videoTrackIndex = i;
                    // 提取视频核心参数
                    formatInfo.mimeType = mimeType;
                    formatInfo.width = trackFormat.getInteger(MediaFormat.KEY_WIDTH);
                    formatInfo.height = trackFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    formatInfo.bitrate = trackFormat.containsKey(MediaFormat.KEY_BIT_RATE)
                            ? trackFormat.getInteger(MediaFormat.KEY_BIT_RATE) : 0;

                    // 提取帧率（兼容不同Android版本）
                    if (trackFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        formatInfo.frameRate = trackFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
                    } else {
                        // 计算帧率：duration / frame count（备用方案）
                        long durationUs = trackFormat.getLong(MediaFormat.KEY_DURATION);
                        formatInfo.frameRate = durationUs > 0 ? 30 : 25; // 默认30fps
                    }

                    // 提取编码配置（如H.264的SPS/PPS）
                    if (mimeType.equals("video/avc")) {
                        formatInfo.sps = trackFormat.getByteBuffer("csd-0");
                        formatInfo.pps = trackFormat.getByteBuffer("csd-1");
                    }

                    Log.d(TAG, "检测到视频格式：");
                    Log.d(TAG, "MIME类型：" + formatInfo.mimeType);
                    Log.d(TAG, "分辨率：" + formatInfo.width + "x" + formatInfo.height);
                    Log.d(TAG, "帧率：" + formatInfo.frameRate + "fps");
                    Log.d(TAG, "码率：" + formatInfo.bitrate / 1000 + "kbps");
                    break;
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "未找到视频轨道");
                return null;
            }

            mediaExtractor.selectTrack(videoTrackIndex);
            return formatInfo;

        } catch (IOException e) {
            Log.e(TAG, "解析视频格式失败", e);
            return null;
        } finally {
            if (mediaExtractor != null) {
                mediaExtractor.release();
            }
        }
    }

    /**
     * 视频格式信息封装类
     */
    public static class VideoFormatInfo {
        public String mimeType;      // 编码格式（如video/avc=H.264）
        public int width;            // 宽度
        public int height;           // 高度
        public int frameRate;        // 帧率
        public int bitrate;          // 码率
        public ByteBuffer sps; // H.264 SPS
        public ByteBuffer pps; // H.264 PPS
    }
}