package app.kinjar.data

import android.content.Context
import java.io.File

fun deviceId(context: Context): String = InstallationIdentity.load(File(context.noBackupFilesDir, "install-id"))
