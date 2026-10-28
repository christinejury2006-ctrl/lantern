package com.lantern.library.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.lantern.library.BuildConfig
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GoogleAuth {
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    private fun options(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .apply { if (BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank()) requestIdToken(BuildConfig.FIREBASE_WEB_CLIENT_ID) }
            .build()

    fun client(activity: Activity): GoogleSignInClient =
        GoogleSignIn.getClient(activity, options())

    fun signInIntent(activity: Activity): Intent = client(activity).signInIntent

    fun lastAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    /** Stable Google user id (`GoogleSignInAccount.id`). Never email. */
    fun accountId(account: GoogleSignInAccount?): String? {
        val id = account?.id?.trim().orEmpty()
        if (id.isEmpty() || '@' in id) return null
        return id
    }

    fun accountId(context: Context): String? = accountId(lastAccount(context))

    fun accountKey(account: GoogleSignInAccount?): String? = accountId(account)

    fun accountKey(context: Context): String? = accountId(lastAccount(context))

    fun parseResult(data: Intent?): GoogleSignInAccount? =
        when (val out = parseOutcome(data)) {
            is GoogleSignInOutcome.Ok -> out.account
            else -> null
        }

    fun parseOutcome(data: Intent?): GoogleSignInOutcome {
        if (data == null) return GoogleSignInOutcome.Cancelled
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            if (account == null) GoogleSignInOutcome.Failed(0, "empty")
            else GoogleSignInOutcome.Ok(account)
        } catch (e: ApiException) {
            if (e.statusCode == 12501) GoogleSignInOutcome.Cancelled
            else GoogleSignInOutcome.Failed(e.statusCode, e.statusMessage.orEmpty())
        } catch (_: Exception) {
            GoogleSignInOutcome.Failed(-1, "unknown")
        }
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

    /**
     * The SHA-1 of the certificate this build was actually signed with, formatted the way the
     * Firebase console wants it (`AB:CD:...`).
     *
     * Google rejects the sign-in sheet with error 10 — "this app isn't registered" — when the
     * Android OAuth client in the Firebase/Cloud project doesn't carry this fingerprint. That is a
     * console setting, not something code can fix, so instead of a dead end the app reports the
     * exact fingerprint to paste in. It is read from the APK's own signature, so it is always the
     * one on the device and can never drift from the keystore CI signed with.
     */
    fun signingFingerprint(context: Context): String? = runCatching {
        val pkg = context.packageName
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(pkg, flags)?.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
                ?.signatures
        }.orEmpty()
        val cert = signatures.firstOrNull()?.toByteArray() ?: return@runCatching null
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(cert)
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    /**
     * Why Google sign-in cannot start on this build, or `null` when it should work.
     *
     * Checked before the sign-in sheet is launched, so the reader is told which console setting is
     * missing while they are still looking at the screen they pressed. Everything here is a fact
     * the app can establish for itself; nothing is guessed.
     */
    fun missingConfiguration(context: Context): String? {
        if (BuildConfig.FIREBASE_WEB_CLIENT_ID.isBlank()) {
            return "This build has no Web OAuth client ID, so Firebase cannot mint a session. " +
                "In Firebase → Project settings → Your apps → com.lantern.library, add the SHA-1 " +
                "below (Google Sign-In creates the Web client at the same time), download " +
                "google-services.json again and rebuild — this build reads the Web client out of " +
                "that file.\n\n" + registrationHint(context)
        }
        val status = runCatching {
            com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
        }.getOrNull()
        if (status != null && status != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            return "Google Play Services is missing or out of date on this device " +
                "(status $status), so the Google sign-in sheet cannot open. Update Play Services " +
                "from the Play Store and try again."
        }
        return null
    }

    /** Package name plus fingerprint, ready to paste into Firebase → Project settings. */
    fun registrationHint(context: Context): String {
        val fp = signingFingerprint(context) ?: "unavailable"
        return "Package: ${context.packageName}\nSHA-1: $fp"
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

sealed class GoogleSignInOutcome {
    data class Ok(val account: GoogleSignInAccount) : GoogleSignInOutcome()
    object Cancelled : GoogleSignInOutcome()
    data class Failed(val code: Int, val message: String) : GoogleSignInOutcome()
}

sealed class DriveTokenResult {
    data class Ok(val token: String) : DriveTokenResult()
    data class Recoverable(val intent: Intent) : DriveTokenResult()
    object Unavailable : DriveTokenResult()
}
