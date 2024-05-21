package com.theta360.sample.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.TextureView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import com.theta360.pluginlibrary.activity.PluginActivity
import com.theta360.pluginlibrary.activity.ThetaInfo
import com.theta360.pluginlibrary.callback.KeyCallback
import com.theta360.pluginlibrary.receiver.KeyReceiver
import theta360.hardware.Camera
import theta360.media.CamcorderProfile
import theta360.media.MediaRecorder
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : PluginActivity(), MediaRecorder.OnInfoListener {
    companion object {
        private val RIC_SHOOTING_MODE = "RIC_SHOOTING_MODE"
        private val RIC_PROC_STITCHING = "RIC_PROC_STITCHING"
        private val RIC_PROC_ZENITH_CORRECTION = "RIC_PROC_ZENITH_CORRECTION"
        private val RIC_EXPOSURE_MODE = "RIC_EXPOSURE_MODE"
        private val RIC_FACE_DETECTION = "RIC_FACE_DETECTION"
        private val RIC_WATER_HOUSING = "RIC_WATER_HOUSING"

        private val TAG = "Camera_API_Sample"
        private val DCIM = "/DCIM/"
        private val FOLDER = "/DCIM/100_TEST/"
        private val PATH_INTERNAL = "/storage/emulated/0"
        private var PATH = PATH_INTERNAL

        enum class MODE {
            IMAGE, VIDEO, PREVIEW
        }
    }

    private var mHandler: Handler = Handler()
    private var mCamera: Camera? = null
    private var mRecorder: MediaRecorder? = null
    private var mPath: String = ""
    private var mFilepath: String = ""

    private var isInitialized: Boolean = false
    private var isCameraOpen:  Boolean = false
    private var isPreview:   Boolean = false
    private var isCapturing: Boolean = false
    private var isRecording: Boolean = false
    private var isMultiShot: Boolean = false
    private var isLongShutter: Boolean = false

    private var isLowPowerPreview: Boolean = false
    private var isNV21Available: Boolean   = false
    private var isJPEG: Boolean = true

    private var mLcdBrightness: Int = 64
    private var mLedPowerBrightness:  IntArray = intArrayOf(0, 0, 0, 64)   //(dummy,R,G,B)
    private var mLedStatusBrightness: IntArray = intArrayOf(0, 0, 0, 64)   //(dummy,R,G,B)

    //location
    private val mLocationManager: LocationManagerUtil by lazy {
        val manager = LocationManagerUtil(this)
        manager
    }

    //
    private var isDouble = true

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG,"onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setFolderPath()

        val switch_camera: Switch = findViewById<Switch>(R.id.switch_camera)
        val button_image: Button = findViewById<Button>(R.id.button_image)
        val button_video: Button = findViewById<Button>(R.id.button_video)
        val spinner_ric_shooting_mode_preview: Spinner = findViewById<Spinner>(R.id.spinner_ric_shooting_mode_preview)
        val spinner_ric_shooting_mode_image: Spinner = findViewById<Spinner>(R.id.spinner_ric_shooting_mode_image)
        val spinner_ric_shooting_mode_video: Spinner = findViewById<Spinner>(R.id.spinner_ric_shooting_mode_video)
        val spinner_ric_proc_stitching: Spinner      = findViewById<Spinner>(R.id.spinner_ric_proc_stitching)
        val spinner_ric_proc_zenith_correction: Spinner = findViewById<Spinner>(R.id.spinner_ric_proc_zenith_correction)
        val spinner_picture_format: Spinner             = findViewById<Spinner>(R.id.spinner_picture_format)

        //KeyCallback
        setKeyCallback(object : KeyCallback {
            override fun onKeyDown(p0: Int, p1: KeyEvent?) {
                if (p0 == KeyReceiver.KEYCODE_CAMERA ||
                    p0 == KeyReceiver.KEYCODE_VOLUME_UP) {  //Bluetooth remote shutter
                    if (isDouble) {
                        button_image.callOnClick()      //executeTakePicture()
                    }
                }
            }
            override fun onKeyUp(p0: Int, p1: KeyEvent?) {
                //do nothing
            }
            override fun onKeyLongPress(p0: Int, p1: KeyEvent) {
                //when camera runs as video recording, just execute stopVideoRecording.
                if (p0 == KeyReceiver.KEYCODE_MEDIA_RECORD) {
                    if(isRecording) {
                        button_video.callOnClick()  //stopVideoRecording()
                    }
                }
            }
        })

        //Switch : start or stop camera preview
        switch_camera.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                openCamera(null)
                enableAllSpinners(false)
                enableAllButtons(true)
                executeStartPreview()
            }
            else {
                enableAllSpinners(true)
                enableAllButtons(false)
                executeStopPreview()
                closeCamera()
                isDouble = !isDouble
            }
        }

        //Button : take picture
        button_image.setEnabled(false)
        button_image.setOnClickListener { _ ->
            if (button_image.isEnabled) {
                switch_camera.isClickable = false
                enableAllButtons(false)
                executeTakePicture()
            }
        }

        //Button : start and stop video recording
        button_video.setEnabled(false)
        button_video.setOnClickListener { _ ->
            if (!isRecording) {
                switch_camera.isClickable = false
                enableAllButtons(false)
                startVideoRecording()
                mHandler.postDelayed({
                    enableButton(button_video, isDouble)
                    button_video.setText(R.string.stop_video)
                }, 1_000)   //TODO
            }
            else {
                switch_camera.isClickable = true
                enableAllButtons(false)
                stopVideoRecording()
                enableAllButtons(true)
                button_video.setText(R.string.start_video)
            }
        }

        //firmware check
        val version = ThetaInfo.getThetaFirmwareVersion().replace(".", "").toFloat()
        isLowPowerPreview = if (version >= 1200) true else false    //15fps preview available with fw1.20 or later
        isNV21Available   = if (version >= 2300) true else false    //NV21 picture format available with fw2.30 or later

        //Spinner : set Camera Parameters
        setSpinner(spinner_ric_shooting_mode_preview, getResources().getStringArray(
            if (version >= 1200) R.array.RIC_SHOOTING_MODE_PREVIEW_ARRAY
            else                 R.array.RIC_SHOOTING_MODE_PREVIEW_OLD_ARRAY))
        setSpinner(spinner_ric_shooting_mode_image, getResources().getStringArray(
            if (version >= 2400) R.array.RIC_SHOOTING_MODE_IMAGE_ARRAY
            else                 R.array.RIC_SHOOTING_MODE_IMAGE_OLD_ARRAY))
        spinner_ric_shooting_mode_preview.setSelection(0)       //RicPreview1024
        spinner_ric_shooting_mode_image.setSelection(0)         //RicStillCaptureStd
        spinner_ric_shooting_mode_video.setSelection(1)         //RicMovieRecording3840
        spinner_ric_proc_stitching.setSelection(2)              //RicDynamicStitchingAuto
        spinner_ric_proc_zenith_correction.setSelection(1)      //RicZenithCorrectionOnAuto
        spinner_picture_format.setSelection(0)                  //ImageFormat.JPEG

        //TextureView : show preview
        val texture_view = findViewById<TextureView>(R.id.texture_view)
        texture_view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
                //do nothing
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
                //do nothing
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
                return false
            }
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
                openCamera(surface)             //open camera
                switch_camera.isChecked = true  //start preview
            }
        }
    }

    override fun onPause() {
        Log.i(TAG,"onPause")
        setAutoClose(false)     //the flag which does not finish plug-in in onPause
        closeCamera()
        mLocationManager.stop()
        super.onPause()
    }

    override fun onResume() {
        Log.i(TAG,"onResume")
        setAutoClose(true)      //the flag which does finish plug-in by long-press MODE
        super.onResume()

        if (isInitialized) {
            openCamera(null)
            findViewById<Switch>(R.id.switch_camera).isChecked = true   //start camera
        }
        mLocationManager.start()
    }

    override fun onInfo(mr: MediaRecorder, what: Int, extra: Int) {
        //Log.d(TAG, "onInfo() : what=" + what + ", extra=" + extra)
        if (what == MediaRecorder.MEDIA_RECORDER_EVENT_RECORD_STOP) {
            notificationAudioMovStop()
        }
    }

    private fun setSpinner(spinner: Spinner, arr: Array<String>){
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arr)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.setAdapter(adapter)
    }

    //
    // folder path and file path related functions
    //
    private fun setFolderPath(): Boolean {
        mPath = PATH
        //check whether SD card is inserted
        val files = File("/storage/").listFiles()
        if (files != null) {
            for (i in 0 until files.size) {
                if (files[i].endsWith("emulated") || files[i].endsWith("self")) {
                    //ignored
                } else {
                    mPath = files[i].toString()
                    break
                }
            }
        }
        //directory check : DCIM
        try{
            val file = File(mPath + DCIM)
            if (!file.exists()) {
                file.mkdir()
            }
        } catch (e: IOException) {
            mPath = PATH_INTERNAL
            return false
        }
        //directory check : FOLDER
        try{
            val file = File(mPath + FOLDER)
            if (!file.exists()) {
                file.mkdir()
            }
        } catch (e: IOException) {
            Log.e(TAG, "mkdir error : " + mPath + FOLDER)
            mPath = PATH_INTERNAL
            return false
        }
        mPath += FOLDER
        return true
    }

    private fun getOutputMediaFile(isJPEG: Boolean): File? {
        if (!setFolderPath()) {
            return null
        }
        mFilepath  = mPath + "PC" + SimpleDateFormat("HHmmss").format(Date())
        mFilepath += if(isJPEG) ".JPG" else ".NV21"
        Log.i(TAG, "file path = " + mFilepath)
        return File(mFilepath)
    }

    //
    // camera controlling related functions
    //
    fun openCamera(surface: SurfaceTexture?) {
        Log.i(TAG, "openCamera")
        if (isCameraOpen) {
            return
        }
        //open camera with id setting CAMERA_FACING_DOUBLE directly
        mCamera = Camera.open(this,
            if(isDouble) Camera.CameraInfo.CAMERA_FACING_DOUBLE
            else         Camera.CameraInfo.CAMERA_FACING_FRONT).apply {
            isCameraOpen = true
            if (surface!=null) {
                setPreviewTexture(surface)
                isInitialized = true
            }
        }
    }

    fun closeCamera() {
        //stop recording
        if (isRecording) {
            findViewById<Button>(R.id.button_video).callOnClick()           //stopVideoRecording()
        }
        //stop preview
        if (isPreview && !isCapturing) {
            findViewById<Switch>(R.id.switch_camera).isChecked = false      //executeStopPreview()
        }
        //close camera
        if (isCameraOpen) {
            Log.i(TAG, "closeCamera")
            mCamera?.apply {
                setPreviewCallback(null)
                release()
            }
            mCamera = null
            isCameraOpen = false
        }
    }

    fun executeStartPreview() {
        Log.i(TAG,"startPreview")
        if (isPreview) {
            //return
        }
        mCamera!!.apply {
            setCameraParameters(MODE.PREVIEW)
            startPreview()
        }
        isPreview = true
    }

    fun executeStopPreview() {
        Log.i(TAG,"stopPreview")
        if (!isPreview) {
            //return
        }
        mCamera!!.stopPreview()
        isPreview = false
    }

    fun executeTakePicture() {
        Log.i(TAG,"executeTakePicture")
        //executeStopPreview()
        turnOnOffLcdLed(false)
        notificationAudioSelf()
        mCamera!!.apply {
            setCameraParameters(MODE.IMAGE)
            isCapturing = true
            takePicture(
                shutterCallbackListner,
                { data, _ ->
                    Log.i(TAG,"receive raw callback")
                },
                { data, _ ->
                    Log.i(TAG,"receive postview callback")
                },
                Camera.PictureCallback{ data, _ ->
                    Log.i(TAG,"receive jpeg callback")
                    val pictureFile: File = getOutputMediaFile(isJPEG) ?: run {
                        Log.e(TAG, "cannot create file")
                        return@PictureCallback
                    }
                    try {
                        val fos = FileOutputStream(pictureFile)
                        fos.write(data)
                        fos.close()
                    } catch (e: FileNotFoundException) {
                        Log.e(TAG, e.message)
                    } catch (e: IOException) {
                        Log.e(TAG, e.message)
                    }
                    notificationDatabaseUpdate(mFilepath)

                    isCapturing = false

                    executeStartPreview()

                    //TODO
                    findViewById<Switch>(R.id.switch_camera).isClickable = true
                    enableAllButtons(true)
                }
            )
        }
    }

    val shutterCallbackListner = object: Camera.ShutterCallback {

        override fun onShutter() {
            Log.i(TAG,"receive onShutter")
            if (!isMultiShot) {
                notificationAudioShutter()
                isLongShutter = false
            } else {
                notificationAudioOpen()
                isLongShutter = true
            }
        }
        override fun onLongShutter() {
            Log.i(TAG,"receive onLongShutter")
            notificationAudioOpen()
            isLongShutter = true
        }
        override fun onShutterend() {
            Log.i(TAG,"receive onShutterend")
            if (isLongShutter) {
                notificationAudioClose()
            }
            isLongShutter = false
            turnOnOffLcdLed(true)
        }
    }

    fun startVideoRecording() {
        Log.i(TAG,"startVideoRecording")

        mCamera!!.apply {
            setCameraParameters(MODE.VIDEO)
        }

        mRecorder = MediaRecorder()
        mRecorder?.setOnInfoListener(this)
        mRecorder!!.apply {
            //Context
            setMediaRecorderContext(getApplicationContext())
            //Audio Setting
            setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            //setMicDeviceId(getExternalMicDeviceId())
            //setExternalMicDevVendorName(NULL)
            setMicGain(1)             //microphone gain
            setMicSamplingFormat(MediaRecorder.MEDIA_FORMAT_PCM_I16)
            setAudioChannels(1)
            setAudioSamplingRate(48_000)
            setAudioEncodingBitRate(128_000)
            //Video Setting
            setVideoSource(MediaRecorder.VideoSource.CAMERA)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            //setVideoFrameRate(30)
            //setVideoSize(3840, 1920)
            //setVideoEncodingBitRate(64_000_000)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingProfileLevel(0x7F000001,0x8000)   //BaseLine@L5.1 0x7F000001

            //FYI: use setProfile to set configuration easily
            val shooting_mode: String = findViewById<Spinner>(R.id.spinner_ric_shooting_mode_video).selectedItem.toString()
            when (shooting_mode) {
                "RicMovieRecording1920" -> {
                    setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_2K))
                    setVideoEncodingIFrameInterval(1.0f)
                }
                "RicMovieRecording3840" -> {
                    setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_4K))
                    setVideoEncodingIFrameInterval(1.0f)
                }
                "RicMovieRecording5760" -> {
                    setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_6K))
                    setVideoEncodingIFrameInterval(1.0f)
                }
                "RicMovieRecording7680" -> {
                    setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_8K))   //set 10fps in this API
                    setVideoEncodingIFrameInterval(0.0f)                            //set I-frame only GOP
                }
            }

            //filename
            setFolderPath()
            mFilepath = mPath + "VD" + SimpleDateFormat("HHmmss").format(Date()) + ".MP4"
            Log.i(TAG, "MP4 file path = " + mFilepath)
            setOutputFile(mFilepath)

            try {
                prepare()
            } catch (exception: IllegalStateException) {
                Log.e(TAG,"IllegalStateException preparing MediaRecorder")
                releaseMediaRecorder()
            } catch (exception: IOException) {
                Log.e(TAG,"IOException preparing MediaRecorder")
                releaseMediaRecorder()
            }

            notificationAudioMovStart()
            isRecording = true
            start()
        }
    }

    fun stopVideoRecording() {
        Log.i(TAG,"stopVideoRecording")
        mRecorder!!.apply {
            stop()
            release()
        }
        mRecorder = null
        isRecording = false
        notificationDatabaseUpdate(mFilepath)
    }

    fun releaseMediaRecorder() {
        Log.i(TAG,"releaseMediaRecorder")
        if (mRecorder != null) {
            mRecorder?.setOnInfoListener(null)
            mRecorder!!.release()
            mRecorder = null
        }
    }

    fun setCameraParameters(mode: MODE) {
        val ric_shooting_mode_image: String = findViewById<Spinner>(R.id.spinner_ric_shooting_mode_image).selectedItem.toString()
        val ric_shooting_mode_video: String = findViewById<Spinner>(R.id.spinner_ric_shooting_mode_video).selectedItem.toString()
        val ric_proc_stitching: String      = findViewById<Spinner>(R.id.spinner_ric_proc_stitching).selectedItem.toString()
        val ric_proc_zenith_correction: String = findViewById<Spinner>(R.id.spinner_ric_proc_zenith_correction).selectedItem.toString()
        val picture_format: String             = findViewById<Spinner>(R.id.spinner_picture_format).selectedItem.toString()
        val picture_format_array: Array<String> = resources.getStringArray(R.array.PICTURE_FORMAT)

        val p = mCamera!!.getParameters()
        p.set(RIC_PROC_STITCHING,         ric_proc_stitching)
        p.set(RIC_PROC_ZENITH_CORRECTION, ric_proc_zenith_correction)
        p.set(RIC_EXPOSURE_MODE, "RicAutoExposureP")
        p.set(RIC_FACE_DETECTION, 0)
        p.set(RIC_WATER_HOUSING, 0)

        when (mode) {
            MODE.PREVIEW -> {
                //DOUBLE
                if (isDouble) {
                    val shooting_mode: String =
                        findViewById<Spinner>(R.id.spinner_ric_shooting_mode_preview).selectedItem.toString()
                    p.set(RIC_SHOOTING_MODE, shooting_mode)
                    p.setPreviewFrameRate(if (isLowPowerPreview) 0 else 30)
                    when (shooting_mode) {
                        "RicPreview1024" -> { p.setPreviewSize(1024, 512) }
                        "RicPreview1920" -> { p.setPreviewSize(1920, 960) }
                        "RicPreview3840" -> { p.setPreviewSize(3840, 1920) }
                        "RicPreview5760" -> { p.setPreviewSize(5760, 2880) }
                        "RicPreview1024:576" -> { p.setPreviewSize(1024, 576) }
                        "RicPreview1920:1080" -> { p.setPreviewSize(1920, 1080) }
                        "RicPreview3840:2160" -> { p.setPreviewSize(3840, 2160) }
                        "RicPreview7680" -> {
                            p.setPreviewSize(7680, 3840)
                            p.setPreviewFrameRate(10)
                        }
                    }
                }
                //SINGLE
                else {
                    p.set(RIC_SHOOTING_MODE, "RicPreviewFront")
                    p.setPreviewFrameRate(if (isLowPowerPreview) 0 else 30)
                    p.setPreviewSize(2752, 2752)
                }
            }
            MODE.IMAGE -> {
                p.set(RIC_SHOOTING_MODE, ric_shooting_mode_image)
                isMultiShot = if(ric_shooting_mode_image.equals("RicStillCaptureStd")) false else true
                p.setPictureSize(11008, 5504)   //11008*5504 or 5504*2752
                if (picture_format == picture_format_array[0]) { //JPEG or NV21
                    p.pictureFormat = ImageFormat.JPEG
                    isJPEG = true
                }
                else {
                    p.pictureFormat = ImageFormat.NV21
                    isJPEG = false
                }
            }
            MODE.VIDEO -> {
                p.set(RIC_SHOOTING_MODE, ric_shooting_mode_video)
                p.set(Camera.Parameters.VIDEO_PREVIEW_SWITCH, 0)
            }
        }

        //mCamera!!.setParameters(p)
        mCamera!!.setBrightnessMode(1) //Auto Control LCD/LED Brightness

        //if you want to put the location data, use setGPSxxxx APIs.
        if (mLocationManager.check()) {
            p.setGpsLatitude (mLocationManager.getLat())
            p.setGpsLongitude(mLocationManager.getLng())
            p.setGpsAltitude (mLocationManager.getAlt())
            p.setGpsTimestamp(mLocationManager.getGpsTime())    //set UTC seconds since 1970
        }
        else {
            p.removeGpsData()
        }
        mCamera!!.setParameters(p)
    }

    //
    // UI related functions
    //
    fun enableAllSpinners(flag: Boolean) {
        findViewById<Spinner>(R.id.spinner_ric_shooting_mode_preview).isEnabled = flag
        findViewById<Spinner>(R.id.spinner_ric_shooting_mode_image).isEnabled = flag
        findViewById<Spinner>(R.id.spinner_ric_shooting_mode_video).isEnabled = flag
        findViewById<Spinner>(R.id.spinner_ric_proc_stitching).isEnabled = flag
        findViewById<Spinner>(R.id.spinner_ric_proc_zenith_correction).isEnabled = flag
        findViewById<Spinner>(R.id.spinner_picture_format).isEnabled = if (isNV21Available) flag else false
    }

    fun enableButton(button: Button, flag: Boolean) {
        button.isEnabled = flag
        button.setBackgroundColor(getColor(if (flag) R.color.button_color_enabled else R.color.button_color_disabled))
    }

    fun enableAllButtons(flag: Boolean) {
        val button_image = findViewById<Button>(R.id.button_image)
        val button_video = findViewById<Button>(R.id.button_video)
        enableButton(button_image, flag)
        enableButton(button_video, flag)
    }

    fun turnOnOffLcdLed(flag: Boolean) {
        //turn on LCD and LED
        if (flag) {
            mCamera!!.apply {
                ctrlLcdBrightness(mLcdBrightness)
                for (i in 1..3) {
                    ctrlLedPowerBrightness(i, mLedPowerBrightness[i])
                    ctrlLedStatusBrightness(i, mLedStatusBrightness[i])
                }
            }
        }
        //turn off LCD and LED
        else {
            mCamera!!.apply{
                mLcdBrightness = lcdBrightness
                ctrlLcdBrightness(0)
                for (i in 1..3) {
                    mLedPowerBrightness[i] = getLedPowerBrightness(i)
                    mLedStatusBrightness[i] = getLedStatusBrightness(i)
                    ctrlLedPowerBrightness(i, 0)
                    ctrlLedStatusBrightness(i, 0)
                }
            }
        }
    }
}
