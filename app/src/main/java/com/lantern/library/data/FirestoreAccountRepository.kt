package com.lantern.library.data

import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.lantern.library.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Stores only the account snapshot metadata in Firestore. Book binaries remain local/Google Drive.
 * Document path is derived from Firebase Auth UID, never an email address supplied by the client.
 */
object FirestoreAccountRepository {
    private const val STATE_DOCUMENT = "lore_account_state"
    private const val MAX_DOCUMENT_CHARS = 850_000

    suspend fun signIn(account: GoogleSignInAccount) = withContext(Dispatchers.IO) {
        check(BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank()) {
            "Firebase Google OAuth client ID is not configured."
        }
        val idToken = account.idToken
        check(!idToken.isNullOrBlank()) {
            "Google did not return a Firebase ID token. Check the Web OAuth client ID and SHA fingerprints."
        }
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(credential).await()
    }

    suspend fun readSnapshot(): String? = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser ?: return@withContext null
        val doc = FirebaseFirestore.getInstance()
            .collection("users").document(user.uid)
            .collection("library").document(STATE_DOCUMENT)
            .get().await()
        doc.getString("snapshot")
    }

    suspend fun writeSnapshot(snapshot: String) = withContext(Dispatchers.IO) {
        require(snapshot.length <= MAX_DOCUMENT_CHARS) {
            "Library metadata is too large for one Firestore document."
        }
        val user = FirebaseAuth.getInstance().currentUser
            ?: error("Firebase user is not signed in.")
        FirebaseFirestore.getInstance()
            .collection("users").document(user.uid)
            .collection("library").document(STATE_DOCUMENT)
            .set(
                mapOf(
                    "schema" to AccountSync.SCHEMA,
                    "updatedAt" to System.currentTimeMillis(),
                    "snapshot" to snapshot
                )
            ).await()
    }

    fun isSignedIn(): Boolean = FirebaseAuth.getInstance().currentUser != null

    fun signOut() {
        FirebaseAuth.getInstance().signOut()
    }
}
