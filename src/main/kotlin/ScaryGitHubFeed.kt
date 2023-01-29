package io.hikarilan

import com.rometools.rome.feed.synd.SyndEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.time.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.command.CommandManager
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.ExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.info
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*

val commandPermission = PermissionService.INSTANCE.register(
    id = PermissionId(
        "io.hikarilan.scarygithubfeed",
        "command.github-feed"
    ),
    description = "Permission of command github-feed"
)

val githubRepoRegex = Regex("^https://github\\.com/([a-zA-Z0-9\\-]+?)/([a-zA-Z0-9\\-_.]+?)(/.+|.{0})\$")

const val githubGraphLinkPrefix =
    "https://opengraph.githubassets.com/31593820a09d4aa76a2b7f30a7efd993982cb622b3607ab21a852c5397bcdde0/"

val githubCompareRegex =
    Regex("^https://github\\.com/([a-zA-Z0-9\\-]+?)/([a-zA-Z0-9\\-_.]+?)/compare/([a-z0-9]{10})...([a-z0-9]{10})\$")

const val githubAPIEndpoint = "https://api.github.com/repos/"

val proxy: Proxy = if (Config.proxyHost.isNotBlank() && Config.proxyPort > 0) {
    Proxy(Proxy.Type.SOCKS, InetSocketAddress(Config.proxyHost, Config.proxyPort))
} else Proxy.NO_PROXY

object ScaryGitHubFeed : KotlinPlugin(
    JvmPluginDescription(
        id = "io.hikarilan.scarygithubfeed",
        name = "ScaryGitHubFeed",
        version = "1.0.0",
    ) {
        author("HikariLan")
        info("""😱😱😱😱😱""")
    }
) {

    private lateinit var postJob: Job

    override fun onEnable() {
        Config.reload()
        Data.reload()
        CommandManager.registerCommand(Command)

        postJob = launch {
            try {
                while (true) {
                    try {
                        withTimeout(Duration.ofSeconds(Config.postTimeoutSec)) {
                            postAllSubscribeMessage()
                        }
                        kotlinx.coroutines.time.delay(Duration.ofSeconds(Config.postDelaySec))
                    } catch (e: TimeoutCancellationException) {
                        logger.error("Post timeout")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Post error", e)
                    }
                }
            } catch (e: CancellationException) {
                logger.info("Post cancelled")
            }
        }

        logger.info("Enabled ScaryGitHubFeed")
    }

    override fun onDisable() {
        postJob.cancel()

        logger.info("Disabled ScaryGitHubFeed")
    }


    private suspend fun postAllSubscribeMessage() {
        logger.info("Posting subscribe message for all feeds")

        val feeds = Data.feedData
            .flatMap { it.value.values }.flatten()
            .associateWith { async { requireFeed(it) } }
            .mapValues { it.value.await() }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
            .filter { it.value.isNotEmpty() }
        logger.info("Got ${feeds.size} feeds for following users: ${feeds.keys.joinToString()}")

        val imageResources = feeds.values.asSequence().flatten()
            .associateWith { entry ->
                val matches =
                    githubRepoRegex.find(entry.link)?.groupValues?.let { (_, user, repo) -> "$user/$repo" }
                        ?: return@associateWith let {
                            logger.error("Failed to build repo image resource for ${entry.link} because of regex failed")
                            null
                        }
                async { requireResource(URL(githubGraphLinkPrefix + matches)) }
            }
            .mapValues { it.value?.await() }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
        logger.info("Got ${imageResources.size} image resources for entries.")

        val commitsResource = feeds.values.flatten()
            .associateWith { entry ->
                val matches = githubCompareRegex.find(entry.link)?.groupValues?.let { (_, owner, repo, from, to) ->
                    "$githubAPIEndpoint$owner/$repo/compare/$from...$to"
                } ?: return@associateWith null
                async { requireResource(URL(matches)) }
            }
            .mapValues { it.value?.await() }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
        logger.info("Got ${commitsResource.size} commits resources for entries.")

        logger.info("Starting to post messages...")
        for ((botId, botData) in Data.feedData) {
            val bot = Bot.getInstanceOrNull(botId) ?: continue
            for ((groupId, githubIds) in botData) {
                for (githubId in githubIds) {
                    val entries = feeds[githubId] ?: continue
                    logger.info("Posting entries for user $githubId in group $groupId by bot $botId")
                    for (entry in entries) {
                        val image = imageResources[entry]
                        val commits = commitsResource[entry]
                        postSubscribeMessage(
                            bot,
                            groupId,
                            entry,
                            image,
                            commits
                        )
                    }
                    Data.lastUpdatedTime[githubId] =
                        (feeds[githubId]?.maxOf { it.publishedDate.time } ?: Date.from(Instant.now()).time).also {
                            logger.info("Update last updated time for user $githubId from ${Data.lastUpdatedTime[githubId]} to $it")
                        }
                }
            }
        }
        logger.info("Finished posting subscribe message for all feeds")

        // Cleanup
        imageResources.values.forEach { it.close() }
        commitsResource.values.forEach { it.close() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun postSubscribeMessage(
        bot: Bot,
        groupId: Long,
        entry: SyndEntry,
        image: ExternalResource? = null,
        commits: ExternalResource? = null
    ) {

        val imageResource = async {
            image?.use { bot.uploadImage(groupId, it) }
        }
        val commitsResource = async {
            commits?.use { res ->
                res.inputStream().use { Json.decodeFromStream<JsonObject>(it)["commits"]?.jsonArray }
            }
        }

        val messageChain = MessageChainBuilder().apply {
            appendLine("GitHub Feed 订阅推送 \uD83D\uDE31\uD83D\uDE31\uD83D\uDE31\uD83D\uDE31\uD83D\uDE31")
            appendLine(entry.title)
            appendLine("时间：" + SimpleDateFormat.getDateTimeInstance().format(entry.publishedDate))
        }

        messageChain.add(imageResource.await() ?: PlainText("无法加载图片"))
        messageChain.appendLine(entry.link)
        messageChain.appendLine("----------")

        commitsResource.await()?.let { commitJson ->
            buildMessageChain {
                appendLine("commit 信息：")
                for (json in commitJson) {
                    val sha = json.jsonObject["sha"]?.jsonPrimitive?.content ?: continue
                    val commit = json.jsonObject["commit"] ?: continue
                    val message = commit.jsonObject["message"]?.jsonPrimitive?.content ?: continue
                    val author = commit.jsonObject["author"] ?: continue
                    val date = author.jsonObject["date"]?.jsonPrimitive?.content ?: continue
                    val name = author.jsonObject["name"]?.jsonPrimitive?.content ?: continue
                    appendLine(
                        "- commit#" + sha.substring(sha.length - 6, sha.length) + ": "
                                + message
                                + " (" + "on " + LocalDateTime.parse(date, DateTimeFormatter.ISO_DATE_TIME)
                            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
                                + " by " + name + ")"
                    )
                }
            }
        }?.let { messageChain.add(it) }

        messageChain.append("from ScaryGitHubFeed")

        bot.sendMessage(groupId, messageChain.build())
    }

    private fun requireFeed(githubId: String): List<SyndEntry>? {
        logger.info("Require feed from $githubId")
        return try {
            val feed = getFeed(githubId, proxy)
            val lastUpdatedTime = Data.lastUpdatedTime.getOrPut(githubId) { Date.from(Instant.now()).time }
            feed.entries.filter { it.publishedDate.time > lastUpdatedTime }
                .also { logger.info("Got ${it.size} entries for $githubId") }
        } catch (e: Exception) {
            logger.error("Failed to get feed for $githubId", e)
            null
        }
    }

    private suspend fun Bot.sendMessage(groupId: Long, message: MessageChain) {
        getGroup(groupId)?.sendMessage(message)
    }

    private suspend fun Bot.uploadImage(groupId: Long, resource: ExternalResource): Image? {
        return resource.use { getGroup(groupId)?.uploadImage(it) }
    }

    private suspend fun requireResource(url: URL): ExternalResource? {
        logger.info("Require resource from $url")
        return try {
            withContext(Dispatchers.IO) {
                url.openConnection(proxy).getInputStream().use { it.toExternalResource() }.also {
                    logger.info { "Resource loaded from $url" }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to load resource for $url", e)
            null
        }
    }

}