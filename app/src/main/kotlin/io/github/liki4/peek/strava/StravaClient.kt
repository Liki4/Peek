package io.github.liki4.peek.strava

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal Strava REST client — refresh-token grant + FIT activity upload.
 *
 * Why no full OAuth dance: the user already mints their own
 * client_id/client_secret/refresh_token via the existing `export_fit/`
 * pipeline. Pasting them into settings is much simpler than wiring an
 * in-app browser tab + deep-link callback for a single-user app.
 *
 * Endpoints (per developers.strava.com):
 *  - POST /oauth/token  (refresh grant)
 *  - POST /uploads      (multipart, returns upload id)
 *  - GET  /uploads/{id} (poll for activity_id)
 */
class StravaClient(
    private val http: OkHttpClient = defaultHttp(),
) {

    /** Result of refresh-token exchange.
     *
     * @property newRefreshToken Strava rotates the refresh_token on every
     *   refresh call. The caller MUST persist this value; the old token is
     *   invalidated after this call. May be the same as the old token in
     *   some cases (Strava only rotates periodically), but always capture it.
     */
    data class AccessToken(
        val accessToken: String,
        val expiresAtUnixS: Long,
        val newRefreshToken: String,
    )

    /** A finished upload that resolved to an activity. */
    data class UploadResult(
        val activityId: Long,
    )

    /**
     * @throws StravaException on any HTTP/JSON error.
     */
    suspend fun refresh(
        clientId: String,
        clientSecret: String,
        refreshToken: String,
    ): AccessToken = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val req = Request.Builder()
            .url("https://www.strava.com/api/v3/oauth/token")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw StravaException("oauth/token ${resp.code}: $text")
            val json = JSONObject(text)
            AccessToken(
                accessToken = json.getString("access_token"),
                expiresAtUnixS = json.getLong("expires_at"),
                newRefreshToken = json.getString("refresh_token"),
            )
        }
    }

    /**
     * Upload a FIT file and poll until Strava finishes processing it.
     *
     * Strava's /uploads is async — POST returns an upload id, then you GET
     * /uploads/{id} until status flips from "Your activity is still being
     * processed." to either an `activity_id` or an `error`. We give it 30s.
     */
    suspend fun uploadFit(
        accessToken: String,
        fitFile: File,
        externalId: String = fitFile.nameWithoutExtension,
    ): UploadResult = withContext(Dispatchers.IO) {
        // ---- POST /uploads ----
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("data_type", "fit")
            .addFormDataPart("name", externalId)
            .addFormDataPart("external_id", externalId)
            .addFormDataPart(
                "file", fitFile.name,
                fitFile.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val postReq = Request.Builder()
            .url("https://www.strava.com/api/v3/uploads")
            .header("Authorization", "Bearer $accessToken")
            .post(multipart)
            .build()
        val uploadId = http.newCall(postReq).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw StravaException("uploads ${resp.code}: $text")
            JSONObject(text).getLong("id")
        }

        // ---- poll /uploads/{id} ----
        repeat(15) { attempt ->
            delay(2000L)
            val getReq = Request.Builder()
                .url("https://www.strava.com/api/v3/uploads/$uploadId")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            http.newCall(getReq).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw StravaException("uploads/$uploadId ${resp.code}: $text")
                val json = JSONObject(text)
                val error = json.optString("error", "").takeIf { it.isNotEmpty() && it != "null" }
                val activityId = json.optLong("activity_id", 0L).takeIf { it > 0 }
                when {
                    error != null -> throw StravaException("Strava rejected upload: $error")
                    activityId != null -> return@withContext UploadResult(activityId)
                    // else: still processing → poll again
                }
            }
        }
        throw StravaException("Strava upload still processing after 30s; check the website")
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

class StravaException(message: String) : RuntimeException(message)
