package org.paramanuseniorshealth.notices.ui

import org.paramanuseniorshealth.notices.NotificationAccent
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.data.NotificationEntity
import androidx.annotation.StringRes

/**
 * The buckets the list can be filtered by, and the labels naming them in the chip row and in the
 * clear-selected confirmation.
 *
 * [OTHER] exists because a level is optional in the push contract and unrecognised values decay to
 * null: without it, messages carrying no level would be unreachable the moment any filter is
 * applied. It also collects everything received before the backend started forwarding `level`.
 *
 * Categorisation is deliberately a single pure function shared by the list, the chip counts, and
 * the delete path, so what "Clear selected" removes is exactly what the filter was showing. Doing
 * the same job again in SQL would risk the two disagreeing over casing and unknown values.
 */
enum class LevelFilter(@StringRes val label: Int) {
    ERROR(R.string.filter_error),
    WARNING(R.string.filter_warning),
    SUCCESS(R.string.filter_success),
    INFO(R.string.filter_info),
    OTHER(R.string.filter_other),
    ;

    companion object {

        val ALL: Set<LevelFilter> = entries.toSet()

        fun of(level: String?): LevelFilter = when (NotificationAccent.levelOf(level)) {
            NotificationAccent.Level.ERROR -> ERROR
            NotificationAccent.Level.WARNING -> WARNING
            NotificationAccent.Level.SUCCESS -> SUCCESS
            NotificationAccent.Level.INFO -> INFO
            null -> OTHER
        }

        fun of(entity: NotificationEntity): LevelFilter = of(entity.level)

        /**
         * Joins names the way a sentence would: "Error", "Error and Warning", "Error, Warning and
         * Success". The confirmation dialog reads as prose, so a bare comma-separated list would
         * scan badly in the one place the user is deciding whether to delete something.
         */
        fun joinLabels(labels: List<String>, conjunction: String): String = when (labels.size) {
            0 -> ""
            1 -> labels.single()
            else -> labels.dropLast(1).joinToString(", ") + " $conjunction " + labels.last()
        }
    }
}
