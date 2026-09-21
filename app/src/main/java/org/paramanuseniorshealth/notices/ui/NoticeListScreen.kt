package org.paramanuseniorshealth.notices.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.data.NoticeEntity
import org.paramanuseniorshealth.notices.data.OfficeInfo
import org.paramanuseniorshealth.notices.fcm.NoticeImageStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Includes the weekday.
 *
 * "Thu 3 Sep" answers the question a reader actually has about a dispensary notice -- which day was
 * this, relative to today -- far faster than a bare date does, and it is the form the notices
 * themselves use ("Blood Test: Every Thu").
 */
private val Stamp = DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a")

/**
 * The notice history, with the standing dispensary information pinned above it.
 *
 * The settings cog is top-right and shown only when nothing is selected: in selection mode that
 * corner belongs to the destructive action, and offering both at once invites the wrong tap on a
 * small screen held in an unsteady hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeListScreen(
    notices: List<NoticeEntity>,
    selected: Set<Long>,
    expandedId: Long?,
    highlightId: Long?,
    officeInfo: OfficeInfo?,
    notificationsBlocked: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onToggleExpanded: (Long) -> Unit,
    onOpenImage: (NoticeEntity) -> Unit,
    /** Notices whose circular is downloading. Held by the view model so it survives scrolling. */
    downloadingPdf: Set<Long>,
    onOpenPdf: (NoticeEntity) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inSelection = selected.isNotEmpty()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Scrolls to the notice the user tapped in the notification shade. The header occupies index 0,
    // so the row index is offset by one.
    LaunchedEffect(highlightId, notices.size) {
        val target = highlightId ?: return@LaunchedEffect
        val index = notices.indexOfFirst { it.id == target }
        if (index >= 0) listState.animateScrollToItem(index + 1)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (inSelection) {
                            stringResource(R.string.selection_count, selected.size)
                        } else {
                            stringResource(R.string.screen_notices)
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (inSelection) {
                        IconButton(onClick = onClearSelection) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.action_exit_selection),
                            )
                        }
                    }
                },
                actions = {
                    if (inSelection) {
                        IconButton(onClick = onDeleteSelected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    } else {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.action_settings),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (notificationsBlocked) {
                NotificationBanner(onOpenNotificationSettings)
            }

            if (notices.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    OfficeInfoHeader(officeInfo) { context.openUrl(it) }
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.empty_state),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Part of the list rather than pinned above it: on a small screen a fixed
                    // header would eat a fifth of the notices, and the hours are the thing you
                    // check before setting out, not while reading.
                    item(key = "office-info") { OfficeInfoHeader(officeInfo) { context.openUrl(it) } }

                    items(notices, key = { it.id }) { notice ->
                        NoticeRow(
                            notice = notice,
                            selected = notice.id in selected,
                            expanded = notice.id == expandedId,
                            highlighted = notice.id == highlightId,
                            inSelection = inSelection,
                            onClick = {
                                if (inSelection) onToggleSelection(notice.id)
                                else onToggleExpanded(notice.id)
                            },
                            onLongClick = { onToggleSelection(notice.id) },
                            onOpenImage = { onOpenImage(notice) },
                            downloading = notice.id in downloadingPdf,
                            onOpenPdf = { onOpenPdf(notice) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Standing information: dispensary and OPD hours.
 *
 * Sized by content rather than to a fixed fraction of the screen. A hard 20% would be a band of
 * empty colour on a large phone and a cramped scroller on a small one, and the point is that these
 * few lines are readable at a glance.
 */
@Composable
private fun OfficeInfoHeader(info: OfficeInfo?, onOpenUrl: (String) -> Unit) {
    if (info == null) return
    val banner = info.html?.takeIf { HtmlBanner.hasContent(it) }
    if (banner == null && info.heading.isBlank() && info.lines.isEmpty()) return

    // Fixed paper colours, identical in light and dark.
    //
    // The banner's colours are chosen in Gmail against a white page: indigo headings, amber and
    // yellow highlights. Dropped onto a dark surface the indigo nearly vanishes and light text on a
    // yellow highlight is unreadable. Rather than reinterpret what the author chose, the card stays
    // paper in both themes -- which is honest, since this is a printed-notice-like object.
    // Warm cream rather than near-white: it separates the standing information from the notice
    // cards below without a border, and reads as a printed notice. Kept clear of the yellow in the
    // highlights, which would lose contrast against anything more saturated.
    val paper = Color(0xFFFAF4E6)
    val ink = Color(0xFF10131A)

    Card(
        colors = CardDefaults.cardColors(containerColor = paper, contentColor = ink),
        // A warm outline a shade deeper than the card. The banner keeps its paper colours in dark
        // mode, so without a border it floats as a bright rectangle with no edge; the outline gives
        // it the containment the notice cards below get from the surface behind them.
        border = BorderStroke(1.dp, Color(0xFFE0D3B0)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // No height cap and no inner scroll.
        //
        // It used to stop at 320dp and scroll inside itself, which nested a scrolling region inside
        // the scrolling list -- a drag starting on the banner fought the list, and at a large system
        // font the banner became a small window onto its own text. The banner is now as tall as it
        // needs to be and the whole list scrolls past it, which is the behaviour a reader expects.
        Column(modifier = Modifier.padding(16.dp)) {
            if (banner != null) {
                // The banner carries its own headings and emphasis, so no separate heading is
                // drawn above it.
                BannerText(html = banner, onOpenUrl = onOpenUrl, color = ink)
            } else {
                if (info.heading.isNotBlank()) {
                    Text(
                        text = info.heading,
                        style = MaterialTheme.typography.titleLarge,
                        color = ink,
                    )
                }
                // Rendered through LinkedText so a web address in the standing information is
                // tappable, the same as one in a notice body.
                info.lines.forEach { line ->
                    LinkedText(
                        text = line,
                        onOpenUrl = onOpenUrl,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink,
                        linkColor = Color(0xFF14315C),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationBanner(onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.permission_banner_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = stringResource(R.string.permission_banner_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        TextButton(onClick = onOpen) {
            Text(stringResource(R.string.permission_banner_action))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoticeRow(
    notice: NoticeEntity,
    selected: Boolean,
    expanded: Boolean,
    highlighted: Boolean,
    inSelection: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenImage: () -> Unit,
    downloading: Boolean,
    onOpenPdf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Local copy first, so a notice still shows its picture with no connection; the URL is the
    // fallback for a render that has since been pruned. Coil keys its caches by model, so notices
    // sharing a URL share one download and one decoded bitmap.
    val photo: File? = NoticeImageStore.cachedImage(context, notice.logId)
    val pdfRender: File? = NoticeImageStore.cachedPdfRender(context, notice.logId)
    val linkImage: File? = NoticeImageStore.cachedLinkImage(context, notice.logId)
    // Local copy first, so a notice still shows its picture with no connection; the URL is the
    // fallback for a render that has since been pruned. A link-only notice falls back to the card's
    // logo, so a row is never left with nothing where every other row has something.
    val thumbModel: Any? = photo ?: pdfRender ?: linkImage
        ?: notice.imageUrl?.takeIf { it.isNotBlank() }
        ?: notice.linkImage?.takeIf { it.isNotBlank() }

    // A row expanding onto nothing.
    //
    // The body is shown in full whether the row is open or closed -- it is never truncated -- so
    // with no picture, no circular and no link card, expanding changes exactly one thing: the body
    // becomes tappable text. That is not worth a gesture, and a chevron promising more where there
    // is none is worse than no chevron at all. Such a row is simply drawn open and does not react
    // to a tap.
    val lean = thumbModel == null &&
        notice.pdfUrl.isNullOrBlank() &&
        notice.linkUrl.isNullOrBlank()
    val open = expanded || lean

    // Rotated rather than swapped for a second drawable, so the arrow travels between its two
    // meanings instead of blinking from one to the other.
    val chevronTurn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        label = "row-chevron",
    )

    val target = MaterialTheme.colorScheme.let {
        when {
            selected -> it.secondaryContainer
            highlighted -> it.tertiaryContainer
            else -> it.surfaceVariant
        }
    }
    // Animated so the highlight fades out instead of snapping, which reads as the app settling
    // rather than as something having gone wrong.
    val container by animateColorAsState(targetValue = target, label = "row-container")

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = modifier
            .fillMaxWidth()
            // Long-press enters selection; once in it a plain tap selects rather than expands, so
            // the gesture does not change meaning halfway through a multi-select. A lean row has
            // nothing to expand, so its tap is inert -- except in selection mode, where every row
            // must stay selectable regardless of what it carries.
            .combinedClickable(
                onClick = { if (!lean || inSelection) onClick() },
                onLongClick = onLongClick,
            )
            .animateContentSize(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thumbModel != null && !open) {
                    AsyncImage(
                        model = thumbModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = notice.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (!lean) {
                    // Not an IconButton: the whole card already handles the tap, and a nested
                    // clickable here would swallow it and give the arrow a different meaning from
                    // the card it sits on. This is the sign, not a second control.
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more),
                        contentDescription = stringResource(
                            if (open) R.string.action_collapse else R.string.action_expand
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(32.dp)
                            .rotate(chevronTurn),
                    )
                }
            }

            // Rules the title off from the notice once the card is open, so an expanded card reads
            // as three bands -- who it is from, what it says, when it arrived -- rather than as a
            // wall of text with an arrow floating over it. Closed, the card stays a single block.
            if (open) {
                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            }

            if (open) {
                if (notice.body.isNotBlank()) {
                    LinkedText(
                        text = notice.body,
                        modifier = Modifier.padding(top = 12.dp),
                        onOpenUrl = { context.openUrl(it) },
                    )
                }

                NoticeAttachments(
                    notice = notice,
                    photo = photo,
                    pdfRender = pdfRender,
                    // The same text a multi-select share produces, so a notice passed on from here
                    // and one passed on from there read identically to whoever receives it.
                    shareText = ShareText.build(listOf(notice)),
                    downloading = downloading,
                    onOpenImage = onOpenImage,
                    onOpenPdf = onOpenPdf,
                    onLongClick = onLongClick,
                )
            } else if (notice.body.isNotBlank()) {
                Text(
                    text = notice.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // The date sits below a rule at the foot of the card, apart from the notice itself, so
            // the title and body read as the message and the stamp as a detail about it.
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Text(
                text = Instant.ofEpochMilli(notice.receivedAt)
                    .atZone(ZoneId.systemDefault())
                    .format(Stamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Delegates so there is one implementation of "open a link", shared with the attachment path. */
private fun Context.openUrl(url: String) = AttachmentActions.openUrl(this, url)
