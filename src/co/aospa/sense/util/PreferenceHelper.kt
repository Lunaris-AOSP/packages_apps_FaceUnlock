package co.aospa.sense.util

import android.annotation.SuppressLint
import android.content.Context
import android.preference.PreferenceManager
import android.text.TextUtils
import android.util.Base64

class PreferenceHelper(context: Context) {

    private val mContext: Context

    @SuppressLint("ApplySharedPref")
    fun saveIntValue(key: String?, value: Int) {
        val edit = PreferenceManager.getDefaultSharedPreferences(mContext).edit()
        edit.putInt(key, value)
        edit.commit()
    }

    @SuppressLint("ApplySharedPref")
    fun saveBooleanValue(key: String?, value: Boolean) {
        val edit = PreferenceManager.getDefaultSharedPreferences(mContext).edit()
        edit.putBoolean(key, value)
        edit.commit()
    }

    @SuppressLint("ApplySharedPref")
    fun removeSharePreferences(value: String?) {
        val edit = PreferenceManager.getDefaultSharedPreferences(mContext).edit()
        edit.remove(value)
        edit.commit()
    }

    fun getIntValueByKey(key: String?, value: Int): Int {
        return PreferenceManager.getDefaultSharedPreferences(mContext).getInt(key, value)
    }

    fun getIntValueByKey(key: String?): Int {
        return getIntValueByKey(key, -1)
    }

    @SuppressLint("ApplySharedPref")
    fun saveStringValue(key: String?, value: String?) {
        val edit = PreferenceManager.getDefaultSharedPreferences(mContext).edit()
        edit.putString(key, value)
        edit.commit()
    }

    fun getStringValueByKey(key: String?): String? {
        return PreferenceManager.getDefaultSharedPreferences(mContext).getString(key, null)
    }

    @SuppressLint("ApplySharedPref")
    fun saveByteArrayValue(key: String?, value: ByteArray?) {
        val bytes = value ?: ByteArray(0)
        val edit = PreferenceManager.getDefaultSharedPreferences(mContext).edit()
        // String(bytes) mangles non-UTF-8 bytes, which corrupts the enrollment token.
        edit.putString(
            key,
            BASE64_PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)
        )
        edit.commit()
    }

    fun getByteArrayValueByKey(key: String?): ByteArray? {
        val string = PreferenceManager.getDefaultSharedPreferences(mContext).getString(key, null)
        if (TextUtils.isEmpty(string)) return null
        return if (string!!.startsWith(BASE64_PREFIX)) {
            Base64.decode(string.substring(BASE64_PREFIX.length), Base64.NO_WRAP)
        } else {
            // Tokens written by older versions were stored as a raw String.
            string.toByteArray()
        }
    }

    fun getBooleanValueByKey(key: String?): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean(key, false)
    }

    init {
        mContext = context.applicationContext
    }

    companion object {
        private const val BASE64_PREFIX = "b64:"
    }
}
