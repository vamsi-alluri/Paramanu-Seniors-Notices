package org.paramanuseniorshealth.notices.activation

/**
 * Whether this install is entitled to show notices.
 *
 * [Unknown] exists on purpose and is not a failure state. Verification needs the network, and the
 * device may be offline exactly when a notice arrives. Treating "could not check" the same as
 * "revoked" would mean an eighty-year-old silently misses a closure notice because their phone was
 * on a weak signal -- so [Unknown] is treated as permitted at delivery time. Only a definitive
 * answer from the server revokes.
 */
enum class ActivationState {
    /** No code has been redeemed on this device yet. */
    NotActivated,

    /** Redeemed, and the server confirms the claim still stands. */
    Active,

    /** The server says this claim is gone: the code was revoked, or the anonymous user was deleted. */
    Revoked,

    /** The check could not be completed -- offline, timed out, or the backend was unreachable. */
    Unknown,
}

/** Outcome of redeeming a code at the activation screen. */
sealed interface RedeemResult {
    data object Success : RedeemResult

    /** Failed the local check-character test: almost certainly a typo, so say so gently. */
    data object Mistyped : RedeemResult

    /** Structurally fine, but the backend refused it -- unknown code, or already claimed. */
    data object NotAccepted : RedeemResult

    /** Could not reach the backend. Distinct from [NotAccepted] so the user is told to retry. */
    data object Offline : RedeemResult
}
