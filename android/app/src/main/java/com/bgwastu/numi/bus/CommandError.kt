package com.bgwastu.numi.bus

/** Errors returned directly by the command handler, never as a raw stack trace. */
enum class CommandError(val message: String) {
    /** Caller is not the configured target chat. */
    NOT_AUTHORIZED("This bot is configured for one owner only."),
    /** Required permissions (e.g. SEND_SMS) are not granted on the device. */
    MISSING_PERMISSION("Numi is not fully configured on the device. Grant SMS permissions and try again."),
    /** The stored confirmation request has expired or was never known. */
    INVALID_OR_EXPIRED_PENDING("This confirmation has expired. Please re-issue the command."),
    /** The command was recognised but could not be carried out. */
    UNKNOWN_ERROR("An unexpected error occurred. Check device logs."),
}
