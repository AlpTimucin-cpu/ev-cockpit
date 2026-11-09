package com.evcockpit.mediahub

import java.net.URI

/** Same-origin protected-media consent; never grants camera/microphone permissions. */
object SubscriptionWebPermissions {
    fun allowsProtectedMedia(service: StreamingService, page: String?, origin: String?): Boolean {
        if (page == null || origin == null ||
            !SubscriptionPortalPolicy.isAllowed(service, page) ||
            !SubscriptionPortalPolicy.isAllowed(service, origin)) return false
        return runCatching { URI(page).host.equals(URI(origin).host, true) }.getOrDefault(false)
    }

    fun userAgent(service: StreamingService, defaultAgent: String): String =
        if (service != StreamingService.NETFLIX) defaultAgent
        else defaultAgent.replace(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
            .replace("; wv", "").replace("Version/4.0 ", "").replace(" Mobile", "")
}
