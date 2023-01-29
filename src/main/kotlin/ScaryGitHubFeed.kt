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
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
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
            while (true) {
                try {
                    withTimeout(Duration.ofSeconds(Config.postTimeoutSec)) {
                        postAllSubscribeMessage()
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.error("Post timeout")
                } catch (e: Exception) {
                    logger.error("Post error", e)
                }
                kotlinx.coroutines.time.delay(Duration.ofSeconds(Config.postDelaySec))
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
            .associateWith { async { requireFeed(it) }.await() }
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
                async { requireResource(URL(githubGraphLinkPrefix + matches)) }.await()
            }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
        logger.info("Got ${imageResources.size} image resources for entries.")

        val commitsResource = feeds.values.flatten()
            .associateWith { entry ->
                val matches = githubCompareRegex.find(entry.link)?.groupValues?.let { (_, owner, repo, from, to) ->
                    "$githubAPIEndpoint$owner/$repo/compare/$from...$to"
                } ?: return@associateWith let {
                    logger.error("Failed to build commits resource for ${entry.link} because of regex failed")
                    null
                }
                async { requireResource(URL(matches)) }.await()
            }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
        logger.info("Got ${commitsResource.size} commits resources for entries.")

        val messageChains = feeds.values.flatten()
            .associateWith { entry ->
                MessageChainBuilder().apply {
                    appendLine("GitHub Feed 订阅推送 \uD83D\uDE31\uD83D\uDE31\uD83D\uDE31\uD83D\uDE31\uD83D\uDE31")
                    appendLine(entry.title)
                    appendLine("时间：" + SimpleDateFormat.getDateTimeInstance().format(entry.publishedDate))
                    appendLine("### Repo Image Here (Placeholder with index 3) ###")
                    appendLine(entry.link)
                    appendLine("----------")
                    appendLine("### Commits Here (Placeholder with index 6) ###")
                    append("from ScaryGitHubFeed")
                }
            }

        for ((botId, botData) in Data.feedData) {
            for ((groupId, githubIds) in botData) {
                val bot = Bot.getInstanceOrNull(botId) ?: continue
                for (githubId in githubIds) {
                    val entries = feeds[githubId] ?: continue
                    logger.info("Posting entries for user $githubId in group $groupId by bot $botId")
                    for (entry in entries) {
                        val messageChain = messageChains[entry] ?: continue
                        val image = imageResources[entry]
                        val commits = commitsResource[entry]
                        postSubscribeMessage(
                            bot,
                            groupId,
                            messageChain,
                            image,
                            commits
                        )
                    }
                    val latest = feeds[githubId]?.maxOf { it.publishedDate.time } ?: Date.from(Instant.now()).time
                    logger.info("Update last updated time for user $githubId from ${Data.lastUpdatedTime[githubId]} to $latest")
                    Data.lastUpdatedTime[githubId] = latest
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun postSubscribeMessage(
        bot: Bot,
        groupId: Long,
        messageChainBuilder: MessageChainBuilder,
        image: ExternalResource? = null,
        commits: ExternalResource? = null
    ) {
        val imageResource = async {
            image?.let { bot.uploadImage(groupId, it) }?.also {
                logger.error("Failed to upload image for group $groupId")
            }
        }
        val commitsResource = async {
            commits?.inputStream()?.use { Json.decodeFromStream<JsonObject>(it)["commits"]?.jsonArray }
        }

        messageChainBuilder[3] = imageResource.await() ?: PlainText("无法加载图片")
        commitsResource.await()?.let { commitJson ->
            buildMessageChain {
                for (json in commitJson) {
                    val commit = json.jsonObject["commit"] ?: continue
                    val message = commit.jsonObject["message"] ?: continue
                    val author = commit.jsonObject["author"] ?: continue
                    val date = author.jsonObject["date"] ?: continue
                    val name = author.jsonObject["name"] ?: continue
                    appendLine(
                        "- " + message.jsonPrimitive.content
                                + " on " + SimpleDateFormat.getDateTimeInstance()
                            .format(date.jsonPrimitive.content)
                                + " by " + name.jsonPrimitive.content
                    )
                    appendLine("----------")
                }
            }
        }?.forEach {
            messageChainBuilder.add(6, it)
        }?.also {
            messageChainBuilder.removeAt(6)
        }

        bot.sendMessage(groupId, messageChainBuilder.build())
    }

    private fun requireFeed(githubId: String): List<SyndEntry>? {
        logger.info("Require feed from $githubId")
        return try {
            val feed = getFeed(githubId)
            val lastUpdatedTime = Data.lastUpdatedTime.getOrPut(githubId) { Date.from(Instant.now()).time }
            feed.entries.filter { it.publishedDate.time > lastUpdatedTime }
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
                url.openStream().use { it.toExternalResource() }.also {
                    logger.info { "Resource loaded from $url" }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to load resource for $url", e)
            null
        }
    }

}