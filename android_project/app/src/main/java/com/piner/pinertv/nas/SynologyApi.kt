package com.piner.pinertv.nas

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface SynologyApi {

    @GET("webapi/auth.cgi")
    suspend fun login(
        @Query("api") api: String = "SYNO.API.Auth",
        @Query("version") version: Int = 3,
        @Query("method") method: String = "login",
        @Query("session") session: String = "FileStation",
        @Query("format") format: String = "sid",
        @QueryMap params: Map<String, String>
    ): Response<SynoResponse<AuthData>>

    @GET("webapi/entry.cgi")
    suspend fun listShares(
        @Query("api") api: String = "SYNO.FileStation.List",
        @Query("version") version: Int = 2,
        @Query("method") method: String = "list_share",
        @Query("_sid") sid: String
    ): Response<SynoResponse<ShareList>>

    @GET("webapi/entry.cgi")
    suspend fun listFiles(
        @Query("api") api: String = "SYNO.FileStation.List",
        @Query("version") version: Int = 2,
        @Query("method") method: String = "list",
        @Query("folder_path") folderPath: String,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("filetype") filetype: String = "all",
        @Query("sort_by") sortBy: String = "name",
        @Query("additional") additional: String = "[\"time\",\"size\"]",
        @Query("_sid") sid: String
    ): Response<SynoResponse<FileList>>
}
