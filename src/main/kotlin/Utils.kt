package io.hikarilan

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.FeedException
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import java.net.Proxy
import java.net.URL


fun checkFeedValid(githubId: String, proxy: Proxy = Proxy.NO_PROXY): Boolean {
    return try {
        getFeed(githubId, proxy)
        true
    } catch (e: Exception) {
        false
    }
}

@Throws(IllegalArgumentException::class, FeedException::class)
fun getFeed(githubId: String, proxy: Proxy = Proxy.NO_PROXY): SyndFeed {
    val url = "https://github.com/$githubId.atom"
    return SyndFeedInput().build(XmlReader(URL(url).openConnection(proxy)))
}

object Utils