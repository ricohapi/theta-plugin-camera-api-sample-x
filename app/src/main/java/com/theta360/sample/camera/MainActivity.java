package com.theta360.sample.camera;

import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;
import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import theta360.hardware.Camera;
import theta360.media.CamcorderProfile;
import theta360.media.MediaRecorder;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.theta360.sample.camera.MainActivity.MODE.PREVIEW;

public class MainActivity extends PluginActivity implements MediaRecorder.OnInfoListener {

    private String RIC_SHOOTING_MODE = "RIC_SHOOTING_MODE";
    private String RIC_PROC_STITCHING = "RIC_PROC_STITCHING";
    private String RIC_PROC_ZENITH_CORRECTION = "RIC_PROC_ZENITH_CORRECTION";
    private String RIC_EXPOSURE_MODE = "RIC_EXPOSURE_MODE";

    private String TAG = "Camera_API_Sample";
    private String DCIM = "/DCIM/";
    private String FOLDER = "/DCIM/100_TEST/";
    private String PATH_INTERNAL = "/storage/emulated/0";
    private String PATH = PATH_INTERNAL;

    enum MODE {
        IMAGE, VIDEO, PREVIEW
    }

    private Handler mHandler = new Handler();
    private Camera mCamera = null;
    private MediaRecorder mRecorder = null;
    private String mPath = "";
    public String mFilepath = "";

    private Boolean isInitialized = false;
    private Boolean isCameraOpen = false;
    private Boolean isPreview = false;
    private Boolean isRecording = false;
    private Boolean isMultiShot = false;
    private Boolean isLongShutter = false;

    private Integer mLcdBrightness = 64;
    private Integer[] mLedPowerBrightness = {0, 0, 0, 64};   //(dummy,R,G,B)
    private Integer[] mLedStatusBrightness = {0, 0, 0, 64};   //(dummy,R,G,B)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setFolderPath();

        Switch switch_camera = findViewById(R.id.switch_camera);
        Button button_image = findViewById(R.id.button_image);
        Button button_video = findViewById(R.id.button_video);
        Spinner spinner_ric_shooting_mode_preview = findViewById(R.id.spinner_ric_shooting_mode_preview);
        Spinner spinner_ric_shooting_mode_image = findViewById(R.id.spinner_ric_shooting_mode_image);
        Spinner spinner_ric_shooting_mode_video = findViewById(R.id.spinner_ric_shooting_mode_video);
        Spinner spinner_ric_proc_stitching = findViewById(R.id.spinner_ric_proc_stitching);
        Spinner spinner_ric_proc_zenith_correction = findViewById(R.id.spinner_ric_proc_zenith_correction);

        //KeyCallback
        setKeyCallback(new KeyCallback() {
            @Override
            public void onKeyDown(int p0, KeyEvent p1) {
                if (p0 == KeyReceiver.KEYCODE_CAMERA) {
                    button_image.callOnClick();      //executeTakePicture()
                }
            }

            @Override
            public void onKeyUp(int p0, KeyEvent p1) {
                //do nothing
            }

            @Override
            public void onKeyLongPress(int p0, KeyEvent p1) {
                //when camera runs as video recording, just execute stopVideoRecording.
                if (p0 == KeyReceiver.KEYCODE_MEDIA_RECORD) {
                    if (isRecording) {
                        button_video.callOnClick();  //stopVideoRecording()
                    }
                }
            }
        });

        //Switch : start or stop camera preview
        switch_camera.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    enableAllSpinners(false);
                    enableAllButtons(true);
                    executeStartPreview();
                } else {
                    enableAllSpinners(true);
                    enableAllButtons(false);
                    executeStopPreview();
                }
            }
        });

        //Button : take picture
        button_image.setEnabled(false);
        button_image.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (button_image.isEnabled()) {
                    switch_camera.setClickable(false);
                    enableAllButtons(false);
                    executeTakePicture();
                }
            }
        });

        //Button : start and stop video recording
        button_video.setEnabled(false);
        button_video.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!isRecording) {
                    switch_camera.setClickable(false);
                    enableAllButtons(false);
                    startVideoRecording();
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            enableButton(button_video, true);
                            button_video.setText(R.string.stop_video);
                        }
                    }, 1000);   //TODO
                } else {
                    switch_camera.setClickable(true);
                    enableAllButtons(false);
                    stopVideoRecording();
                    enableAllButtons(true);
                    button_video.setText(R.string.start_video);
                }
            }
        });

        //Spinner : set Camera Parameters
        spinner_ric_shooting_mode_preview.setSelection(0);      //RicPreview1024
        spinner_ric_shooting_mode_image.setSelection(0);        //RicStillCaptureStd
        spinner_ric_shooting_mode_video.setSelection(1);        //RicMovieRecording3840
        spinner_ric_proc_stitching.setSelection(2);             //RicDynamicStitchingAuto
        spinner_ric_proc_zenith_correction.setSelection(1);     //RicZenithCorrectionOnAuto

        //TextureView : show preview
        TextureView texture_view = findViewById(R.id.texture_view);
        texture_view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openCamera(surface);             //open camera
                switch_camera.setChecked(true);  //start preview
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                //do nothing
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                //do nothing
            }
        });
    }

    @Override
    protected void onPause() {
        Log.i(TAG,"onPause");
        setAutoClose(false);    //the flag which does not finish plug-in in onPause
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.i(TAG,"onResume");
        setAutoClose(true);     //the flag which does finish plug-in by long-press MODE
        super.onResume();

        if (isInitialized) {
            openCamera(null);
            Switch switch_camera = findViewById(R.id.switch_camera);
            switch_camera.setChecked(true);   //start camera
        }
    }

    @Override
    public void onInfo(MediaRecorder mr, int what, int extra) {
        //Log.d(TAG, "onInfo() : what=" + what + ", extra=" + extra)
        if (what == MediaRecorder.MEDIA_RECORDER_EVENT_RECORD_STOP) {
            notificationAudioMovStop();
        }
    }

    //
    // folder path and file path related functions
    //
    Boolean setFolderPath() {
        mPath = PATH;
        //check whether SD card is inserted
        File dir = new File("/storage/");
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].toString().endsWith("emulated") || files[i].toString().endsWith("self")) {
                    //ignored
                } else {
                    mPath = files[i].toString();
                    break;
                }
            }
        }
        //directory check : DCIM
        File dcim = new File(mPath + DCIM);
        if (!dcim.exists()) {
            dcim.mkdir();
            mPath = PATH_INTERNAL;
            return false;
        }
        //directory check : FOLDER
        File folder = new File(mPath + FOLDER);
        if (!folder.exists()) {
            folder.mkdir();
            mPath = PATH_INTERNAL;
            return false;
        }
        mPath += FOLDER;
        return true;
    }

    private File getOutputMediaFile() {
        if (!setFolderPath()) {
            return null;
        }
        mFilepath = mPath + "PC" + (new SimpleDateFormat("HHmmss")).format(new Date()) + ".JPG";
        Log.i(TAG, "JPEG file path = " + mFilepath);
        return (new File(mFilepath));
    }

    //
    // camera controlling related functions
    //
    void openCamera(SurfaceTexture surface) {
        Log.i(TAG, "openCamera");
        if (isCameraOpen) {
            return;
        }
        //open camera with id setting CAMERA_FACING_DOUBLE directly
        mCamera = Camera.open(this, Camera.CameraInfo.CAMERA_FACING_DOUBLE);
        isCameraOpen = true;
        if (surface!=null) {
            try {
                mCamera.setPreviewTexture(surface);
            } catch (IOException e) {
                e.printStackTrace();
            }
            isInitialized = true;
        }
    }

    void closeCamera() {
        //stop recording
        if (isRecording) {
            findViewById(R.id.button_video).callOnClick();          //stopVideoRecording()
        }
        //stop preview
        if (isPreview) {
            Switch switch_camera = findViewById(R.id.switch_camera);
            switch_camera.setChecked(false);                        //executeStopPreview()
        }
        //close camera
        if (isCameraOpen) {
            Log.i(TAG, "closeCamera");
            mCamera.setPreviewCallback(null);
            mCamera.release();
            mCamera = null;
            isCameraOpen = false;
        }
    }

    void executeStartPreview() {
        Log.i(TAG,"startPreview");
        if (isPreview) {
            //return
        }
        setCameraParameters(PREVIEW);
        mCamera.startPreview();
        isPreview = true;
    }

    void executeStopPreview() {
        Log.i(TAG,"stopPreview");
        if (!isPreview) {
            //return
        }
        mCamera.stopPreview();
        isPreview = false;
    }

    void executeTakePicture() {
        Log.i(TAG,"executeTakePicture");
        //executeStopPreview()
        turnOnOffLcdLed(false);
        notificationAudioSelf();
        setCameraParameters(MODE.IMAGE);
        mCamera.takePicture(
            shutterCallbackListner,
            new Camera.PictureCallback() {
                @Override
                public void onPictureTaken (byte[] bytes, Camera camera) {
                    Log.i(TAG,"receive raw callback");
                }
            },
            new Camera.PictureCallback() {
                @Override
                public void onPictureTaken (byte[] bytes, Camera camera) {
                    Log.i(TAG, "receive postview callback");
                }
            },
            new Camera.PictureCallback() {
                @Override
                public void onPictureTaken (byte[] bytes, Camera camera) {
                    Log.i(TAG, "receive jpeg callback");
                    File pictureFile = getOutputMediaFile();
                    if (pictureFile == null) {
                        return;
                    }
                    try {
                        FileOutputStream fos = new FileOutputStream(pictureFile);
                        fos.write(bytes);
                        fos.close();
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    notificationDatabaseUpdate(mFilepath);
                    executeStartPreview();

                    //TODO
                    Switch switch_camera = findViewById(R.id.switch_camera);
                    switch_camera.setEnabled(true);
                    enableAllButtons(true);
                }
            }
        );
    }

    Camera.ShutterCallback shutterCallbackListner = new Camera.ShutterCallback() {

        public void onShutter() {
            Log.i(TAG,"receive onShutter");
            if (!isMultiShot) {
                notificationAudioShutter();
                isLongShutter = false;
            } else {
                notificationAudioOpen();
                isLongShutter = true;
            }
        }
        public void onLongShutter() {
            Log.i(TAG,"receive onLongShutter");
            notificationAudioOpen();
            isLongShutter = true;
        }
        public void  onShutterend() {
            Log.i(TAG,"receive onShutterend");
            if (isLongShutter) {
                notificationAudioClose();
            }
            isLongShutter = false;
            turnOnOffLcdLed(true);
        }
    };

    private void startVideoRecording() {
        Log.i(TAG,"startVideoRecording");

        setCameraParameters(MODE.VIDEO);

        mRecorder = new MediaRecorder();
        mRecorder.setOnInfoListener(this);
        //Context
        mRecorder.setMediaRecorderContext(getApplicationContext());
        //Audio Setting
        mRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        //mRecorder.setMicDeviceId(getExternalMicDeviceId());
        //mRecorder.setExternalMicDevVendorName(NULL);
        mRecorder.setMicGain(1);             //microphone gain
        mRecorder.setMicSamplingFormat(MediaRecorder.MEDIA_FORMAT_PCM_I16);
        mRecorder.setAudioChannels(1);
        mRecorder.setAudioSamplingRate(48_000);
        mRecorder.setAudioEncodingBitRate(128_000);
        //Video Setting
        mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        //mRecorder.setVideoFrameRate(30);
        //mRecorder.setVideoSize(3840, 1920);
        //mRecorder.setVideoEncodingBitRate(64_000_000);
        mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mRecorder.setVideoEncodingProfileLevel(0x7F000001,0x8000);   //BaseLine@L5.1 0x7F000001

        //FYI: use setProfile to set configuration easily
        Spinner spinner_ric_shooting_mode_video = findViewById(R.id.spinner_ric_shooting_mode_video);
        String shooting_mode = spinner_ric_shooting_mode_video.getSelectedItem().toString();
        switch (shooting_mode) {
            case "RicVideoRecording1920":
                mRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_2K));
                mRecorder.setVideoEncodingIFrameInterval(1.0f);
                break;
            case "RicMovieRecording3840":
                mRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_4K));
                mRecorder.setVideoEncodingIFrameInterval(1.0f);
                break;
            case "RicVideoRecording5760":
                mRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_6K));
                mRecorder.setVideoEncodingIFrameInterval(1.0f);
                break;
            case "RicMovieRecording7680":
                mRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_8K));   //set 10fps in this API
                mRecorder.setVideoEncodingIFrameInterval(0.0f);                            //set I-frame only GOP
                break;
        }

        //filename
        Boolean result = setFolderPath();
        mFilepath = mPath + "VD" + (new SimpleDateFormat("HHmmss")).format(new Date()) + ".MP4";
        Log.i(TAG, "MP4 file path = " + mFilepath);
        mRecorder.setOutputFile(mFilepath);

        try {
            mRecorder.prepare();
        } catch (IOException e) {
            Log.i(TAG, "here4");
            e.printStackTrace();
            releaseMediaRecorder();
        }

        notificationAudioMovStart();
        isRecording = true;
        mRecorder.start();
    }

    void stopVideoRecording() {
        Log.i(TAG,"stopVideoRecording");
        mRecorder.stop();
        mRecorder.release();
        mRecorder = null;
        isRecording = false;
        notificationDatabaseUpdate(mFilepath);
    }

    void releaseMediaRecorder() {
        Log.i(TAG,"releaseMediaRecorder");
        if (mRecorder != null) {
            mRecorder.setOnInfoListener(null);
            mRecorder.release();
            mRecorder = null;
        }
    }

    void setCameraParameters(MODE mode) {
        Spinner spinner_ric_shooting_mode_image = findViewById(R.id.spinner_ric_shooting_mode_image);
        Spinner spinner_ric_shooting_mode_video = findViewById(R.id.spinner_ric_shooting_mode_video);
        Spinner spinner_ric_proc_stitching      = findViewById(R.id.spinner_ric_proc_stitching);
        Spinner spinner_ric_proc_zenith_correction = findViewById(R.id.spinner_ric_proc_zenith_correction);
        String ric_shooting_mode_image = spinner_ric_shooting_mode_image.getSelectedItem().toString();
        String ric_shooting_mode_video = spinner_ric_shooting_mode_video.getSelectedItem().toString();
        String ric_proc_stitching      = spinner_ric_proc_stitching.getSelectedItem().toString();
        String ric_proc_zenith_correction = spinner_ric_proc_zenith_correction.getSelectedItem().toString();

        Camera.Parameters p = mCamera.getParameters();
        p.set(RIC_PROC_STITCHING,         ric_proc_stitching);
        p.set(RIC_PROC_ZENITH_CORRECTION, ric_proc_zenith_correction);
        p.set(RIC_EXPOSURE_MODE, "RicAutoExposureP");

        switch (mode) {
            case PREVIEW:
                Spinner spinner_ric_shooting_mode_preview = findViewById(R.id.spinner_ric_shooting_mode_preview);
                String shooting_mode = spinner_ric_shooting_mode_preview.getSelectedItem().toString();
                switch (shooting_mode) {
                    case "RicPreview1024":
                        p.setPreviewSize(1024, 512);
                        break;
                    case "RicPreview1920":
                        p.setPreviewSize(1920, 960);
                        break;
                    case "RicPreview3840":
                        p.setPreviewSize(3840, 1920);
                        break;
                    case "RicPreview5760":
                        p.setPreviewSize(5760, 2880);
                        break;
                }
                p.setPreviewFrameRate(30);
                break;
            case IMAGE:
                p.set(RIC_SHOOTING_MODE, ric_shooting_mode_image);
                isMultiShot = ric_shooting_mode_image.equals("RicStillCaptureStd")? false: true;
                p.setPictureSize(11008, 5504);   //11008*5504 or 5504*2752
                break;
            case VIDEO:
                p.set(RIC_SHOOTING_MODE, ric_shooting_mode_video);
                break;
        }

        mCamera.setParameters(p);
        mCamera.setBrightnessMode(1); //Auto Control LCD/LED Brightness

        //TODO if you want to put the location data, use setGPSxxxx APIs.
        //p.setGpsLatitude(35.65859)
        //p.setGpsLongitude(139.74543)
        //p.setGpsAltitude(+15.5)
        //p.setGpsTimestamp(System.currentTimeMillis() / 1000)   //need to set UTC seconds since 1970
        //setParameters(p)
    }

    //
    // UI related functions
    //
    void enableAllSpinners(Boolean flag) {
        findViewById(R.id.spinner_ric_shooting_mode_preview).setEnabled(flag);
        findViewById(R.id.spinner_ric_shooting_mode_image).setEnabled(flag);
        findViewById(R.id.spinner_ric_shooting_mode_video).setEnabled(flag);
        findViewById(R.id.spinner_ric_proc_stitching).setEnabled(flag);
        findViewById(R.id.spinner_ric_proc_zenith_correction).setEnabled(flag);
    }

    void enableButton(Button button, Boolean flag) {
        button.setEnabled(flag);
        button.setBackgroundColor(getResources().getColor(
                flag? R.color.button_color_enabled: R.color.button_color_disabled));
    }

    void enableAllButtons(Boolean flag) {
        Button button_image = findViewById(R.id.button_image);
        Button button_video = findViewById(R.id.button_video);
        enableButton(button_image, flag);
        enableButton(button_video, flag);
    }

    void turnOnOffLcdLed(Boolean flag) {
        //turn on LCD and LED
        if (flag) {
            mCamera.ctrlLcdBrightness(mLcdBrightness);
            for (int i = 1; i < 3; i++) {
                mCamera.ctrlLedPowerBrightness(i, mLedPowerBrightness[i]);
                mCamera.ctrlLedStatusBrightness(i, mLedStatusBrightness[i]);
            }
        }
        //turn off LCD and LED
        else {
            mLcdBrightness = mCamera.getLcdBrightness();
            mCamera.ctrlLcdBrightness(0);
            for (int i = 1; i < 3; i++) {
                mLedPowerBrightness[i] = mCamera.getLedPowerBrightness(i);
                mLedStatusBrightness[i] = mCamera.getLedStatusBrightness(i);
                mCamera.ctrlLedPowerBrightness(i, 0);
                mCamera.ctrlLedStatusBrightness(i, 0);
            }
        }
    }
}
