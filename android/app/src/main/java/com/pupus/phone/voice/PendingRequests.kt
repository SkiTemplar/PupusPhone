package com.pupus.phone.voice

/** What to run once the bridge socket opens, in this order. */
data class OnConnect(val shouldReset: Boolean, val shouldTap: Boolean)

/**
 * Requests that arrive while the bridge is still connecting. A reset survives
 * failed attempts (it is sent on the next connect); a tap does not, because
 * the user must not dictate a whole sentence that cannot be sent.
 */
class PendingRequests {
    var isResetPending: Boolean = false
        private set

    var isTapPending: Boolean = false
        private set

    /** Returns true when the reset can be sent now; otherwise it is queued. */
    fun requestReset(isConnected: Boolean): Boolean {
        isResetPending = !isConnected
        return isConnected
    }

    /** A reset that could not be sent although the socket looked open. */
    fun deferReset() {
        isResetPending = true
    }

    fun queueTap() {
        isTapPending = true
    }

    fun onConnected(): OnConnect {
        val actions = OnConnect(shouldReset = isResetPending, shouldTap = isTapPending)
        isResetPending = false
        isTapPending = false
        return actions
    }

    /** Returns true if a queued tap was dropped and the user must be told. */
    fun onConnectFailed(): Boolean {
        val hadTap = isTapPending
        isTapPending = false
        return hadTap
    }

    fun clear() {
        isResetPending = false
        isTapPending = false
    }
}
