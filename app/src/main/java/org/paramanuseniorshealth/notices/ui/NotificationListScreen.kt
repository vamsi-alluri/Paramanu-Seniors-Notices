package org.paramanuseniorshealth.notices.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.paramanuseniorshealth.notices.NotificationAccent
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.data.NotificationEntity
import org.paramanuseniorshealth.notices.fcm.NotificationImageStore
import coil3.compose.AsyncImage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationListScreen(
    modifier: Modifier = Modifier,
    viewModel: NotificationViewModel = viewModel(factory = NotificationViewModel.Factory),
) {
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val hasAny by viewModel.hasAny.collectAsStateWithLifecycle()
    // Which confirmation is open, if any. Both destructive actions route through the same dialog.
    var pendingClear by remember { mutableStateOf<ClearAction?>(null) }
    // Only one card is open at a time; null means everything is collapsed.
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val accessState = rememberNotificationAccessState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    // A swiped row is hidden here first and only deleted once the undo window closes, so Undo
    // restores it exactly -- including its cached image, which an actual delete would have removed.
    var pendingDelete by remember { mutableStateOf<NotificationEntity?>(null) }

    val visible = notifications.filter { it.id != pendingDelete?.id }

    // Intersected with what is on screen rather than trusted outright: a filter change or an
    // arriving message must never leave a hidden row queued for deletion.
    val selection = visible.filter { it.id in selectedIds }
    val selectionMode = selection.isNotEmpty()

    // Leaving selection mode is the natural meaning of Back while a selection exists.
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    pendingDelete?.let { row ->
        val message = stringResource(R.string.snackbar_deleted)
        val undo = stringResource(R.string.action_undo)
        LaunchedEffect(row.id) {
            val result = snackbarHost.showSnackbar(
                message = message,
                actionLabel = undo,
                duration = SnackbarDuration.Long,
            )
            // Dismissal is the commit. If this effect is cancelled instead -- the app backgrounded
            // mid-window -- nothing is deleted and the row simply returns, which is the safe way
            // for an interrupted delete to fail.
            if (result == SnackbarResult.ActionPerformed) {
                pendingDelete = null
            } else {
                viewModel.delete(listOf(row))
                pendingDelete = null
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            if (selectionMode) {
                // The whole bar changes, not just the icon's meaning: the count and the close
                // action are what tell the user why the bin now deletes three things and not all.
                TopAppBar(
                    title = { Text(stringResource(R.string.selection_count, selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.action_exit_selection),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { context.shareNotifications(ShareText.build(selection)) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = stringResource(R.string.action_share),
                            )
                        }
                        IconButton(onClick = { pendingClear = ClearAction.SELECTION }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        // Clears whatever the list is currently showing. With no filter that is
                        // everything; with one, only those. The chips sitting directly below are
                        // the visible statement of scope, and the dialog names it outright.
                        IconButton(
                            onClick = { pendingClear = ClearAction.SHOWN },
                            enabled = visible.isNotEmpty(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.action_clear),
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        pendingClear?.let { action ->
            ClearConfirmationDialog(
                action = action,
                shown = visible,
                selection = selection,
                hiddenCount = counts.values.sum() - visible.size,
                selectedFilters = selected,
                onDismiss = { pendingClear = null },
                onConfirm = {
                    pendingClear = null
                    expandedId = null
                    when (action) {
                        ClearAction.SHOWN -> viewModel.clearShown()
                        ClearAction.SELECTION -> viewModel.delete(selection)
                    }
                    selectedIds = emptySet()
                },
            )
        }

        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (!accessState.isEnabled) {
                NotificationsDisabledBanner(
                    onFixClick = { accessState.request() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (hasAny) {
                LevelFilterRow(
                    selected = selected,
                    counts = counts,
                    onToggle = viewModel::toggle,
                    onSetAll = viewModel::setAllSelected,
                )
            }

            if (visible.isEmpty()) {
                // Distinguishes "nothing has arrived" from "the filter is hiding everything", so an
                // empty screen never looks like lost messages.
                EmptyState(if (hasAny) R.string.empty_filtered else R.string.empty_state)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = visible, key = { it.id }) { notification ->
                        SwipeToDeleteBox(
                            // Swiping is disabled mid-selection: the same drag would otherwise
                            // both pick a row and destroy it.
                            enabled = !selectionMode,
                            onDelete = { pendingDelete = notification },
                            modifier = Modifier.animateItem(),
                        ) {
                        NotificationCard(
                            notification = notification,
                            expanded = expandedId == notification.id,
                            selected = notification.id in selectedIds,
                            onClick = {
                                // While selecting, a tap adjusts the selection; otherwise it
                                // expands, which is what a tap has always done here.
                                if (selectionMode) {
                                    selectedIds = if (notification.id in selectedIds) {
                                        selectedIds - notification.id
                                    } else {
                                        selectedIds + notification.id
                                    }
                                } else {
                                    expandedId = if (expandedId == notification.id) {
                                        null
                                    } else {
                                        notification.id
                                    }
                                }
                            },
                            onLongClick = {
                                // Long press starts a selection, and collapses the open card so the
                                // rows line up while picking.
                                expandedId = null
                                selectedIds = selectedIds + notification.id
                            },
                        )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotificationCard(
    notification: NotificationEntity,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val accent = accentColor(notification.color, notification.level)

    // Recomputed per recomposition of this item rather than cached: pruning deletes files behind the
    // UI's back, so a stale "cached" flag would leave a permanently broken thumbnail.
    val imageFile = notification.logId
        ?.let { NotificationImageStore.fileFor(context, it) }
        ?.takeIf { it.exists() }

    // Local copy first so recent alerts still show their picture with no connection; otherwise fall
    // back to the URL and let Coil fetch it as the row scrolls into view. Coil keys its caches by
    // model, so alerts sharing a URL share one download, one disk entry and one decoded bitmap.
    val imageModel: Any? = imageFile ?: notification.imageUrl?.takeIf { it.isNotBlank() }

    // Tapping opens the cached file through the FileProvider when we hold one, and otherwise sends
    // the original URL out, since there is no local file to grant access to.
    val onImageClick: () -> Unit = {
        if (imageFile != null) {
            context.openImage(imageFile)
        } else {
            notification.imageUrl?.let { context.openUrl(it) }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .animateContentSize(),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Accent stripe carries the level/hex colour without competing with the text.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(modifier = Modifier.padding(16.dp)) {
                if (expanded) {
                    ExpandedContent(notification, imageModel, onImageClick)
                } else {
                    CollapsedContent(notification, imageModel)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = formatTimestamp(notification.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CollapsedContent(notification: NotificationEntity, imageModel: Any?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(
                text = notification.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (notification.body.isNotBlank()) {
                Text(
                    text = notification.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ExpandedContent(
    notification: NotificationEntity,
    imageModel: Any?,
    onImageClick: () -> Unit,
) {
    Column {
        Text(
            text = notification.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (notification.body.isNotBlank()) {
            Text(
                text = notification.body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = stringResource(R.string.image_open),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    // The card is width-constrained and downsamples, so detail is only reachable in
                    // a real viewer with its own zoom.
                    .clickable(onClick = onImageClick),
            )
        }
    }
}

/**
 * Swipe a row in either direction to delete it.
 *
 * Deliberately no confirmation dialog: the undo snackbar the caller raises is the safety net, which
 * is the familiar bargain from a mail or messaging app. Asking first would make the gesture slower
 * than the bin it exists to shortcut.
 *
 * Both directions do the same thing. There is only one action, and a one-way swipe would leave the
 * other half of the gesture silently inert.
 *
 * The row must be dragged most of the way across before a release deletes it, and the background
 * only turns full error red once it has passed that point. A list is scrolled far more often than
 * it is pruned, so the gesture is tuned to be hard to trigger by accident and to announce itself
 * while there is still time to drag back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteBox(
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // Returning true commits the dismissal. The row leaves the list immediately because the
            // caller hides it, so the box is never left stranded in a dismissed state.
            if (value == SwipeToDismissBoxValue.Settled) false else { onDelete(); true }
        },
        // Well past the default half-width, so a glancing drag during a scroll settles back.
        positionalThreshold = { distance -> distance * COMMIT_FRACTION },
    )

    // rememberSwipeToDismissBoxState is saveable, and LazyColumn keeps saved state per item key, so
    // a row brought back by Undo returns still marked dismissed: its content parked off-screen with
    // the red background stranded in view. Snap it home as it re-enters composition.
    LaunchedEffect(Unit) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) {
            state.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    // Where the row would land if released right now, which is exactly the question the background
    // needs to answer: let go here and this is deleted.
    val willDelete = state.targetValue != SwipeToDismissBoxValue.Settled

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        gesturesEnabled = enabled,
        backgroundContent = {
            val background by animateColorAsState(
                targetValue = if (willDelete) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                label = "swipeBackground",
            )
            val tint = if (willDelete) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .padding(horizontal = 24.dp),
            ) {
                // Shown at both ends so the icon is visible whichever way the row is dragged.
                listOf(Alignment.CenterStart, Alignment.CenterEnd).forEach { alignment ->
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.align(alignment),
                    )
                }
            }
        },
        content = { content() },
    )
}

/** Fraction of the row's width a swipe must cross before releasing deletes it. */
private const val COMMIT_FRACTION = 0.75f

/** What a confirmation dialog is standing in for. */
private enum class ClearAction { SHOWN, SELECTION }

/**
 * Opens a cached image in whatever app handles pictures.
 *
 * The file is served through a FileProvider rather than copied anywhere: the viewer gets a
 * read-only content:// URI it can display and pass on to a share target, and the bytes stay in the
 * app's own storage, still subject to the ten-newest pruning.
 *
 * A device with no image viewer is possible, so the failure is swallowed rather than crashing on
 * ActivityNotFoundException; the picture is already visible in the expanded card either way.
 */
private fun Context.openImage(file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(this, "$packageName.images", file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                // Explicit type because the cached file keeps the sender's bytes under a .jpg name,
                // so the extension is not reliable enough to infer from.
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }
}

/**
 * Opens the original image URL when no local copy survives, so a pruned alert is still reachable.
 */
private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

/**
 * Hands text to the system share sheet. Text only: see [ShareText].
 */
private fun Context.shareNotifications(text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, getString(R.string.share_chooser)))
}

/**
 * The filter row. Chips carry their unfiltered count, so a deselected chip still advertises how
 * much it is hiding rather than looking like an empty category.
 *
 * Horizontally scrollable because five chips plus counts overflow a narrow screen, and wrapping
 * them onto a second line would push the list down on every launch.
 */
@Composable
private fun LevelFilterRow(
    selected: Set<LevelFilter>,
    counts: Map<LevelFilter, Int>,
    onToggle: (LevelFilter) -> Unit,
    onSetAll: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allSelected = selected == LevelFilter.ALL
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // An AssistChip rather than a sixth FilterChip: this performs an action instead of
        // representing a category, and its label states what the tap will do rather than what is
        // currently true, so it never reads as a toggle that is already on.
        AssistChip(
            onClick = { onSetAll(!allSelected) },
            label = {
                Text(
                    stringResource(
                        if (allSelected) R.string.filter_select_none else R.string.filter_select_all
                    )
                )
            },
        )
        Spacer(Modifier.width(4.dp))

        LevelFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter in selected,
                onClick = { onToggle(filter) },
                label = { Text("${stringResource(filter.label)} (${counts[filter] ?: 0})") },
            )
        }
    }
}

/**
 * Confirms a destructive clear, naming exactly what is about to go.
 *
 * The selected variant lists the categories by name because the filter chips scroll and may be
 * off-screen when the menu is used; "Clear selected" alone would leave the user guessing. The
 * all variant mentions hidden messages for the opposite reason: it deletes rows the filter is
 * currently keeping out of sight.
 */
@Composable
private fun ClearConfirmationDialog(
    action: ClearAction,
    shown: List<NotificationEntity>,
    selection: List<NotificationEntity>,
    hiddenCount: Int,
    selectedFilters: Set<LevelFilter>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val conjunction = stringResource(R.string.list_conjunction)
    val title: String
    val message: String
    when (action) {
        ClearAction.SHOWN -> {
            // Only categories actually contributing a row are named: listing a selected-but-empty
            // one would overstate what is about to be deleted.
            val names = LevelFilter.entries
                .filter { filter -> filter in selectedFilters && shown.any { LevelFilter.of(it) == filter } }
                .map { stringResource(it.label) }
            val scope = LevelFilter.joinLabels(names, conjunction)
            title = stringResource(R.string.clear_shown_title)
            message = if (hiddenCount > 0) {
                // Says what survives, because the bin's scope is the filter and the filter may be
                // scrolled out of view by the time the menu is used.
                stringResource(R.string.clear_shown_message_hidden, shown.size, scope, hiddenCount)
            } else {
                stringResource(R.string.clear_shown_message, shown.size, scope)
            }
        }
        ClearAction.SELECTION -> {
            title = stringResource(R.string.delete_selection_title)
            message = stringResource(R.string.delete_selection_message, selection.size)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.dialog_clear)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/**
 * Theme-aware counterpart to [NotificationAccent.trayColor]. Same hues, adjusted per light/dark so
 * a level colour stays legible against the surface.
 */
@Composable
private fun accentColor(color: String?, level: String?): Color {
    val dark = isSystemInDarkTheme()
    NotificationAccent.parseHex(color)?.let { return Color(it) }
    NotificationAccent.levelOf(level)?.let {
        return Color(if (dark) it.darkColor else it.lightColor)
    }
    return MaterialTheme.colorScheme.primary
}

/**
 * Shown whenever notifications cannot reach the user. Not dismissible: the app has no purpose
 * without notification access, so a banner the user can permanently hide would be a dead end.
 */
@Composable
private fun NotificationsDisabledBanner(onFixClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.permission_banner_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.permission_banner_body),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(
                onClick = onFixClick,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.permission_banner_action))
            }
        }
    }
}

@Composable
private fun EmptyState(@StringRes message: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

private val DateAndTime = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

/**
 * Always qualified with the date. Alerts are read long after they arrive and often out of order, so
 * a bare time forces the reader to work out which day it belonged to.
 */
private fun formatTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateAndTime)
