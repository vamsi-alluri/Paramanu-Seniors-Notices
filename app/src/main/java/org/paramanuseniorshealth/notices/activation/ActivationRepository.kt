package org.paramanuseniorshealth.notices.activation

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import org.paramanuseniorshealth.notices.data.DispensaryRepository
import org.paramanuseniorshealth.notices.data.awaitResult
import org.paramanuseniorshealth.notices.fcm.ControlMessage
import org.paramanuseniorshealth.notices.fcm.ControlTopics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.content.edit

/**
 * Owns the one-time activation handshake, the entitlement check that gates every notice, and which
 * topics the phone is subscribed to.
 *
 * Redemption goes through the `code_management` Cloud Function over HTTPS, which claims the code
 * atomically, and [verify] asks the same endpoint, so the phone holds no Realtime Database socket for
 * either. The security rules in `database.rules.json` stay deployed, so a handset on an older
 * build keeps working through its direct write.
 *
 * Deliberately, no contact information is ever written. A claim is an anonymous Firebase UID
 * against a code, and nothing else.
 */
class ActivationRepository(
    private val context: Context,
    private val dispensaries: DispensaryRepository,
    /** Makes the claim. Injected, as [AnalyticsConfig] takes its switch. */
    private val api: CodeManagementApi,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

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
     * When a status call was last made, whatever it answered -- unlike [lastVerifiedAt], which moves
     * only for a definitive answer. It feeds [ActivationFreshness.shouldCall], and an attempt that
     * came back Unknown has still spent a function execution. Zero if there has never been one.
     */
    val lastCalledAt: Long get() = prefs.getLong(KEY_LAST_CALLED_AT, 0L)

    /** Stamps a status call as made now. Called by [verify] and by the view model after a redeem. */
    fun noteCall() {
        prefs.edit { putLong(KEY_LAST_CALLED_AT, System.currentTimeMillis()) }
    }

    /**
     * The delivery gate: may this notice be shown?
     *
     * Answers from the last persisted result, with **no network at all**. This is the whole point
     * of the split. Calling verify() from onMessageReceived meant a broadcast to four hundred
     * phones opened ~400 Realtime Database websockets within a few seconds, against a Spark cap of
     * 100 simultaneous connections; over the cap the connection is refused, the answer comes back
     * Unknown, and the check silently stopped working during exactly the event it exists for.
     *
     * Nothing stored yet, or anything unrecognized, permits the notice -- the same reasoning that
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
        prefs.edit {
            putString(KEY_LAST_STATE, state.name)
                .putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())
        }
    }

    // ---------------------------------------------------------------- topics

    /** Whether [topic] is switched on. The dispensary's default holds until the user chooses. */
    fun isTopicOn(topic: DispensaryTopic): Boolean =
        prefs.getBoolean(topicKey(topic.topic), topic.defaultOn)

    /** Every offered topic's state, keyed by topic name, for Settings. */
    fun topicChoices(): Map<String, Boolean> =
        dispensaries.current?.topics.orEmpty().associate { it.topic to isTopicOn(it) }

    /** Whether the hidden testing topics are switched on. Not dispensary topics: they are ours. */
    val testingOn: Boolean get() = prefs.getBoolean(KEY_TESTING, false)

    /**
     * Whether a notice that arrived on [topic] should be shown.
     *
     * A second check behind the subscription. Unsubscribing is not instant -- FCM can keep delivering
     * for a short while afterward -- so without this a user who has just switched a topic off gets
     * the next one anyway, and reasonably concludes the switch does not work. A topic the dispensary
     * does not offer is never shown, whatever reached the phone.
     */
    fun wantsTopic(topic: String): Boolean = when (topic) {
        in TESTING_TOPICS -> testingOn
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
        prefs.edit { putBoolean(topicKey(topic), enabled) }
        syncSubscriptions()
    }

    suspend fun setTesting(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_TESTING, enabled) }
        syncSubscriptions()
    }

    /**
     * Brings FCM into line with the claim, the dispensary and the stored choices. Safe to call on
     * every launch.
     *
     * The device topic follows the claim alone, revoked or not: it is how a revoke and a resume reach
     * this phone, and a revoked phone is precisely the one waiting for a resume. The banner topic and
     * the notice topics additionally wait for the claim to stand, since a revoked phone applies no
     * banner either. See [ControlTopics].
     *
     * What was subscribed last time is remembered, so a topic the dispensary stops offering is
     * unsubscribed rather than left behind. A failed unsubscribe stays in that record and is tried
     * again next time. That record is also how the retired `control-v1` is left: every build that
     * subscribed to it recorded it, and nothing wants it now.
     */
    suspend fun syncSubscriptions() {
        val desired = buildSet {
            if (isActivated) {
                storedUid?.let(ControlTopics::device)?.let { add(it) }
                if (!isRevoked) {
                    dispensaryId?.let(ControlTopics::banner)?.let { add(it) }
                    dispensaries.current?.topics.orEmpty().filter { isTopicOn(it) }.forEach { add(it.topic) }
                    if (testingOn) addAll(TESTING_TOPICS)
                }
            }
        }
        val previous = prefs.getStringSet(KEY_SUBSCRIBED, null).orEmpty()

        val stillSubscribed = (previous - desired).filterNot { unsubscribeTopic(it) }
        desired.forEach { subscribeTopic(it) }

        prefs.edit { putStringSet(KEY_SUBSCRIBED, desired + stillSubscribed) }
    }

    // ---------------------------------------------------------------- the claim

    /**
     * Redeems [rawCode]. The check character is tested locally first so an obvious typo is answered
     * instantly and offline, rather than by a network round trip that comes back with the much more
     * worrying "not valid".
     */
    suspend fun redeem(rawCode: String): RedeemResult {
        lastOfflineCause = null
        // Mistyped and SameCode are answered here, before any call: see RedeemPolicy.localAnswer.
        RedeemPolicy.localAnswer(rawCode, storedCode, isRevoked)?.let { return it }
        val code = ActivationCode.normalise(rawCode)
        val ownCode = code == storedCode

        return withTimeoutOrNull(NETWORK_TIMEOUT_MS.milliseconds) {
            try {
                // Reuse the existing anonymous account when there is one: signing in again would
                // mint a new UID and orphan any claim this device already holds.
                val uid = auth.currentUser?.uid
                    ?: auth.signInAnonymously().awaitResult().user?.uid
                    ?: run {
                        lastOfflineCause = OFFLINE_NO_UID
                        return@withTimeoutOrNull RedeemResult.Offline
                    }

                // The claim is made by the code_management function, which authenticates with this
                // account's token (hence the sign-in above) and answers with the code's dispensary.
                // A retry of a claim this uid already won is answered as still theirs by the server.
                val outcome = RedeemPolicy.claim(api, code)
                if (outcome !is ClaimOutcome.Claimed) {
                    // The result and the cause both come from RedeemPolicy, where they are tested:
                    // an uncertain answer must reach the patient as Offline, never NotAccepted.
                    lastOfflineCause = RedeemPolicy.offlineCauseFor(outcome)
                    Log.w(TAG, "Redeem not completed: ${outcome::class.simpleName}")
                    return@withTimeoutOrNull RedeemPolicy.resultFor(outcome, ownCode)
                }
                val dispensary = outcome.dispensary

                // KEY_REVOKED is cleared here on purpose. A revoked device keeps its old code and
                // UID so a console Enable can bring it back, but the user may instead be handed a
                // fresh slip -- and without this the new claim would inherit the old one's
                // revocation and the gate would refuse every notice for a code that is perfectly
                // good.
                prefs.edit {
                    putString(KEY_CODE, code)
                        .putString(KEY_UID, uid)
                        .putString(KEY_DISPENSARY, dispensary)
                        .putBoolean(KEY_REVOKED, false)
                }
                _revoked.value = false

                // A fresh claim is known-good, so record it rather than leaving the gate to answer
                // Unknown for the first notice.
                recordVerification(ActivationState.Active)

                // What the dispensary offers, then subscribe to its defaults. Without this first,
                // a new phone would subscribe to nothing until the app was next opened. The node
                // came back with the claim, so this opens no database connection; a missing or
                // junk node leaves the cached list and banner as they were (DispensaryRepository.apply).
                dispensary?.let { dispensaries.apply(it, outcome.node) }
                syncSubscriptions()

                // Start the daily check now rather than waiting for the next app start: on a phone
                // that is activated and then left alone, this is the only thing that would ever
                // notice a revocation whose broadcast was missed.
                ActivationRefreshWorker.ensurePeriodic(context)
                RedeemResult.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever got here (sign-in, local storage) is not the server refusing the code, so
                // it is never NotAccepted: "try again" is useful advice, "ask for a new code" is not.
                Log.w(TAG, "Redeem failed before an answer", e)
                lastOfflineCause = OFFLINE_TRANSPORT
                RedeemResult.Offline
            }
        } ?: run {
            lastOfflineCause = OFFLINE_TIMEOUT
            RedeemResult.Offline
        }
    }

    /**
     * Why the last Offline was Offline. Three unrelated failures produce the same result -- no uid
     * from anonymous sign-in, a transport exception, and the 20-second timeout elapsing -- and
     * telling them apart is most of the value of the activation funnel.
     *
     * Cleared at the start of every [redeem], so it only ever describes the latest attempt, and is
     * meaningful only when that attempt returned [RedeemResult.Offline].
     */
    var lastOfflineCause: String? = null
        private set

    /**
     * Confirms the claim still stands, and picks up the code's dispensary while it is reading.
     *
     * Returns [ActivationState.Unknown] rather than [ActivationState.Revoked] whenever the answer
     * could not be obtained -- see the note on [ActivationState].
     */
    suspend fun verify(): ActivationState {
        val code = storedCode ?: return ActivationState.NotActivated
        if (storedUid == null) return ActivationState.NotActivated

        return withTimeoutOrNull(VERIFY_TIMEOUT_MS.milliseconds) {
            try {
                // FirebaseAuth restores the persisted user asynchronously, so a null here early in
                // the process is a startup race, not a revocation. Not an answer: nothing persisted.
                if (auth.currentUser == null) return@withTimeoutOrNull ActivationState.Unknown

                // One HTTPS call, no database socket. The endpoint checks the ID token itself, so a
                // deleted or disabled anonymous user is rejected there and arrives as Inconclusive
                // -> Unknown. It can therefore never read as Revoked here: only the server's own
                // explicit "revoked" does.
                // Stamped before the call, not after: a slow or failed call has spent the execution
                // all the same, and a resume landing while it is in flight must not start another.
                noteCall()
                val verdict = VerifyPolicy.check(api, code)

                // Persisted only for a definitive answer, so the gate can answer the next notice
                // without a connection. An inconclusive one leaves what is stored untouched.
                if (verdict.persist) recordVerification(verdict.state)

                if (verdict.state == ActivationState.Active) {
                    verdict.dispensaryId?.let { id ->
                        if (id != dispensaryId) prefs.edit { putString(KEY_DISPENSARY, id) }
                    }
                    // The dispensary arrived with the answer: apply it rather than read it again.
                    (verdict.dispensaryId ?: dispensaryId)?.let { id ->
                        dispensaries.apply(id, verdict.dispensaryNode)
                    }
                }
                verdict.state
            } catch (e: CancellationException) {
                throw e
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
     * The device topic is deliberately kept, so a resume can still reach this phone.
     *
     * Safe to call repeatedly: a second revoke broadcast for the same code changes nothing.
     */
    suspend fun suspendClaim() {
        prefs.edit {
            putBoolean(KEY_REVOKED, true)
                .putString(KEY_LAST_STATE, ActivationState.Revoked.name)
                .putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())
        }
        _revoked.value = true
        syncSubscriptions()
    }

    /** The server says the claim stands again: clear the banner and start receiving once more. */
    suspend fun resumeClaim() {
        prefs.edit { putBoolean(KEY_REVOKED, false) }
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
        prefs.edit { putLong(KEY_LAST_CONTROL_AT, at) }
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

    companion object {
        /**
         * Delivery checks against real devices. Not dispensary topics, and hidden behind seven taps
         * on the version number in Settings.
         *
         * Two of them, because the sender splits its test sends by shape: anything carrying a PDF or
         * an image goes to the attachment topic, everything else to the plain one. A phone with the
         * switch on wants both, or half the checks never arrive. Must match
         * TEST_SEND_ATTACHMENT_TOPIC and TEST_SEND_PLAIN_TOPIC in the sender's TestSends.gs.
         */
        const val TESTING_ATTACHMENT_TOPIC = "test-notices-v1"
        const val TESTING_PLAIN_TOPIC = "test-notifications-v1"

        val TESTING_TOPICS = setOf(TESTING_ATTACHMENT_TOPIC, TESTING_PLAIN_TOPIC)

        private const val TAG = "ActivationRepo"
        private const val PREFS = "activation"
        private const val KEY_CODE = "code"
        private const val KEY_UID = "uid"
        private const val KEY_DISPENSARY = "dispensary"
        private const val KEY_REVOKED = "revoked"
        private const val KEY_LAST_STATE = "last_state"
        private const val KEY_LAST_VERIFIED_AT = "last_verified_at"
        private const val KEY_LAST_CALLED_AT = "last_called_at"
        private const val KEY_LAST_CONTROL_AT = "last_control_at"
        private const val KEY_SUBSCRIBED = "subscribed_topics"
        private const val KEY_TESTING = "testing_enabled"

        private fun topicKey(topic: String) = "topic_$topic"

        private const val NETWORK_TIMEOUT_MS = 20_000L

        // The values of lastOfflineCause. They are an analytics contract: the funnel is queried by
        // these exact strings, so they are not to be renamed or added to casually.
        // Defined in RedeemPolicy, where a test pins the exact strings.
        private const val OFFLINE_NO_UID = RedeemPolicy.CAUSE_NO_UID
        private const val OFFLINE_TRANSPORT = RedeemPolicy.CAUSE_TRANSPORT
        private const val OFFLINE_TIMEOUT = RedeemPolicy.CAUSE_TIMEOUT

        /**
         * Kept short: an inconclusive check costs nothing, because Unknown is permitted, and a person
         * pressing Check again should not wait long to be told so.
         */
        private const val VERIFY_TIMEOUT_MS = 4_000L
    }
}
