package com.vector.console.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vector.console.data.FaceCrop
import com.vector.console.data.FaceMatch
import com.vector.console.data.FaceSearch
import com.vector.console.data.Store
import com.vector.console.ui.components.DataRow
import com.vector.console.ui.components.DividerLine
import com.vector.console.ui.components.GoldButton
import com.vector.console.ui.components.LoadingLine
import com.vector.console.ui.components.OutlineActionButton
import com.vector.console.ui.components.PanelCard
import com.vector.console.ui.components.ScreenHeader
import com.vector.console.ui.components.SectionLabel
import com.vector.console.ui.components.Tag
import com.vector.console.ui.components.VectorTextField
import com.vector.console.ui.theme.Alert
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.BoneDim
import com.vector.console.ui.theme.Green
import com.vector.console.ui.theme.Hairline
import com.vector.console.ui.theme.Ink
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FaceScreen(store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var crops by remember { mutableStateOf<List<FaceCrop>>(emptyList()) }
    var selected by remember { mutableIntStateOf(0) }
    var faces by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var resultUrl by remember { mutableStateOf<String?>(null) }
    var publicUrl by remember { mutableStateOf("") }

    var compareBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var compareCrop by remember { mutableStateOf<Bitmap?>(null) }
    var compareFaces by remember { mutableIntStateOf(0) }
    var match by remember { mutableStateOf<FaceMatch?>(null) }

    val crop: Bitmap? = crops.getOrNull(selected)?.bitmap

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            resultUrl = null
            match = null
            status = "Reading file"
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("File could not be opened.")
                val bitmap = FaceSearch.decode(bytes)
                status = "Detecting faces"
                val found = FaceSearch.allFaces(bitmap)
                source = bitmap
                crops = found
                selected = 0
                faces = if (found.isNotEmpty()) found.size else 0
            }.onFailure { error = it.message }
            status = null
            busy = false
        }
    }

    val comparePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            error = null
            match = null
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("File could not be opened.")
                val bitmap = FaceSearch.decode(bytes)
                val found = FaceSearch.allFaces(bitmap)
                compareBitmap = bitmap
                compareCrop = found.firstOrNull()?.bitmap ?: bitmap
                compareFaces = found.size
            }.onFailure { error = it.message }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        ScreenHeader(
            kicker = "File 03 · Face",
            title = "Face",
            subtitle = "Detect every face in a photo, pick the subject, then run a reverse-image engine."
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel(
            label = "Subject",
            trailing = when {
                faces > 1 -> "$faces FACES"
                faces == 1 -> "1 FACE"
                else -> "NO IMAGE"
            }
        )
        Spacer(Modifier.height(10.dp))

        PanelCard {
            val shown = crop ?: source
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (shown != null) Modifier.aspectRatio(1f)
                        else Modifier.height(168.dp)
                    )
                    .border(1.dp, Hairline)
                    .background(Ink),
                contentAlignment = Alignment.Center
            ) {
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "Face preview",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "NO IMAGE LOADED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedDeep
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Pick a photo to detect and crop the subject.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedDeep
                        )
                    }
                }
            }

            if (crops.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "${crops.size} faces detected. Tap the subject.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(crops) { index, item ->
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .border(1.dp, if (index == selected) Bone else Hairline)
                                .clickable { selected = index; resultUrl = null }
                        ) {
                            Image(
                                bitmap = item.bitmap.asImageBitmap(),
                                contentDescription = "Face ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(58.dp)
                            )
                            Text(
                                text = (index + 1).toString().padStart(2, '0'),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (index == selected) Ink else Bone,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .background(if (index == selected) Bone else Ink.copy(alpha = 0.78f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            GoldButton(
                label = "Choose image",
                enabled = !busy,
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LoadingLine(label = status?.uppercase() ?: "WORKING")
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
            }
            if (source != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineActionButton(
                        label = "Use full frame",
                        onClick = {
                            crops = emptyList()
                            selected = 0
                            resultUrl = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                    OutlineActionButton(
                        label = "Clear",
                        onClick = {
                            source = null
                            crops = emptyList()
                            selected = 0
                            faces = 0
                            resultUrl = null
                            error = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Reverse search", trailing = "ANONYMOUS")
        Spacer(Modifier.height(10.dp))

        PanelCard {
            Text(
                text = "Yandex accepts anonymous multipart uploads and answers with a normal image-search " +
                    "page. Nothing is stored on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            GoldButton(
                label = if (busy) "Uploading" else "Search on Yandex",
                enabled = !busy && crop != null,
                onClick = {
                    val bitmap = crop ?: return@GoldButton
                    scope.launch {
                        busy = true
                        error = null
                        resultUrl = null
                        runCatching {
                            val jpeg = withContext(Dispatchers.IO) { FaceSearch.toJpeg(bitmap) }
                            FaceSearch.yandexUpload(jpeg)
                        }
                            .onSuccess { target ->
                                resultUrl = target.url
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url)))
                            }
                            .onFailure { error = it.message }
                        busy = false
                    }
                }
            )

            if (store.key("face_endpoint").isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                OutlineActionButton(
                    label = "Send to custom provider",
                    enabled = !busy && crop != null,
                    onClick = {
                        val bitmap = crop ?: return@OutlineActionButton
                        scope.launch {
                            busy = true
                            error = null
                            runCatching {
                                val jpeg = withContext(Dispatchers.IO) { FaceSearch.toJpeg(bitmap) }
                                FaceSearch.customProvider(
                                    jpeg = jpeg,
                                    endpoint = store.key("face_endpoint"),
                                    apiKey = store.key("face_key")
                                )
                            }
                                .onSuccess { target ->
                                    resultUrl = target.url
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(target.url))
                                    )
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    }
                )
            }

            if (busy) {
                Spacer(Modifier.height(8.dp))
                LoadingLine(label = "UPLOADING FILE")
            }
            resultUrl?.let { url ->
                Spacer(Modifier.height(14.dp))
                DividerLine()
                Spacer(Modifier.height(12.dp))
                DataRow("Result", url, Green)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineActionButton(
                        label = "Open again",
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    OutlineActionButton(
                        label = "Copy",
                        onClick = {
                            val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            manager.setPrimaryClip(
                                android.content.ClipData.newPlainText("OVERSIGHT face", url)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Compare", trailing = "ON DEVICE")
        Spacer(Modifier.height(10.dp))

        PanelCard {
            Text(
                text = "Average-hash at 8×8, computed locally. Useful for a quick check whether two " +
                    "photos point at the same person before an external engine is spent.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .border(1.dp, Hairline)
                        .background(Ink),
                    contentAlignment = Alignment.Center
                ) {
                    crop?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Face A",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        )
                    } ?: Text("A", style = MaterialTheme.typography.labelSmall, color = MutedDeep)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .border(1.dp, Hairline)
                        .background(Ink),
                    contentAlignment = Alignment.Center
                ) {
                    compareCrop?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Face B",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        )
                    } ?: Text("B", style = MaterialTheme.typography.labelSmall, color = MutedDeep)
                }
            }
            if (compareFaces > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "$compareFaces faces in photo B. The largest one is used.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedDeep
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineActionButton(
                    label = "Choose photo B",
                    onClick = {
                        comparePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                GoldButton(
                    label = "Compare",
                    enabled = crop != null && compareCrop != null,
                    onClick = {
                        val a = crop ?: return@GoldButton
                        val b = compareCrop ?: return@GoldButton
                        match = FaceSearch.compare(a, b)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            match?.let { result ->
                Spacer(Modifier.height(14.dp))
                DividerLine()
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${result.similarity}% · DISTANCE ${result.distance}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Bone
                    )
                    Tag(
                        text = result.verdict,
                        color = when {
                            result.distance <= 6 -> Green
                            result.distance <= 12 -> BoneDim
                            result.distance <= 20 -> Muted
                            else -> Alert
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The hash reads global texture, not facial geometry. Always confirm through an " +
                        "external engine.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedDeep
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Public URL", trailing = "NO UPLOAD")
        Spacer(Modifier.height(10.dp))

        PanelCard {
            Text(
                text = "If the image is already online, URL engines beat upload engines — they read a full " +
                    "index instead of a single probe.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            VectorTextField(
                value = publicUrl,
                onValueChange = { publicUrl = it },
                placeholder = "https://.../photo.jpg"
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Lens", "Yandex", "Bing", "TinEye").forEach { label ->
                    Tag(text = label, color = Muted)
                }
            }
            Spacer(Modifier.height(14.dp))
            DividerLine()
            Spacer(Modifier.height(6.dp))
            FaceSearch.links(publicUrl.ifBlank { "https://example.com/x.jpg" })
                .forEach { target ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = target.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (publicUrl.startsWith("http")) BoneDim else MutedDeep,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlineActionButton(
                            label = "Open",
                            enabled = publicUrl.startsWith("http"),
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(target.url))
                                )
                            }
                        )
                    }
                    DividerLine()
                }
        }

        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.width(2.dp).height(34.dp).background(Alert))
            Spacer(Modifier.width(12.dp))
            Text(
                text = "A reverse-image hit is a lead, not an identity. Confirm through two independent " +
                    "sources before concluding anything.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedDeep
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}