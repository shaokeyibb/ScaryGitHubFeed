package io.hikarilan

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.FeedException
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import java.net.URL


fun checkFeedValid(githubId: String): Boolean {
    val url = "https://github.com/$githubId.atom"
    return try {
        SyndFeedInput().build(XmlReader(URL(url)))
        true
    } catch (e: Exception) {
        false
    }
}

@Throws(IllegalArgumentException::class, FeedException::class)
fun getFeed(githubId: String): SyndFeed {
    val url = "https://github.com/$githubId.atom"
    return SyndFeedInput().build(XmlReader(URL(url)))
}

object Utils