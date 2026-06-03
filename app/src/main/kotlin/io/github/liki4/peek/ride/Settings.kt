package io.github.liki4.peek.ride

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.peekDataStore: DataStore<Preferences> by preferencesDataStore("peek_settings")

/**
 * Persistent user preferences.
 *
 * - `userId`: 24-char Keep ObjectId (used in PUT 106/3 UserInfo auth).
 *   The bike doesn't validate it — any string works — but storing a real id
 *   keeps server-side reconciliation straightforward if/when we add cloud sync.
 * - `weightKg`: defaults to 70 if unset. Embedded in UserInfo auth.
 * - `deviceId`: 16-char hex; auto-generated on first launch.
 * - `lastBikeAddr` / `lastHrAddr`: for one-tap reconnect.
 */
class Settings(private val context: Context) {

    object Keys {
        val USER_ID         = stringPreferencesKey("user_id")
        val WEIGHT_KG       = floatPreferencesKey("weight_kg")
        val MAX_HR_BPM      = intPreferencesKey("max_hr_bpm")
        val DEVICE_ID       = stringPreferencesKey("device_id")
        val LAST_BIKE_ADDR  = stringPreferencesKey("last_bike_addr")
        val LAST_HR_ADDR    = stringPreferencesKey("last_hr_addr")
        // Strava OAuth — user pre-mints these via the export_fit/ workflow.
        val STRAVA_CLIENT_ID     = stringPreferencesKey("strava_client_id")
        val STRAVA_CLIENT_SECRET = stringPreferencesKey("strava_client_secret")
        val STRAVA_REFRESH_TOKEN = stringPreferencesKey("strava_refresh_token")
    }

    val userId:       Flow<String?> = context.peekDataStore.data.map { it[Keys.USER_ID] }
    val weightKg:     Flow<Float>   = context.peekDataStore.data.map { it[Keys.WEIGHT_KG] ?: 70.0f }
    /** Personal max HR for zone calc — defaults to the 220-30 estimate. */
    val maxHrBpm:     Flow<Int>     = context.peekDataStore.data.map { it[Keys.MAX_HR_BPM] ?: DEFAULT_MAX_HR }
    val deviceId:     Flow<String?> = context.peekDataStore.data.map { it[Keys.DEVICE_ID] }
    val lastBikeAddr: Flow<String?> = context.peekDataStore.data.map { it[Keys.LAST_BIKE_ADDR] }
    val lastHrAddr:   Flow<String?> = context.peekDataStore.data.map { it[Keys.LAST_HR_ADDR] }
    val stravaClientId:     Flow<String?> = context.peekDataStore.data.map { it[Keys.STRAVA_CLIENT_ID] }
    val stravaClientSecret: Flow<String?> = context.peekDataStore.data.map { it[Keys.STRAVA_CLIENT_SECRET] }
    val stravaRefreshToken: Flow<String?> = context.peekDataStore.data.map { it[Keys.STRAVA_REFRESH_TOKEN] }

    suspend fun setUserId(v: String)        = context.peekDataStore.edit { it[Keys.USER_ID]        = v }
    suspend fun setWeightKg(v: Float)       = context.peekDataStore.edit { it[Keys.WEIGHT_KG]      = v }
    suspend fun setMaxHrBpm(v: Int)         = context.peekDataStore.edit { it[Keys.MAX_HR_BPM]     = v }
    suspend fun setLastBikeAddr(v: String)  = context.peekDataStore.edit { it[Keys.LAST_BIKE_ADDR] = v }
    suspend fun setLastHrAddr(v: String)    = context.peekDataStore.edit { it[Keys.LAST_HR_ADDR]   = v }
    suspend fun setStravaClientId(v: String)     = context.peekDataStore.edit { it[Keys.STRAVA_CLIENT_ID]     = v }
    suspend fun setStravaClientSecret(v: String) = context.peekDataStore.edit { it[Keys.STRAVA_CLIENT_SECRET] = v }
    suspend fun setStravaRefreshToken(v: String) = context.peekDataStore.edit { it[Keys.STRAVA_REFRESH_TOKEN] = v }

    companion object {
        const val DEFAULT_MAX_HR = 190
    }

    /** Returns true iff all three Strava credentials are set (and non-blank). */
    suspend fun hasStravaCredentials(): Boolean {
        val prefs = context.peekDataStore.data.first()
        val id = prefs[Keys.STRAVA_CLIENT_ID].orEmpty()
        val sec = prefs[Keys.STRAVA_CLIENT_SECRET].orEmpty()
        val ref = prefs[Keys.STRAVA_REFRESH_TOKEN].orEmpty()
        return id.isNotBlank() && sec.isNotBlank() && ref.isNotBlank()
    }

    /** Read [Keys.DEVICE_ID], generating one if it's not set yet. Returns the value either way. */
    suspend fun ensureDeviceId(): String {
        val updated = context.peekDataStore.edit { prefs ->
            if (prefs[Keys.DEVICE_ID].isNullOrEmpty()) {
                prefs[Keys.DEVICE_ID] = UUID.randomUUID().toString().replace("-", "").take(16)
            }
        }
        return updated[Keys.DEVICE_ID]!!
    }
}
