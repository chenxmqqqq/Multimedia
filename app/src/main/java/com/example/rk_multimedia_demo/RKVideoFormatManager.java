package com.example.rk_multimedia_demo;

import android.content.Context;
import android.util.Log;

import java.util.List;

/**
 * RK Android 14 视频查询+格式检测管理类
 * 先扫描视频，再检测指定视频的格式
 */
public class RKVideoFormatManager {
    private static final String TAG = "RKVideoFormatManager";
    private final Context mContext;
    private final RKVideoScanner mVideoScanner;
    private final RKVideoFormatDetector mFormatDetector;

    public RKVideoFormatManager(Context context) {
        this.mContext = context;
        this.mVideoScanner = new RKVideoScanner(context);
        this.mFormatDetector = new RKVideoFormatDetector();
    }

    /**
     * 第一步：扫描设备中所有视频
     * @return 视频列表
     */
    public List<RKVideoScanner.VideoItem> scanVideos() {
        return mVideoScanner.scanAllVideos();
    }

    /**
     * 第二步：检测指定视频的详细格式（编码、分辨率、帧率等）
     * @param videoItem 扫描得到的视频项
     * @return 视频格式信息（null=检测失败）
     */
    public RKVideoFormatDetector.VideoFormatInfo detectVideoFormat(RKVideoScanner.VideoItem videoItem) {
        if (videoItem == null) {
            Log.e(TAG, "视频项为空，无法检测格式");
            return null;
        }

        Log.d(TAG, "开始检测视频格式：" + videoItem.displayName);
        RKVideoFormatDetector.VideoFormatInfo formatInfo = mFormatDetector.detectVideoFormat(mContext, videoItem.path);

        if (formatInfo != null) {
            Log.d(TAG, "✅ 视频格式检测成功：");
            Log.d(TAG, "编码格式：" + formatInfo.mimeType);
            Log.d(TAG, "分辨率：" + formatInfo.width + "x" + formatInfo.height);
            Log.d(TAG, "帧率：" + formatInfo.frameRate + "fps");
            Log.d(TAG, "码率：" + formatInfo.bitrate/1000 + "kbps");
        } else {
            Log.e(TAG, "❌ 视频格式检测失败：" + videoItem.displayName);
        }

        return formatInfo;
    }
}