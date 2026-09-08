package org.paramanuseniorshealth.notices.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.data.NoticeEntity
import org.paramanuseniorshealth.notices.fcm.NoticeImageStore

/**
 * Full-screen view of one notice.
 *
 * This is where the notice is actually *read*. The tray thumbnail is a recognition cue and the
 * headline carries the message, but an A4 page shrunk into a notification is roughly four-point
 * text -- so pinch-to-zoom here is not a nicety, it is the only way the page itself becomes
 * legible to the people this app is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeViewerScreen(
    notice: NoticeEntity?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(notice?.id) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(notice?.logId) {
        val current = notice ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            // The sender's photo first, then the rendered PDF page. A notice may carry both, and
            // the photo is the one chosen deliberately for people to look at.
            val file = NoticeImageStore.cachedImage(context, current.logId)
                ?: NoticeImageStore.cachedPdfRender(context, current.logId)
            file?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
        }
    }

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        // Floor of 1 so the page can never be pinched smaller than the frame and lost; ceiling of 6
        // is enough to read body text on an A4 scan at arm's length.
        scale = (scale * zoomChange).coerceIn(1f, 6f)
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
                    // the PDF was unreachable when the notice arrived. The headline and body above
                    // are still shown, so the notice is never reduced to nothing.
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
