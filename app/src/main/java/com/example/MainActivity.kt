package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.RoboXrRepository
import com.example.firebase.attemptAutoSignIn
import com.example.firebase.authStateFlow
import com.example.firebase.configureAppCheck
import com.example.firebase.onGoogleSignInClicked
import com.example.firebase.signOutOperator
import com.example.ui.RoboXrViewModel
import com.example.ui.screens.RoboXrWorkspaceScreen
import com.example.ui.theme.RoboXrTheme
import com.example.xr.XrealSdkBridge
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var xrealBridge: XrealSdkBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1 (MANDATORY): Configure Firebase App Check with launch intent BEFORE UI or Firebase calls
        configureAppCheck(this, intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        xrealBridge = XrealSdkBridge(this)

        setContent {
            RoboXrTheme {
                AppNavigation(xrealBridge = xrealBridge)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        xrealBridge.start()
    }

    override fun onStop() {
        super.onStop()
        xrealBridge.stop()
    }
}

@Composable
private fun AppNavigation(xrealBridge: XrealSdkBridge) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val auth = Firebase.auth
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    // Launch straight into the interactive XREAL Manufacturing Hub (zero sign-in wall)
    val viewModel: RoboXrViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val auditFile = File(app.filesDir, "roboxr_audit_chain.json")
                RoboXrViewModel(localAuditFile = auditFile)
            }
        }
    )

    // Silently restore previously authorized Google session if available (non-blocking)
    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {},
            onUnauthenticated = {},
            scope = scope
        )
    }

    // Strictly gate Firestore repository attachment behind authenticated Google user
    LaunchedEffect(currentUser?.uid) {
        val uid = currentUser?.uid
        if (uid.isNullOrBlank()) {
            viewModel.bindAuthenticatedCloudSession(userId = null, repository = null)
        } else {
            val databaseId = context.applicationContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            val auditFile = File(context.applicationContext.filesDir, "roboxr_audit_chain.json")
            viewModel.bindAuthenticatedCloudSession(
                userId = uid,
                repository = RoboXrRepository(db = db, localAuditFile = auditFile)
            )
        }
    }

    RoboXrWorkspaceScreen(
        viewModel = viewModel,
        xrealBridge = xrealBridge,
        operatorEmail = currentUser?.email,
        onCloudSyncClick = {
            if (currentUser != null) {
                viewModel.bindAuthenticatedCloudSession(userId = null, repository = null)
                signOutOperator(credentialManager = credentialManager, scope = scope)
            } else {
                onGoogleSignInClicked(
                    context = context,
                    credentialManager = credentialManager,
                    onAuthSuccess = {},
                    onAuthError = {},
                    scope = scope
                )
            }
        }
    )
}
