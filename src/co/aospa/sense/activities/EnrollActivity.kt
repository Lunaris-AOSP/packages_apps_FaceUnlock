package co.aospa.sense.activities

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.Camera
import android.hardware.face.FaceManager
import android.media.AudioAttributes
import android.os.*
import android.text.TextUtils
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import co.aospa.sense.R
import co.aospa.sense.controller.FaceEnrollController
import co.aospa.sense.controller.FaceEnrollController.CameraCallback
import co.aospa.sense.util.Constants
import co.aospa.sense.util.PreferenceHelper
import co.aospa.sense.util.Util
import co.aospa.sense.view.CircleSurfaceView
import co.aospa.sense.view.FaceUnlockAnimationView
import com.google.android.setupdesign.GlifLayout
import com.google.android.setupdesign.util.ThemeHelper

open class EnrollActivity : FaceBaseActivity() {

    private var mEnrollController: FaceEnrollController? = null
    private var mEnrollmentCancel: CancellationSignal? = null
    private var mFaceManager: FaceManager? = null
    private var mPreferenceHelper: PreferenceHelper? = null
    private var mSurfaceView: CircleSurfaceView? = null
    private var mAnimationView: FaceUnlockAnimationView? = null
    private var mEnrollVendorMessage: TextView? = null
    private var mProgress = 0.0f
    private var mHasCameraPermission = false
    private var mIsActivityPaused = false
    private var mIsFaceDetected = false

    private val mCameraCallback: CameraCallback = object : CameraCallback {
        override fun handleSaveFeature(data: ByteArray?, width: Int, height: Int, angle: Int): Int {
            return -1
        }

        override fun setDetectArea(size: Camera.Size?) {}
        override fun handleSaveFeatureResult(result: Int) {
            runOnUiThread {
                var errorString = 0
                when (result) {
                    Constants.MSG_UNLOCK_FACE_SCALE_TOO_SMALL -> errorString =
                        R.string.unlock_failed_face_small
                    Constants.MSG_UNLOCK_FACE_SCALE_TOO_LARGE -> errorString =
                        R.string.unlock_failed_face_large
                    Constants.MSG_UNLOCK_FACE_OFFSET_LEFT,
                    Constants.MSG_UNLOCK_FACE_OFFSET_RIGHT,
                    Constants.MSG_UNLOCK_FACE_ROTATED_LEFT,
                    Constants.MSG_UNLOCK_FACE_ROTATED_RIGHT -> {
                        mProgress = (mProgress + 10.0f).coerceAtMost(100.0f)
                        mEnrollVendorMessage?.text = ""
                    }
                    Constants.MSG_UNLOCK_KEEP -> {
                        mProgress = (mProgress + 10.0f).coerceAtMost(100.0f)
                        mIsFaceDetected = true
                        mEnrollVendorMessage?.text = ""
                    }
                    Constants.MSG_UNLOCK_FACE_MULTI -> errorString =
                        R.string.unlock_failed_face_multi
                    Constants.MSG_UNLOCK_FACE_BLUR -> errorString = R.string.unlock_failed_face_blur
                    Constants.MSG_UNLOCK_FACE_NOT_COMPLETE -> errorString =
                        R.string.unlock_failed_face_not_complete
                    Constants.MSG_UNLOCK_DARKLIGHT -> errorString = R.string.attr_light_dark
                    Constants.MSG_UNLOCK_HIGHLIGHT -> errorString = R.string.attr_light_high
                    Constants.MSG_UNLOCK_HALF_SHADOW -> errorString = R.string.attr_light_shadow
                }
                if (errorString != 0) {
                    mEnrollVendorMessage?.setText(errorString)
                } else if (mIsFaceDetected && mProgress < 100.0f) {
                    // The vendor needs multiple captures; only surface the first 60% here.
                    mAnimationView?.setProgress(minOf(mProgress, 60.0f))
                }
            }
        }

        override fun onTimeout() {
            if (!isFinishing && !isDestroyed) {
                if (Util.isFaceUnlockAvailable(this@EnrollActivity)) {
                    startFinishActivity()
                    return
                }
                retryToEnroll()
            }
        }

        override fun onCameraError() {
            if (!isFinishing && !isDestroyed) {
                retryToEnroll()
            }
        }

        override fun onFaceDetected() {
            mIsFaceDetected = true
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(CLICK_VIBRATION, SONIFICATION)
        }
    }

    private val mEnrollmentCallback: FaceManager.EnrollmentCallback =
        object : FaceManager.EnrollmentCallback() {
            override fun onEnrollmentProgress(progress: Int) {
                if (progress == 0) {
                    runOnUiThread {
                        mProgress = 100.0f
                        mAnimationView?.setSuccess()
                        mEnrollVendorMessage?.text = ""
                        val activity = this@EnrollActivity
                        val view = activity.mAnimationView
                        if (view != null && !activity.isDestroyed) {
                            view.postDelayed({
                                if (!activity.isDestroyed && !activity.isFinishing) {
                                    activity.startFinishActivity()
                                }
                            }, SUCCESS_DELAY_MS)
                        } else {
                            activity.startFinishActivity()
                        }
                    }
                }
            }

            override fun onEnrollmentHelp(helpMessageId: Int, charSequence: CharSequence) {
                runOnUiThread {
                    if (!TextUtils.isEmpty(charSequence)) {
                        mEnrollVendorMessage?.text = charSequence
                    }
                }
            }

            override fun onEnrollmentError(errorMessageId: Int, charSequence: CharSequence) {
                retryToEnroll(clearResult = errorMessageId == Constants.MSG_UNLOCK_FAILED)
            }
        }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)

        setTheme(ThemeHelper.getSuwDefaultTheme(applicationContext))
        ThemeHelper.trySetDynamicColor(this)

        mPreferenceHelper = PreferenceHelper(this)
        mHasCameraPermission = false
        mFaceManager = getSystemService(FaceManager::class.java)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != 0) {
            val shouldShowRequestPermissionRationale =
                ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.CAMERA
                )
            if (Util.IS_DEBUG_LOGGING) Log.i(
                TAG,
                "shouldShowRequestPermissionRationale: $shouldShowRequestPermissionRationale"
            )
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
            return
        }
        synchronized(this) {
            mHasCameraPermission = true
            if (Util.IS_DEBUG_LOGGING) Log.i(TAG, "hasCameraPermission: > M")
        }
    }

    override fun onApplyThemeResource(theme: Resources.Theme, resid: Int, first: Boolean) {
        theme.applyStyle(R.style.SetupWizardPartnerResource, true)
        super.onApplyThemeResource(theme, resid, first)
    }

    private fun init() {
        setContentView(R.layout.face_enroll)
        setHeaderText(R.string.face_enroll_title)
        getLayout().setDescriptionText(R.string.face_enroll_description)
        mSurfaceView = findViewById(R.id.camera_surface)
        mAnimationView = findViewById(R.id.face_animation)
        mEnrollVendorMessage = findViewById(R.id.face_vendor_message)
        mProgress = 0.0f
        mIsFaceDetected = false
        mAnimationView?.reset()
        mAnimationView?.startScanning()

        if (mEnrollController == null) {
            mEnrollController = FaceEnrollController.instance
            mEnrollController?.setSurfaceHolder(mSurfaceView?.holder)
        }
        // A CancellationSignal cannot be reused after cancel(), so create a fresh one on
        // every (re)start; otherwise enroll() silently fails after a configuration change.
        mEnrollmentCancel?.cancel()
        mEnrollmentCancel = CancellationSignal()
        if (mToken != null && mToken!!.isNotEmpty()) {
            mFaceManager?.enroll(
                Util.getUserId(this),
                mToken,
                mEnrollmentCancel,
                mEnrollmentCallback,
                intArrayOf(1)
            )
        }
        mEnrollController!!.start(mCameraCallback, 15000)
    }

    override fun getLayout(): GlifLayout {
        return findViewById(R.id.face_enroll)
            ?: throw IllegalStateException("Missing required GlifLayout view with id 'face_enroll'")
    }

    override fun onDestroy() {
        if (Util.IS_DEBUG_LOGGING) Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        mIsActivityPaused = true
        mAnimationView?.stopScanning()
        if (mEnrollController != null) {
            mEnrollController!!.setSurfaceHolder(null)
            mEnrollController!!.stop(mCameraCallback)
            mEnrollController = null
        }
        mEnrollmentCancel?.cancel()
        mEnrollmentCancel = null
    }

    override fun onResume() {
        super.onResume()
        mIsActivityPaused = false
        synchronized(this) {
            if (mHasCameraPermission) {
                if (Util.IS_DEBUG_LOGGING) Log.i(TAG, "onResume")
                init()
            }
        }
    }

    /** Brief error animation, then hand back to the retry screen. */
    private fun retryToEnroll(clearResult: Boolean = false) {
        runOnUiThread {
            if (mIsActivityPaused || isFinishing || isDestroyed) return@runOnUiThread
            if (clearResult) setResult(-1)
            mAnimationView?.setError()
            val navigate = Runnable {
                if (isFinishing || isDestroyed) return@Runnable
                val intent = Intent()
                intent.setClass(this@EnrollActivity, TryAgainActivity::class.java)
                parseIntent(intent)
                startActivity(intent)
                finish()
            }
            val view = mAnimationView
            if (view != null) {
                view.postDelayed(navigate, ERROR_DELAY_MS)
            } else {
                navigate.run()
            }
        }
    }

    private fun startFinishActivity() {
        val intent = Intent()
        if (mToken != null) {
            intent.putExtra(Constants.EXTRA_KEY_CHALLENGE_TOKEN, mToken)
        }
        if (mUserId != UserHandle.USER_NULL) {
            intent.putExtra(Intent.EXTRA_USER_ID, mUserId)
        }
        val enrollFinish = ComponentName.unflattenFromString(
            "com.android.settings/com.android.settings.biometrics.face.FaceEnrollFinish"
        )
        intent.component = enrollFinish
        startActivityForResult(intent, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            setResult(resultCode)
            finish()
        } else if (requestCode == 2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != 0) {
                if (Util.IS_DEBUG_LOGGING) Log.i(TAG, "REQUEST_CAMERA finish")
                finish()
                return
            }
            if (Util.IS_DEBUG_LOGGING) Log.i(TAG, "REQUEST_CAMERA init")
            synchronized(this) {
                if (Util.IS_DEBUG_LOGGING) Log.i(TAG, "hasCameraPermission: REQUEST_CAMERA")
                mHasCameraPermission = true
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == 0) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                synchronized(this) { mHasCameraPermission = true }
            } else {
                Toast.makeText(this, R.string.face_camera_permission_required, Toast.LENGTH_SHORT)
                    .show()
                finish()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        private val TAG = EnrollActivity::class.java.simpleName
        private val CLICK_VIBRATION: VibrationEffect = VibrationEffect.get(0)
        private val SONIFICATION = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()
        private const val SUCCESS_DELAY_MS = 1600L
        private const val ERROR_DELAY_MS = 650L
    }
}
