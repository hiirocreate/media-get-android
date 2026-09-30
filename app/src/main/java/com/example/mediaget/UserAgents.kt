package com.example.mediaget

/**
 * Shared Chrome User-Agent strings, used by both the visible "ブラウザ" tab
 * (BrowserScreen.kt's applyDisplayMode()) and HeadlessPageLoader's invisible
 * warm-up visits, so a headless load presents exactly the same browser
 * identity the user's own session was established under.
 */
object UserAgents {

    /**
     * A stock modern-Chrome-on-Android UA string. Sites use the UA to detect
     * "in-app browsers" (WebViews embedded inside other apps) and deliberately
     * cripple them — this makes MediaGet's browser present as a normal phone
     * browser instead, which is what fixes TikTok/X's blocked scrolling and
     * "open in app" nags.
     */
    const val MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    /**
     * Same idea as before (no "Mobile" token, so sites treat this as a tablet-
     * class touch browser rather than the phone-web experience they gate), but
     * the device name matches the actual phone this app runs on (OUKITEL C50)
     * instead of a made-up Samsung tablet model. A UA claiming a device that
     * doesn't match the real hardware is itself one more signal a site's
     * fraud/device-recognition system can use to flag "unrecognized device" —
     * this narrows that gap while keeping the non-"Mobile" trick that gets the
     * login screen to render at all.
     */
    const val TABLET =
        "Mozilla/5.0 (Linux; Android 14; OUKITEL C50) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    /**
     * A plain desktop-Chrome-on-Windows UA, offered as a manual option for sites
     * the user wants to browse in full PC layout. Unlike the earlier desktop-UA
     * attempt, switching to this never injects any reflow/caret-fix JavaScript —
     * that JS (not the UA itself) is what caused the scroll/white-screen bugs —
     * so this is just the UA string plus the same wide-viewport flags used for
     * tablet mode.
     */
    const val DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
}
