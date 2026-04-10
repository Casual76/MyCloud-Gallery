package com.mycloudgallery.presentation.auth

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.mycloudgallery.ui.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test

class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loginScreen_mostraTitolo() {
        composeTestRule.setContent {
            MyCloudGalleryTheme {
                // LoginScreen standalone senza ViewModel reale
                LoginScreenContent(
                    username = "",
                    password = "",
                    isLoading = false,
                    errorMessage = null,
                    onUsernameChanged = {},
                    onPasswordChanged = {},
                    onLogin = {},
                )
            }
        }
        composeTestRule.onNodeWithText("MyCloud Gallery").assertExists()
    }

    @Test
    fun loginScreen_pulsanteDisabilitoConCampiVuoti() {
        composeTestRule.setContent {
            MyCloudGalleryTheme {
                LoginScreenContent(
                    username = "",
                    password = "",
                    isLoading = false,
                    errorMessage = null,
                    onUsernameChanged = {},
                    onPasswordChanged = {},
                    onLogin = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Accedi").assertIsNotEnabled()
    }

    @Test
    fun loginScreen_pulsanteAbilitatoConCampiCompilati() {
        composeTestRule.setContent {
            MyCloudGalleryTheme {
                LoginScreenContent(
                    username = "utente@test.com",
                    password = "password123",
                    isLoading = false,
                    errorMessage = null,
                    onUsernameChanged = {},
                    onPasswordChanged = {},
                    onLogin = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Accedi").assertIsEnabled()
    }

    @Test
    fun loginScreen_mostraErroreInline() {
        composeTestRule.setContent {
            MyCloudGalleryTheme {
                LoginScreenContent(
                    username = "utente@test.com",
                    password = "sbagliata",
                    isLoading = false,
                    errorMessage = "Credenziali non valide",
                    onUsernameChanged = {},
                    onPasswordChanged = {},
                    onLogin = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Credenziali non valide").assertExists()
    }
}
