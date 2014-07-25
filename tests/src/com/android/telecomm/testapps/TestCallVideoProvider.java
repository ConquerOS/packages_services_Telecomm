/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.telecomm.testapps;

import com.android.ex.camera2.blocking.BlockingCameraManager;
import com.android.ex.camera2.blocking.BlockingCameraManager.BlockingOpenException;
import com.android.ex.camera2.blocking.BlockingSessionListener;
import com.android.telecomm.tests.R;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.RemoteException;
import android.telecomm.CallCameraCapabilities;
import android.telecomm.CallVideoClient;
import android.telecomm.CallVideoProvider;
import android.telecomm.RemoteCallVideoClient;
import android.telecomm.VideoCallProfile;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Implements the CallVideoProvider.
 */
public class TestCallVideoProvider extends CallVideoProvider {
    private RemoteCallVideoClient mCallVideoClient;
    private CallCameraCapabilities mCapabilities;
    private Random random;
    private Surface mDisplaySurface;
    private Surface mPreviewSurface;
    private Context mContext;
    /** Used to play incoming video during a call. */
    private MediaPlayer mIncomingMediaPlayer;

    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCameraSession;
    private CameraThread mLooperThread;

    private String mCameraId;

    private static final long SESSION_TIMEOUT_MS = 2000;

    public TestCallVideoProvider(Context context) {
        mContext = context;
        mCapabilities = new CallCameraCapabilities(false /* zoomSupported */, 0 /* maxZoom */);
        random = new Random();
        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    /**
     * Save the reference to the CallVideoClient so callback can be invoked.
     */
    @Override
    public void onSetCallVideoClient(RemoteCallVideoClient callVideoClient) {
        mCallVideoClient = callVideoClient;
    }

    @Override
    public void onSetCamera(String cameraId) {
        log("Set camera to " + cameraId);
        mCameraId = cameraId;
        if (mPreviewSurface != null && mCameraId != null) {
            startCamera(cameraId);
        }
    }

    @Override
    public void onSetPreviewSurface(Surface surface) {
        log("Set preview surface " + (surface == null ? "unset" : "set"));
        mPreviewSurface = surface;

        if (mPreviewSurface == null) {
            stopCamera();
        }

        if (!TextUtils.isEmpty(mCameraId) && mPreviewSurface != null) {
            startCamera(mCameraId);
        }
    }

    @Override
    public void onSetDisplaySurface(Surface surface) {
        log("Set display surface " + (surface == null ? "unset" : "set"));
        mDisplaySurface = surface;

        if (mDisplaySurface != null) {
            if (mIncomingMediaPlayer == null) {
                mIncomingMediaPlayer = createMediaPlayer(mDisplaySurface, R.raw.test_video);
            }
            mIncomingMediaPlayer.setSurface(mDisplaySurface);
            if (!mIncomingMediaPlayer.isPlaying()) {
                mIncomingMediaPlayer.start();
            }
        } else {
            if (mIncomingMediaPlayer != null) {
                mIncomingMediaPlayer.stop();
                mIncomingMediaPlayer.setSurface(null);
            }
        }
    }

    @Override
    public void onSetDeviceOrientation(int rotation) {
        log("Set device orientation");
    }

    /**
     * Sets the zoom value, creating a new CallCameraCapabalities object. If the zoom value is
     * non-positive, assume that zoom is not supported.
     */
    @Override
    public void onSetZoom(float value) {
        log("Set zoom to " + value);

        if (value <= 0) {
            mCapabilities = new CallCameraCapabilities(false /* zoomSupported */, 0 /* maxZoom */);
        } else {
            mCapabilities = new CallCameraCapabilities(true /* zoomSupported */, value);
        }

        mCallVideoClient.handleCameraCapabilitiesChange(mCapabilities);
    }

    /**
     * "Sends" a request with a video call profile. Assumes that this response succeeds and sends
     * the response back via the CallVideoClient.
     */
    @Override
    public void onSendSessionModifyRequest(VideoCallProfile requestProfile) {
        log("Sent session modify request");

        VideoCallProfile responseProfile = new VideoCallProfile(
                requestProfile.getVideoState(), requestProfile.getQuality());
        mCallVideoClient.receiveSessionModifyResponse(
                CallVideoClient.SESSION_MODIFY_REQUEST_SUCCESS,
                requestProfile,
                responseProfile);
    }

    @Override
    public void onSendSessionModifyResponse(VideoCallProfile responseProfile) {

    }

    /**
     * Returns a CallCameraCapabilities object without supporting zoom.
     */
    @Override
    public void onRequestCameraCapabilities() {
        log("Requested camera capabilities");
        mCallVideoClient.handleCameraCapabilitiesChange(mCapabilities);
    }

    /**
     * Randomly reports data usage of value ranging from 10MB to 60MB.
     */
    @Override
    public void onRequestCallDataUsage() {
        log("Requested call data usage");
        int dataUsageKb = (10 *1024) + random.nextInt(50 * 1024);
        mCallVideoClient.updateCallDataUsage(dataUsageKb);
    }

    /**
     * We do not have a need to set a paused image.
     */
    @Override
    public void onSetPauseImage(String uri) {
        // Not implemented.
    }

    /**
     * Stop and cleanup the media players used for test video playback.
     */
    public void stopAndCleanupMedia() {
        if (mIncomingMediaPlayer != null) {
            mIncomingMediaPlayer.setSurface(null);
            mIncomingMediaPlayer.stop();
            mIncomingMediaPlayer.release();
            mIncomingMediaPlayer = null;
        }
    }

    private static void log(String msg) {
        Log.w("TestCallVideoProvider", "[TestCallServiceProvider] " + msg);
    }

    /**
     * Creates a media player to play a video resource on a surface.
     * @param surface The surface.
     * @param videoResource The video resource.
     * @return The {@code MediaPlayer}.
     */
    private MediaPlayer createMediaPlayer(Surface surface, int videoResource) {
        MediaPlayer mediaPlayer = MediaPlayer.create(mContext.getApplicationContext(),
                videoResource);
        mediaPlayer.setSurface(surface);
        mediaPlayer.setLooping(true);
        return mediaPlayer;
    }

    /**
     * Starts displaying the camera image on the preview surface.
     *
     * @param cameraId
     */
    private void startCamera(String cameraId) {
        stopCamera();

        if (mPreviewSurface == null) {
            return;
        }

        // Configure a looper thread.
        mLooperThread = new CameraThread();
        Handler mHandler;
        try {
            mHandler = mLooperThread.start();
        } catch (Exception e) {
            log("Exception: " + e);
            return;
        }

        // Get the camera device.
        try {
            BlockingCameraManager blockingCameraManager = new BlockingCameraManager(mCameraManager);
            mCameraDevice = blockingCameraManager.openCamera(cameraId, null /* listener */,
                    mHandler);
        } catch (CameraAccessException e) {
            log("CameraAccessException: " + e);
            return;
        } catch (BlockingOpenException be) {
            log("BlockingOpenException: " + be);
            return;
        }

        // Create a capture session to get the preview and display it on the surface.
        List<Surface> surfaces = new ArrayList<Surface>();
        surfaces.add(mPreviewSurface);
        CaptureRequest.Builder mCaptureRequest = null;
        try {
            BlockingSessionListener blkSession = new BlockingSessionListener();
            mCameraDevice.createCaptureSession(surfaces, blkSession, mHandler);
            mCaptureRequest = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequest.addTarget(mPreviewSurface);
            mCameraSession = blkSession.waitAndGetSession(SESSION_TIMEOUT_MS);
        } catch (CameraAccessException e) {
            log("CameraAccessException: " + e);
            return;
        }

        // Keep repeating
        try {
            mCameraSession.setRepeatingRequest(mCaptureRequest.build(), new CameraCaptureListener(),
                    mHandler);
        } catch (CameraAccessException e) {
            log("CameraAccessException: " + e);
            return;
        }
    }

    /**
     * Stops the camera and looper thread.
     */
    public void stopCamera() {
        try {
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (mLooperThread != null) {
                mLooperThread.close();
                mLooperThread = null;
            }
        } catch (Exception e) {
           log("stopCamera Exception: "+e.toString());
        }
    }

    /**
     * Required listener for camera capture events.
     */
    private class CameraCaptureListener extends CameraCaptureSession.CaptureListener {
        @Override
        public void onCaptureCompleted(CameraCaptureSession camera, CaptureRequest request,
                TotalCaptureResult result) {
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession camera, CaptureRequest request,
                CaptureFailure failure) {
        }
    }
}