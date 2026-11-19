package com.ercan.heybe.core

/** GitHub sürüm notundan sürüm bilgisini okur ("versionCode=123", "versionName=0.7.123"). */
data class ReleaseInfo(val versionCode: Long, val versionName: String) {
    fun isNewerThan(installed: Long) = versionCode > installed

    companion object {
        fun parse(body: String?): ReleaseInfo? {
            val text = body ?: return null
            val code = Regex("versionCode\\s*=\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val name = Regex("versionName\\s*=\\s*([\\w.\\-]+)").find(text)?.groupValues?.get(1) ?: code.toString()
            return ReleaseInfo(code, name)
        }
    }
}
