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
import org.paramanuseniorshealth.notices.data.DispensaryRepository
import org.paramanuseniorshealth.notices.fcm.ControlMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the one-time activation handshake, the entitlement check that gates every notice, and which
 * topics the phone is subscribed to.
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
class ActivationRepository(
    private val context: Context,
    private val dispensaries: DispensaryRepository,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val database: FirebaseDatabase get() = FirebaseDatabase.getInstance()

    val storedCode: String? get() = prefs.getString(KEY_CODE, null)
    val storedUid: String? get() = prefs.getString(KEY_UID, null)

    /**
     * The dispensary this phone's code belongs to, as last read from the code. Null until then.
     *
     * Decided by the code, never chosen on the phone: the console writes it when the slip is
     * generated, and it decides what Settings offers.
     */
    val dispensaryId: String? get() = prefs.getString(KEY_DISPENSARY, null)

    /** True once a code has been redeemed on this device, regardless of server state. */
    val isActivated: Boolean get() = storedCode != null && storedUid != null

    /**
     * True while this device's claim is known to be revoked.
     *
     * Persistent, not a one-shot flag: the notice list shows a banner for as long as it is set, and
     * it is cleared only when the server says the claim stands again -- which is what makes the
     * console's Enable button work.
     */
    val isRevoked: Boolean get() = prefs.getBoolean(KEY_REVOKED, false)

    /**
     * The same fact as [isRevoked], as a stream, so a screen that is already on display updates the
     * moment a revoke arrives.
     *
     * A revoke is applied by the messaging service, which runs in this process but with no UI
     * attached. Without this the banner appeared only when something else happened to redraw the
     * screen -- so the user could sit looking at a list that had silently stopped receiving.
     */
    private val _revoked = MutableStateFlow(prefs.getBoolean(KEY_REVOKED, false))
    val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

    /** When the last definitive answer was obtained. Zero if there has never been one. */
    val lastVerifiedAt: Long get() = prefs.getLong(KEY_LAST_VERIFIED_AT, 0L)

    /**
     * The delivery gate: may this notice be shown?
     *
     * Answers from the last persisted result, with **no network at all**. This is the whole point
     * of the split. Calling verify() from onMessageReceived meant a broadcast to four hundred
     * phones opened ~400 Realtime Database websockets within a few seconds, against a Spark cap of
     * 100 simultaneous connections; over the cap the connection is refused, the answer comes back
     * Unknown, and the check silently stopped working during exactly the event it exists for.
     *
     * Nothing stored yet, or anything unrecognised, permits the notice -- the same reasoning that
     * already permits Unknown. A missed closure notice is worse than a revoked device seeing one
     * more public announcement. See docs/decisions.md.
     */
    fun gate(): ActivationState {
        if (!isActivated) return ActivationState.NotActivated
        if (isRevoked) return ActivationState.Revoked
        return when (prefs.getString(KEY_LAST_STATE, null)) {
            ActivationState.Revoked.name -> ActivationState.Revoked
            ActivationState.Active.name -> ActivationState.Active
            else -> ActivationState.Unknown
        }
    }

    /** Stores a definitive answer. [ActivationState.Unknown] is not an answer, so it is not stored. */
    fun recordVerification(state: ActivationState) {
        if (state == ActivationState.Unknown) return
        prefs.edit()
            .putString(KEY_LAST_STATE, state.name)
            .putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())
            .apply()
    }

    // ---------------------------------------------------------------- topics

    /** Whether [topic] is switched on. The dispensary's default holds until the user chooses. */
    fun isTopicOn(topic: DispensaryTopic): Boolean =
        prefs.getBoolean(topicKey(topic.topic), topic.defaultOn)

    /** Every offered topic's state, keyed by topic name, for Settings. */
    fun topicChoices(): Map<String, Boolean> =
        dispensaries.current?.topics.orEmpty().associate { it.topic to isTopicOn(it) }

    /** Whether the hidden testing topic is switched on. Not a dispensary topic: it is ours. */
    val testingOn: Boolean get() = prefs.getBoolean(KEY_TESTING, false)

    /**
     * Whether a notice that arrived on [topic] should be shown.
     *
     * A second check behind the subscription. Unsubscribing is not instant -- FCM can keep delivering
     * for a short while afterwards -- so without this a user who has just switched a topic off gets
     * the next one anyway, and reasonably concludes the switch does not work. A topic the dispensary
     * does not offer is never shown, whatever reached the phone.
     */
    fun wantsTopic(topic: String): Boolean = when (topic) {
        TESTING_TOPIC -> testingOn
        else -> dispensaries.current?.topic(topic)?.let { isTopicOn(it) } == true
    }

    /**
     * Turns a topic on or off from inside the app.
     *
     * Switching off unsubscribes but deliberately keeps the code, the UID and the anonymous account.
     * Surrendering them would make the decision irreversible: the code is already marked used, a
     * fresh anonymous sign-in would produce a different UID, and the rules would then refuse to
     * re-claim it -- so a user who turned notices off out of curiosity could never turn them back on
     * without a trip to the counter for a new slip.
     */
    suspend fun setTopic(topic: String, enabled: Boolean) {
        prefs.edit().putBoolean(topicKey(topic), enabled).apply()
        syncSubscriptions()
    }

    suspend fun setTesting(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TESTING, enabled).apply()
        syncSubscriptions()
    }

    /**
     * Brings FCM into line with the claim, the dispensary and the stored choices. Safe to call on
     * every launch.
     *
     * The control topic follows the claim alone, revoked or not: it is how a revoke, a resume and a
     * banner change reach the phone, and a revoked phone is precisely the one waiting for a resume.
     * The notice topics additionally wait for the claim to stand.
     *
     * What was subscribed last time is remembered, so a topic the dispensary stops offering is
     * unsubscribed rather than left behind. A failed unsubscribe stays in that record and is tried
     * again next time.
     */
    suspend fun syncSubscriptions() {
        val desired = buildSet {
            if (isActivated) {
                add(CONTROL_TOPIC)
                if (!isRevoked) {
                    dispensaries.current?.topics.orEmpty().filter { isTopicOn(it) }.forEach { add(it.topic) }
                    if (testingOn) add(TESTING_TOPIC)
                }
            }
        }
        val previous = prefs.getStringSet(KEY_SUBSCRIBED, null).orEmpty()

        val stillSubscribed = (previous - desired).filterNot { unsubscribeTopic(it) }
        desired.forEach { subscribeTopic(it) }

        prefs.edit().putStringSet(KEY_SUBSCRIBED, desired + stillSubscribed).apply()
    }

    // ---------------------------------------------------------------- the claim

    /**
     * Redeems [rawCode]. The check character is tested locally first so an obvious typo is answered
     * instantly and offline, rather than by a network round trip that comes back with the much more
     * worrying "not valid".
     */
    suspend fun redeem(rawCode: String): RedeemResult {
        if (!ActivationCode.isValid(rawCode)) return RedeemResult.Mistyped
        val code = ActivationCode.normalise(rawCode)

        // Retyping the code this phone already holds. Answered here rather than by the server: the
        // rules would refuse it, but only as a generic permission denial, and the user would be
        // told their code "may already have been used" while holding the slip it is printed on.
        if (code == storedCode) return RedeemResult.SameCode

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

                // Which dispensary the code belongs to decides what the phone is offered. Read after
                // the claim, and allowed to fail: a claim that has succeeded must not be undone by a
                // slow read, and the next verification fills this in anyway.
                val dispensary = runCatching {
                    database.reference.child(CODES).child(code).child(FIELD_DISPENSARY).get()
                        .awaitResult().getValue(String::class.java)
                }.onFailure { Log.w(TAG, "Could not read the dispensary for $code", it) }.getOrNull()

                // KEY_REVOKED is cleared here on purpose. A revoked device keeps its old code and
                // UID so a console Enable can bring it back, but the user may instead be handed a
                // fresh slip -- and without this the new claim would inherit the old one's
                // revocation and the gate would refuse every notice for a code that is perfectly
                // good.
                prefs.edit()
                    .putString(KEY_CODE, code)
                    .putString(KEY_UID, uid)
                    .putString(KEY_DISPENSARY, dispensary)
                    .putBoolean(KEY_REVOKED, false)
                    .apply()
                _revoked.value = false

                // A fresh claim is known-good, so record it rather than leaving the gate to answer
                // Unknown for the first notice.
                recordVerification(ActivationState.Active)

                // What the dispensary offers, then subscribe to its defaults. Without the read first,
                // a new phone would subscribe to nothing until the app was next opened.
                dispensary?.let { dispensaries.refresh(it) }
                syncSubscriptions()

                // Start the daily check now rather than waiting for the next app start: on a phone
                // that is activated and then left alone, this is the only thing that would ever
                // notice a revocation whose broadcast was missed.
                ActivationRefreshWorker.ensurePeriodic(context)
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
     * Confirms the claim still stands, and picks up the code's dispensary while it is reading.
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
                val user = auth.currentUser ?: run {
                    recordVerification(ActivationState.Revoked)
                    return@withTimeoutOrNull ActivationState.Revoked
                }
                user.getIdToken(true).awaitResult()

                val snapshot: DataSnapshot =
                    database.reference.child(CODES).child(code).get().awaitResult()

                val claimedBy = snapshot.child(FIELD_USED_BY).getValue(String::class.java)
                val revoked = snapshot.child(FIELD_REVOKED).getValue(Boolean::class.java) == true
                val issued = snapshot.child(FIELD_ISSUED).exists()

                snapshot.child(FIELD_DISPENSARY).getValue(String::class.java)?.let { id ->
                    if (id != dispensaryId) prefs.edit().putString(KEY_DISPENSARY, id).apply()
                }

                val state = when {
                    revoked -> ActivationState.Revoked
                    // Every code the console prints carries `issued`. A node without it was never
                    // issued by the NGO, so the claim on it is not one we honour -- this catches
                    // any self-minted code created before the rules refused to create them.
                    !issued -> ActivationState.Revoked
                    claimedBy == null -> ActivationState.Revoked   // claim erased by the NGO
                    claimedBy != uid -> ActivationState.Revoked    // reissued to somebody else
                    else -> ActivationState.Active
                }
                // Persisted here so the gate can answer the next notice without a connection.
                recordVerification(state)
                state
            } catch (e: FirebaseAuthInvalidUserException) {
                // Definitive: the anonymous account behind this claim no longer exists.
                Log.i(TAG, "Anonymous user is gone; treating as revoked", e)
                recordVerification(ActivationState.Revoked)
                ActivationState.Revoked
            } catch (e: Exception) {
                Log.w(TAG, "Verification inconclusive", e)
                ActivationState.Unknown
            }
        } ?: ActivationState.Unknown
    }

    /**
     * Stops delivery without surrendering the claim.
     *
     * The code and the UID are kept on purpose, and this is the correction of a real bug. Revoking
     * sets `revoked: true` and leaves `usedBy` in place, and the security rules refuse to write
     * `usedBy` on a code that already carries one -- so a device that had thrown its code away
     * could never come back, by any route, and the console's Enable button had nothing left to
     * restore. Keeping them means an Enable is noticed and the phone simply resumes.
     *
     * The control topic is deliberately kept, so a resume can still reach this phone.
     *
     * Safe to call repeatedly: a second revoke broadcast for the same code changes nothing.
     */
    suspend fun suspendClaim() {
        prefs.edit()
            .putBoolean(KEY_REVOKED, true)
            .putString(KEY_LAST_STATE, ActivationState.Revoked.name)
            .putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())
            .apply()
        _revoked.value = true
        syncSubscriptions()
    }

    /** The server says the claim stands again: clear the banner and start receiving once more. */
    suspend fun resumeClaim() {
        prefs.edit().putBoolean(KEY_REVOKED, false).apply()
        _revoked.value = false
        recordVerification(ActivationState.Active)
        syncSubscriptions()
    }

    /**
     * Whether a control message stamped [at] may be applied here, recording the stamp if so.
     *
     * Call only once the message is known to concern this phone's code. See [ControlMessage.isNewer]
     * for why the order has to be checked at all.
     */
    fun acceptControl(at: Long): Boolean {
        if (!ControlMessage.isNewer(at, prefs.getLong(KEY_LAST_CONTROL_AT, 0L))) return false
        prefs.edit().putLong(KEY_LAST_CONTROL_AT, at).apply()
        return true
    }

    /** Idempotent and locally persisted by the SDK, so calling it on every launch is cheap. */
    private suspend fun subscribeTopic(topic: String): Boolean =
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic(topic).awaitResult() }
            .onFailure { Log.w(TAG, "Subscribe to '$topic' failed", it) }
            .isSuccess

    private suspend fun unsubscribeTopic(topic: String): Boolean =
        runCatching { FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).awaitResult() }
            .onFailure { Log.w(TAG, "Unsubscribe from '$topic' failed", it) }
            .isSuccess

    /**
     * A refusal by the security rules is a real answer and must not be retried as if it were a
     * network blip; anything else is treated as transport trouble.
     */
    private fun isTransportFailure(e: Exception): Boolean {
        val message = (e.message ?: "").lowercase()
        return "permission" !in message && "denied" !in message
    }

    companion object {
        /**
         * Revoke, resume and banner changes. Held for as long as the phone holds a claim, revoked or
         * not, and never shown in Settings: it is how the app is managed, not something a user
         * chooses. Must match CONTROL_TOPIC in the sender's Code.gs.
         */
        const val CONTROL_TOPIC = "control-v1"

        /**
         * Delivery checks against real devices. Not a dispensary topic, and hidden behind seven taps
         * on the version number in Settings.
         */
        const val TESTING_TOPIC = "testing-v1"

        private const val TAG = "ActivationRepo"
        private const val PREFS = "activation"
        private const val KEY_CODE = "code"
        private const val KEY_UID = "uid"
        private const val KEY_DISPENSARY = "dispensary"
        private const val KEY_REVOKED = "revoked"
        private const val KEY_LAST_STATE = "last_state"
        private const val KEY_LAST_VERIFIED_AT = "last_verified_at"
        private const val KEY_LAST_CONTROL_AT = "last_control_at"
        private const val KEY_SUBSCRIBED = "subscribed_topics"
        private const val KEY_TESTING = "testing_enabled"

        private fun topicKey(topic: String) = "topic_$topic"

        private const val CODES = "codes"
        private const val FIELD_USED_BY = "usedBy"
        private const val FIELD_ACTIVATED_AT = "activatedAt"
        private const val FIELD_REVOKED = "revoked"
        private const val FIELD_ISSUED = "issued"
        private const val FIELD_DISPENSARY = "dispensary"

        private const val NETWORK_TIMEOUT_MS = 20_000L

        /**
         * Kept short: an inconclusive check costs nothing, because Unknown is permitted, and a person
         * pressing Check again should not wait long to be told so.
         */
        private const val VERIFY_TIMEOUT_MS = 4_000L
    }
}

/**
 * Bridges a Play Services [Task] into a coroutine without pulling in
 * `kotlinx-coroutines-play-services` for the handful of call sites that need it.
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
