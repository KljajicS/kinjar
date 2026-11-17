package app.kinjar.ui.projects

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.kinjar.R
import app.kinjar.domain.Project
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProjectReportDialog(project: Project, data: ProjectData, today: LocalDate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val report = projectReport(project, data.book, today, LocalLocale.current.platformLocale) { resources.getString(it) }
    val excludedCount = data.book.categories.count { it.projectId == project.id && it.excludedFromReport }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val files by produceState<List<File>?>(null, report) {
        value = null
        failed = false
        try {
            value = withContext(Dispatchers.IO) {
                writeProjectReportImages(report, File(context.cacheDir, "reports"), resources.getString(R.string.project_report_adjustment), resources.getString(R.string.project_monthly_expenses))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.project_report_export)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                if (excludedCount > 0) item { Hint(stringResource(R.string.project_report_excluded_notice, excludedCount)) }
                if (failed) item { Text(stringResource(R.string.project_report_error)) }
                if (files == null && !failed) item { CircularProgressIndicator() }
                items(files.orEmpty(), key = { it.path }) { file ->
                    val preview by produceState<android.graphics.Bitmap?>(null, file) {
                        value = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                        }
                    }
                    preview?.let { Image(it.asImageBitmap(), stringResource(R.string.project_report_export), Modifier.fillMaxWidth()) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.dialog_cancel)) } },
        confirmButton = {
            TextButton(enabled = !busy && !files.isNullOrEmpty(), onClick = {
                busy = true
                failed = false
                scope.launch {
                    try {
                        val uris = ArrayList(requireNotNull(files).map { file ->
                            FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                        })
                        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_SUBJECT, project.name)
                            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
                            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            clipData = ClipData.newUri(context.contentResolver, project.name, uris.first()).apply {
                                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                            }
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, resources.getString(R.string.project_report_share)))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.project_report_share)) }
        })
}
