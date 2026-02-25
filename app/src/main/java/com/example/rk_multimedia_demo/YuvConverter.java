package com.example.rk_multimedia_demo;

import android.media.MediaCodecInfo;
import android.util.Log;

public class YuvConverter {
    private static final String TAG = "YuvConverter";

    /**
     * I420(YUV420P) 转 NV12
     * I420格式：Y(宽×高) + U(宽/2×高/2) + V(宽/2×高/2)
     * NV12格式：Y(宽×高) + UV(宽/2×高/2，U和V交织)
     */
    public static byte[] i420ToNv12(byte[] i420Data, int width, int height) {
        if (i420Data == null || i420Data.length != width * height * 3 / 2) {
            Log.e(TAG, "I420数据无效：长度=" + (i420Data == null ? 0 : i420Data.length) + "，期望=" + width * height * 3 / 2);
            return null;
        }

        byte[] nv12Data = new byte[width * height * 3 / 2];
        int ySize = width * height;
        int uvSize = width * height / 4;

        // 1. 拷贝Y分量（两者Y分量完全相同）
        System.arraycopy(i420Data, 0, nv12Data, 0, ySize);

        // 2. 转换UV分量（I420的U/V分离 → NV12的UV交织）
        byte[] uData = new byte[uvSize];
        byte[] vData = new byte[uvSize];
        System.arraycopy(i420Data, ySize, uData, 0, uvSize);
        System.arraycopy(i420Data, ySize + uvSize, vData, 0, uvSize);

        int uvIndex = ySize;
        for (int i = 0; i < uvSize; i++) {
            nv12Data[uvIndex++] = uData[i];
            nv12Data[uvIndex++] = vData[i];
        }

        return nv12Data;
    }

    /**
     * 判断是否为I420格式
     */
    public static boolean isI420Format(int colorFormat) {
        return colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
    }

    /**
     * 判断是否为NV12格式
     */
    public static boolean isNv12Format(int colorFormat) {
        return colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    }
}