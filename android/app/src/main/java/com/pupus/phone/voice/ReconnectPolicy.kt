package com.pupus.phone.voice

/** What the bridge client does after the socket closes or fails. */
sealed class ReconnectDecision {
    data class Retry(val delaySeconds: Int) : ReconnectDecision()
    object Rejected : ReconnectDecision()
}

/**
 * Backoff bookkeeping for the bridge socket. The backoff only resets once the
 * bridge sends `ready` (a bare TCP/WebSocket open proves nothing), and a policy
 * violation close (1008, bad token) or an HTTP 401/403 handshake stops retrying.
 */
class ReconnectPolicy(private val backoff: Backoff = Backoff()) {

    fun onReady() = backoff.reset()

    fun onUserConnect() = backoff.reset()

    fun onClosed(code: Int): ReconnectDecision =
        if (code == CLOSE_POLICY_VIOLATION) ReconnectDecision.Rejected else retry()

    fun onFailure(httpStatus: Int?): ReconnectDecision =
        if (httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN) ReconnectDecision.Rejected else retry()

    private fun retry() = ReconnectDecision.Retry(backoff.nextDelaySeconds())

    companion object {
        const val CLOSE_POLICY_VIOLATION = 1008
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
    }
}
