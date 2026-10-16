package org.paramanuseniorshealth.notices.ui

import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.paramanuseniorshealth.notices.NoticesApplication
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.analytics.AnalyticsEvent
import org.paramanuseniorshealth.notices.data.NoticeEntity
import org.paramanuseniorshealth.notices.fcm.NoticeImageStore
import java.io.File

/**
 * Full-screen view of one notice.
 *
 * This is where the notice is actually *read*. The tray thumbnail is a recognition cue and the
 * headline carries the message, but an A4 page shrunk into a notification is roughly four-point
 * text -- so pinch-to-zoom here is not a nicety, it is the only way the page itself becomes
 * legible to the people this app is for.
 *
 * Kept alongside the hand-off to other apps rather than replaced by it. Opening here is instant,
 * offline, and free of an app-chooser dialog, which matters for the audience; the overflow menu is
 * for the reader who wants their own gallery, or wants to keep a copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeViewerScreen(
    notice: NoticeEntity?,
    onBack: () -> Unit,
    shareText: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(notice?.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var file by remember(notice?.id) { mutableStateOf<File?>(null) }

    LaunchedEffect(notice?.logId) {
        val current = notice ?: return@LaunchedEffect
        // Image only. A PDF never reaches this screen: its thumbnail attachment tap goes straight
        // to an external reader (see NoticeViewModel.openPdf), and cachedPdfRender is a rendered
        // page one, display-only -- resolving it here would only produce a picture the user cannot
        // act on, exactly the confusion the one-attachment redesign removes.
        val found = withContext(Dispatchers.IO) {
            NoticeImageStore.cachedImage(context, current.logId)
        }
        file = found
        bitmap = withContext(Dispatchers.IO) {
            found?.let { NoticeImageStore.decodeDownsampled(it) }
        }
    }

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // The largest zoom reached, for the close event. A plain holder written from the transform
    // callback below, which runs on every pinch frame: nothing there may log, and nothing reads
    // this until the screen is left.
    val peak = remember { PeakScale() }
    val noticeId by rememberUpdatedState(notice?.id)
    val lifecycleOwner = LocalLifecycleOwner.current

    // Reports how long the viewer was open, and is this screen's own effect rather than a hook on
    // the back path: that path is shared with Settings and sees neither backgrounding nor a
    // configuration change. Only time with the activity started counts. A phone left on this
    // screen overnight, or put in a pocket, is not someone reading.
    //
    // A configuration change disposes and recreates this screen, so it reports twice, each covering
    // its own part of one viewing. Durations for a notice must therefore be SUMMED per notice id,
    // never maxed: several rows can belong to one reading session (a rotation, or a trip out and
    // back), and a reader can also open the same notice twice.
    //
    // A process killed while in the background reports nothing; the event is written on leaving,
    // and there is no leaving to observe. The longest sessions are the likeliest to be killed, so
    // the recorded durations are biased slightly short.
    DisposableEffect(lifecycleOwner) {
        var startedAt: Long? = null
        var openMs = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_STOP -> startedAt?.let {
                    openMs += SystemClock.elapsedRealtime() - it
                    startedAt = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            startedAt?.let { openMs += SystemClock.elapsedRealtime() - it }
            noticeId?.let { id ->
                (context.applicationContext as NoticesApplication).analytics.log(
                    AnalyticsEvent.ImageViewerClosed(
                        noticeId = id,
                        durationMs = openMs,
                        maxScale = peak.value,
                    )
                )
            }
        }
    }

    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        // Floor of 1 so the page can never be pinched smaller than the frame and lost; ceiling of 6
        // is enough to read body text on an A4 scan at arm's length.
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        peak.value = maxOf(peak.value, scale)
        offsetX += panChange.x
        offsetY += panChange.y
        if (scale == 1f) {
            offsetX = 0f
            offsetY = 0f
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(notice?.title.orEmpty(), maxLines = 2) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Offered only for a picture that is actually on this phone. A menu item that
                    // shares nothing, because the render was pruned, is worse than no menu item.
                    file?.let { ViewerActions(file = it, shareText = shareText) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (notice != null && notice.body.isNotBlank()) {
                Text(
                    text = notice.body,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val image = bitmap
                if (image != null) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = notice?.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            )
                            .transformable(transform),
                    )
                } else {
                    // The render is pruned after twenty notices, and it may never have existed if
                    // the attachment was unreachable when the notice arrived. The headline and body
                    // above are still shown, so the notice is never reduced to nothing.
                    Text(
                        text = stringResource(R.string.viewer_no_image),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
        }
    }
}

/** The largest zoom reached in one viewing. A class rather than state: no composition reads it. */
private class PeakScale {
    var value = 1f
}

/** Share / Save, behind the overflow so the picture keeps the screen. */
@Composable
private fun ViewerActions(file: File, shareText: String) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<File?>(null) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val destination: Uri? = result.data?.data
        val source = pending
        pending = null
        if (destination != null && source != null) {
            val saved = AttachmentActions.writeTo(context, destination, source)
            Toast.makeText(
                context,
                if (saved) R.string.toast_saved else R.string.toast_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    IconButton(onClick = { expanded = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_more_vert),
            contentDescription = stringResource(R.string.action_share),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_share)) },
            onClick = {
                expanded = false
                AttachmentActions.share(
                    context = context,
                    file = file,
                    text = shareText,
                    chooserTitle = context.getString(R.string.action_share_chooser),
                )
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_save)) },
            onClick = {
                expanded = false
                pending = file
                saver.launch(AttachmentActions.saveIntent(file.name, AttachmentActions.mimeOf(file)))
            },
        )
    }
}
