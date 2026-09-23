package com.mitsugei.app.ui.search

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mitsugei.app.R
import com.mitsugei.app.data.SearchRepository
import com.mitsugei.app.data.SearchResult
import com.mitsugei.app.ui.theme.GoogleGrey
import com.mitsugei.app.ui.theme.LinkBlue
import com.mitsugei.app.ui.theme.LinkBlueDark
import com.mitsugei.app.util.TtsHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenTools: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    darkTheme: Boolean
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val repo = remember { SearchRepository(context) }
    val tts = remember { TtsHelper(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        focus.clearFocus()
        loading = true
        hasSearched = true
        errorMsg = null
        scope.launch {
            try {
                val resp = repo.search(q)
                results = resp.results
                errorMsg = resp.error
            } finally {
                loading = false
            }
        }
    }

    val linkColor = if (darkTheme) LinkBlueDark else LinkBlue

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Top bar: logo + overflow
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Tools") },
                        onClick = { menuOpen = false; onOpenTools() },
                        leadingIcon = { Icon(Icons.Default.Build, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Account") },
                        onClick = { menuOpen = false; onOpenAccount() },
                        leadingIcon = { Icon(Icons.Default.Person, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Settings & theme") },
                        onClick = { menuOpen = false; onOpenSettings() },
                        leadingIcon = { Icon(Icons.Default.Settings, null) }
                    )
                }
            }
        }

        if (!hasSearched) {
            // Google-style home: big logo + search
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_full),
                    contentDescription = "Mitsugei",
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .heightIn(max = 72.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(28.dp))
                SearchBarField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { doSearch() },
                    onTools = onOpenTools
                )
            }
        } else {
            // Results mode: compact logo + search on top
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_m_logo),
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clickable {
                                hasSearched = false
                                results = emptyList()
                            },
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        SearchBarField(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = { doSearch() },
                            onTools = onOpenTools,
                            compact = true
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(results) { r ->
                    ResultRow(
                        result = r,
                        linkColor = linkColor,
                        grey = GoogleGrey,
                        onOpen = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)))
                            } catch (_: Exception) {}
                        },
                        onSpeak = { tts.speak("${r.title}. ${r.snippet}") }
                    )
                }
                if (!loading && errorMsg != null) {
                    item {
                        Text(
                            errorMsg ?: "",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else if (!loading && results.isEmpty()) {
                    item {
                        Text(
                            "No results found",
                            color = GoogleGrey,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBarField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTools: () -> Unit,
    compact: Boolean = false
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search Mitsugei or type a URL") },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        leadingIcon = {
            if (compact) {
                Icon(Icons.Default.Search, null, tint = GoogleGrey)
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_m_logo),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).clip(CircleShape),
                    contentScale = ContentScale.Fit
                )
            }
        },
        trailingIcon = {
            Row {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, null)
                    }
                }
                IconButton(onClick = onTools) {
                    Icon(Icons.Default.Apps, contentDescription = "Tools")
                }
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        )
    )
}

@Composable
private fun ResultRow(
    result: SearchResult,
    linkColor: Color,
    grey: Color,
    onOpen: () -> Unit,
    onSpeak: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        // Site row: favicon + display URL
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (result.faviconUrl != null) {
                AsyncImage(
                    model = result.faviconUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                result.displayUrl,
                color = grey,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(2.dp))
        // Title — blue link style
        Text(
            result.title,
            color = linkColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 24.sp
        )
        Spacer(Modifier.height(2.dp))
        // Snippet
        Text(
            result.snippet,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            fontSize = 14.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp
        )
        // Optional image thumbnail
        result.imageUrl?.let { img ->
            Spacer(Modifier.height(8.dp))
            AsyncImage(
                model = img,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        }
        // Pronounce
        Row {
            TextButton(onClick = onSpeak, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.VolumeUp, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pronounce", fontSize = 12.sp)
            }
        }
    }
}
