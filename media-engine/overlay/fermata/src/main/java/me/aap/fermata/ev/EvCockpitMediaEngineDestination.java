package me.aap.fermata.ev;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.NavigationCoordinator;

/**
 * Trusted in-package, user-initiated phone navigation from the EV Cockpit cockpit.
 * This does not create media streams, bypass DRM, enable car video, or replace
 * the existing MediaEngine add-ons. Unknown destinations fail closed to Dashboard.
 */
public final class EvCockpitMediaEngineDestination {
    public static final String EXTRA = "evcockpit_media_target";

    private EvCockpitMediaEngineDestination() {}

    /** Only literal IDs sent by our JavaScriptInterface may reach this method. */
    public static int fragmentFor(String target) {
        if (target == null) return R.id.dashboard_fragment;
        switch (target) {
            case "tv": return R.id.tv_fragment;
            case "youtube": return R.id.youtube_fragment;
            case "radio": return R.id.radio_fragment;
            case "podcast": return R.id.podcast_fragment;
            case "media": return R.id.folders_fragment;
            case "web":
            case "netflix":
            case "disney":
            case "prime": return R.id.web_browser_fragment;
            case "settings":
            case "equalizer": return R.id.settings_fragment;
            // Internal MediaEngine dashboard actions stay on the original dashboard.
            case "mediaengine":
            case "mirror":
            case "windasist":
            case "shortcuts": return R.id.dashboard_fragment;
            default: return R.id.dashboard_fragment;
        }
    }

    public static String siteFor(String target) {
        if (target == null) return null;
        switch (target) {
            case "netflix": return "https://www.netflix.com/";
            case "disney": return "https://www.disneyplus.com/";
            case "prime": return "https://www.primevideo.com/";
            default: return null;
        }
    }

    public static void navigate(MainActivityDelegate delegate, String target) {
        if (delegate == null) return;
        int fragment = fragmentFor(target);
        try {
            String site = siteFor(target);
            if (site != null) {
                int previous = delegate.getActiveNavItemId();
                delegate.setActiveNavItemId(fragment);
                // Existing add-on owns its WebView, DRM permission policy, cookies and MediaSession.
                // The input survives asynchronous installation/activation of the web add-on.
                if (!delegate.showFragmentWhenReady(fragment, site)) {
                    delegate.setActiveNavItemId(previous);
                    delegate.returnToDashboard();
                }
                return;
            }
            if (!NavigationCoordinator.select(delegate, fragment)) {
                delegate.returnToDashboard();
            }
        } catch (RuntimeException e) {
            // A missing/disabled add-on should never terminate the original
            // media dashboard. Navigation is a convenience, not playback.
            delegate.returnToDashboard();
        }
    }
}
