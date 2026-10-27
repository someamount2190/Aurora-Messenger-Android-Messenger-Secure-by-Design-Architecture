package com.aura.ui.conversation

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.R
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.db.PairState
import com.aura.disappearing.DisappearingMessages
import com.aura.media.ByteArrayMediaDataSource
import com.aura.media.MediaTransfer
import com.aura.media.VoiceRecorder
import com.aura.pairing.PairingCoordinator
import com.aura.reaction.Reactions
import com.aura.settings.DisappearingTimer
import com.aura.streak.Streaks
import com.aura.transport.MessageSender
import com.aura.transport.rtc.RtcState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    contactId: String,
    onBack: () -> Unit,
    onStartCall: (String, Boolean) -> Unit = { _, _ -> },
    viewModel: ConversationViewModel = hiltViewModel()
) {
    val contact by viewModel.contact.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val myVerifyCode by viewModel.myVerifyCode.collectAsState()
    val verifyError by viewModel.verifyError.collectAsState()
    val streak by viewModel.streak.collectAsState()
    val mediaProgress by viewModel.mediaProgress.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val playingId by viewModel.playingId.collectAsState()
    val callActive by viewModel.callActive.collectAsState()
    val connection by viewModel.connection.collectAsState()
    // Mark the conversation read on open and whenever new messages land while it's visible.
    LaunchedEffect(messages) { if (messages.isNotEmpty()) viewModel.markRead() }
    // Leave the screen if the contact disappears (we deleted it, or the peer removed us).
    // Guarded by a "was loaded" latch so the momentary null on first composition (before
    // the contact flow emits) doesn't bounce us straight back out.
    var contactWasLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(contact) {
        if (contact != null) contactWasLoaded = true
        else if (contactWasLoaded) onBack()
    }
    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var timerMenuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var viewerMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var replyingTo by remember { mutableStateOf<MessageEntity?>(null) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var pendingRecord by remember { mutableStateOf(false) }
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted && pendingRecord) viewModel.startRecording(); pendingRecord = false }
    val tryRecord = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startRecording()
        else { pendingRecord = true; audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
    }

    // Calls must hold mic (and camera, for video) BEFORE the call screen opens the
    // devices — otherwise the first call comes up with a dead/black camera because the
    // capture starts before the permission grant. So request here, then start.
    fun isGranted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    var pendingCallVideo by remember { mutableStateOf<Boolean?>(null) }
    val callPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val video = pendingCallVideo ?: return@rememberLauncherForActivityResult
        pendingCallVideo = null
        val audioOk = result[android.Manifest.permission.RECORD_AUDIO] ?: isGranted(android.Manifest.permission.RECORD_AUDIO)
        if (audioOk != true) return@rememberLauncherForActivityResult   // a call can't run without the mic
        val camOk = result[android.Manifest.permission.CAMERA] ?: isGranted(android.Manifest.permission.CAMERA)
        onStartCall(viewModel.contactId, video && camOk == true)        // camera denied → fall back to a voice call
    }
    val launchCall: (Boolean) -> Unit = { video ->
        val perms = if (video) arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
                    else arrayOf(android.Manifest.permission.RECORD_AUDIO)
        if (perms.all { isGranted(it) }) onStartCall(viewModel.contactId, video)
        else { pendingCallVideo = video; callPermLauncher.launch(perms) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.sendMedia(it, "image") } }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.sendMedia(it, "video") } }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val currentTimer = DisappearingTimer.fromKey(contact?.disappearingTimer)

  Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                contact?.displayName ?: "…",
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (streak >= Streaks.MIN_DISPLAY_DAYS) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "🔥 $streak",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                            }
                        }
                        // One compact status line under the name. Disappearing timer and P2P
                        // reachability share it (each on its own when present), each kept to a
                        // single line so the app bar can't balloon. The full failure guidance
                        // lives in a banner above the messages, not here.
                        if (currentTimer != DisappearingTimer.OFF) {
                            Text(
                                stringResource(R.string.conv_disappearing, stringResource(currentTimer.labelRes)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        val connText = when (connection) {
                            RtcState.CONNECTED -> stringResource(R.string.conv_connected)
                            RtcState.SIGNALING, RtcState.GATHERING, RtcState.CHECKING -> stringResource(R.string.conv_connecting)
                            RtcState.UNREACHABLE -> stringResource(R.string.conv_not_direct)
                            RtcState.FAILED -> stringResource(R.string.conv_offline)
                            RtcState.IDLE -> null
                        }
                        if (connText != null) {
                            Text(
                                connText,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = when (connection) {
                                    RtcState.CONNECTED -> MaterialTheme.colorScheme.primary
                                    RtcState.FAILED, RtcState.UNREACHABLE -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { launchCall(false) }, enabled = !callActive) {
                        Icon(Icons.Default.Call, contentDescription = stringResource(R.string.conv_voice_call))
                    }
                    IconButton(onClick = { launchCall(true) }, enabled = !callActive) {
                        Icon(Icons.Default.Videocam, contentDescription = stringResource(R.string.conv_video_call))
                    }
                    IconButton(onClick = { timerMenuOpen = true }) {
                        Icon(Icons.Default.Timer, contentDescription = stringResource(R.string.conv_timer))
                    }
                    DropdownMenu(expanded = timerMenuOpen, onDismissRequest = { timerMenuOpen = false }) {
                        DisappearingTimer.entries.forEach { timer ->
                            DropdownMenuItem(
                                text = {
                                    val label = stringResource(timer.labelRes)
                                    Text(if (timer == currentTimer) stringResource(R.string.conv_timer_selected, label) else label)
                                },
                                onClick = { timerMenuOpen = false; viewModel.setTimer(timer) }
                            )
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.conv_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conv_rename)) },
                            onClick = { menuOpen = false; showRename = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conv_delete_contact), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; showDelete = true }
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
          if (contact?.pairState == PairState.VERIFY) {
            // Communication is blocked until both sides verify each other's code.
            VerifyPanel(
                myCode = myVerifyCode,
                awaitingPartner = contact?.iVerified == true,
                error = verifyError,
                onSubmit = viewModel::submitVerify
            )
          } else {
            // Honest, full-width guidance when a direct path couldn't be established
            // (the app-bar status is kept to a terse one-liner). The two failure modes
            // get different copy (patch §G): UNREACHABLE = both reachable on the network
            // but no direct path (likely mobile data); FAILED = the peer never answered.
            val failureBanner = when (connection) {
                RtcState.UNREACHABLE ->
                    stringResource(R.string.conv_banner_unreachable)
                RtcState.FAILED ->
                    stringResource(R.string.conv_banner_failed)
                else -> null
            }
            if (failureBanner != null) {
                Text(
                    failureBanner,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        if (message.type == "call") {
                            CallLogRow(message)
                        } else {
                            MessageBubble(
                                message = message,
                                progress = mediaProgress[message.id],
                                isPlaying = playingId == message.id,
                                loadMedia = { path -> viewModel.mediaBytes(path) },
                                onOpenViewer = { viewerMessage = message },
                                onReact = { emoji -> viewModel.react(message, emoji) },
                                onReply = { replyingTo = message },
                                onTogglePlay = { viewModel.togglePlay(message) }
                            )
                        }
                    }
                }
                // Empty new chat: gentle hint to rename (replaces the old name prompt).
                if (messages.isEmpty() && contact?.nicknameSet == false) {
                    Text(
                        stringResource(R.string.conv_rename_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                    )
                }
            }

            // Reply quote above the composer.
            replyingTo?.let { rt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (rt.fromMe) stringResource(R.string.conv_replying_self)
                            else stringResource(R.string.conv_replying_to, contact?.displayName ?: stringResource(R.string.conv_them)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            viewModel.previewOf(rt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { replyingTo = null }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.conv_cancel_reply))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRecording) {
                    IconButton(onClick = { viewModel.cancelRecording() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.conv_cancel_recording),
                            tint = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        stringResource(R.string.conv_recording),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.stopAndSendRecording() }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.conv_send_voice),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    var attachMenuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { attachMenuOpen = true }) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.conv_attach))
                    }
                    DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conv_photo)) },
                            onClick = { attachMenuOpen = false; imagePicker.launch("image/*") }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conv_video)) },
                            onClick = { attachMenuOpen = false; videoPicker.launch("video/*") }
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.conv_message_hint)) },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    if (draft.isBlank()) {
                        IconButton(onClick = { tryRecord() }) {
                            Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.conv_record_voice),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        IconButton(
                            onClick = {
                                viewModel.send(draft, replyingTo)
                                draft = ""
                                replyingTo = null
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.conv_send),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
          }
        }
    }

    if (showRename) {
        var nameDraft by remember { mutableStateOf(contact?.displayName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.conv_rename)) },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(nameDraft)
                    showRename = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showDelete) {
        val name = contact?.displayName ?: stringResource(R.string.conv_this_contact)
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.conv_delete_title)) },
            text = {
                Text(stringResource(R.string.conv_delete_body, name))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.deleteContact()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // (No mandatory naming dialog — the empty chat shows a rename hint instead.)

    // Fullscreen image viewer overlay — inside the Box so it stacks over the
    // Scaffold and stays within the Activity's FLAG_SECURE window.
    val savedToGallery = stringResource(R.string.conv_saved_gallery)
    val saveFailed = stringResource(R.string.conv_save_failed)
    viewerMessage?.let { msg ->
        FullscreenImageViewer(
            message = msg,
            loadMedia = { path -> viewModel.mediaBytes(path) },
            onClose = { viewerMessage = null },
            onSave = {
                viewModel.saveToGallery(msg) { ok ->
                    scope.launch {
                        snackbarHostState.showSnackbar(if (ok) savedToGallery else saveFailed)
                    }
                }
            },
            onShare = { viewModel.shareMedia(msg) }
        )
    }
  }
}
