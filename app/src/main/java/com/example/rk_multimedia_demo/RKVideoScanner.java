package com.example.rk_multimedia_demo;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class RKVideoScanner {
    private static final String TAG = "RKVideoScanner";
    private final Context mContext;
    private ContentResolver mContentResolver = null;

    public RKVideoScanner(Context mContext) {
        this.mContext = mContext;
        this.mContentResolver = mContext.getContentResolver();
    }

    // 视频基础信息封装类
    public static class VideoItem {
        public long id;             // 视频在MediaStore中的ID
        public String displayName;  // 视频文件名（如test.mp4）
        public String path;         // 视频文件绝对路径（Android 10+可能为content:// URI）
        public long size;           // 视频大小（字节）
        public long duration;       // 视频时长（毫秒）
        public String mimeType;     // 视频MIME类型（如video/mp4）

        @Override
        public String toString() {
            return "视频名称：" + displayName + "\n路径：" + path + "\n时长：" + duration/1000 + "秒";
        }
    }

    /**
     * 扫描设备中所有视频文件（适配Android 14）
     * @return 视频列表（空列表=无视频/权限不足）
     */
    public List<VideoItem> scanAllVideos() {
        List<VideoItem> videoList = new ArrayList<>();

        // 1. 定义要查询的视频字段
        String[] projection = {
                MediaStore.Video.Media._ID,               // 视频ID
                MediaStore.Video.Media.DISPLAY_NAME,      // 文件名
                MediaStore.Video.Media.DATA,              // 文件路径（Android 10-）
                MediaStore.Video.Media.SIZE,              // 文件大小
                MediaStore.Video.Media.DURATION,          // 时长
                MediaStore.Video.Media.MIME_TYPE          // MIME类型
        };

        // 2. Android 10+适配：DATA字段被废弃，改用RELATIVE_PATH（但仍兼容DATA）
        Uri videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        // 3. 查询视频数据（按修改时间倒序）
        try (Cursor cursor = mContentResolver.query(
                videoUri,
                projection,
                null,
                null,
                MediaStore.Video.Media.DATE_MODIFIED + " DESC"
        )) {
            if (cursor == null) {
                Log.e(TAG, "查询视频失败：Cursor为空（权限不足/无视频）");
                return videoList;
            }

            // 4. 解析Cursor数据，封装为VideoItem
            int idIndex = cursor.getColumnIndex(MediaStore.Video.Media._ID);
            int nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            int pathIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
            int sizeIndex = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);
            int durationIndex = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
            int mimeTypeIndex = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE);

            while (cursor.moveToNext()) {
                VideoItem item = new VideoItem();
                item.id = cursor.getLong(idIndex);
                item.displayName = cursor.getString(nameIndex);
                item.path = cursor.getString(pathIndex); // Android 10+可能为null，需兼容
                item.size = cursor.getLong(sizeIndex);
                item.duration = cursor.getLong(durationIndex);
                item.mimeType = cursor.getString(mimeTypeIndex);

                // Android 14适配：若path为null，用Content URI替代
                if (item.path == null || item.path.isEmpty()) {
                    item.path = MediaStore.Video.Media.EXTERNAL_CONTENT_URI + "/" + item.id;
                }

                videoList.add(item);
                Log.d(TAG, "扫描到视频：" + item);
            }

            Log.d(TAG, "✅ 视频扫描完成，共找到 " + videoList.size() + " 个视频");
        } catch (Exception e) {
            Log.e(TAG, "❌ 扫描视频异常", e);
        }

        return videoList;
    }
}