package com.layerbit.abhyas.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.BuildConfig
import com.layerbit.abhyas.R
import com.layerbit.abhyas.brand.BrandLinks
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.SectionLabel
import com.layerbit.abhyas.ui.components.StatRow
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class AboutViewModel(repository: AbhyasRepository) : ViewModel() {

    val totalReviews = repository.totalReviews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // The window is named in days rather than pinned to a timestamp here, so "this week" still means
    // the last seven days when the screen is opened again tomorrow.
    val reviewsThisWeek = repository.reviewsInLast(days = 7)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val activeDays = repository.dailyCountsInLast(days = 7)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel = repositoryViewModel { AboutViewModel(it) }

    val total by viewModel.totalReviews.collectAsStateWithLifecycle()
    val week by viewModel.reviewsThisWeek.collectAsStateWithLifecycle()
    val days by viewModel.activeDays.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextLink(
                text = stringResource(R.string.action_back),
                color = AbhyasColors.Muted,
                onClick = onBack,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(18.dp))
            ScreenTitle(stringResource(R.string.app_name), stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
        }

        item {
            Card {
                SectionLabel(stringResource(R.string.about_your_practice), color = AbhyasColors.Dim, fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(14.dp))
                StatRow(
                    listOf(
                        total.toString() to stringResource(R.string.about_answers_ever),
                        week.toString() to stringResource(R.string.about_this_week),
                        days.size.toString() to stringResource(R.string.about_days_of_7)
                    )
                )
            }
        }

        item {
            Card {
                Text(stringResource(R.string.about_privacy_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.about_privacy_body),
                    color = AbhyasColors.Muted,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.about_privacy_cost),
                    color = AbhyasColors.Dim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }

        item {
            Card {
                Text(stringResource(R.string.about_scheduling_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.about_scheduling_body),
                    color = AbhyasColors.Muted,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.about_scheduling_honest),
                    color = AbhyasColors.Dim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }

        item {
            Card {
                SectionLabel(stringResource(R.string.about_support), fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                val subject = stringResource(R.string.about_feedback_subject, BuildConfig.VERSION_NAME)
                LinkRow(stringResource(R.string.about_send_feedback)) {
                    BrandLinks.sendEmail(context, subject = subject, body = "")
                }
                LinkRow(stringResource(R.string.about_whatsapp)) { BrandLinks.openUrl(context, BrandLinks.WHATSAPP_URL) }
                LinkRow(stringResource(R.string.about_coffee)) { BrandLinks.openUrl(context, BrandLinks.COFFEE_URL) }
                LinkRow(stringResource(R.string.about_privacy_policy)) {
                    BrandLinks.openUrl(context, BrandLinks.PRIVACY_URL)
                }
                LinkRow(BrandLinks.BRAND_LABEL) {
                    BrandLinks.openUrl(context, BrandLinks.WEBSITE_URL)
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    // The 12dp Spacer used to sit outside the clickable, separating the rows while leaving each one
    // a single text line to hit. Spent as padding inside it, the same pixels make a real target.
    Text(
        text = label,
        color = AbhyasColors.Saffron,
        fontSize = 14.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp)
    )
}
