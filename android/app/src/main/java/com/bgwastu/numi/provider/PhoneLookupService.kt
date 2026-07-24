package com.bgwastu.numi.provider

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Runs all available lookup providers in parallel for a single phone number. */
class PhoneLookupService(private val providers: List<PhoneLookupProvider>) {

    companion object {
        fun create(context: Context): PhoneLookupService {
            return PhoneLookupService(listOf(GetContactLookupProvider(context)))
        }
    }

    /** Run all available providers. Returns successes and provider errors separately. */
    suspend fun lookup(phoneNumber: String): PhoneLookupBatch = coroutineScope {
        val available = providers.filter { it.isAvailable() }
        if (available.isEmpty()) {
            return@coroutineScope PhoneLookupBatch(emptyList(), emptyList())
        }

        val outcomes = available.map { provider ->
            async {
                provider.name to provider.lookup(phoneNumber)
            }
        }.awaitAll()

        val results = mutableListOf<PhoneLookupResult>()
        val errors = mutableListOf<PhoneLookupError>()
        for ((name, outcome) in outcomes) {
            outcome.fold(
                onSuccess = { results += it },
                onFailure = { err ->
                    val pe = err as? PhoneLookupException
                    errors += PhoneLookupError(
                        provider = name,
                        message = err.message ?: "Lookup failed",
                        errorCode = pe?.errorCode,
                        captchaImageBase64 = pe?.captchaImageBase64
                    )
                }
            )
        }
        PhoneLookupBatch(
            results = results.sortedBy { it.provider },
            errors = errors.sortedBy { it.provider }
        )
    }
}
