package com.maxrave.simpmusic.ui.screen.login

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeLoginHelperTest {
    @Test
    fun musicYouTubeUrlMatchesHostOnly() {
        assertTrue(isMusicYouTubeUrl("https://music.youtube.com/"))
        assertTrue(isMusicYouTubeUrl("https://music.youtube.com/?hl=vi"))
        assertFalse(isMusicYouTubeUrl("https://accounts.google.com/ServiceLogin?continue=https://music.youtube.com/"))
    }

    @Test
    fun authCookiesRequireSapisidAnd3Papisid() {
        assertFalse(hasYouTubeAuthCookies("VISITOR_INFO1_LIVE=abc; YSC=def"))
        assertFalse(hasYouTubeAuthCookies("SAPISID=s1"))
        assertTrue(hasYouTubeAuthCookies("SAPISID=s1; __Secure-3PAPISID=s2"))
        assertFalse(hasYouTubeAuthCookies("SAPISID=s1; __Secure-3PAPISID=s2", requireLoginInfo = true))
        assertTrue(hasYouTubeAuthCookies("SAPISID=s1; __Secure-3PAPISID=s2; LOGIN_INFO=li", requireLoginInfo = true))
    }

    @Test
    fun autoCompleteTriggersOnAuthCookiesOrMusicRedirectWithSession() {
        val fullCookies = "SAPISID=s1; __Secure-3PAPISID=s2; LOGIN_INFO=li"
        val sessionCookies = "SAPISID=s1; __Secure-3PAPISID=s2"

        assertTrue(shouldAutoCompleteYouTubeLogin("https://accounts.youtube.com/accounts/SetSID", fullCookies))
        assertTrue(shouldAutoCompleteYouTubeLogin("https://music.youtube.com/?hl=en", sessionCookies))
        assertFalse(shouldAutoCompleteYouTubeLogin("https://music.youtube.com/", "VISITOR_INFO1_LIVE=guest"))
        assertFalse(shouldAutoCompleteYouTubeLogin("https://accounts.google.com/v3/signin", sessionCookies))
    }
}
