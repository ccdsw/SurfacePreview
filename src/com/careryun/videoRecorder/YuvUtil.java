package com.careryun.videoRecorder;


import android.util.Log;
import android.view.Surface;
public class YuvUtil {
	private native void nativeTest();
	private native boolean nativeSetVideoSurface(Surface surface);
	private native void nativeShowYUV(byte[] yuvArray,int width,int height);
	static {
        System.loadLibrary("showYUV");
    }
	public boolean setVideoSUface(Surface surface){
		return nativeSetVideoSurface(surface);
	}
	public void setVideoSUface(byte[] yuvArray,int width,int height){
		 nativeShowYUV(yuvArray, width, height);
	}
}
