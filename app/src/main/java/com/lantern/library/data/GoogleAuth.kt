package com.lantern.library.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GoogleAuth {
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    private fun options(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .build()

    fun client(activity: Activity): GoogleSignInClient =
        GoogleSignIn.getClient(activity, options())

    fun signInIntent(activity: Activity): Intent = client(activity).signInIntent

    fun lastAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun accountKey(account: GoogleSignInAccount?): String? {
        if (account == null) return null
        return account.id?.takeIf { it.isNotBlank() }
            ?: account.email?.takeIf { it.isNotBlank() }
    }

    fun accountKey(context: Context): String? = accountKey(lastAccount(context))

    fun parseResult(data: Intent?): GoogleSignInAccount? {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return if (task.isSuccessful) task.result else null
    }

    suspend fun driveToken(context: Context): DriveTokenResult = withContext(Dispatchers.IO) {
        val account = lastAccount(context)?.account ?: return@withContext DriveTokenResult.Unavailable
        try {
            val token = GoogleAuthUtil.getToken(context, account, "oauth2:$DRIVE_FILE_SCOPE")
            if (token.isNullOrBlank()) DriveTokenResult.Unavailable else DriveTokenResult.Ok(token)
        } catch (e: UserRecoverableAuthException) {
            val intent = e.intent
            if (intent != null) DriveTokenResult.Recoverable(intent) else DriveTokenResult.Unavailable
        } catch (_: Exception) {
            DriveTokenResult.Unavailable
        }
    }

    fun clearToken(context: Context, token: String) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }

    suspend fun signOutAndClearToken(activity: Activity, context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val androidAccount = lastAccount(context)?.account
            if (androidAccount != null) {
                val token = runCatching {
                    GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_FILE_SCOPE")
                }.getOrNull()
                if (!token.isNullOrBlank()) {
                    runCatching { GoogleAuthUtil.clearToken(context, token) }
                }
            }
        }
        runCatching { Tasks.await(client(activity).signOut()) }
    }
}

sealed class DriveTokenResult {
    data class Ok(val token: String) : DriveTokenResult()
    data class Recoverable(val intent: Intent) : DriveTokenResult()
    object Unavailable : DriveTokenResult()
}
