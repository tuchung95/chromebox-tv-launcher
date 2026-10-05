package local.chromebox.tvlauncher

import android.text.format.DateFormat
import android.view.KeyEvent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CompactCard
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_BROWSER4K
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_CHROME
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** One Android app on the home screen. */
data class AppTile(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap,
    val banner: ImageBitmap?,
    val isFavorite: Boolean
)

data class VoiceChoice(val label: String, val action: () -> Unit)

/** The voice command panel; null in [HomeState.voice] means hidden. */
data class VoicePanel(
    val status: String,
    val transcript: String = "",
    val listening: Boolean = false,
    val choices: List<VoiceChoice> = emptyList()
)

/** Everything the home screen shows. The activity changes it and Compose redraws. */
class HomeState {
    var web by mutableStateOf(emptyList<WebShortcut>())
    var favorites by mutableStateOf(emptyList<AppTile>())
    var apps by mutableStateOf(emptyList<AppTile>())
    var remoteLabel by mutableStateOf("")
    var updateAvailable by mutableStateOf(false)
    var fullscreen by mutableStateOf(true)
    var voice by mutableStateOf<VoicePanel?>(null)

    /** Raised when Back asks to return to the top of the home screen. */
    var backToTop by mutableIntStateOf(0)
}

interface HomeActions {
    fun openWeb(shortcut: WebShortcut)
    fun webMenu(shortcut: WebShortcut)
    fun addWeb()
    fun launchApp(pkg: String)
    fun appMenu(pkg: String, inFavorites: Boolean)
    fun remoteClicked()
    fun updateClicked()
    fun toggleFullscreen()
    fun dismissVoice()
}

// Android TV layout: designed at 960 x 540 dp with 5% overscan margins (see TvDensity)
private val SafeHorizontal = 48.dp
private val SafeVertical = 27.dp
private val CardGap = 20.dp
private val FeatureCardWidth = 268.dp // three cards per row
private val AppCardWidth = 196.dp // four cards per row

private object Palette {
    val BackgroundTop = Color(0xFF121620)
    val BackgroundBottom = Color(0xFF07080C)
    val Surface = Color(0xFF1D2230)
    val SurfaceFocused = Color(0xFF2B3347)
    val Card = Color(0xFF171B25)
    val Text = Color(0xFFF2F4F8)
    val TextDim = Color(0xFF9AA3B4)
    val Accent = Color(0xFF4FB3FF)
    val OnAccent = Color(0xFF03121F)
    val Web = listOf(
        Color(0xFF9C2B23), Color(0xFF1E5AA8), Color(0xFF2E7D5B),
        Color(0xFF6E3C96), Color(0xFF99601F), Color(0xFF1F6F80)
    )
}

@Composable
fun ChromeboxTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.OnAccent,
            background = Palette.BackgroundBottom,
            onBackground = Palette.Text,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Surface,
            onSurfaceVariant = Palette.TextDim,
            border = Palette.Accent
        ),
        content = content
    )
}

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val listState = rememberLazyListState()
    val firstCard = remember { FocusRequester() }

    // Start with a card in focus, so the remote works right away
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstCard.requestFocus() }
    }
    LaunchedEffect(state.backToTop) {
        if (state.backToTop == 0) return@LaunchedEffect
        listState.animateScrollToItem(0)
        runCatching { firstCard.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.BackgroundTop, Palette.BackgroundBottom)))
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = SafeVertical)
        ) {
            item(key = "header") { Header(state, actions) }
            item(key = "web") {
                CardRow(stringResource(R.string.row_watch)) {
                    itemsIndexed(state.web, key = { _, shortcut -> "web:" + shortcut.id }) { index, shortcut ->
                        WebCard(shortcut, actions, if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
                    }
                    item(key = "add") {
                        AddCard(actions, if (state.web.isEmpty()) Modifier.focusRequester(firstCard) else Modifier)
                    }
                }
            }
            if (state.favorites.isNotEmpty()) {
                item(key = "favorites") {
                    CardRow(stringResource(R.string.row_favorites)) {
                        items(state.favorites, key = { "fav:" + it.pkg }) { AppCard(it, inFavorites = true, actions) }
                    }
                }
            }
            item(key = "apps") {
                CardRow(stringResource(R.string.row_apps)) {
                    items(state.apps, key = { "app:" + it.pkg }) { AppCard(it, inFavorites = false, actions) }
                }
            }
        }
    }

    state.voice?.let { VoiceDialog(it, actions) }
}

@Composable
private fun Header(state: HomeState, actions: HomeActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = SafeHorizontal, end = SafeHorizontal, top = SafeVertical, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Clock(Modifier.weight(1f))
        HeaderButton(state.remoteLabel, onClick = actions::remoteClicked)
        if (state.updateAvailable) {
            Button(
                onClick = actions::updateClicked,
                modifier = Modifier.focusOnHover().pointerClick(actions::updateClicked),
                scale = ButtonDefaults.scale(focusedScale = 1.05f),
                colors = ButtonDefaults.colors(
                    containerColor = Palette.Accent,
                    contentColor = Palette.OnAccent,
                    focusedContainerColor = Palette.Text,
                    focusedContentColor = Palette.OnAccent
                )
            ) { Text(stringResource(R.string.update_button_available)) }
        } else {
            HeaderButton(stringResource(R.string.update_button), onClick = actions::updateClicked)
        }
        HeaderButton(
            stringResource(if (state.fullscreen) R.string.fullscreen_exit else R.string.fullscreen_enter),
            onClick = actions::toggleFullscreen
        )
    }
}

@Composable
private fun HeaderButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.focusOnHover().pointerClick(onClick),
        scale = OutlinedButtonDefaults.scale(focusedScale = 1.05f)
    ) { Text(label, maxLines = 1) }
}

@Composable
private fun Clock(modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = System.currentTimeMillis()
        }
    }
    val date = Date(now)
    val locale = Locale.getDefault()
    Column(modifier) {
        Text(
            DateFormat.getTimeFormat(context).format(date),
            style = MaterialTheme.typography.displayMedium,
            color = Palette.Text
        )
        Text(
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(date),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextDim
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CardRow(title: String, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Palette.TextDim,
            modifier = Modifier.padding(start = SafeHorizontal, top = 12.dp)
        )
        LazyRow(
            // Coming back into a row lands on the card that was focused there before
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = SafeHorizontal, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
            content = content
        )
    }
}

private val focusedBorder = Border(BorderStroke(3.dp, Palette.Accent), shape = RoundedCornerShape(12.dp))
private val cardShape = RoundedCornerShape(12.dp)

@Composable
private fun WebCard(shortcut: WebShortcut, actions: HomeActions, modifier: Modifier) {
    val opener = when (shortcut.opener) {
        OPENER_BROWSER4K -> stringResource(R.string.name_browser4k)
        OPENER_CHROME -> stringResource(R.string.name_chrome)
        else -> null // opens inside the launcher, the default
    }
    CompactCard(
        onClick = { actions.openWeb(shortcut) },
        onLongClick = { actions.webMenu(shortcut) },
        modifier = modifier
            .width(FeatureCardWidth)
            .aspectRatio(16f / 9f)
            .focusOnHover()
            .contextMenu { actions.webMenu(shortcut) }
            .pointerClick({ actions.openWeb(shortcut) }, { actions.webMenu(shortcut) }),
        image = { Box(Modifier.fillMaxSize().background(webColor(shortcut.url))) },
        title = {
            Text(
                shortcut.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        },
        subtitle = {
            Text(
                MainActivity.hostOf(shortcut.url),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        },
        description = {
            if (opener != null) {
                Text(
                    opener,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }
        },
        shape = CardDefaults.shape(cardShape),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        border = CardDefaults.border(focusedBorder = focusedBorder),
        glow = CardDefaults.glow(focusedGlow = Glow(Palette.Accent.copy(alpha = 0.35f), 12.dp))
    )
}

@Composable
private fun AddCard(actions: HomeActions, modifier: Modifier) {
    Card(
        onClick = actions::addWeb,
        modifier = modifier
            .width(FeatureCardWidth)
            .aspectRatio(16f / 9f)
            .focusOnHover()
            .pointerClick(actions::addWeb),
        shape = CardDefaults.shape(cardShape),
        colors = CardDefaults.colors(containerColor = Palette.Surface, focusedContainerColor = Palette.SurfaceFocused),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        border = CardDefaults.border(focusedBorder = focusedBorder),
        glow = CardDefaults.glow(focusedGlow = Glow(Palette.Accent.copy(alpha = 0.35f), 12.dp))
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("+", style = MaterialTheme.typography.displaySmall, color = Palette.Accent)
            Text(stringResource(R.string.add_web), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        }
    }
}

@Composable
private fun AppCard(app: AppTile, inFavorites: Boolean, actions: HomeActions) {
    Card(
        onClick = { actions.launchApp(app.pkg) },
        onLongClick = { actions.appMenu(app.pkg, inFavorites) },
        modifier = Modifier
            .width(AppCardWidth)
            .aspectRatio(16f / 9f)
            .focusOnHover()
            .contextMenu { actions.appMenu(app.pkg, inFavorites) }
            .pointerClick({ actions.launchApp(app.pkg) }, { actions.appMenu(app.pkg, inFavorites) }),
        shape = CardDefaults.shape(cardShape),
        colors = CardDefaults.colors(containerColor = Palette.Surface, focusedContainerColor = Palette.SurfaceFocused),
        scale = CardDefaults.scale(focusedScale = 1.1f),
        border = CardDefaults.border(focusedBorder = focusedBorder),
        glow = CardDefaults.glow(focusedGlow = Glow(Palette.Accent.copy(alpha = 0.35f), 12.dp))
    ) {
        Box(Modifier.fillMaxSize()) {
            if (app.banner != null) {
                Image(app.banner, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier.fillMaxSize().padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(app.icon, contentDescription = null, modifier = Modifier.size(44.dp))
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = Palette.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            if (app.isFavorite && !inFavorites) {
                Text(
                    "★",
                    color = Palette.Accent,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun VoiceDialog(panel: VoicePanel, actions: HomeActions) {
    Dialog(
        onDismissRequest = actions::dismissVoice,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 96.dp).fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = Palette.Card, contentColor = Palette.Text)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MicBadge(panel.listening)
                Text(
                    panel.status,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.TextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
                val hint = if (panel.listening && panel.transcript.isEmpty()) stringResource(R.string.voice_hint) else null
                Text(
                    hint ?: panel.transcript,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (hint != null) Palette.TextDim else Palette.Text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                if (panel.choices.isNotEmpty()) {
                    val first = remember(panel.choices) { FocusRequester() }
                    Column(
                        Modifier.padding(top = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        panel.choices.forEachIndexed { index, choice ->
                            Button(
                                onClick = choice.action,
                                modifier = Modifier
                                    .width(360.dp)
                                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                    .focusOnHover()
                                    .pointerClick(choice.action),
                                scale = ButtonDefaults.scale(focusedScale = 1.05f)
                            ) {
                                Text(choice.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    LaunchedEffect(panel.choices) {
                        withFrameNanos { }
                        runCatching { first.requestFocus() }
                    }
                }
            }
        }
    }
}

@Composable
private fun MicBadge(listening: Boolean) {
    val alpha = if (listening) {
        val transition = rememberInfiniteTransition(label = "mic")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "micAlpha"
        ).value
    } else {
        1f
    }
    Box(
        Modifier.size(72.dp).alpha(alpha).background(Palette.Accent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(36.dp))
    }
}

private fun webColor(url: String): Color =
    Palette.Web[Math.floorMod(MainActivity.hostOf(url).hashCode(), Palette.Web.size)]

/** Moves focus to the element under the mouse pointer, so mouse and remote share one highlight. */
@Composable
private fun Modifier.focusOnHover(): Modifier {
    val requester = remember { FocusRequester() }
    return this
        .focusRequester(requester)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent().type == PointerEventType.Enter) runCatching { requester.requestFocus() }
                }
            }
        }
}

/**
 * Compose for TV cards and buttons react to the remote only. On a Chromebox people also use a
 * mouse, so clicks and long presses from the pointer trigger the same actions.
 */
private fun Modifier.pointerClick(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    pointerInput(onClick, onLongClick) {
        detectTapGestures(
            onTap = { onClick() },
            onLongPress = if (onLongClick != null) { _ -> onLongClick() } else null
        )
    }

/** Opens a card's menu with the remote's Menu key or a right click. */
private fun Modifier.contextMenu(open: () -> Unit): Modifier = this
    .onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.type == KeyEventType.KeyUp) open()
            true
        } else {
            false
        }
    }
    .pointerInput(open) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    open()
                }
            }
        }
    }
