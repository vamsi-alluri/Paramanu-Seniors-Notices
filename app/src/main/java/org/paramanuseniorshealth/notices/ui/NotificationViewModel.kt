package org.paramanuseniorshealth.notices.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.paramanuseniorshealth.notices.NoticesApplication
import org.paramanuseniorshealth.notices.data.NotificationEntity
import org.paramanuseniorshealth.notices.data.NotificationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NotificationViewModel(
    private val repository: NotificationRepository,
) : ViewModel() {

    private val all: StateFlow<List<NotificationEntity>> = repository.notifications
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Starts with everything showing, and is not persisted: a filter surviving a restart is a good
     * way to miss an error days later because a chip was left deselected. The list should tell the
     * truth whenever the app is opened.
     */
    private val _selected = MutableStateFlow(LevelFilter.ALL)
    val selected: StateFlow<Set<LevelFilter>> = _selected.asStateFlow()

    val notifications: StateFlow<List<NotificationEntity>> =
        combine(all, _selected) { list, selected ->
            list.filter { LevelFilter.of(it) in selected }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** Counts come from the unfiltered list, so a chip still reports what deselecting it would restore. */
    val counts: StateFlow<Map<LevelFilter, Int>> = all
        .map { list -> list.groupingBy { LevelFilter.of(it) }.eachCount() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    /** True while any notification is stored, regardless of the filter, to decide whether chips are worth showing. */
    val hasAny: StateFlow<Boolean> = all
        .map { it.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    fun toggle(filter: LevelFilter) {
        _selected.update { if (filter in it) it - filter else it + filter }
    }

    /**
     * Selecting none is left reachable rather than being clamped back to "all": it is the quickest
     * route to picking one category out of five, since deselecting four by hand is the tedious way
     * to say the same thing. The empty list explains itself through the filtered empty state.
     */
    fun setAllSelected(all: Boolean) {
        _selected.value = if (all) LevelFilter.ALL else emptySet()
    }

    /**
     * Deletes precisely the rows the list is currently showing. Snapshotting [notifications] means
     * what disappears is what the user was looking at when they confirmed, even if a message
     * arrives while the dialog is open.
     *
     * With no filter applied this is every row, and it takes the wholesale path so the image cache
     * is emptied too rather than left holding files for rows that no longer exist.
     */
    fun clearShown() {
        val visible = notifications.value
        viewModelScope.launch {
            if (_selected.value == LevelFilter.ALL) repository.clearAll() else repository.clear(visible)
        }
    }

    /** Deletes an explicit set of rows, for the selection's bin. */
    fun delete(notifications: List<NotificationEntity>) {
        viewModelScope.launch { repository.clear(notifications) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as NoticesApplication
                NotificationViewModel(app.repository)
            }
        }
    }
}
