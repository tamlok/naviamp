package app.naviamp.presentation

import app.naviamp.domain.settings.NaviampBuiltinTheme
import app.naviamp.domain.settings.NaviampThemeMode
import app.naviamp.domain.settings.NaviampThemes
import app.naviamp.domain.settings.NaviampThemeSettings

import app.naviamp.domain.connect.NaviampConnectProvisioningProfile
import app.naviamp.domain.settings.AppBackgroundStyle
import app.naviamp.domain.settings.ConnectionFormState
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.PlaybackSettings
import app.naviamp.domain.settings.QuickAccessKind
import app.naviamp.domain.settings.QuickAccessPin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class NaviampCoreConnectProvisioningTest {
    @Test
    fun portablePreferencesKeepLocalQuickAccessWithoutImportingUnmappedSourceIds() {
        val localPin = QuickAccessPin("local-source", QuickAccessKind.Playlist, "item", "Local playlist")
        val offeredPins = listOf(
            QuickAccessPin("sender-source", QuickAccessKind.Album, "item", "Sender album"),
            localPin.copy(title = "Coincident sender identity"),
        )
        val local = InterfaceSettings(quickAccess = listOf(localPin))
        val offered = InterfaceSettings(keepScreenAwake = true, quickAccess = offeredPins)

        val applied = local.withConnectPortableValues(offered)
        assertEquals(listOf(localPin), applied.quickAccess)
        assertEquals(true, applied.keepScreenAwake)
        assertEquals(listOf(localPin), local.withConnectPortableValues(InterfaceSettings()).quickAccess)
        assertEquals(emptyList(), InterfaceSettings().withConnectPortableValues(offered).quickAccess)
    }

    @Test
    fun themePreferencesTransferThroughConnectAndPreserveTargetHostPolicy() {
        val ready = assertIs<NaviampCoreConnectProvisioningExport.Ready>(ConnectionFormState(
            serverUrl = "https://music.example.test", username = "listener", password = "secret"
        ).toConnectProvisioningExport())
        for (pack in NaviampThemes.packs) for (mode in NaviampThemeMode.entries) {
            val offered = InterfaceSettings(checkForUpdates = false, keepScreenAwake = true,
                theme = NaviampThemeSettings(mode, pack.id))
            val envelope = app.naviamp.domain.connect.NaviampConnectEnvelope(1, "session", 1,
                message = app.naviamp.domain.connect.NaviampConnectCommandRequest(
                    app.naviamp.domain.connect.NaviampConnectOfferConnectionProvisioning(ready.profile,
                        portableSettings = app.naviamp.domain.connect.NaviampConnectPortableSettings(offered, PlaybackSettings()))))
            val decoded = app.naviamp.domain.connect.NaviampConnectWireCodec.decode(
                app.naviamp.domain.connect.NaviampConnectWireCodec.encode(envelope))
            val offer = ((decoded.message as app.naviamp.domain.connect.NaviampConnectCommandRequest).command as
                app.naviamp.domain.connect.NaviampConnectOfferConnectionProvisioning)
            val received = kotlin.test.assertNotNull(offer.portableSettings).interfaceSettings
            assertEquals(offered, received)
            val applied = InterfaceSettings(checkForUpdates = true).withConnectPortableValues(received)
            assertEquals(offered.theme, applied.theme)
            assertEquals(true, applied.checkForUpdates)
            assertEquals(true, applied.keepScreenAwake)
        }
    }

    @Test
    fun connectApplyNormalizesThemesWithoutLosingPortableOrHostPreferences() {
        for (mode in NaviampThemeMode.entries) {
            val offered = InterfaceSettings(checkForUpdates = false, keepScreenAwake = true,
                theme = NaviampThemeSettings(mode, "missing"))
            val applied = InterfaceSettings(checkForUpdates = true).withConnectPortableValues(offered)
            assertEquals(NaviampThemeSettings(mode, NaviampBuiltinTheme.id), applied.theme)
            assertEquals(true, applied.checkForUpdates)
            assertEquals(true, applied.keepScreenAwake)
        }
    }

    @Test fun companionCredentialsAndDisabledConfigurationSurviveEncryptedProvisioning() {
        val companion = app.naviamp.domain.source.AurralCompanion(true, " https://aurral.example/ ", " secondary ", "companion-secret",
            app.naviamp.domain.source.ConnectionTlsSettings(insecureSkipTlsVerification = true),
            listOf(app.naviamp.domain.source.ConnectionHeaderDefinition("X-Secret", "header-secret", true)))
        for (enabled in listOf(true, false)) {
            val value = companion.copy(enabled = enabled)
            val ready = assertIs<NaviampCoreConnectProvisioningExport.Ready>(ConnectionFormState(
                serverUrl = "https://primary.example", username = "primary", password = "primary-secret",
                aurralCompanion = value).toConnectProvisioningExport())
            val envelope = app.naviamp.domain.connect.NaviampConnectEnvelope(1, "session", 1,
                message = app.naviamp.domain.connect.NaviampConnectCommandRequest(
                    app.naviamp.domain.connect.NaviampConnectOfferConnectionProvisioning(ready.profile)))
            val decoded = app.naviamp.domain.connect.NaviampConnectWireCodec.decode(
                app.naviamp.domain.connect.NaviampConnectWireCodec.encode(envelope))
            val profile = ((decoded.message as app.naviamp.domain.connect.NaviampConnectCommandRequest).command as
                app.naviamp.domain.connect.NaviampConnectOfferConnectionProvisioning).profile
            assertEquals(value.normalized(), profile.toConnectionFormState().aurralCompanion)
            assertEquals("primary-secret", profile.toConnectionFormState().password)
            for (secret in listOf("primary-secret", "companion-secret", "header-secret")) {
                assertFalse(profile.toString().contains(secret))
                assertFalse(profile.aurralCompanion.toString().contains(secret))
            }
        }
    }

    @Test fun companionLocalCertificatesBlockTransferInsteadOfSavingUnusablePaths() {
        for (tls in listOf(
            app.naviamp.domain.source.ConnectionTlsSettings(customCertificatePath = "/phone/ca.pem"),
            app.naviamp.domain.source.ConnectionTlsSettings(clientCertificateKeyStorePath = "/phone/client.p12"),
        )) for (enabled in listOf(true, false)) {
            assertIs<NaviampCoreConnectProvisioningExport.Unsupported>(ConnectionFormState(
                serverUrl = "https://primary.example", username = "primary", password = "primary-secret",
                aurralCompanion = app.naviamp.domain.source.AurralCompanion(enabled, "https://aurral.example", "secondary", "secret", tls)
            ).toConnectProvisioningExport())
        }
    }

    @Test
    fun missingCredentialRequestsCredentialRepair() {
        val unsupported = assertIs<NaviampCoreConnectProvisioningExport.Unsupported>(
            ConnectionFormState(
                serverUrl = "https://music.example.test",
                username = "listener",
            ).toConnectProvisioningExport(),
        )

        assertEquals(true, unsupported.credentialUnavailable)
    }

    @Test
    fun exportsCredentialAndLibrariesWithoutMachineLocalCertificatePaths() {
        val ready = assertIs<NaviampCoreConnectProvisioningExport.Ready>(
            ConnectionFormState(
                providerId = "navidrome",
                displayName = "Home",
                serverUrl = "https://music.example.test",
                username = "listener",
                password = "secret-value",
                selectedMusicFolderIds = listOf("music", "music", " spoken "),
            ).toConnectProvisioningExport(),
        )

        assertEquals(listOf("music", "spoken"), ready.profile.selectedLibraryIds)
        assertFalse(ready.profile.toString().contains("secret-value"))
        assertIs<NaviampCoreConnectProvisioningExport.Unsupported>(
            ConnectionFormState(
                serverUrl = "https://music.example.test",
                username = "listener",
                password = "secret",
                customCertificatePath = "/local/certificate.pem",
            ).toConnectProvisioningExport(),
        )
    }

    @Test
    fun targetFormRetainsEncryptedConnectionFields() {
        val form = NaviampConnectProvisioningProfile(
            providerId = "navidrome",
            displayName = "Home",
            serverUrl = "https://music.example.test",
            username = "listener",
            password = "secret",
            skipTlsVerification = true,
            selectedLibraryIds = listOf("music"),
        ).toConnectionFormState()

        assertEquals("secret", form.password)
        assertEquals(true, form.skipTlsVerification)
        assertEquals(listOf("music"), form.selectedMusicFolderIds)
    }

    @Test
    fun portableSettingsPreserveTargetOnlyDeviceAndHostPolicy() {
        val currentInterface = InterfaceSettings(checkForUpdates = true)
        val offeredInterface = InterfaceSettings(
            checkForUpdates = false,
            keepScreenAwake = true,
            appBackgroundStyle = AppBackgroundStyle.SingleColor,
        )
        val currentPlayback = PlaybackSettings(volumePercent = 77, allowMobileDownloads = false)
        val offeredPlayback = PlaybackSettings(
            gaplessEnabled = false,
            crossfadeDurationSeconds = 8,
            volumePercent = 20,
            allowMobileDownloads = true,
        )

        val mergedInterface = currentInterface.withConnectPortableValues(offeredInterface)
        val mergedPlayback = currentPlayback.withConnectPortableValues(offeredPlayback)

        assertEquals(AppBackgroundStyle.SingleColor, mergedInterface.appBackgroundStyle)
        assertEquals(true, mergedInterface.checkForUpdates)
        assertEquals(true, mergedInterface.keepScreenAwake)
        assertEquals(false, mergedPlayback.gaplessEnabled)
        assertEquals(8, mergedPlayback.crossfadeDurationSeconds)
        assertEquals(77, mergedPlayback.volumePercent)
        assertEquals(false, mergedPlayback.allowMobileDownloads)
    }
}
