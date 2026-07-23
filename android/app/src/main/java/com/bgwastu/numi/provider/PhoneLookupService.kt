package com.bgwastu.numi.provider

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Runs all available lookup providers in parallel for a single phone number. */
class PhoneLookupService(private val providers: List<PhoneLookupProvider>) {

    companion object {
        fun create(getContact: GetContactClient): PhoneLookupService {
            val gc = object : PhoneLookupProvider {
                override val name = "GetContact"
                override fun isAvailable() = getContact.isAvailable()
                override suspend fun lookup(phoneNumber: String): Result<PhoneLookupResult> {
                    return getContact.performLookup(phoneNumber)
                }
            }
            return PhoneLookupService(listOf(gc))
        }
    }

    /** Run all available providers. Returns results sorted by provider name. */
    suspend fun lookup(phoneNumber: String): List<PhoneLookupResult> = coroutineScope {
        providers
            .filter { it.isAvailable() }
            .map { provider ->
                async { provider.lookup(phoneNumber) }
            }
            .awaitAll()
            .mapNotNull { it.getOrNull() }
            .sortedBy { it.provider }
    }
}
