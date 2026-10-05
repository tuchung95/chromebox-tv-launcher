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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.ui.text.input.ImeAction
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
import androidx.tv.material3.Carousel
import androidx.tv.material3.CarouselDefaults
import androidx.tv.material3.CompactCard
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.TabRowDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.rememberCarouselState
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_BROWSER4K
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_CHROME
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_YOUTUBE_TV
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

/** Top navigation, as on Google TV. Search is the icon at the end. */
enum class HomeTab(val label: Int) {
    HOME(R.string.tab_home),
    APPS(R.string.tab_apps),
    WEB(R.string.tab_web),
    SETTINGS(R.string.tab_settings),
    SEARCH(R.string.tab_search)
}

/** Everything the home screen shows. The activity changes it and Compose redraws. */
class HomeState {
    var tab by mutableStateOf(HomeTab.HOME)
    var web by mutableStateOf(emptyList<WebShortcut>())
    var favorites by mutableStateOf(emptyList<AppTile>())
    var apps by mutableStateOf(emptyList<AppTile>())
    var remoteLabel by mutableStateOf("")
    var remoteReady by mutableStateOf(false)
    var updateAvailable by mutableStateOf(false)
    var updateLabel by mutableStateOf("")
    var fullscreen by mutableStateOf(true)
    var voice by mutableStateOf<VoicePanel?>(null)

    /** Raised on Back: focus returns to the top navigation, then to the Home tab. */
    var backRequest by mutableIntStateOf(0)
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
    /** [shortcut] null searches Google. */
    fun searchFor(query: String, shortcut: WebShortcut?)
}

// Android TV layout: designed at 960 x 540 dp with 5% overscan margins (see TvDensity)
private val SafeHorizontal = 48.dp
private val SafeVertical = 27.dp
private val CardGap = 20.dp
private val FeatureCardWidth = 268.dp // three cards per row
private val AppCardWidth = 196.dp // four cards per row
private val HeroHeight = 290.dp

private object Palette {
    val Background = Color(0xFF121418)
    val Surface = Color(0xFF1F232B)
    val SurfaceFocused = Color(0xFF2C323D)
    val Card = Color(0xFF181B21)
    val Text = Color(0xFFF2F4F8)
    val TextDim = Color(0xFF9EA4B0)
    val Accent = Color(0xFF4FB3FF)
    val OnAccent = Color(0xFF03121F)
    val App = Color(0xFF2B4C7E)
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
            background = Palette.Background,
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

/** A web page or a favorite app shown in the featured carousel. */
private sealed interface Featured {
    val key: String

    data class Web(val shortcut: WebShortcut) : Featured {
        override val key get() = "web:" + shortcut.id
    }

    data class App(val app: AppTile) : Featured {
        override val key get() = "app:" + app.pkg
    }
}

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val tabRequesters = remember { HomeTab.entries.associateWith { FocusRequester() } }
    val contentStart = remember { FocusRequester() }
    var navHasFocus by remember { mutableStateOf(false) }
    val homeList = rememberLazyListState()

    // Start inside the content, so OK on the remote opens the featured item right away
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { contentStart.requestFocus() }
    }
    // Back: first to the navigation bar, then to the Home tab
    LaunchedEffect(state.backRequest) {
        if (state.backRequest == 0) return@LaunchedEffect
        if (!navHasFocus) {
            if (state.tab == HomeTab.HOME) homeList.animateScrollToItem(0)
            runCatching { tabRequesters.getValue(state.tab).requestFocus() }
        } else if (state.tab != HomeTab.HOME) {
            state.tab = HomeTab.HOME
            runCatching { tabRequesters.getValue(HomeTab.HOME).requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
    ) {
        TopBar(state, tabRequesters, onNavFocus = { navHasFocus = it })
        Box(Modifier.fillMaxSize()) {
            when (state.tab) {
                HomeTab.HOME -> HomeTabContent(state, actions, homeList, contentStart)
                HomeTab.APPS -> AppsTabContent(state, actions, contentStart)
                HomeTab.WEB -> WebTabContent(state, actions, contentStart)
                HomeTab.SETTINGS -> SettingsTabContent(state, actions, contentStart)
                HomeTab.SEARCH -> SearchTabContent(state, actions, contentStart)
            }
        }
    }

    state.voice?.let { VoiceDialog(it, actions) }
}

// --- Top navigation ---

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(
    state: HomeState,
    tabRequesters: Map<HomeTab, FocusRequester>,
    onNavFocus: (Boolean) -> Unit
) {
    val tabs = HomeTab.entries
    val selected = tabs.indexOf(state.tab)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = SafeHorizontal, end = SafeHorizontal, top = SafeVertical, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).background(Palette.Surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.width(16.dp))
        TabRow(
            selectedTabIndex = selected,
            modifier = Modifier
                .focusRestorer(tabRequesters.getValue(state.tab))
                .onFocusChanged { onNavFocus(it.hasFocus) },
            separator = { Spacer(Modifier.width(4.dp)) },
            indicator = { positions, hasFocus ->
                positions.getOrNull(selected)?.let { TabRowDefaults.PillIndicator(currentTabPosition = it, doesTabRowHaveFocus = hasFocus) }
            }
        ) {
            tabs.forEach { tab ->
                val select = { state.tab = tab }
                val requester = tabRequesters.getValue(tab)
                // A mouse click also moves focus, so the remote continues from the clicked tab
                val click = {
                    state.tab = tab
                    runCatching { requester.requestFocus() }
                    Unit
                }
                Tab(
                    selected = state.tab == tab,
                    onFocus = select,
                    onClick = select,
                    modifier = Modifier
                        .focusRequester(requester)
                        .pointerClick(click)
                ) {
                    if (tab == HomeTab.SEARCH) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(tab.label),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).size(20.dp)
                        )
                    } else {
                        val badge = if (tab == HomeTab.SETTINGS && state.updateAvailable) " •" else ""
                        Text(
                            stringResource(tab.label) + badge,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_mic),
            contentDescription = state.remoteLabel,
            tint = if (state.remoteReady) Palette.Accent else Palette.TextDim,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Clock()
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = System.currentTimeMillis()
        }
    }
    Text(
        DateFormat.getTimeFormat(context).format(Date(now)),
        style = MaterialTheme.typography.titleLarge,
        color = Palette.Text
    )
}

// --- Home tab: featured carousel and rows ---

@Composable
private fun HomeTabContent(state: HomeState, actions: HomeActions, listState: androidx.compose.foundation.lazy.LazyListState, contentStart: FocusRequester) {
    val featured = remember(state.web, state.favorites) {
        (state.web.map { Featured.Web(it) } + state.favorites.map { Featured.App(it) }).take(6)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = SafeVertical)
    ) {
        if (featured.isNotEmpty()) {
            item(key = "hero") { Hero(featured, actions, contentStart) }
        }
        item(key = "web") {
            CardRow(stringResource(R.string.row_watch)) {
                itemsIndexed(state.web, key = { _, shortcut -> "web:" + shortcut.id }) { index, shortcut ->
                    val start = featured.isEmpty() && index == 0
                    WebCard(shortcut, actions, Modifier.width(FeatureCardWidth).then(if (start) Modifier.focusRequester(contentStart) else Modifier))
                }
                item(key = "add") {
                    val start = featured.isEmpty() && state.web.isEmpty()
                    AddCard(actions, Modifier.width(FeatureCardWidth).then(if (start) Modifier.focusRequester(contentStart) else Modifier))
                }
            }
        }
        if (state.favorites.isNotEmpty()) {
            item(key = "favorites") {
                CardRow(stringResource(R.string.row_favorites)) {
                    items(state.favorites, key = { "fav:" + it.pkg }) { AppCard(it, inFavorites = true, actions, Modifier.width(AppCardWidth)) }
                }
            }
        }
        item(key = "apps") {
            CardRow(stringResource(R.string.row_apps)) {
                items(state.apps, key = { "app:" + it.pkg }) { AppCard(it, inFavorites = false, actions, Modifier.width(AppCardWidth)) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Hero(items: List<Featured>, actions: HomeActions, focusRequester: FocusRequester) {
    val carouselState = rememberCarouselState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    // As in Google's JetStream sample, the carousel itself takes focus: left and right change
    // the item, OK opens it, and the "Mở ngay" button only shows what OK will do
    val openActive = { open(items[carouselState.activeItemIndex.coerceIn(0, items.lastIndex)], actions) }
    Carousel(
        itemCount = items.size,
        carouselState = carouselState,
        autoScrollDurationMillis = 7_000,
        modifier = Modifier
            .padding(horizontal = SafeHorizontal)
            .fillMaxWidth()
            .height(HeroHeight)
            .border(if (focused) BorderStroke(3.dp, Palette.Text) else BorderStroke(0.dp, Color.Transparent), shape)
            .clip(shape)
            .onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.type == KeyEventType.KeyUp) openActive()
                    true
                } else {
                    false
                }
            }
            .pointerClick(openActive)
            .focusRequester(focusRequester),
        carouselIndicator = {
            CarouselDefaults.IndicatorRow(
                itemCount = items.size,
                activeItemIndex = carouselState.activeItemIndex,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    ) { index ->
        HeroSlide(items[index], focused)
    }
}

private fun open(item: Featured, actions: HomeActions) = when (item) {
    is Featured.Web -> actions.openWeb(item.shortcut)
    is Featured.App -> actions.launchApp(item.app.pkg)
}

@Composable
private fun HeroSlide(item: Featured, carouselFocused: Boolean) {
    val title = when (item) {
        is Featured.Web -> item.shortcut.title
        is Featured.App -> item.app.label
    }
    val description = when (item) {
        is Featured.App -> stringResource(R.string.hero_app)
        is Featured.Web -> {
            val host = MainActivity.hostOf(item.shortcut.url)
            when (item.shortcut.opener) {
                OPENER_YOUTUBE_TV -> stringResource(R.string.hero_youtube_tv)
                OPENER_BROWSER4K -> stringResource(R.string.hero_browser, stringResource(R.string.name_browser4k), host)
                OPENER_CHROME -> stringResource(R.string.hero_browser, stringResource(R.string.name_chrome), host)
                else -> stringResource(R.string.hero_launcher, host)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        HeroArt(item)
        // Darkens the left side so the text stays readable over any artwork
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.65f to Color.Transparent))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.6f)
                .padding(start = 32.dp, bottom = 28.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Text.copy(alpha = 0.8f),
                maxLines = 2,
                modifier = Modifier.padding(top = 8.dp)
            )
            // Shows what OK does; lights up while the carousel has focus
            val buttonColor = if (carouselFocused) Palette.Text else Color.White.copy(alpha = 0.18f)
            val labelColor = if (carouselFocused) Palette.OnAccent else Palette.Text
            Row(
                Modifier
                    .padding(top = 20.dp)
                    .background(buttonColor, RoundedCornerShape(20.dp))
                    .padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = labelColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.hero_open), style = MaterialTheme.typography.labelLarge, color = labelColor)
            }
        }
    }
}

@Composable
private fun HeroArt(item: Featured) {
    val base = when (item) {
        is Featured.Web -> webColor(item.shortcut.url)
        is Featured.App -> Palette.App
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(lerp(base, Color.Black, 0.55f), base, lerp(base, Color.White, 0.12f))))
    ) {
        // Soft rings give the flat colour some depth
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 80.dp)
                .size(420.dp)
                .background(Color.White.copy(alpha = 0.05f), CircleShape)
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 20.dp, y = 60.dp)
                .size(260.dp)
                .background(Color.White.copy(alpha = 0.05f), CircleShape)
        )
        when (item) {
            is Featured.App -> {
                val banner = item.app.banner
                if (banner != null) {
                    Image(
                        banner,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 56.dp)
                            .width(320.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Image(
                        item.app.icon,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 110.dp).size(150.dp)
                    )
                }
            }
            is Featured.Web -> Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 110.dp)
                    .size(160.dp)
                    .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(36.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (item.shortcut.opener == OPENER_YOUTUBE_TV) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(110.dp))
                } else {
                    Text(
                        item.shortcut.title.take(1).uppercase(),
                        style = MaterialTheme.typography.displayLarge,
                        color = Color.White
                    )
                }
            }
        }
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
            modifier = Modifier.padding(start = SafeHorizontal, top = 20.dp)
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

// --- Apps and Web tabs: grids ---

@Composable
private fun AppsTabContent(state: HomeState, actions: HomeActions, contentStart: FocusRequester) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = SafeHorizontal, end = SafeHorizontal, top = 16.dp, bottom = SafeVertical),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap)
    ) {
        items(state.apps, key = { "app:" + it.pkg }) { app ->
            val start = app == state.apps.firstOrNull()
            AppCard(app, inFavorites = false, actions, Modifier.fillMaxWidth().then(if (start) Modifier.focusRequester(contentStart) else Modifier))
        }
    }
}

@Composable
private fun WebTabContent(state: HomeState, actions: HomeActions, contentStart: FocusRequester) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = SafeHorizontal, end = SafeHorizontal, top = 16.dp, bottom = SafeVertical),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap)
    ) {
        items(state.web, key = { "web:" + it.id }) { shortcut ->
            val start = shortcut == state.web.firstOrNull()
            WebCard(shortcut, actions, Modifier.fillMaxWidth().then(if (start) Modifier.focusRequester(contentStart) else Modifier))
        }
        item(key = "add") {
            AddCard(actions, Modifier.fillMaxWidth().then(if (state.web.isEmpty()) Modifier.focusRequester(contentStart) else Modifier))
        }
    }
}

// --- Search tab ---

@Composable
private fun SearchTabContent(state: HomeState, actions: HomeActions, contentStart: FocusRequester) {
    var query by rememberSaveable { mutableStateOf("") }
    val normalized = VoiceCommands.normalize(query)
    val matchingApps = if (normalized.isEmpty()) emptyList() else state.apps.filter { VoiceCommands.normalize(it.label).contains(normalized) }
    val matchingWeb = if (normalized.isEmpty()) emptyList() else state.web.filter { VoiceCommands.normalize(it.title).contains(normalized) }
    val searchable = state.web.filter { it.search.isNotEmpty() }
    val defaultSearch = searchable.firstOrNull { it.opener == OPENER_YOUTUBE_TV } ?: searchable.firstOrNull()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = SafeVertical)) {
        item(key = "field") {
            Box(
                Modifier
                    .padding(start = SafeHorizontal, end = SafeHorizontal, top = 16.dp)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .background(Palette.Surface, RoundedCornerShape(28.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = Palette.Text),
                    cursorBrush = SolidColor(Palette.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (query.isNotBlank()) actions.searchFor(query.trim(), defaultSearch)
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(contentStart),
                    decorationBox = { field ->
                        if (query.isEmpty()) {
                            Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.titleLarge, color = Palette.TextDim)
                        }
                        field()
                    }
                )
            }
        }
        if (query.isNotBlank()) {
            item(key = "actions") {
                Row(
                    Modifier.padding(start = SafeHorizontal, end = SafeHorizontal, top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    (searchable.map { it.title to it } + (stringResource(R.string.google) to null)).forEach { (site, shortcut) ->
                        val search = { actions.searchFor(query.trim(), shortcut) }
                        Button(
                            onClick = search,
                            modifier = Modifier.pointerClick(search),
                            scale = ButtonDefaults.scale(focusedScale = 1.05f)
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.search_on, site))
                        }
                    }
                }
            }
            if (matchingWeb.isNotEmpty() || matchingApps.isNotEmpty()) {
                item(key = "results") {
                    CardRow(stringResource(R.string.search_results)) {
                        items(matchingWeb, key = { "web:" + it.id }) { WebCard(it, actions, Modifier.width(FeatureCardWidth)) }
                        items(matchingApps, key = { "app:" + it.pkg }) { AppCard(it, inFavorites = false, actions, Modifier.width(AppCardWidth)) }
                    }
                }
            }
        }
    }
}

// --- Settings tab ---

@Composable
private fun SettingsTabContent(state: HomeState, actions: HomeActions, contentStart: FocusRequester) {
    LazyColumn(
        Modifier.widthIn(max = 640.dp),
        contentPadding = PaddingValues(start = SafeHorizontal, end = SafeHorizontal, top = 16.dp, bottom = SafeVertical),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "remote") {
            SettingItem(
                title = stringResource(R.string.remote_title),
                detail = state.remoteLabel,
                onClick = actions::remoteClicked,
                modifier = Modifier.focusRequester(contentStart),
                icon = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(24.dp)) }
            )
        }
        item(key = "update") {
            SettingItem(
                title = stringResource(R.string.update_title),
                detail = state.updateLabel,
                onClick = actions::updateClicked,
                highlight = state.updateAvailable,
                icon = { Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(24.dp)) }
            )
        }
        item(key = "fullscreen") {
            SettingItem(
                title = stringResource(R.string.settings_fullscreen),
                detail = stringResource(if (state.fullscreen) R.string.setting_on else R.string.setting_off),
                onClick = actions::toggleFullscreen,
                icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(24.dp)) }
            )
        }
        item(key = "add") {
            SettingItem(
                title = stringResource(R.string.add_web),
                detail = stringResource(R.string.settings_add_web_detail),
                onClick = actions::addWeb,
                icon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(24.dp)) }
            )
        }
        item(key = "about") {
            SettingItem(
                title = stringResource(R.string.settings_about),
                detail = stringResource(R.string.settings_about_detail),
                onClick = {},
                icon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(24.dp)) }
            )
        }
    }
}

@Composable
private fun SettingItem(
    title: String,
    detail: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier.pointerClick(onClick),
        leadingContent = { icon() },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = if (highlight) Palette.Accent else Color.Unspecified)
        }
    )
}

// --- Cards ---

private val focusedBorder = Border(BorderStroke(3.dp, Palette.Text), shape = RoundedCornerShape(12.dp))
private val cardShape = RoundedCornerShape(12.dp)
private val focusedGlow = Glow(Palette.Accent.copy(alpha = 0.35f), 12.dp)

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
        glow = CardDefaults.glow(focusedGlow = focusedGlow)
    )
}

@Composable
private fun AddCard(actions: HomeActions, modifier: Modifier) {
    Card(
        onClick = actions::addWeb,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .focusOnHover()
            .pointerClick(actions::addWeb),
        shape = CardDefaults.shape(cardShape),
        colors = CardDefaults.colors(containerColor = Palette.Surface, focusedContainerColor = Palette.SurfaceFocused),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        border = CardDefaults.border(focusedBorder = focusedBorder),
        glow = CardDefaults.glow(focusedGlow = focusedGlow)
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(40.dp))
            Text(stringResource(R.string.add_web), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        }
    }
}

@Composable
private fun AppCard(app: AppTile, inFavorites: Boolean, actions: HomeActions, modifier: Modifier) {
    Card(
        onClick = { actions.launchApp(app.pkg) },
        onLongClick = { actions.appMenu(app.pkg, inFavorites) },
        modifier = modifier
            .aspectRatio(16f / 9f)
            .focusOnHover()
            .contextMenu { actions.appMenu(app.pkg, inFavorites) }
            .pointerClick({ actions.launchApp(app.pkg) }, { actions.appMenu(app.pkg, inFavorites) }),
        shape = CardDefaults.shape(cardShape),
        colors = CardDefaults.colors(containerColor = Palette.Surface, focusedContainerColor = Palette.SurfaceFocused),
        scale = CardDefaults.scale(focusedScale = 1.1f),
        border = CardDefaults.border(focusedBorder = focusedBorder),
        glow = CardDefaults.glow(focusedGlow = focusedGlow)
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

// --- Voice panel ---

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

// --- Helpers ---

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
