package com.piner.pinertv.nas

import com.google.gson.annotations.SerializedName

data class SynoResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: SynoError?
)

data class SynoError(val code: Int)

data class AuthData(val sid: String)

data class FileList(
    val total: Int,
    val offset: Int,
    val files: List<FileItem>
)

data class FileItem(
    val path: String,
    val name: String,
    val isdir: Boolean,
    @SerializedName("additional") val additional: FileAdditional? = null
)

data class FileAdditional(
    val size: Long?,
    val time: FileTime?
)

data class FileTime(val mtime: Long)

data class ShareList(val shares: List<ShareItem>)

data class ShareItem(
    val path: String,
    val name: String,
    val isdir: Boolean
)
