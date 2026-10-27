package com.aura.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.R
import com.aura.identity.IdentityStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val identityManager: IdentityStore
) : ViewModel() {

    private val _ready = MutableStateFlow(false)
    /** True once the device identity exists — generated while the user reads onboarding. */
    val ready: StateFlow<Boolean> = _ready

    init {
        viewModelScope.launch {
            identityManager.getOrCreate()
            _ready.value = true
        }
    }
}

private data class OnboardingPage(
    @StringRes val title: Int?,
    @StringRes val body: Int,
    @StringRes val closing: Int? = null,
    val isWelcome: Boolean = false
)

private val onboardingPages = listOf(
    OnboardingPage(
        title = null,
        body = R.string.onboarding_welcome,
        isWelcome = true
    ),
    OnboardingPage(
        title = R.string.onboarding_what_title,
        body = R.string.onboarding_what_body
    ),
    OnboardingPage(
        title = R.string.onboarding_cannot_title,
        body = R.string.onboarding_cannot_body
    ),
    OnboardingPage(
        title = R.string.onboarding_for_title,
        body = R.string.onboarding_for_body,
        closing = R.string.onboarding_closing
    )
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onOpenLegal: (String) -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val ready by viewModel.ready.collectAsState()
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()
    val lastIndex = onboardingPages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp)
    ) {
        // Skip → jump to the final page.
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Spacer(Modifier.weight(1f))
            if (pagerState.currentPage < lastIndex) {
                TextButton(onClick = { scope.launch { pagerState.scrollToPage(lastIndex) } }) {
                    Text(stringResource(R.string.action_skip))
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { index ->
            PageContent(onboardingPages[index])
        }

        // Page dots
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(onboardingPages.size) { i ->
                val active = i == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (active) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
            if (pagerState.currentPage < lastIndex) {
                Button(
                    onClick = { scope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.action_continue)) }
            } else {
                if (!ready) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.onboarding_generating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onDone() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.onboarding_get_started)) }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.onboarding_agree),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        // FlowRow: longer translations wrap whole links instead of mid-word.
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(onClick = { onOpenLegal("terms") }) { Text(stringResource(R.string.legal_terms)) }
                            Text(
                                stringResource(R.string.onboarding_and),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                            TextButton(onClick = { onOpenLegal("privacy") }) { Text(stringResource(R.string.legal_privacy)) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = if (page.isWelcome) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        if (page.isWelcome) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(140.dp).clip(CircleShape).background(Color(0xFFF8F2F6))
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Aurora",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(page.body),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            page.title?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(20.dp))
            }
            Text(
                stringResource(page.body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            page.closing?.let {
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
