package com.layerbit.abhyas.brand

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Company-wide links, the same destinations LayerLink's `core` module and Deja resolve through.
 * Kept in step by hand rather than shared, since Abhyas depends on neither.
 *
 * Opening any of these does not contradict the no-internet guarantee: Abhyas hands a URL to
 * another app through an Intent and that app does the fetching under its own permissions. Abhyas
 * itself still cannot open a socket, and nothing about the user's cards travels with the intent.
 */
object BrandLinks {
    const val WEBSITE_URL = "https://layerbit.co.in"
    /** How the company is named in the UI - never a bare URL. Matches LayerLink's footer. */
    const val BRAND_LABEL = "Layerbit AI"
    const val COFFEE_URL = "https://www.buymeacoffee.com/layerbit"
    const val WHATSAPP_URL = "https://wa.me/916282595823"
    const val SUPPORT_EMAIL = "ceo@layerbit.co.in"
    /** The public privacy policy, served from the repository's GitHub Pages site. */
    const val PRIVACY_URL = "https://vysakhsuresh.github.io/abhyas-android/privacy/"
    const val PLAY_LISTING = "https://play.google.com/store/apps/details?id=com.layerbit.abhyas"

    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device can open it - no browser, or WhatsApp not installed. There is
            // nothing sensible to fall back to, so do nothing rather than crash.
        }
    }

    /**
     * Open the user's mail app with a message to support, optionally carrying [attachment].
     *
     * With an attachment this has to be ACTION_SEND - ACTION_SENDTO is the mailto action and most
     * mail apps ignore a stream on it - so the mailto intent goes on as the *selector*, which limits
     * the chooser to apps that handle email rather than offering every app that can share a file.
     * The read grant is what lets the mail app open a file that lives in Abhyas's private storage.
     */
    fun sendEmail(context: Context, subject: String, body: String, attachment: Uri? = null) {
        val mailto = Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:") }
        val intent = if (attachment == null) {
            Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$SUPPORT_EMAIL")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, attachment)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                selector = mailto
            }
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openUrl(context, WEBSITE_URL)
        }
    }
}
