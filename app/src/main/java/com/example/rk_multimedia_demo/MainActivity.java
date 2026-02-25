package com.example.rk_multimedia_demo;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "RKVideoMain";
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int REQUEST_MANAGE_EXTERNAL_STORAGE = 1002;
    private static final int CPU_UPDATE_INTERVAL = 1000;
    private static final float TOTAL_CPU_CAPACITY = 800.0f;

    // UI控件
    private TextView tvResult;
    private Button btnRequestPermission;
    private Button btnScanVideos;
    private Button btnDetectFormat;
    private Spinner spVideoIndex;
    private com.github.mikephil.charting.charts.PieChart donutChartCpu;
    private TextView tvCpuTitle;
    private Button btnHardwareDecode;
    private Button btnHardwareEncode;

    // 核心组件
    private RKVideoFormatManager videoFormatManager;
    private List<RKVideoScanner.VideoItem> videoList;
    private Handler mainHandler;
    private ArrayAdapter<String> spinnerAdapter;
    private Timer cpuUpdateTimer;
    private CPUUtils cpuUtils;

    // 4K原始帧解码器
    private RK4KRawFrameDecoder rawFrameDecoder;
    private boolean isDecoding = false;
    private boolean isDecodeCompleted = false;

    // 核心变量
    private String yuvOutputPath;       // YUV原始帧文件路径
    private FileOutputStream yuvFos;    // YUV文件输出流
    private MediaCodec h265Encoder;     // H265硬件编码器
    private MediaMuxer mediaMuxer;      // MP4封装器
    private int videoTrackIndex = -1;   // 视频轨道索引
    private boolean isMuxerStarted = false;// 封装器启动标记
    private long encodePts = 0;         // 编码时间戳
    private String h265OutputPath;      // H265输出文件路径
    private boolean isEncoding = false; // 编码状态标记

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化控件
        initViews();

        // 初始化核心组件
        mainHandler = new Handler(Looper.getMainLooper());
        videoFormatManager = new RKVideoFormatManager(this);
        videoList = new ArrayList<>();
        cpuUtils = new CPUUtils();
        rawFrameDecoder = new RK4KRawFrameDecoder();

        // 初始化下拉框
        initSpinner();

        // 初始化CPU环形图
        initCpuChart();

        // 启动CPU实时更新
        startCpuUpdateTimer();

        // 设置按钮事件
        setButtonListeners();
    }

    private void initViews() {
        tvResult = findViewById(R.id.tv_result);
        btnRequestPermission = findViewById(R.id.btn_request_permission);
        btnScanVideos = findViewById(R.id.btn_scan_videos);
        btnDetectFormat = findViewById(R.id.btn_detect_format);
        spVideoIndex = findViewById(R.id.sp_video_index);
        donutChartCpu = findViewById(R.id.donut_chart_cpu);
        tvCpuTitle = findViewById(R.id.tv_cpu_title);
        tvCpuTitle.setText("CPU 总占用");

        btnHardwareDecode = findViewById(R.id.btn_hardware_decode);
        btnHardwareEncode = findViewById(R.id.btn_hardware_encode);
    }

    private void initSpinner() {
        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spVideoIndex.setAdapter(spinnerAdapter);
    }

    private void initCpuChart() {
        Description description = new Description();
        description.setEnabled(false);
        donutChartCpu.setDescription(description);
        donutChartCpu.setRotationEnabled(false);
        donutChartCpu.setHoleRadius(50f);
        donutChartCpu.setTransparentCircleRadius(55f);
        donutChartCpu.setDrawCenterText(true);
        donutChartCpu.setCenterText("0.0%");
        // 核心修改：将中心文字大小从24f改为14f（可根据需求调整为16f/18f）
        donutChartCpu.setCenterTextSize(14f);
        Legend legend = donutChartCpu.getLegend();
        legend.setEnabled(false);
        updateCpuChart(0);
    }

    private void startCpuUpdateTimer() {
        cpuUpdateTimer = new Timer();
        cpuUpdateTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                float cpuUsage = cpuUtils.getTopStyleCPUUsage();
                mainHandler.post(() -> updateCpuChart(cpuUsage));
            }
        }, 0, CPU_UPDATE_INTERVAL);
    }

    private void updateCpuChart(float cpuUsage) {
        float totalCapacity = TOTAL_CPU_CAPACITY;
        float idle = totalCapacity - cpuUsage;

        List<PieEntry> entries = new ArrayList<>();
        entries.add(new PieEntry(cpuUsage, "已使用"));
        entries.add(new PieEntry(Math.max(idle, 0), "空闲"));

        PieDataSet dataSet = new PieDataSet(entries, "");
        dataSet.setColors(new int[]{0xFFFF7F24, 0xFFE3F2FD});
        dataSet.setDrawValues(false);

        PieData data = new PieData(dataSet);
        donutChartCpu.setData(data);
        donutChartCpu.setCenterText(String.format("%.1f%%", cpuUsage));
        donutChartCpu.invalidate();
    }

    // 检查存储空间是否足够
    private boolean isStorageEnough(long requiredSpaceMB) {
        StatFs statFs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            statFs = new StatFs(Environment.getExternalStorageDirectory().getPath());
        } else {
            statFs = new StatFs(Environment.getExternalStorageDirectory().getPath());
        }
        long blockSize = statFs.getBlockSizeLong();
        long availableBlocks = statFs.getAvailableBlocksLong();
        long availableSpaceMB = (availableBlocks * blockSize) / (1024 * 1024);

        Log.d(TAG, "📱 可用存储空间：" + availableSpaceMB + "MB，需要：" + requiredSpaceMB + "MB");
        if (availableSpaceMB < requiredSpaceMB) {
            safeUpdateResult("❌ 存储空间不足！\n可用：" + availableSpaceMB + "MB，需要：" + requiredSpaceMB + "MB");
            return false;
        }
        return true;
    }

    // 完善权限检查（适配Android 11+）
    private boolean hasAllPermissions() {
        boolean hasReadPermission = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasReadPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        } else {
            hasReadPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        Log.d(TAG, "📌 视频读取权限：" + hasReadPermission);

        boolean hasWritePermission = true;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            hasWritePermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else {
            // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE 权限才能写入公共目录
            hasWritePermission = Environment.isExternalStorageManager();
        }
        Log.d(TAG, "📌 存储写入权限：" + hasWritePermission);

        return hasReadPermission && hasWritePermission;
    }

    // 完善权限申请逻辑（适配Android 11+）
    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("申请必要权限")
                .setMessage(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ?
                        "Android 11+ 需要授予「所有文件访问权限」才能写入公共目录，是否前往设置页面授权？" :
                        "为了正常扫描视频和转码保存文件，需要授予「媒体读取」和「存储写入」权限，是否前往设置页面授权？")
                .setPositiveButton("去授权", (dialog, which) -> {
                    dialog.dismiss();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Android 11+ 跳转到所有文件访问权限设置
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE);
                    } else {
                        // 低版本跳转到应用详情页
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    dialog.dismiss();
                    safeUpdateResult("❌ 取消授权：将无法正常使用转码功能");
                })
                .setCancelable(false)
                .show();
    }

    private void setButtonListeners() {
        // 申请权限按钮（适配Android 11+）
        btnRequestPermission.setOnClickListener(v -> {
            if (hasAllPermissions()) {
                safeUpdateResult("✅ 已授予所有必要权限（媒体读取+存储写入）！");
                Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show();
                return;
            }

            List<String> needPermissions = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                    needPermissions.add(Manifest.permission.READ_MEDIA_VIDEO);
                }
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    needPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    needPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }

            if (!needPermissions.isEmpty()) {
                ActivityCompat.requestPermissions(this, needPermissions.toArray(new String[0]), REQUEST_PERMISSIONS);
            } else {
                showPermissionDialog();
            }
        });

        // 扫描视频按钮
        btnScanVideos.setOnClickListener(v -> {
            if (!hasAllPermissions()) {
                safeUpdateResult("❌ 请先点击【申请权限】按钮授予所有必要权限！");
                return;
            }

            new Thread(() -> {
                safeUpdateResult("🔄 正在扫描设备中的视频...");
                videoList = videoFormatManager.scanVideos();

                mainHandler.post(() -> {
                    List<String> indexList = new ArrayList<>();
                    if (videoList != null && !videoList.isEmpty()) {
                        for (int i = 0; i < videoList.size(); i++) {
                            indexList.add(String.valueOf(i + 1) + ". " + videoList.get(i).displayName);
                        }
                    }
                    spinnerAdapter.clear();
                    spinnerAdapter.addAll(indexList);
                    spinnerAdapter.notifyDataSetChanged();

                    if (videoList == null || videoList.isEmpty()) {
                        safeUpdateResult("❌ 扫描完成：未找到任何视频文件");
                    } else {
                        safeUpdateResult("✅ 扫描完成！共找到 " + videoList.size() + " 个视频");
                    }
                });
            }).start();
        });

        // 检测格式按钮
        btnDetectFormat.setOnClickListener(v -> {
            if (!hasAllPermissions()) {
                safeUpdateResult("❌ 请先点击【申请权限】按钮授予所有必要权限！");
                return;
            }

            if (videoList == null || videoList.isEmpty()) {
                safeUpdateResult("❌ 未扫描到任何视频，请先点击【扫描视频】按钮");
                return;
            }

            int selectedPosition = spVideoIndex.getSelectedItemPosition();
            if (selectedPosition == -1) {
                safeUpdateResult("❌ 请先选择视频序号");
                return;
            }

            new Thread(() -> {
                RKVideoScanner.VideoItem selectedVideo = videoList.get(selectedPosition);
                safeUpdateResult("🔄 正在检测视频格式：" + selectedVideo.displayName);

                RKVideoFormatDetector.VideoFormatInfo formatInfo = videoFormatManager.detectVideoFormat(selectedVideo);

                mainHandler.post(() -> {
                    if (formatInfo != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("✅ 视频格式检测成功：\n");
                        sb.append("名称：").append(selectedVideo.displayName).append("\n");
                        sb.append("编码格式：").append(formatInfo.mimeType).append("\n");
                        sb.append("分辨率：").append(formatInfo.width).append(" × ").append(formatInfo.height).append("\n");
                        sb.append("帧率：").append(formatInfo.frameRate).append(" fps\n");
                        sb.append("码率：").append(formatInfo.bitrate / 1000).append(" kbps");
                        safeUpdateResult(sb.toString());
                    } else {
                        safeUpdateResult("❌ 格式检测失败：无法解析 " + selectedVideo.displayName);
                    }
                });
            }).start();
        });

        // 解码按钮（核心修改：支持颜色格式传递）
        btnHardwareDecode.setOnClickListener(v -> {
            if (!hasAllPermissions()) {
                safeUpdateResult("❌ 请先点击【申请权限】按钮授予所有必要权限！");
                return;
            }

            if (videoList == null || videoList.isEmpty()) {
                safeUpdateResult("❌ 未扫描到任何视频，请先扫描！");
                return;
            }

            int selectedPos = spVideoIndex.getSelectedItemPosition();
            if (selectedPos == -1) {
                safeUpdateResult("❌ 请先从下拉框选择视频！");
                return;
            }

            if (isDecoding) {
                safeUpdateResult("⚠️ 正在解码中，请等待完成后再操作！");
                return;
            }

            // 检查存储空间（半帧约15GB，预留20GB）
            if (!isStorageEnough(20480)) { // 20480MB=20GB
                return;
            }

            // 子线程解码半帧并写入YUV文件
            RKVideoScanner.VideoItem selectedVideo = videoList.get(selectedPos);
            new Thread(() -> {
                isDecoding = true;
                isDecodeCompleted = false;
                safeUpdateResult("🔄 开始解码4K原始帧（仅半帧）：" + selectedVideo.displayName);

                try {
                    // 1. 创建YUV输出文件（YUV仍存私有目录，编码后删除）
                    File yuvDir = new File(getExternalFilesDir("RK_YUV"), "raw");
                    if (!yuvDir.exists()) yuvDir.mkdirs();
                    yuvOutputPath = new File(yuvDir, "4k_raw_half_" + System.currentTimeMillis() + ".yuv").getAbsolutePath();
                    yuvFos = new FileOutputStream(yuvOutputPath, false); // 覆盖写入

                    // 2. 解码4K原始帧（仅半帧）并写入文件（修改回调，接收颜色格式）
                    String decodeResult = rawFrameDecoder.decode4KRawFrames(
                            MainActivity.this,
                            selectedVideo.path,
                            new RK4KRawFrameDecoder.OnYuvFrameCallback() {
                                @Override
                                public boolean onFrameReceived(byte[] yuvData, int width, int height, long pts, int colorFormat) {
                                    try {
                                        // 将单帧YUV数据写入文件（追加）
                                        yuvFos.write(yuvData);
                                        yuvFos.flush();
                                        Log.d(TAG, "📝 写入YUV帧：格式=" + getColorFormatName(colorFormat)
                                                + "，大小=" + yuvData.length + "字节，时间戳=" + pts + "μs");
                                    } catch (IOException e) {
                                        Log.e(TAG, "写入YUV帧失败", e);
                                        return false; // 停止解码
                                    }
                                    return true; // 继续解码下一帧
                                }
                            },
                            true // 关键：decodeHalf=true，只解码半帧
                    );

                    // 3. 关闭YUV文件流
                    if (yuvFos != null) {
                        yuvFos.close();
                        yuvFos = null;
                    }

                    isDecoding = false;
                    isDecodeCompleted = true;
                    safeUpdateResult(decodeResult + "\n✅ 半帧YUV文件保存完成：" + yuvOutputPath);

                } catch (Exception e) {
                    Log.e(TAG, "解码并写入YUV失败", e);
                    try {
                        if (yuvFos != null) yuvFos.close();
                    } catch (IOException ignored) {}
                    yuvFos = null;
                    isDecoding = false;
                    safeUpdateResult("❌ 解码失败：" + e.getMessage());
                }
            }).start();
        });

        // 编码按钮
        btnHardwareEncode.setOnClickListener(v -> {
            if (!hasAllPermissions()) {
                safeUpdateResult("❌ 请先点击【申请权限】按钮授予所有必要权限！");
                return;
            }

            if (!isDecodeCompleted || yuvOutputPath == null || !new File(yuvOutputPath).exists()) {
                safeUpdateResult("❌ 请先完成4K半帧解码并生成YUV文件！");
                return;
            }

            // 检查编码所需空间（H265约2GB）
            if (!isStorageEnough(2048)) { // 2048MB=2GB
                return;
            }

            if (isEncoding) {
                safeUpdateResult("⚠️ 正在编码中，请等待！");
                return;
            }

            new Thread(() -> {
                isEncoding = true;
                safeUpdateResult("🔄 开始编码H.265：读取半帧YUV文件 → " + yuvOutputPath);

                try {
                    // 1. 初始化H265编码器
                    int width = rawFrameDecoder.getVideoWidth();
                    int height = rawFrameDecoder.getVideoHeight();
                    int frameRate = rawFrameDecoder.getFrameRate();
                    int frameSize = width * height * 3 / 2; // 单帧YUV大小
                    long totalHalfFrames = rawFrameDecoder.getDecodedHalfFrameCount(); // 获取半帧数量

                    boolean initSuccess = initH265Encoder(width, height, frameRate);
                    if (!initSuccess) {
                        isEncoding = false;
                        return;
                    }

                    // 2. 读取YUV文件并逐帧编码
                    FileInputStream yuvFis = new FileInputStream(yuvOutputPath);
                    byte[] frameBuffer = new byte[frameSize]; // 单帧缓冲区
                    int readLen;
                    encodePts = 0;
                    long encodedFrameCount = 0;

                    while ((readLen = yuvFis.read(frameBuffer)) != -1 && encodedFrameCount < totalHalfFrames) {
                        if (readLen != frameSize) {
                            Log.w(TAG, "读取YUV帧不完整：实际=" + readLen + "，期望=" + frameSize);
                            continue;
                        }

                        // 编码单帧YUV数据（核心修改：支持格式转换）
                        encodeSingleYuvFrame(frameBuffer, width, height);
                        encodedFrameCount++;
                        encodePts++;
                    }

                    // 3. 关闭YUV输入流
                    yuvFis.close();

                    // 4. 完成编码并封装MP4
                    finishEncode();

                    // 5. 删除YUV文件
                    boolean deleteSuccess = deleteYuvFile();
                    String tempYuvPath = yuvOutputPath; // 临时保存路径避免null
                    if (deleteSuccess) {
                        safeUpdateResult("✅ 已删除原始YUV文件：" + tempYuvPath);
                    } else {
                        safeUpdateResult("⚠️ YUV文件删除失败，请手动清理：" + tempYuvPath);
                    }

                    isEncoding = false;
                    safeUpdateResult(String.format(
                            "✅ H265编码完成！\n分辨率：%dx%d\n帧率：%dfps\n编码帧数：%d\n输出文件：%s",
                            width, height, frameRate, encodedFrameCount, h265OutputPath
                    ));

                } catch (Exception e) {
                    Log.e(TAG, "编码H265失败", e);
                    releaseEncoder();
                    isEncoding = false;
                    safeUpdateResult("❌ 编码失败：" + e.getMessage());
                }
            }).start();
        });
    }

    // 删除YUV文件（修复日志null问题）
    private boolean deleteYuvFile() {
        if (yuvOutputPath == null) return false;
        File yuvFile = new File(yuvOutputPath);
        String tempPath = yuvOutputPath; // 临时保存路径
        if (yuvFile.exists()) {
            boolean deleted = yuvFile.delete();
            Log.d(TAG, "🗑️ YUV文件删除：" + (deleted ? "成功" : "失败") + "，路径：" + tempPath);
            if (deleted) {
                yuvOutputPath = null; // 清空路径
            }
            return deleted;
        }
        return true;
    }

    // 初始化H265编码器（保存到公共目录）
    private boolean initH265Encoder(int width, int height, int frameRate) {
        try {
            // 1. 创建H265输出文件（保存到系统公共视频目录）
            File publicVideoDir;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 分区存储：公共视频目录
                publicVideoDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            } else {
                // 低版本：直接使用公共目录
                publicVideoDir = new File(Environment.getExternalStorageDirectory(), "Movies");
            }

            // 创建自定义子目录
            File h265Dir = new File(publicVideoDir, "RK_H265/encoded");
            if (!h265Dir.exists()) {
                boolean isCreated = h265Dir.mkdirs();
                if (!isCreated) {
                    safeUpdateResult("❌ 公共目录创建失败！请检查存储权限");
                    return false;
                }
            }

            // 生成输出文件路径
            h265OutputPath = new File(h265Dir, "4k_h265_half_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
            Log.d(TAG, "📁 H265输出路径（公共目录）：" + h265OutputPath);

            // 2. 配置H265编码格式（适配RK平台NV12格式）
            MediaFormat encodeFormat = MediaFormat.createVideoFormat("video/hevc", width, height);
            encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, 8 * 1024 * 1024); // 8Mbps码率
            encodeFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);      // 帧率
            encodeFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);       // I帧间隔
            // 【关键】适配RK平台NV12格式
            encodeFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);

            // 3. 查找RK平台H265硬件编码器
            MediaCodecInfo encoderInfo = findRKHardwareCodec("video/hevc", true);
            if (encoderInfo == null) {
                safeUpdateResult("❌ 未找到RK平台H265硬件编码器！");
                return false;
            }

            // 4. 初始化编码器
            h265Encoder = MediaCodec.createByCodecName(encoderInfo.getName());
            h265Encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            h265Encoder.start();

            // 5. 初始化MP4封装器
            try {
                mediaMuxer = new MediaMuxer(h265OutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException e) {
                safeUpdateResult("❌ 创建MP4文件失败：" + e.getMessage() + "\n请清理存储空间或检查存储权限后重试");
                return false;
            }
            videoTrackIndex = -1;
            isMuxerStarted = false;

            Log.d(TAG, "✅ H265编码器初始化成功：" + encoderInfo.getName());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "H265编码器初始化失败", e);
            safeUpdateResult("❌ 编码器初始化失败：" + e.getMessage());
            releaseEncoder();
            return false;
        }
    }

    // 编码单帧YUV数据为H265（核心修改：增加I420转NV12）
    private void encodeSingleYuvFrame(byte[] yuvData, int width, int height) {
        try {
            // 关键：根据解码格式转换为编码器需要的NV12
            byte[] encodeData = yuvData;
            int decodeFormat = rawFrameDecoder.getOutputColorFormat();
            if (isI420Format(decodeFormat)) {
                encodeData = YuvConverter.i420ToNv12(yuvData, width, height);
                if (encodeData == null) {
                    Log.e(TAG, "I420转NV12失败，跳过当前帧");
                    return;
                }
                Log.d(TAG, "🔄 完成I420转NV12，帧大小：" + encodeData.length);
            }

            // 1. 获取编码器输入缓冲区
            int inputBufferId = h265Encoder.dequeueInputBuffer(1000);
            if (inputBufferId >= 0) {
                ByteBuffer inputBuffer = h265Encoder.getInputBuffer(inputBufferId);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(encodeData); // 写入转换后的YUV数据
                    // 计算时间戳（微秒）
                    long pts = encodePts * (1000000 / rawFrameDecoder.getFrameRate());
                    h265Encoder.queueInputBuffer(inputBufferId, 0, encodeData.length, pts, 0);
                }
            }

            // 2. 获取编码器输出数据
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferId = h265Encoder.dequeueOutputBuffer(bufferInfo, 1000);
            while (outputBufferId >= 0) {
                ByteBuffer outputBuffer = h265Encoder.getOutputBuffer(outputBufferId);
                if (outputBuffer != null && bufferInfo.size > 0) {
                    // 3. 启动封装器（首次输出时）
                    if (!isMuxerStarted) {
                        MediaFormat outputFormat = h265Encoder.getOutputFormat();
                        videoTrackIndex = mediaMuxer.addTrack(outputFormat);
                        mediaMuxer.start();
                        isMuxerStarted = true;
                        Log.d(TAG, "✅ MP4封装器启动，轨道索引：" + videoTrackIndex);
                    }

                    // 4. 写入编码数据到MP4（跳过配置帧）
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                    }
                }

                // 5. 释放输出缓冲区
                h265Encoder.releaseOutputBuffer(outputBufferId, false);
                outputBufferId = h265Encoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "单帧编码失败", e);
        }
    }

    // 完成编码并释放资源
    private void finishEncode() {
        try {
            // 1. 给编码器喂入结束标记
            if (h265Encoder != null) {
                int inputBufferId = h265Encoder.dequeueInputBuffer(1000);
                if (inputBufferId >= 0) {
                    h265Encoder.queueInputBuffer(
                            inputBufferId,
                            0,
                            0,
                            encodePts * (1000000 / rawFrameDecoder.getFrameRate()),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    );
                }

                // 2. 处理编码器剩余的输出数据
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                int outputBufferId;
                do {
                    outputBufferId = h265Encoder.dequeueOutputBuffer(bufferInfo, 1000);
                    if (outputBufferId >= 0) {
                        if (isMuxerStarted && bufferInfo.size > 0) {
                            ByteBuffer outputBuffer = h265Encoder.getOutputBuffer(outputBufferId);
                            if (outputBuffer != null) {
                                outputBuffer.position(bufferInfo.offset);
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                                mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                            }
                        }
                        h265Encoder.releaseOutputBuffer(outputBufferId, false);
                    }
                } while (outputBufferId >= 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0);
            }

            // 3. 停止并释放封装器和编码器
            releaseEncoder();

            // 强制通知系统扫描文件（确保系统识别）
            if (h265OutputPath != null && new File(h265OutputPath).exists()) {
                MediaScannerConnection.scanFile(
                        this,
                        new String[]{h265OutputPath},
                        new String[]{"video/mp4"}, // 明确指定文件类型
                        (path, uri) -> {
                            Log.d(TAG, "✅ H265文件已被系统媒体库识别，URI：" + uri);
                            // 扫描完成后刷新视频列表
                            mainHandler.post(() -> {
                                btnScanVideos.performClick(); // 自动触发扫描
                            });
                        }
                );
            }

        } catch (Exception e) {
            Log.e(TAG, "编码收尾失败", e);
        }
    }

    // 释放编码器资源
    private void releaseEncoder() {
        try {
            if (h265Encoder != null) {
                h265Encoder.stop();
                h265Encoder.release();
                h265Encoder = null;
            }
        } catch (Exception ignored) {}

        try {
            if (mediaMuxer != null) {
                if (isMuxerStarted) {
                    mediaMuxer.stop();
                }
                mediaMuxer.release();
                mediaMuxer = null;
            }
        } catch (Exception ignored) {}

        videoTrackIndex = -1;
        isMuxerStarted = false;
        encodePts = 0;
        isEncoding = false;
    }

    // 查找RK平台硬件编解码器
    private MediaCodecInfo findRKHardwareCodec(String mime, boolean isEncoder) {
        int codecCount = MediaCodecList.getCodecCount();
        for (int i = 0; i < codecCount; i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            // 过滤软件编码器
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly()) {
                continue;
            }
            // 区分编码器/解码器
            if (info.isEncoder() != isEncoder) {
                continue;
            }
            // 检查格式支持且包含RK标识
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime) && info.getName().contains("rk")) {
                    return info;
                }
            }
        }
        return null;
    }

    // 安全更新UI
    private void safeUpdateResult(String msg) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tvResult.setText(msg);
        } else {
            mainHandler.post(() -> tvResult.setText(msg));
        }
        Log.d(TAG, msg);
    }

    // 辅助方法：判断是否为I420格式
    private boolean isI420Format(int colorFormat) {
        return colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
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

    // 处理Android 11+权限申请结果
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE) {
            if (hasAllPermissions()) {
                safeUpdateResult("✅ 已授予所有文件访问权限！");
            } else {
                safeUpdateResult("❌ 未授予所有文件访问权限，无法写入公共目录！");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                safeUpdateResult("✅ 权限申请成功！");
            } else {
                safeUpdateResult("❌ 部分权限申请失败，功能可能受限");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasAllPermissions()) {
            safeUpdateResult("✅ 当前已拥有所有必要权限（媒体读取+存储写入）");
        } else {
            safeUpdateResult("⚠️ 缺少必要权限，请点击【申请权限】按钮授权");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cpuUpdateTimer != null) {
            cpuUpdateTimer.cancel();
        }
        // 释放编码器资源
        releaseEncoder();
        // 关闭YUV文件流
        try {
            if (yuvFos != null) yuvFos.close();
        } catch (IOException ignored) {}
        // 重置状态
        isDecoding = false;
        isDecodeCompleted = false;
        yuvOutputPath = null;
    }

    // YUV420P转RGB（可选参考）
    private byte[] convertYuv420ToRgb(byte[] yuvData, int width, int height) {
        int frameSize = width * height;
        byte[] rgbData = new byte[frameSize * 3];
        int y, u, v;
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                y = yuvData[i * width + j] & 0xff;
                u = yuvData[frameSize + (i / 2) * width + (j / 2) * 2] & 0xff;
                v = yuvData[frameSize + (i / 2) * width + (j / 2) * 2 + 1] & 0xff;

                int r = (int) (y + 1.402 * (v - 128));
                int g = (int) (y - 0.34414 * (u - 128) - 0.71414 * (v - 128));
                int b = (int) (y + 1.772 * (u - 128));

                r = Math.max(0, Math.min(255, r));
                g = Math.max(0, Math.min(255, g));
                b = Math.max(0, Math.min(255, b));

                rgbData[(i * width + j) * 3] = (byte) r;
                rgbData[(i * width + j) * 3 + 1] = (byte) g;
                rgbData[(i * width + j) * 3 + 2] = (byte) b;
            }
        }
        return rgbData;
    }
}