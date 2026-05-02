package xyz.libravault.core.licensing

import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The ONLY network-touching component in the app.
 *
 * Used exclusively by the recovery flow when a user has lost their key
 * and presents a BIP39 recovery phrase. The reader/player never touches
 * this service.
 *
 * Server contract:
 *   POST /recover   { "recovery_phrase": "word1 … word12" }
 *   200 OK          { "license_key": "<base32 key>" }
 *   400             { "error": "Invalid recovery phrase" }
 *
 * The server stores only sha256(recovery_phrase) → license_key.
 * No user identity, no device data is ever sent.
 */
interface RecoveryService {
    @POST("recover")
    suspend fun recover(@Body request: RecoveryRequest): RecoveryResponse
}

@JsonClass(generateAdapter = true)
data class RecoveryRequest(val recovery_phrase: String)

@JsonClass(generateAdapter = true)
data class RecoveryResponse(val license_key: String)
