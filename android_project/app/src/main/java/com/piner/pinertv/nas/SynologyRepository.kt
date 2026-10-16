package com.piner.pinertv.nas

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class SynologyRepository(private val baseUrl: String) {

    private val api: SynologyApi
    private var sid: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE })
        .build()

    init {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        api = Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SynologyApi::class.java)
    }

    fun getSid(): String? = sid
    fun setSid(value: String?) { sid = value }
    fun getBaseUrl(): String = baseUrl.trimEnd('/')

    suspend fun login(user: String, pass: String): Boolean {
        return try {
            val response = api.login(params = mapOf("account" to user, "passwd" to pass))
            if (response.isSuccessful && response.body()?.success == true) {
                sid = response.body()?.data?.sid
                Log.d(TAG, "Login OK")
                true
            } else {
                val code = response.body()?.error?.code
                Log.e(TAG, "Login failed HTTP=${response.code()} SynoErr=$code")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login exception", e)
            false
        }
    }

    /** Re-login if SID missing or known expired (119). */
    suspend fun ensureSession(user: String, pass: String): Boolean {
        if (sid != null) return true
        return login(user, pass)
    }

    suspend fun listShares(): List<ShareItem>? {
        if (sid == null) return null
        return try {
            val response = api.listShares(sid = sid!!)
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.shares
            } else {
                if (response.body()?.error?.code == 119) sid = null
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "listShares", e)
            null
        }
    }

    suspend fun listFiles(path: String, maxRetries: Int = 3): List<FileItem>? {
        if (sid == null) return null
        val pageSize = 200
        repeat(maxRetries) { attempt ->
            try {
                val all = mutableListOf<FileItem>()
                var offset = 0
                while (true) {
                    val response = api.listFiles(
                        folderPath = path,
                        limit = pageSize,
                        offset = offset,
                        sid = sid!!
                    )
                    if (!response.isSuccessful || response.body()?.success != true) {
                        if (response.body()?.error?.code == 119) sid = null
                        break
                    }
                    val data = response.body()?.data ?: break
                    all.addAll(data.files)
                    offset += data.files.size
                    if (data.files.isEmpty() || offset >= data.total || data.files.size < pageSize) {
                        return all
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "listFiles attempt=${attempt + 1} path=$path", e)
            }
            if (attempt < maxRetries - 1) {
                kotlinx.coroutines.delay(1500L * (attempt + 1))
            }
        }
        return null
    }

    fun getDownloadUrl(path: String): String {
        return android.net.Uri.parse(getBaseUrl()).buildUpon()
            .appendEncodedPath("webapi/entry.cgi")
            .appendQueryParameter("api", "SYNO.FileStation.Download")
            .appendQueryParameter("version", "2")
            .appendQueryParameter("method", "download")
            .appendQueryParameter("path", path)
            .appendQueryParameter("mode", "open")
            .appendQueryParameter("_sid", sid)
            .build()
            .toString()
    }

    companion object {
        private const val TAG = "PinerTV-Syno"
    }
}
