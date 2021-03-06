/*
 * Copyright (C) 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.client.android.consts.HelpActivity;
import com.google.zxing.client.android.consts.IntentSource;
import com.google.zxing.client.android.consts.Intents;
import com.google.zxing.client.android.consts.PreferencesActivity;
import com.google.zxing.client.android.helper.AmbientLightManager;
import com.google.zxing.client.android.helper.BeepManager;
import com.google.zxing.client.android.helper.FinishListener;
import com.google.zxing.client.android.helper.InactivityTimer;
import com.google.zxing.client.android.result.ResultHandler;
import com.google.zxing.client.android.result.ResultHandlerFactory;
import com.google.zxing.client.android.util.SystemBarTintManager;
import com.google.zxing.client.android.view.ViewfinderView;
import com.google.zxing.common.HybridBinarizer;

import java.io.IOException;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to activity_help the user place the barcode correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 * @author gaoxiaohui
 *         <p>
 *         custom changes
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback,
        EasyPermissions.PermissionCallbacks, View.OnClickListener {

    private static final String TAG = CaptureActivity.class.getSimpleName();

    private static final long DEFAULT_INTENT_RESULT_DURATION_MS = 1500L;
    //private static final long BULK_MODE_SCAN_DELAY_MS = 1000L;

    private static final int REQUEST_CODE_CAMERA = 1;
    private static final int REQUEST_CODE_ALBUM = 2;

    private CameraManager cameraManager;
    private CaptureActivityHandler handler;
    private Result savedResultToShow;

    /**
     * 扫描区域
     */
    private ViewfinderView viewfinderView;
    //private TextView statusView;
    //private View resultView;
    private ImageView mIvBack;
    private TextView mTvAlbum;
    //private AppCompatImageView mAcivLight;

    private Result lastResult;

    /**
     * 是否有预览
     */
    private boolean hasSurface;

    //    private boolean copyToClipboard;
    private IntentSource source;
    //    private String sourceUrl;
    //    private ScanFromWebPageManager scanFromWebPageManager;

    /**
     * 【辅助解码的参数(用作MultiFormatReader的参数)】 编码类型，该参数告诉扫描器采用何种编码方式解码，即EAN-13，QR
     * Code等等 对应于DecodeHintType.POSSIBLE_FORMATS类型
     * 参考DecodeThread构造函数中如下代码：hints.put(DecodeHintType.POSSIBLE_FORMATS,
     * decodeFormats);
     */
    private Collection<BarcodeFormat> decodeFormats;
    /**
     * 【辅助解码的参数(用作MultiFormatReader的参数)】 该参数最终会传入MultiFormatReader，
     * 上面的decodeFormats和characterSet最终会先加入到decodeHints中 最终被设置到MultiFormatReader中
     * 参考DecodeHandler构造器中如下代码：multiFormatReader.setHints(hints);
     */
    private Map<DecodeHintType, ?> decodeHints;
    /**
     * 【辅助解码的参数(用作MultiFormatReader的参数)】 字符集，告诉扫描器该以何种字符集进行解码
     * 对应于DecodeHintType.CHARACTER_SET类型
     * 参考DecodeThread构造器如下代码：hints.put(DecodeHintType.CHARACTER_SET,
     * characterSet);
     */
    private String characterSet;
    //private HistoryManager historyManager;

    /**
     * 活动监控器
     * 作用是:如果手机没有连接电源线,那么当相机开启后如果一直处于不被使用状态,则该服务会将当前Activity关闭;
     * 活动监控器全程监控扫描活跃状态,与CaptureActivity生命周期相同.每一次扫描过后会重置该监控,重新计时
     */
    private InactivityTimer inactivityTimer;
    /**
     * 声音震动管理器.
     * 如果扫描成功,可以播放一段音频,也可以震动提醒,可以通过配置来决定扫描成功后的行为
     */
    private BeepManager beepManager;
    /**
     * 闪光灯调节器.
     * 自动检测环境光线强弱,并决定是否开启闪光灯
     */
    private AmbientLightManager ambientLightManager;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        setContentView(R.layout.activity_capture);

        hasSurface = false;
        inactivityTimer = new InactivityTimer(this);
        beepManager = new BeepManager(this);
        ambientLightManager = new AmbientLightManager(this);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        //
        //setStatusColor();
        mTvAlbum = (TextView) findViewById(R.id.tv_album);
        mIvBack = (ImageView) findViewById(R.id.iv_back);
        //mAcivLight = (AppCompatImageView) findViewById(R.id.aciv_light);
    }

    @Override
    protected void onResume() {
        super.onResume();

        //        // historyManager must be initialized here to update the history preference
        //        historyManager = new HistoryManager(this);
        //        historyManager.trimHistory();

        // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
        // want to open the camera driver and measure the screen size if we're going to show the activity_help on
        // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
        // off screen.
        // 相机初始化的动作需要开启相机并测量屏幕大小，这些操作
        // 不建议放到onCreate中，因为如果在onCreate中加上首次启动展示帮助信息的代码的 话，
        // 会导致扫描窗口的尺寸计算有误的bug
        cameraManager = new CameraManager(getApplication());

        viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
        viewfinderView.setCameraManager(cameraManager);

        //resultView = findViewById(R.id.result_view);
        //statusView = (TextView) findViewById(R.id.status_view);

        handler = null;
        lastResult = null;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (prefs.getBoolean(PreferencesActivity.KEY_DISABLE_AUTO_ORIENTATION, false)) {
            //noinspection WrongConstant
            setRequestedOrientation(getCurrentOrientation());
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }

        resetStatusView();

        // 加载声音配置，其实在BeemManager的构造器中也会调用该方法，即在onCreate的时候会调用一次
        beepManager.updatePrefs();
        // 启动闪光灯调节器
        ambientLightManager.start(cameraManager);
        // 恢复活动监控器
        inactivityTimer.onResume();


        source = IntentSource.NONE;
        //        sourceUrl = null;
        //        scanFromWebPageManager = null;
        decodeFormats = null;
        characterSet = null;

        //        Intent intent = getIntent();
        //        if (intent != null) {
        //
        //            String action = intent.getAction();
        //            String dataString = intent.getDataString();
        //
        //            if (Intents.Scan.ACTION.equals(action)) {
        //
        //                // Scan the formats the intent requested, and return the result to the calling activity.
        //                source = IntentSource.NATIVE_APP_INTENT;
        //                decodeFormats = DecodeFormatManager.parseDecodeFormats(intent);
        //                decodeHints = DecodeHintManager.parseDecodeHints(intent);
        //
        //                if (intent.hasExtra(Intents.Scan.WIDTH) && intent.hasExtra(Intents.Scan.HEIGHT)) {
        //                    int width = intent.getIntExtra(Intents.Scan.WIDTH, 0);
        //                    int height = intent.getIntExtra(Intents.Scan.HEIGHT, 0);
        //                    if (width > 0 && height > 0) {
        //                        cameraManager.setManualFramingRect(width, height);
        //                    }
        //                }
        //
        //                if (intent.hasExtra(Intents.Scan.CAMERA_ID)) {
        //                    int cameraId = intent.getIntExtra(Intents.Scan.CAMERA_ID, -1);
        //                    if (cameraId >= 0) {
        //                        cameraManager.setManualCameraId(cameraId);
        //                    }
        //                }
        //
        //                String customPromptMessage = intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE);
        //                if (customPromptMessage != null) {
        //                    //statusView.setText(customPromptMessage);
        //                }
        //
        //            } else if (dataString != null &&
        //                    dataString.contains("http://www.google") &&
        //                    dataString.contains("/m/products/scan")) {
        //
        //                // Scan only products and send the result to mobile Product Search.
        //                source = IntentSource.PRODUCT_SEARCH_LINK;
        //                sourceUrl = dataString;
        //                decodeFormats = DecodeFormatManager.PRODUCT_FORMATS;
        //
        //            } /*else if (isZXingURL(dataString)) {
        //
        //                // Scan formats requested in query string (all formats if none specified).
        //                // If a return URL is specified, send the results there. Otherwise, handle it ourselves.
        //                source = IntentSource.ZXING_LINK;
        //                sourceUrl = dataString;
        //                Uri inputUri = Uri.parse(dataString);
        //                scanFromWebPageManager = new ScanFromWebPageManager(inputUri);
        //                decodeFormats = DecodeFormatManager.parseDecodeFormats(inputUri);
        //                // Allow a sub-set of the hints to be specified by the caller.
        //                decodeHints = DecodeHintManager.parseDecodeHints(inputUri);
        //
        //            }*/
        //
        //            characterSet = intent.getStringExtra(Intents.Scan.CHARACTER_SET);
        //        }

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        if (hasSurface) {
            // The activity was paused but not stopped, so the surface still exists. Therefore
            // surfaceCreated() won't be called, so init the camera here.
            initCamera(surfaceHolder);
        } else {
            // 防止sdk8的设备初始化预览异常
            surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

            // Install the callback and wait for surfaceCreated() to init the camera.
            surfaceHolder.addCallback(this);
        }

        mTvAlbum.setOnClickListener(this);
        mIvBack.setOnClickListener(this);
        //mAcivLight.setOnClickListener(this);


    }

    @Override
    protected void onPause() {
        if (handler != null) {
            handler.quitSynchronously();
            handler = null;
        }
        inactivityTimer.onPause();
        ambientLightManager.stop();
        beepManager.close();

        // 关闭摄像头
        cameraManager.closeDriver();
        //historyManager = null; // Keep for onActivityResult
        if (!hasSurface) {
            SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
            SurfaceHolder surfaceHolder = surfaceView.getHolder();
            surfaceHolder.removeCallback(this);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        inactivityTimer.shutdown();
        super.onDestroy();
    }

    ViewfinderView getViewfinderView() {
        return viewfinderView;
    }

    public Handler getHandler() {
        return handler;
    }

    public CameraManager getCameraManager() {
        return cameraManager;
    }

    private int getCurrentOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            switch (rotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_90:
                    return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                default:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            }
        } else {
            switch (rotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_270:
                    return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                default:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (source == IntentSource.NATIVE_APP_INTENT) {
                    setResult(RESULT_CANCELED);
                    finish();
                    return true;
                }
                if ((source == IntentSource.NONE || source == IntentSource.ZXING_LINK) && lastResult != null) {
                    restartPreviewAfterDelay(0L);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_FOCUS:
            case KeyEvent.KEYCODE_CAMERA:
                // Handle these events so they don't launch the Camera app
                return true;
            // Use volume up/down to turn on light
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                cameraManager.setTorch(false);
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
                cameraManager.setTorch(true);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.capture, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        int i = item.getItemId();
        /*if (i == R.id.menu_share) {
            intent.setClassName(this, ShareActivity.class.getName());
            startActivity(intent);

        } else if (i == R.id.menu_history) {
            intent.setClassName(this, HistoryActivity.class.getName());
            startActivityForResult(intent, HISTORY_REQUEST_CODE);

        } else */
        if (i == R.id.menu_settings) {
            intent.setClassName(this, PreferencesActivity.class.getName());
            startActivity(intent);

        } else if (i == R.id.menu_help) {
            intent.setClassName(this, HelpActivity.class.getName());
            startActivity(intent);

        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        Log.i(TAG, "onActivityResult called");
        //        if (resultCode == RESULT_OK && requestCode == HISTORY_REQUEST_CODE && historyManager != null) {
        //            int itemNumber = intent.getIntExtra(Intents.History.ITEM_NUMBER, -1);
        //            if (itemNumber >= 0) {
        //                HistoryItem historyItem = historyManager.buildHistoryItem(itemNumber);
        //                decodeOrStoreSavedBitmap(null, historyItem.getResult());
        //            }
        //        }
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_CODE_ALBUM) {
                // get album path here
                String filePath = this.getAlbumPath(intent);
//                // ready to parse album img
//                Bitmap bitmap = BitmapFactory.decodeFile(filePath);
                // TODO
                decodeOrStoreSavedBitmap(filePath, null);

            } else {
                Log.i(TAG, "request code:" + requestCode);
            }


        }

    }

    /**
     * get album path
     * <p>
     * android版本不同,返回路径不同的情况(一共三种情况,无法解析图片直接奔溃bug),
     * 1.content://com.android.providers.media.documents/document/image%3A137424  sony
     * 2.file:///storage/emulated/0/Tencent/QQ_Images/3afe8750f0b4b8ce.jpg       xiaomi
     * 3.content://media/external/images/media/13323                           smartOS
     * </p>
     *
     * @param intent intent
     * @return
     */
    private String getAlbumPath(Intent intent) {
        Log.i(TAG, "getAlbumPath called");

        String filePath = "$$";

        Uri contentUri = intent.getData();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                DocumentsContract.isDocumentUri(CaptureActivity.this, contentUri)) {
            String wholeID = DocumentsContract.getDocumentId(contentUri);
            String id = wholeID.split(":")[1];
            String[] column = {MediaStore.Images.Media.DATA};
            String sel = MediaStore.Images.Media._ID + "=?";
            Cursor cursor = CaptureActivity.this.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    column, sel, new String[]{id}, null);
            if (cursor != null) {
                int columnIndex = cursor.getColumnIndex(column[0]);
                if (cursor.moveToFirst()) {
                    filePath = cursor.getString(columnIndex);
                }
                cursor.close();
            }
        } else if (!TextUtils.isEmpty(contentUri.getAuthority())) {
            Cursor cursor = getContentResolver().query(contentUri,
                    new String[]{MediaStore.Images.Media.DATA},
                    null, null, null);
            if (null != cursor) {
                cursor.moveToFirst();
                filePath = cursor.getString(cursor
                        .getColumnIndex(MediaStore.Images.Media.DATA));
                cursor.close();
            }
        } else {
            filePath = contentUri.getPath();
        }
        Log.i(TAG, "get file path :" + filePath);

        return filePath;
    }

//    /**
//     * decode or store saved bitmap
//     *
//     * @param bitmap bitmap
//     * @param result result
//     */
//    private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
//        // Bitmap isn't used yet -- will be used soon TODO
//        if (handler == null) {
//            savedResultToShow = result;
//        } else {
//            if (result != null) {
//                savedResultToShow = result;
//            } else {
//                if (bitmap != null){
//                    Message message = Message.obtain(handler, R.id.decode_album, bitmap);
////                    Bundle bundle = new Bundle();
////                    bundle.put
////                    message.setData();
//                    handler.sendMessage(message);
//                }
//            }
//            if (savedResultToShow != null) {
//                Message message = Message.obtain(handler, R.id.decode_succeeded, savedResultToShow);
//                handler.sendMessage(message);
//            }
//            savedResultToShow = null;
//        }
//    }

    /**
     * decode or store saved bitmap
     *
     * @param filePath filePath
     * @param result   result
     */
    private void decodeOrStoreSavedBitmap(String filePath, Result result) {
        // Bitmap isn't used yet -- will be used soon
        if (!TextUtils.isEmpty(filePath)) {
            Result rawResult = this.decodeAlbum(filePath);
            if (null == rawResult) {
                Log.e(TAG, "rawResult is null");
                return;
            }

            handleDecode(rawResult, null, -1);
            return;
        }

        if (handler == null) {
            savedResultToShow = result;
        } else if (result != null) {
            savedResultToShow = result;
        }

        if (savedResultToShow != null) {
            Message message = Message.obtain(handler, R.id.decode_succeeded, savedResultToShow);
            handler.sendMessage(message);
        }
        savedResultToShow = null;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (holder == null) {
            Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
        }
        if (!hasSurface) {
            //            hasSurface = true;
            //            initCamera(holder);
            if (EasyPermissions.hasPermissions(this, Manifest.permission.CAMERA)) {
                hasSurface = true;
                initCamera(holder);
            } else {
                hasSurface = false;
                EasyPermissions.requestPermissions(this, getString(R.string.label_need_camera_tips),
                        REQUEST_CODE_CAMERA, Manifest.permission.CAMERA);
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    /**
     * A valid barcode has been found, so give an indication of success and show the results.
     *
     * @param rawResult   The contents of the barcode.
     * @param scaleFactor amount by which thumbnail was scaled
     * @param barcode     A greyscale bitmap of the camera data which was decoded.
     */
    public void handleDecode(Result rawResult, Bitmap barcode, float scaleFactor) {
        Log.i(TAG, "handleDecode called!");
        inactivityTimer.onActivity();
        lastResult = rawResult;
        ResultHandler resultHandler = ResultHandlerFactory.makeResultHandler(this, rawResult);

        boolean fromLiveScan = barcode != null;
        if (fromLiveScan) {
            //historyManager.addHistoryItem(rawResult, resultHandler);
            // Then not from history, so beep/vibrate and we have an image to draw on
            beepManager.playBeepSoundAndVibrate();
            //drawResultPoints(barcode, scaleFactor, rawResult);
        }

        //        switch (source) {
        //            case NATIVE_APP_INTENT:
        //            case PRODUCT_SEARCH_LINK:
        //                handleDecodeExternally(rawResult, resultHandler, barcode);
        //                break;
        //            case ZXING_LINK:
        //                if (scanFromWebPageManager == null || !scanFromWebPageManager.isScanFromWebPage()) {
        //                    handleDecodeInternally(rawResult, resultHandler, barcode);
        //                } else {
        //                    handleDecodeExternally(rawResult, resultHandler, barcode);
        //                }
        //                break;
        //            case NONE:
        //                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //                if (fromLiveScan && prefs.getBoolean(PreferencesActivity.KEY_BULK_MODE, false)) {
        //                    Toast.makeText(getApplicationContext(),
        //                            getResources().getString(R.string.msg_bulk_mode_scanned) + " (" + rawResult.getText() + ')',
        //                            Toast.LENGTH_SHORT).show();
        //                    maybeSetClipboard(resultHandler);
        //                    // Wait a moment or else it will scan the same barcode continuously about 3 times
        //                    restartPreviewAfterDelay(BULK_MODE_SCAN_DELAY_MS);
        //                } else {
        //                    handleDecodeInternally(rawResult, resultHandler, barcode);
        //                }
        //                break;
        //        }

        handleDecodeExternally(rawResult, resultHandler, barcode);
    }

    private void handleDecodeExternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {
        if (barcode != null) {
            viewfinderView.drawResultBitmap(barcode);
        }

        long resultDurationMS;
        if (getIntent() == null) {
            resultDurationMS = DEFAULT_INTENT_RESULT_DURATION_MS;
        } else {
            resultDurationMS = getIntent().getLongExtra(Intents.Scan.RESULT_DISPLAY_DURATION_MS,
                    DEFAULT_INTENT_RESULT_DURATION_MS);
        }

        if (resultDurationMS > 0) {
            String rawResultString = String.valueOf(rawResult);
            //            if (rawResultString.length() > 32) {
            //                rawResultString = rawResultString.substring(0, 32) + " ...";
            //            }
            //statusView.setText(getString(resultHandler.getDisplayTitle()) + " : " + rawResultString);
            Toast.makeText(this, getString(resultHandler.getDisplayTitle()) + " : " + rawResultString, Toast.LENGTH_SHORT).show();
        }
    }

    //    /**
    //     * Superimpose a line for 1D or dots for 2D to highlight the key features of the barcode.
    //     *
    //     * @param barcode     A bitmap of the captured image.
    //     * @param scaleFactor amount by which thumbnail was scaled
    //     * @param rawResult   The decoded results which contains the points to draw.
    //     */
    //    private void drawResultPoints(Bitmap barcode, float scaleFactor, Result rawResult) {
    //        ResultPoint[] points = rawResult.getResultPoints();
    //        if (points != null && points.length > 0) {
    //            Canvas canvas = new Canvas(barcode);
    //            Paint paint = new Paint();
    //            paint.setColor(getResources().getColor(R.color.result_points));
    //            if (points.length == 2) {
    //                paint.setStrokeWidth(4.0f);
    //                drawLine(canvas, paint, points[0], points[1], scaleFactor);
    //            } else if (points.length == 4 &&
    //                    (rawResult.getBarcodeFormat() == BarcodeFormat.UPC_A ||
    //                            rawResult.getBarcodeFormat() == BarcodeFormat.EAN_13)) {
    //                // Hacky special case -- draw two lines, for the barcode and metadata
    //                drawLine(canvas, paint, points[0], points[1], scaleFactor);
    //                drawLine(canvas, paint, points[2], points[3], scaleFactor);
    //            } else {
    //                paint.setStrokeWidth(10.0f);
    //                for (ResultPoint point : points) {
    //                    if (point != null) {
    //                        canvas.drawPoint(scaleFactor * point.getX(), scaleFactor * point.getY(), paint);
    //                    }
    //                }
    //            }
    //        }
    //    }

    // Put up our own UI for how to handle the decoded contents.
    //    private void handleDecodeInternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {
    //
    //        maybeSetClipboard(resultHandler);
    //
    //        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    //
    //        if (resultHandler.getDefaultButtonID() != null && prefs.getBoolean(PreferencesActivity.KEY_AUTO_OPEN_WEB, false)) {
    //            resultHandler.handleButtonPress(resultHandler.getDefaultButtonID());
    //            return;
    //        }
    //
    //        statusView.setVisibility(View.GONE);
    //        viewfinderView.setVisibility(View.GONE);
    //        //resultView.setVisibility(View.VISIBLE);
    //
    //        ImageView barcodeImageView = (ImageView) findViewById(R.id.barcode_image_view);
    //        if (barcode == null) {
    //            barcodeImageView.setImageBitmap(BitmapFactory.decodeResource(getResources(),
    //                    R.mipmap.launcher_icon));
    //        } else {
    //            barcodeImageView.setImageBitmap(barcode);
    //        }
    //
    //        TextView formatTextView = (TextView) findViewById(R.id.format_text_view);
    //        formatTextView.setText(rawResult.getBarcodeFormat().toString());
    //
    //        TextView typeTextView = (TextView) findViewById(R.id.type_text_view);
    //        typeTextView.setText(resultHandler.getType().toString());
    //
    //        DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
    //        TextView timeTextView = (TextView) findViewById(R.id.time_text_view);
    //        timeTextView.setText(formatter.format(rawResult.getTimestamp()));
    //
    //
    //        TextView metaTextView = (TextView) findViewById(R.id.meta_text_view);
    //        View metaTextViewLabel = findViewById(R.id.meta_text_view_label);
    //        metaTextView.setVisibility(View.GONE);
    //        metaTextViewLabel.setVisibility(View.GONE);
    //        Map<ResultMetadataType, Object> metadata = rawResult.getResultMetadata();
    //        if (metadata != null) {
    //            StringBuilder metadataText = new StringBuilder(20);
    //            for (Map.Entry<ResultMetadataType, Object> entry : metadata.entrySet()) {
    //                if (DISPLAYABLE_METADATA_TYPES.contains(entry.getKey())) {
    //                    metadataText.append(entry.getValue()).append('\n');
    //                }
    //            }
    //            if (metadataText.length() > 0) {
    //                metadataText.setLength(metadataText.length() - 1);
    //                metaTextView.setText(metadataText);
    //                metaTextView.setVisibility(View.VISIBLE);
    //                metaTextViewLabel.setVisibility(View.VISIBLE);
    //            }
    //        }
    //
    //        CharSequence displayContents = resultHandler.getDisplayContents();
    //        TextView contentsTextView = (TextView) findViewById(R.id.contents_text_view);
    //        contentsTextView.setText(displayContents);
    //        int scaledSize = Math.max(22, 32 - displayContents.length() / 4);
    //        contentsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);
    //
    //        TextView supplementTextView = (TextView) findViewById(R.id.contents_supplement_text_view);
    //        supplementTextView.setText("");
    //        supplementTextView.setOnClickListener(null);
    //        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
    //                PreferencesActivity.KEY_SUPPLEMENTAL, true)) {
    //            SupplementalInfoRetriever.maybeInvokeRetrieval(supplementTextView,
    //                    resultHandler.getResult(),
    //                    historyManager,
    //                    this);
    //        }
    //
    //        int buttonCount = resultHandler.getButtonCount();
    //        ViewGroup buttonView = (ViewGroup) findViewById(R.id.result_button_view);
    //        buttonView.requestFocus();
    //        for (int x = 0; x < ResultHandler.MAX_BUTTON_COUNT; x++) {
    //            TextView button = (TextView) buttonView.getChildAt(x);
    //            if (x < buttonCount) {
    //                button.setVisibility(View.VISIBLE);
    //                button.setText(resultHandler.getButtonText(x));
    //                button.setOnClickListener(new ResultButtonListener(resultHandler, x));
    //            } else {
    //                button.setVisibility(View.GONE);
    //            }
    //        }
    //
    //    }
    //
    //    // Briefly show the contents of the barcode, then handle the result outside Barcode Scanner.
    //    private void handleDecodeExternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {
    //
    //        if (barcode != null) {
    //            viewfinderView.drawResultBitmap(barcode);
    //        }
    //
    //        long resultDurationMS;
    //        if (getIntent() == null) {
    //            resultDurationMS = DEFAULT_INTENT_RESULT_DURATION_MS;
    //        } else {
    //            resultDurationMS = getIntent().getLongExtra(Intents.Scan.RESULT_DISPLAY_DURATION_MS,
    //                    DEFAULT_INTENT_RESULT_DURATION_MS);
    //        }
    //
    //        if (resultDurationMS > 0) {
    //            String rawResultString = String.valueOf(rawResult);
    //            if (rawResultString.length() > 32) {
    //                rawResultString = rawResultString.substring(0, 32) + " ...";
    //            }
    //            statusView.setText(getString(resultHandler.getDisplayTitle()) + " : " + rawResultString);
    //        }
    //
    //        maybeSetClipboard(resultHandler);
    //
    //        if (source == IntentSource.NATIVE_APP_INTENT) {
    //
    //            // Hand back whatever action they requested - this can be changed to Intents.Scan.ACTION when
    //            // the deprecated intent is retired.
    //            Intent intent = new Intent(getIntent().getAction());
    //            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
    //            intent.putExtra(Intents.Scan.RESULT, rawResult.toString());
    //            intent.putExtra(Intents.Scan.RESULT_FORMAT, rawResult.getBarcodeFormat().toString());
    //            byte[] rawBytes = rawResult.getRawBytes();
    //            if (rawBytes != null && rawBytes.length > 0) {
    //                intent.putExtra(Intents.Scan.RESULT_BYTES, rawBytes);
    //            }
    //            Map<ResultMetadataType, ?> metadata = rawResult.getResultMetadata();
    //            if (metadata != null) {
    //                if (metadata.containsKey(ResultMetadataType.UPC_EAN_EXTENSION)) {
    //                    intent.putExtra(Intents.Scan.RESULT_UPC_EAN_EXTENSION,
    //                            metadata.get(ResultMetadataType.UPC_EAN_EXTENSION).toString());
    //                }
    //                Number orientation = (Number) metadata.get(ResultMetadataType.ORIENTATION);
    //                if (orientation != null) {
    //                    intent.putExtra(Intents.Scan.RESULT_ORIENTATION, orientation.intValue());
    //                }
    //                String ecLevel = (String) metadata.get(ResultMetadataType.ERROR_CORRECTION_LEVEL);
    //                if (ecLevel != null) {
    //                    intent.putExtra(Intents.Scan.RESULT_ERROR_CORRECTION_LEVEL, ecLevel);
    //                }
    //                @SuppressWarnings("unchecked")
    //                Iterable<byte[]> byteSegments = (Iterable<byte[]>) metadata.get(ResultMetadataType.BYTE_SEGMENTS);
    //                if (byteSegments != null) {
    //                    int i = 0;
    //                    for (byte[] byteSegment : byteSegments) {
    //                        intent.putExtra(Intents.Scan.RESULT_BYTE_SEGMENTS_PREFIX + i, byteSegment);
    //                        i++;
    //                    }
    //                }
    //            }
    //            sendReplyMessage(R.id.return_scan_result, intent, resultDurationMS);
    //
    //        } else if (source == IntentSource.PRODUCT_SEARCH_LINK) {
    //
    //            // Reformulate the URL which triggered us into a query, so that the request goes to the same
    //            // TLD as the scan URL.
    //            int end = sourceUrl.lastIndexOf("/scan");
    //            String replyURL = sourceUrl.substring(0, end) + "?q=" + resultHandler.getDisplayContents() + "&source=zxing";
    //            sendReplyMessage(R.id.launch_product_query, replyURL, resultDurationMS);
    //
    //        } else if (source == IntentSource.ZXING_LINK) {
    //
    //            if (scanFromWebPageManager != null && scanFromWebPageManager.isScanFromWebPage()) {
    //                String replyURL = scanFromWebPageManager.buildReplyURL(rawResult, resultHandler);
    //                scanFromWebPageManager = null;
    //                sendReplyMessage(R.id.launch_product_query, replyURL, resultDurationMS);
    //            }
    //
    //        }
    //    }
    //
    //    private void maybeSetClipboard(ResultHandler resultHandler) {
    //        if (copyToClipboard && !resultHandler.areContentsSecure()) {
    //            ClipboardInterface.setText(resultHandler.getDisplayContents(), this);
    //        }
    //    }
    //
    //    private void sendReplyMessage(int id, Object arg, long delayMS) {
    //        if (handler != null) {
    //            Message message = Message.obtain(handler, id, arg);
    //            if (delayMS > 0L) {
    //                handler.sendMessageDelayed(message, delayMS);
    //            } else {
    //                handler.sendMessage(message);
    //            }
    //        }
    //    }

    private void initCamera(SurfaceHolder surfaceHolder) {
        if (surfaceHolder == null) {
            throw new IllegalStateException("No SurfaceHolder provided");
        }
        if (cameraManager.isOpen()) {
            Log.w(TAG, "initCamera() while already open -- late SurfaceView callback?");
            return;
        }
        try {
            cameraManager.openDriver(surfaceHolder);
            // Creating the handler starts the preview, which can also throw a RuntimeException.
            if (handler == null) {
                handler = new CaptureActivityHandler(this, decodeFormats, decodeHints, characterSet, cameraManager);
            }
            decodeOrStoreSavedBitmap(null, null);
        } catch (IOException ioe) {
            Log.w(TAG, ioe);
            displayFrameworkBugMessageAndExit();
        } catch (RuntimeException e) {
            // Barcode Scanner has seen crashes in the wild of this variety:
            // java.?lang.?RuntimeException: Fail to connect to camera service
            Log.w(TAG, "Unexpected error initializing camera", e);
            displayFrameworkBugMessageAndExit();
        }
    }

    private void displayFrameworkBugMessageAndExit() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.app_name));
        builder.setMessage(getString(R.string.msg_camera_framework_bug));
        builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
        builder.setOnCancelListener(new FinishListener(this));
        builder.show();
    }

    public void restartPreviewAfterDelay(long delayMS) {
        if (handler != null) {
            handler.sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
        }
        resetStatusView();
    }

    private void resetStatusView() {
        //resultView.setVisibility(View.GONE);
        //statusView.setText(R.string.msg_default_status);
        //statusView.setVisibility(View.VISIBLE);
        viewfinderView.setVisibility(View.VISIBLE);
        lastResult = null;
    }

    public void drawViewfinder() {
        viewfinderView.drawViewfinder();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    public void onPermissionsGranted(int requestCode, List<String> perms) {
        Toast.makeText(this, "权限请求成功！", Toast.LENGTH_SHORT).show();
        hasSurface = true;
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> perms) {
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            new AppSettingsDialog.Builder(this, getString(R.string.label_rationale_ask_again))
                    .setTitle(getString(R.string.label_title_settings_dialog))
                    .setPositiveButton(getString(R.string.label_setting))
                    .setNegativeButton(getString(R.string.label_ignore), null /* click listener */)
                    .setRequestCode(REQUEST_CODE_CAMERA)
                    .build()
                    .show();
        }
    }

    public void setStatusColor() {
        int theme_color = R.color.transparent;
        //set the status bar color ,only worked above 4.4(kitkat)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            setTranslucentStatus(true);

            SystemBarTintManager tintManager = new SystemBarTintManager(this);
            tintManager.setTintColor(theme_color);
            // status bar
            tintManager.setStatusBarTintEnabled(true);
            tintManager.setStatusBarTintResource(theme_color);
            // navigation bar
            tintManager.setNavigationBarTintResource(Color.TRANSPARENT);
            tintManager.setNavigationBarTintEnabled(true);
        }
    }

    /**
     * set translucent status
     *
     * @param on on
     */
    public void setTranslucentStatus(boolean on) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Window win = getWindow();
            WindowManager.LayoutParams winParams = win.getAttributes();
            final int bits = WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
            if (on) {
                winParams.flags |= bits;
            } else {
                winParams.flags &= ~bits;
            }
            win.setAttributes(winParams);
        }

    }

    @Override
    public void onClick(View v) {
        int vid = v.getId();
        if (vid == R.id.iv_back) {
            setResult(RESULT_CANCELED);
            finish();

        } else if (vid == R.id.tv_album) {
            chooseAlbum();
        } /*else if (vid == R.id.aciv_light) {
            // 闪光灯
            cameraManager.setTorch(mAcivLight.isPressed());
            mAcivLight.setPressed(!mAcivLight.isPressed());
        }*/

    }

    /**
     * 跳转到系统相册选图片
     * android版本不同,返回路径不同的情况(一共三种情况,无法解析图片直接奔溃bug),
     * content://com.android.providers.media.documents/document/image%3A137424  sony
     * file:///storage/emulated/0/Tencent/QQ_Images/3afe8750f0b4b8ce.jpg       xiaomi
     * content://media/external/images/media/13323                           smartOS
     */
    private void chooseAlbum() {
        Intent innerIntent = new Intent();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            innerIntent.setAction(Intent.ACTION_GET_CONTENT);
        } else {
            innerIntent.setAction(Intent.ACTION_OPEN_DOCUMENT);
        }
        innerIntent.setType("image/*");

        Intent wrapperIntent = Intent.createChooser(innerIntent, "选择二维码图片");
        CaptureActivity.this.startActivityForResult(wrapperIntent, REQUEST_CODE_ALBUM);
    }

    /**
     * decode album from files
     *
     * @param albumPath albumPath
     */
    public Result decodeAlbum(String albumPath) {

        Result rawResult = null;

        Bitmap bitmap = compress(albumPath);

        //得到图片的宽高
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        //得到图片的像素
        int[] pixels = new int[width * height];
        //
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);


        RGBLuminanceSource rgbLuminanceSource = new RGBLuminanceSource(width, height, pixels);
        //解析转换类型UTF-8
        Hashtable<DecodeHintType, String> hints = new Hashtable<DecodeHintType, String>();
        hints.put(DecodeHintType.CHARACTER_SET, "utf-8");

        MultiFormatReader multiFormatReader = new MultiFormatReader();
        multiFormatReader.setHints(hints);

        //把可视图片转为二进制图片
        BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(rgbLuminanceSource));
        try {
            //解析图片中的code
            rawResult = multiFormatReader.decode(binaryBitmap);
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        } finally {
            multiFormatReader.reset();
        }

        return rawResult;

    }

    /**
     * compress bitmap
     *
     * @param srcPath srcPath
     * @return bitmap
     */
    public static Bitmap compress(String srcPath) {
        BitmapFactory.Options newOpts = new BitmapFactory.Options();
        //开始读入图片，此时把options.inJustDecodeBounds 设回true了
        newOpts.inJustDecodeBounds = true;
        Bitmap bitmap = BitmapFactory.decodeFile(srcPath, newOpts);//此时返回bm为空

        newOpts.inJustDecodeBounds = false;
        int w = newOpts.outWidth;
        int h = newOpts.outHeight;
        //现在主流手机比较多是800*480分辨率，所以高和宽我们设置为
        float hh = 800f;//这里设置高度为800f
        float ww = 480f;//这里设置宽度为480f
        //缩放比。由于是固定比例缩放，只用高或者宽其中一个数据进行计算即可
        int be = 1;//be=1表示不缩放
        if (w > h && w > ww) {//如果宽度大的话根据宽度固定大小缩放
            be = (int) (newOpts.outWidth / ww);
        } else if (w < h && h > hh) {//如果高度高的话根据宽度固定大小缩放
            be = (int) (newOpts.outHeight / hh);
        }
        if (be <= 0)
            be = 1;
        newOpts.inSampleSize = be;//设置缩放比例
        //重新读入图片，注意此时已经把options.inJustDecodeBounds 设回false了
        bitmap = BitmapFactory.decodeFile(srcPath, newOpts);

        return bitmap;//压缩好比例大小后再进行质量压缩
    }


}
