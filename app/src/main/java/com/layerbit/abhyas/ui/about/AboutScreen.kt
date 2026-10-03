package com.layerbit.abhyas.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.layerbit.abhyas.BuildConfig
import com.layerbit.abhyas.brand.BrandLinks
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.ui.components.screenPadding
import com.layerbit.abhyas.ui.components.Card
import com.layerbit.abhyas.ui.components.ScreenTitle
import com.layerbit.abhyas.ui.components.SectionLabel
import com.layerbit.abhyas.ui.components.TextLink
import com.layerbit.abhyas.ui.components.StatRow
import com.layerbit.abhyas.ui.repositoryViewModel
import com.layerbit.abhyas.ui.theme.AbhyasColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit

class AboutViewModel(repository: AbhyasRepository) : ViewModel() {

    private val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)

    val totalReviews = repository.totalReviews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val reviewsThisWeek = repository.reviewsSince(weekAgo)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val activeDays = repository.dailyCounts(weekAgo)
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
                text = "Back",
                color = AbhyasColors.Muted,
                onClick = onBack,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(18.dp))
            ScreenTitle("Abhyas", "Version ${BuildConfig.VERSION_NAME}")
        }

        item {
            Card {
                SectionLabel("YOUR PRACTICE", color = AbhyasColors.Dim, fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(14.dp))
                StatRow(
                    listOf(
                        total.toString() to "ANSWERS EVER",
                        week.toString() to "THIS WEEK",
                        days.size.toString() to "DAYS OF 7"
                    )
                )
            }
        }

        item {
            Card {
                Text("Nothing leaves your phone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Abhyas does not have permission to use the internet at all. Not " +
                        "restricted, not promised in a policy - the permission is stripped out " +
                        "of the app, so the operating system will not let it open a connection " +
                        "even if it tried. Your pages are read and your cards are written " +
                        "entirely on this device.",
                    color = AbhyasColors.Muted,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "The cost of that is real and accepted: no accounts, no sync, no " +
                        "crash reports. If something breaks, we only find out because you " +
                        "tell us.",
                    color = AbhyasColors.Dim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }

        item {
            Card {
                Text("How the scheduling works", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A new card comes back after a minute, then ten, until you have " +
                        "actually recalled it. After that the gap grows each time you get it " +
                        "right - a day, then a few, then weeks. Forget one and it drops back " +
                        "to minutes, but keeps its history rather than starting over.",
                    color = AbhyasColors.Muted,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Answer honestly. The schedule is only useful if it knows what you " +
                        "actually remember.",
                    color = AbhyasColors.Dim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }

        item {
            Card {
                SectionLabel("Support", fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                LinkRow("Send feedback") {
                    BrandLinks.sendEmail(
                        context,
                        subject = "Abhyas ${BuildConfig.VERSION_NAME} feedback",
                        body = ""
                    )
                }
                LinkRow("WhatsApp") { BrandLinks.openUrl(context, BrandLinks.WHATSAPP_URL) }
                LinkRow("Buy me a coffee") { BrandLinks.openUrl(context, BrandLinks.COFFEE_URL) }
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
