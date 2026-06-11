package io.github.liki4.peek.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shared display formatting utilities used across multiple screens.
 *
 * Why shared: [formatDuration] was defined in three places (RideScreen,
 * SessionScreen, HistoryScreen) with inconsistent zero-hour formatting;
 * [shareFit] was duplicated with differing File-vs-String signatures.
 */

/** Format [sec] as H:MM:SS (or MM:SS when under 1 hour). */
fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Open the system share sheet for a FIT file via [FileProvider]. */
fun shareFit(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share FIT to…"))
}