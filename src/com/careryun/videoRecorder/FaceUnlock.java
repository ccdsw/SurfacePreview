package com.samsung.faceunlock;

import java.io.IOException;

import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;

import com.careryun.videoRecorder.YuvUtil;
import com.iflytek.cloud.FaceDetector;
import com.iflytek.cloud.util.Accelerometer;
import com.samsung.faceunlock.R;
import com.samsung.faceunlock.provider.FaceInfo;
import com.samsung.faceunlock.util.FaceRect;
import com.samsung.faceunlock.util.FaceUtil;
import com.samsung.faceunlock.util.ParseResult;
import java.text.NumberFormat;
import android.app.admin.DevicePolicyManager;
/**
 * 离线视频流检测示例
 * 该业务仅支持离线人脸检测SDK，请开发者前往<a href="http://www.xfyun.cn/">讯飞语音云</a>SDK下载界面，下载对应离线SDK
 */
public class FaceUnlock extends Activity {
	private final static String TAG = FaceUnlock.class.getSimpleName();
	private SurfaceView mPreviewSurface;
	private SurfaceView mVideoView;
	private boolean mVideoViewCreated;
	private TextView mProgress;
	private Camera mCamera;
	private YuvUtil mYuvUtil;
	private int mCameraId = CameraInfo.CAMERA_FACING_FRONT;
	// Camera nv21格式预览帧的尺寸，默认设置640*480
	private int PREVIEW_WIDTH = 1280;
	private int PREVIEW_HEIGHT = 720;
	// 预览帧数据存储数组和缓存数组
	private byte[] nv21;
	private byte[] buffer;
	// 缩放矩阵
	private Matrix mScaleMatrix = new Matrix();
	// 加速度感应器，用于获取手机的朝向
	private Accelerometer mAcc;
	// FaceDetector对象，集成了离线人脸识别：人脸检测、视频流检测功能
	private FaceDetector mFaceDetector;
	private boolean mStopTrack;
	private Toast mToast;
	private int isAlign = 1;
	private static final int MSG_UPDATE_PROGRESS = 0;
	private static final int MSG_UPDATE_FACEINFO = 1;
	private int mDetectCount = 0;
	private static final int MIN_PASSWORD_LENGTH = 4;
	private static final int MAX_PASSWORD_LENGTH = 16;
	private static final String CONFIRM_CREDENTIALS = "confirm_credentials";
    public static final String PASSWORD_MIN_KEY = "lockscreen.password_min";
    public static final String PASSWORD_MAX_KEY = "lockscreen.password_max";
    public static final String EXTRA_REQUIRE_PASSWORD = "extra_require_password";
    public final static String PASSWORD_TYPE_KEY = "lockscreen.password_type";
    public final static String LOCKSCREEN_WEAK_FALLBACK = "lockscreen.weak_fallback";
    public final static String LOCKSCREEN_WEAK_FALLBACK_FOR  = "lockscreen.weak_fallback_for";
    public final static String TYPE_FACE_UNLOCK = "face_unlock";
    private float[] mFaceInfo;
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_video_demo);
		initUI();
		
		nv21 = new byte[PREVIEW_WIDTH * PREVIEW_HEIGHT * 2];
		buffer = new byte[PREVIEW_WIDTH * PREVIEW_HEIGHT * 2];
		mAcc = new Accelerometer(FaceUnlock.this);
		mFaceDetector = FaceDetector.createDetector(FaceUnlock.this, null);	
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mReceiver, filter);
	}
    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_PROGRESS:
                	mProgress.setText(NumberFormat.getPercentInstance().format(mDetectCount / (double)(FaceUtil.NEED_DETECT_COUNT)));
                    break;
                case MSG_UPDATE_FACEINFO:
					onFaceUnlockSet();
                	break;
                default:
                    break;
            }
        };
    };
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            	finish();
            }
        }
    };
    
	private Callback mVideoCallback = new Callback() {
		
		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {
			mVideoViewCreated = false;
		}
		
		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			mVideoViewCreated = true;
			mYuvUtil.setVideoSUface(holder.getSurface());
		}
		
		@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int width,
				int height) {
		}
	};
	private Callback mPreviewCallback = new Callback() {
		
		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {
			closeCamera();
		}
		
		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			openCamera();
		}
		
		@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int width,
				int height) {
			mScaleMatrix.setScale(width/(float)PREVIEW_HEIGHT, height/(float)PREVIEW_WIDTH);
		}
	};
	
	private void setSurfaceSize() {
		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);
		int marginTop = getResources().getDimensionPixelSize(R.dimen.surface_margin_top);
		int width = metrics.widthPixels;
		int height = (int) (width * PREVIEW_WIDTH / (float)PREVIEW_HEIGHT);
		LinearLayout.LayoutParams params = new LayoutParams(width, height);
		params.topMargin = marginTop;
		mPreviewSurface.setLayoutParams(params);
		mVideoView.setLayoutParams(params);
	}

	@SuppressLint("ShowToast")
	@SuppressWarnings("deprecation")
	private void initUI() {
		mYuvUtil = new YuvUtil();
		mPreviewSurface = (SurfaceView) findViewById(R.id.sfv_preview);
		mVideoView = (SurfaceView) findViewById(R.id.video_view);
		mVideoView.getHolder().addCallback(mVideoCallback);
		//mProgress = (TextView)findViewById(R.id.face_unlock_progress);
		//mProgress.setText(NumberFormat.getPercentInstance().format(0 / 100.0));
		mPreviewSurface.getHolder().addCallback(mPreviewCallback);
		mPreviewSurface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		//setSurfaceSize();
		mToast = Toast.makeText(FaceUnlock.this, "", Toast.LENGTH_SHORT);
	}
	
	private void openCamera() {
		if (null != mCamera) {
			return;
		}
		// 只有一个摄相头，打开后置
		if (true ||Camera.getNumberOfCameras() == 1) {
			mCameraId = CameraInfo.CAMERA_FACING_BACK;
		}
		
		try {
			mCamera = Camera.open(mCameraId);
		} catch (Exception e) {
			e.printStackTrace();
			closeCamera();
			return;
		}
		
		Parameters params = mCamera.getParameters();
		params.setPreviewFormat(ImageFormat.NV21);
		params.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
		mCamera.setParameters(params);
		mCamera.setDisplayOrientation(90);
		// 设置显示的偏转角度，大部分机器是顺时针90度，某些机器需要按情况设置

		mCamera.setPreviewCallback(new PreviewCallback() {
			
			@Override
			public void onPreviewFrame(byte[] data, Camera camera) {
				if(mVideoViewCreated){
					mYuvUtil.setVideoSUface(data, camera.getParameters().getPreviewSize().width, camera.getParameters().getPreviewSize().height);
				}
			}
		});
		
		try {
			mCamera.setPreviewDisplay(mPreviewSurface.getHolder());
			mCamera.startPreview();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private void closeCamera() {
		if (null != mCamera) {
			mCamera.setPreviewCallback(null);
			mCamera.stopPreview();
			mCamera.release();
			mCamera = null;
		}
	}
	
	private boolean checkCameraPermission() {
		int status = checkPermission(permission.CAMERA, Process.myPid(), Process.myUid());
		if (PackageManager.PERMISSION_GRANTED == status) {
			return true;
		}
		
		return false;
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		mDetectCount = 0;
		if (null != mAcc) {
			mAcc.start();
		}
		
		mStopTrack = false;
//		new Thread(new Runnable() {
//			
//			@Override
//			public void run() {
//				while (!mStopTrack) {
//					if (null == nv21) {
//						continue;
//					}
//					
//					synchronized (nv21) {
//						System.arraycopy(nv21, 0, buffer, 0, nv21.length);
//					}
//					
//					// 获取手机朝向，返回值0,1,2,3分别表示0,90,180和270度
//					int direction = Accelerometer.getDirection();
//					boolean frontCamera = (Camera.CameraInfo.CAMERA_FACING_FRONT == mCameraId);
//					// 前置摄像头预览显示的是镜像，需要将手机朝向换算成摄相头视角下的朝向。
//					// 转换公式：a' = (360 - a)%360，a为人眼视角下的朝向（单位：角度）
//					if (frontCamera) {
//						// SDK中使用0,1,2,3,4分别表示0,90,180,270和360度
//						direction = (4 - direction)%4;
//					}
//
//					if(mFaceDetector == null) {
//						/**
//						 * 离线视频流检测功能需要单独下载支持离线人脸的SDK
//						 * 请开发者前往语音云官网下载对应SDK
//						 */
//						showTip("本SDK不支持离线视频流检测");
//						break;
//					}
//					
//					String result = mFaceDetector.trackNV21(buffer, PREVIEW_WIDTH, PREVIEW_HEIGHT, isAlign, direction);
//					
//					FaceRect[] faces = ParseResult.parseResult(result);
//
//					if( faces.length <=0 ) {
//						continue;
//					}
//					if (null != faces && frontCamera == (Camera.CameraInfo.CAMERA_FACING_FRONT == mCameraId)) {
//						for (FaceRect face: faces) {
//							face.bound = FaceUtil.RotateDeg90(face.bound, PREVIEW_WIDTH, PREVIEW_HEIGHT);
//							//Log.d("shiwei","face.bound.left="+face.bound.left+"  face.bound.top="+ face.bound.top);
//							
//							float width = face.bound.right-face.bound.left;
//							float height = face.bound.bottom-face.bound.top;
//							if (face.point != null) {
//								for (int i = 0; i < face.point.length; i++) {
//									face.point[i] = FaceUtil.RotateDeg90(face.point[i], PREVIEW_WIDTH, PREVIEW_HEIGHT);
//								}
//							}
//							//FaceUtil.drawFaceRect(canvas, face, PREVIEW_WIDTH, PREVIEW_HEIGHT, 
//								//	frontCamera, false);
//							if(mDetectCount < FaceUtil.NEED_DETECT_COUNT && face.point != null && face.point.length == 21 && 48< face.bound.left && face.bound.left < 112 && 67 < face.bound.top && face.bound.top < 162){
//								mDetectCount++;
//								if(mDetectCount > FaceUtil.RECORD_DETECT_COUNT){
//									if(mFaceInfo == null){
//										mFaceInfo = getFaceValues(face);
//									}else{
//										float[] faceInfo = getFaceValues(face);
//										mFaceInfo[FaceUtil.ROW_LEFT_EYE_WIDTH] = (mFaceInfo[FaceUtil.ROW_LEFT_EYE_WIDTH]+faceInfo[FaceUtil.ROW_LEFT_EYE_WIDTH])/2;
//										mFaceInfo[FaceUtil.ROW_RIGHT_EYE_WIDTH] = (mFaceInfo[FaceUtil.ROW_RIGHT_EYE_WIDTH]+faceInfo[FaceUtil.ROW_RIGHT_EYE_WIDTH])/2;
//										mFaceInfo[FaceUtil.ROW_EYE_SPACE] = (mFaceInfo[FaceUtil.ROW_EYE_SPACE]+faceInfo[FaceUtil.ROW_EYE_SPACE])/2;
//										mFaceInfo[FaceUtil.ROW_NOSE_WIDTH] = (mFaceInfo[FaceUtil.ROW_NOSE_WIDTH]+faceInfo[FaceUtil.ROW_NOSE_WIDTH])/2;
//										mFaceInfo[FaceUtil.ROW_NOSE_HEIGHT] = (mFaceInfo[FaceUtil.ROW_NOSE_HEIGHT]+faceInfo[FaceUtil.ROW_NOSE_HEIGHT])/2;
//										mFaceInfo[FaceUtil.ROW_MOUTH_WIDTH] = (mFaceInfo[FaceUtil.ROW_MOUTH_WIDTH]+faceInfo[FaceUtil.ROW_MOUTH_WIDTH])/2;
//										mFaceInfo[FaceUtil.ROW_MOUTH_HEIGHT] = (mFaceInfo[FaceUtil.ROW_MOUTH_HEIGHT]+faceInfo[FaceUtil.ROW_MOUTH_HEIGHT])/2;
//									}
//								}
//								mHandler.sendEmptyMessage(MSG_UPDATE_PROGRESS);
//								if(mDetectCount == FaceUtil.NEED_DETECT_COUNT){
//									mHandler.sendEmptyMessage(MSG_UPDATE_FACEINFO);
//								}
//							}
//						}
//					} else {
//						Log.d(TAG, "faces:0");
//					}
//				}
//			}
//		}).start();
	}
	private float[] getFaceValues(FaceRect face){
		if (face.point != null && face.point.length == 21) {
			float width = face.bound.right-face.bound.left;
			float height = face.bound.bottom-face.bound.top;
			float[] face_value = new float[FaceUtil.ROW_COUNT];
			face_value[FaceUtil.ROW_LEFT_EYE_WIDTH] = (face.point[8].x-face.point[9].x)/width;
			face_value[FaceUtil.ROW_RIGHT_EYE_WIDTH] = (face.point[6].x-face.point[7].x)/width;
			face_value[FaceUtil.ROW_EYE_SPACE] = (face.point[7].x-face.point[8].x)/width;
			face_value[FaceUtil.ROW_NOSE_WIDTH] = (face.point[10].x-face.point[12].x)/width;
			face_value[FaceUtil.ROW_NOSE_HEIGHT] = (face.point[18].y-face.point[11].y)/height;
			face_value[FaceUtil.ROW_MOUTH_WIDTH] = (face.point[19].x-face.point[20].x)/width;
			face_value[FaceUtil.ROW_MOUTH_HEIGHT] = (face.point[13].y-face.point[15].y)/height;
			return face_value;
		}
		return null;
	}
	private void onFaceUnlockSet(){
		Uri uri = ContentUris.withAppendedId(FaceInfo.FaceInfo_Column.CONTENT_URI, 1);
		ContentValues values = new ContentValues();
		if(mFaceInfo.length == FaceUtil.ROW_COUNT){
			FaceUtil.saveFaceInfo(this,mFaceInfo);
		}
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.ChooseLockPassword"));
        intent.putExtra(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC);
        intent.putExtra(PASSWORD_MIN_KEY, MIN_PASSWORD_LENGTH);
        intent.putExtra(PASSWORD_MAX_KEY, MAX_PASSWORD_LENGTH);
        intent.putExtra(CONFIRM_CREDENTIALS, false);
        intent.putExtra(LOCKSCREEN_WEAK_FALLBACK, true);
		intent.putExtra(EXTRA_REQUIRE_PASSWORD, false);
    	intent.putExtra(LOCKSCREEN_WEAK_FALLBACK_FOR,TYPE_FACE_UNLOCK);
		startActivity(intent);	
		finish();
	}
	@Override
	protected void onPause() {
		super.onPause();
		closeCamera();
		if (null != mAcc) {
			mAcc.stop();
		}
		mStopTrack = true;
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		// 销毁对象
		mFaceDetector.destroy();
		unregisterReceiver(mReceiver);
	}
	
	private void showTip(final String str) {
		mToast.setText(str);
		mToast.show();
	}

}
