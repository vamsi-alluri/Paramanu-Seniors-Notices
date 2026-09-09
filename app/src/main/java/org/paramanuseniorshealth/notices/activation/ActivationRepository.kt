package org.paramanuseniorshealth.notices.activation

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the one-time activation handshake and the entitlement check that gates every notice.
 *
 * There is no server of ours anywhere in this flow. Redemption is a single write to Realtime
 * Database that the security rules either accept or refuse -- the rules are the validator. That is
 * what keeps the project on the no-cost plan and keeps a service-account credential out of the
 * picture entirely. The rules that make this safe live in `database.rules.json`; the client code
 * below is meaningless without them deployed.
 *
 * Deliberately, no contact information is ever written. A claim is an anonymous Firebase UID
 * against a code, and nothing else.
 */
class ActivationRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val database: FirebaseDatabase get() = FirebaseDatabase.getInstance()

    val storedCode: String? get() = prefs.getString(KEY_CODE, null)
    val storedUid: String? get() = prefs.getString(KEY_UID, null)

    /** True once a code has been redeemed on this device, regardless of server state. */
    val isActivated: Boolean get() = storedCode != null && storedUid != null

    /** Whether [subscription] is switched on for this phone. */
    fun isSubscribed(subscription: Subscription): Boolean =
        prefs.getBoolean(subscription.preferenceKey, subscription.defaultEnabled)

    /** Snapshot of every subscription's state, for the Settings screen. */
    fun subscriptions(): Map<Subscription, Boolean> =
        Subscription.entries.associateWith { isSubscribed(it) }

    /** True when the phone wants anything at all. Used to decide whether to subscribe on launch. */
    val anySubscribed: Boolean
        get() = Subscription.entries.any { isSubscribed(it) }

    /**
     * Redeems [rawCode]. The check character is tested locally first so an obvious typo is answered
     * instantly and offline, rather than by a network round trip that comes back with the much more
     * worrying "not valid".
     */
    suspend fun redeem(rawCode: String): RedeemResult {
        if (!ActivationCode.isValid(rawCode)) return RedeemResult.Mistyped
        val code = ActivationCode.normalise(rawCode)

        return withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            try {
                // Reuse the existing anonymous account when there is one: signing in again would
                // mint a new UID and orphan any claim this device already holds.
                val uid = auth.currentUser?.uid
                    ?: auth.signInAnonymously().awaitResult().user?.uid
                    ?: return@withTimeoutOrNull RedeemResult.Offline

                // updateChildren rather than setValue, so the `issued` metadata the NGO wrote when
                // it printed the slip survives the claim.
                database.reference.child(CODES).child(code).updateChildren(
                    mapOf(
                        FIELD_USED_BY to uid,
                        FIELD_ACTIVATED_AT to ServerValue.TIMESTAMP,
                    )
                ).awaitResult()

                prefs.edit()
                    .putString(KEY_CODE, code)
                    .putString(KEY_UID, uid)
                    .apply()

                // Only the defaults are turned on here. STATUS stays off until the user asks for
                // it, which is the whole reason it is a separate subscription.
                syncSubscriptions()
                RedeemResult.Success
            } catch (e: Exception) {
                // The rules reject an unknown or already-claimed code as a permission denial, which
                // is indistinguishable at this layer from any other refusal -- and to the user the
                // distinction does not matter. A transport failure is a separate message because
                // "try again" is useful advice and "ask for a new code" is not.
                Log.w(TAG, "Redeem refused for $code", e)
                if (isTransportFailure(e)) RedeemResult.Offline else RedeemResult.NotAccepted
            }
        } ?: RedeemResult.Offline
    }

    /**
     * Confirms the claim still stands. Called on every arriving notice, so it is capped hard.
     *
     * Returns [ActivationState.Unknown] rather than [ActivationState.Revoked] whenever the answer
     * could not be obtained -- see the note on [ActivationState].
     */
    suspend fun verify(): ActivationState {
        val code = storedCode ?: return ActivationState.NotActivated
        val uid = storedUid ?: return ActivationState.NotActivated

        return withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
            try {
                // Forces a refresh against the server, which is the only way a deleted or disabled
                // anonymous user is ever discovered. A cached ID token stays happily valid
                // otherwise, so `currentUser != null` proves nothing at all.
                val user = auth.currentUser ?: return@withTimeoutOrNull ActivationState.Revoked
                user.getIdToken(true).awaitResult()

                val snapshot: DataSnapshot =
                    database.reference.child(CODES).child(code).get().awaitResult()

                val claimedBy = snapshot.child(FIELD_USED_BY).getValue(String::class.java)
                val revoked = snapshot.child(FIELD_REVOKED).getValue(Boolean::class.java) == true
                val issued = snapshot.child(FIELD_ISSUED).exists()

                when {
                    revoked -> ActivationState.Revoked
                    // Every code the console prints carries `issued`. A node without it was never
                    // issued by the NGO, so the claim on it is not one we honour -- this catches
                    // any self-minted code created before the rules refused to create them.
                    !issued -> ActivationState.Revoked
                    claimedBy == null -> ActivationState.Revoked   // claim erased by the NGO
                    claimedBy != uid -> ActivationState.Revoked    // reissued to somebody else
                    else -> ActivationState.Active
                }
            } catch (e: FirebaseAuthInvalidUserException) {
                // Definitive: the anonymous account behind this claim no longer exists.
                Log.i(TAG, "Anonymous user is gone; treating as revoked", e)
                ActivationState.Revoked
            } catch (e: Exception) {
                Log.w(TAG, "Verification inconclusive", e)
                ActivationState.Unknown
            }
        } ?: ActivationState.Unknown
    }

    /**
     * Turns delivery on or off from inside the app.
     *
     * Switching off unsubscribes but deliberately keeps the code, the UID and the anonymous account.
     * Surrendering them would make the decision irreversible: the code is already marked used, a
     * fresh anonymous sign-in would produce a different UID, and the rules would then refuse to
     * re-claim it -- so a user who turned notices off out of curiosity could never turn them back on
     * without a trip to the counter for a new slip.
     */
    suspend fun setSubscribed(subscription: Subscription, enabled: Boolean) {
        prefs.edit().putBoolean(subscription.preferenceKey, enabled).apply()
        if (enabled) subscribe(subscription) else unsubscribe(subscription)
    }

    /** Brings FCM into line with the stored preferences. Safe to call on every launch. */
    suspend fun syncSubscriptions() {
        for (subscription in Subscription.entries) {
            if (isActivated && isSubscribed(subscription)) subscribe(subscription)
            else unsubscribe(subscription)
        }
    }

    /**
     * Called when verification says the claim is gone: stop delivery and forget the claim.
     *
     * Leaves a flag behind. Revocation is usually discovered by the messaging service, with no UI
     * running -- so without this the user simply finds the code screen next time they open the app,
     * with their history gone and no explanation. The flag lets whoever opens the app next say what
     * happened. See [consumeRevokedNotice].
     */
    suspend fun clearRevoked() = forgetClaim(announce = true)

    /**
     * The user chose to reset from Settings. Identical mechanically to [clearRevoked], but it must
     * not raise the revocation message: they already know why their code is gone, and telling them
     * their access "has been removed" would read as though something had been done to them.
     */
    suspend fun resetByUser() = forgetClaim(announce = false)

    private suspend fun forgetClaim(announce: Boolean) {
        unsubscribeAll()
        prefs.edit()
            .remove(KEY_CODE)
            .remove(KEY_UID)
            .apply { Subscription.entries.forEach { remove(it.preferenceKey) } }
            .apply {
                if (announce) putBoolean(KEY_REVOKED_NOTICE, true)
                else remove(KEY_REVOKED_NOTICE)
            }
            .apply()
    }

    /** Returns true once after a revocation, then clears the flag so the message is shown only once. */
    fun consumeRevokedNotice(): Boolean {
        if (!prefs.getBoolean(KEY_REVOKED_NOTICE, false)) return false
        prefs.edit().remove(KEY_REVOKED_NOTICE).apply()
        return true
    }

    /** Idempotent and locally persisted by the SDK, so calling it on every launch is cheap. */
    suspend fun subscribe(subscription: Subscription) {
        runCatching {
            FirebaseMessaging.getInstance().subscribeToTopic(subscription.topic).awaitResult()
        }.onFailure { Log.w(TAG, "Subscribe to '${subscription.topic}' failed", it) }
    }

    suspend fun unsubscribe(subscription: Subscription) {
        runCatching {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(subscription.topic).awaitResult()
        }.onFailure { Log.w(TAG, "Unsubscribe from '${subscription.topic}' failed", it) }
    }

    /** Every topic off. Used when a claim is surrendered or revoked. */
    suspend fun unsubscribeAll() {
        Subscription.entries.forEach { unsubscribe(it) }
    }

    /**
     * A refusal by the security rules is a real answer and must not be retried as if it were a
     * network blip; anything else is treated as transport trouble.
     */
    private fun isTransportFailure(e: Exception): Boolean {
        val message = (e.message ?: "").lowercase()
        return "permission" !in message && "denied" !in message
    }

    companion object {
        private const val TAG = "ActivationRepo"
        private const val PREFS = "activation"
        private const val KEY_CODE = "code"
        private const val KEY_UID = "uid"
        private const val KEY_REVOKED_NOTICE = "revoked_notice_pending"

        private const val CODES = "codes"
        private const val FIELD_USED_BY = "usedBy"
        private const val FIELD_ACTIVATED_AT = "activatedAt"
        private const val FIELD_REVOKED = "revoked"
        private const val FIELD_ISSUED = "issued"


        private const val NETWORK_TIMEOUT_MS = 20_000L

        /**
         * Runs inside onMessageReceived, where the whole budget before teardown is roughly 10-20
         * seconds and the PDF still has to be fetched and rendered afterwards. Kept short: an
         * inconclusive check costs nothing, because Unknown is permitted.
         */
        private const val VERIFY_TIMEOUT_MS = 4_000L
    }
}

/**
 * Bridges a Play Services [Task] into a coroutine without pulling in
 * `kotlinx-coroutines-play-services` for the four call sites that need it.
 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            continuation.resume(task.result as T)
        } else {
            continuation.resumeWithException(
                task.exception ?: IllegalStateException("Task failed without an exception")
            )
        }
    }
}
