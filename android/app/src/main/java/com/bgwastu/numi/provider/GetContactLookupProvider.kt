package com.bgwastu.numi.provider

import android.content.Context
import android.util.Log

/**
 * Adapts the existing GetContactClient to the PhoneLookupProvider interface.
 * Truecaller will get its own adapter next to this one.
 */
class GetContactLookupProvider(private val context: Context) : PhoneLookupProvider {

    override val name = "GetContact"

    private val client by lazy { GetContactClient(context) }

    override fun isAvailable(): Boolean = client.isAvailable()

    override suspend fun lookup(phoneNumber: String): Result<PhoneLookupResult> {
        return try {
            when (val result = client.performLookup(phoneNumber)) {
                is GetContactResult.Success -> Result.success(
                    PhoneLookupResult(
                        provider = name,
                        tags = result.tags.map { tag ->
                            PhoneLookupTag(
                                name = tag.name,
                                tag = tag.tag,
                                category = tag.category,
                                count = tag.count
                            )
                        },
                        displayNumber = result.displayNumber,
                        displayName = result.displayName,
                        profileImageUrl = result.profileImageUrl
                    )
                )
                is GetContactResult.Error -> Result.failure(
                    RuntimeException("GetContact: ${result.message}")
                )
            }
        } catch (e: Exception) {
            Log.e("GetContactProvider", "Lookup failed", e)
            Result.failure(e)
        }
    }
}
