package com.bgwastu.numi.provider

/** A single tag returned by any phone lookup provider. */
data class PhoneLookupTag(
    val name: String,
    val tag: String,
    val category: String = "",
    val count: Int = 0
)

/** Normalized result from one provider. */
data class PhoneLookupResult(
    val provider: String,
    val tags: List<PhoneLookupTag>,
    val displayNumber: String,
    val displayName: String? = null,
    val profileImageUrl: String? = null
)

data class PhoneLookupError(
    val provider: String,
    val message: String,
    val errorCode: String? = null,
    val captchaImageBase64: String? = null
)

data class PhoneLookupBatch(
    val results: List<PhoneLookupResult>,
    val errors: List<PhoneLookupError>
) {
    val isEmpty: Boolean get() = results.isEmpty() && errors.isEmpty()
}

class PhoneLookupException(
    message: String,
    val errorCode: String? = null,
    val captchaImageBase64: String? = null
) : RuntimeException(message)

/** Every lookup provider implements this. Drop in Truecaller next to GetContact. */
interface PhoneLookupProvider {
    val name: String
    suspend fun lookup(phoneNumber: String): Result<PhoneLookupResult>
    fun isAvailable(): Boolean
}
