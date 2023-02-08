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
import net.mamoe.mirai.contact.getMemberOrFail
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.ExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.time.*
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

val githubIssueRegex = Regex("^https://github\\.com/([a-zA-Z0-9\\-]+?)/([a-zA-Z0-9\\-_.]+?)/issues/(\\d+)")

val githubIssueCommentRegex =
    Regex("^https://github\\.com/([a-zA-Z0-9\\-]+?)/([a-zA-Z0-9\\-_.]+?)/issues/\\d+#issuecomment-(\\d+)\$")

val githubPullRequestRegex = Regex("^https://github\\.com/([a-zA-Z0-9\\-]+?)/([a-zA-Z0-9\\-_.]+?)/pull/(\\d+)")

val githubPullRequestCommentRegex =
    Regex("^https://github\\.com/([a-zA-Z0-9\\-]+?)/([a-zA-Z0-9\\-_.]+?)/pull/\\d+#discussion_r(\\d+)\$")

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
                logger.debug("Post cancelled")
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
        logger.debug("Got ${feeds.size} feeds for following users: ${feeds.keys.joinToString()}")

        // skip if no feeds
        if (feeds.isEmpty()) {
            logger.info("No feeds to post, skip...")
            return
        }

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
        logger.debug("Got ${imageResources.size} image resources for entries.")

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
        logger.debug("Got ${commitsResource.size} commits resources for entries.")

        val issueResource = feeds.values.flatten()
            .associateWith { entry ->
                val matches = githubIssueRegex.find(entry.link)?.groupValues?.let { (_, owner, repo, issue) ->
                    "$githubAPIEndpoint$owner/$repo/issues/$issue"
                } ?: return@associateWith null
                async { requireResource(URL(matches)) }
            }
            .mapValues { it.value?.await() }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
        logger.debug("Got ${issueResource.size} issue resources for entries.")

        val issueCommentsResource = feeds.values.flatten()
            .associateWith { entry ->
                val matches = githubIssueCommentRegex.find(entry.link)?.groupValues?.let { (_, owner, repo, comment) ->
                    "$githubAPIEndpoint$owner/$repo/issues/comments/$comment"
                } ?: return@associateWith null
                async { requireResource(URL(matches)) }
            }
            .mapValues { it.value?.await() }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
        logger.debug("Got ${issueCommentsResource.size} comment resources for entries.")

        val pullRequestResource = feeds.values.flatten()
            .associateWith { entry ->
                val matches = githubPullRequestRegex.find(entry.link)?.groupValues?.let { (_, owner, repo, pr) ->
                    "$githubAPIEndpoint$owner/$repo/pulls/$pr"
                } ?: return@associateWith null
                async { requireResource(URL(matches)) }
            }
            .mapValues { it.value?.await() }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
        logger.debug("Got ${pullRequestResource.size} pull request resources for entries.")

        val pullRequestCommentResource = feeds.values.flatten()
            .associateWith { entry ->
                val matches =
                    githubPullRequestCommentRegex.find(entry.link)?.groupValues?.let { (_, owner, repo, comment) ->
                        "$githubAPIEndpoint$owner/$repo/pulls/comments/$comment"
                    } ?: return@associateWith null
                async { requireResource(URL(matches)) }
            }
            .mapValues { it.value?.await() }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
        logger.debug("Got ${pullRequestCommentResource.size} pull request comment resources for entries.")

        logger.debug("Starting to post messages...")
        for ((botId, botData) in Data.feedData) {
            val bot = Bot.getInstanceOrNull(botId) ?: continue
            for ((groupId, githubIds) in botData) {
                for (githubId in githubIds) {
                    val entries = feeds[githubId] ?: continue
                    logger.debug("Posting entries for user $githubId in group $groupId by bot $botId")
                    for (entry in entries) {
                        val image = imageResources[entry]
                        val commits = commitsResource[entry]
                        val issue = issueResource[entry]
                        val issueComment = issueCommentsResource[entry]
                        val pullRequest = pullRequestResource[entry]
                        val pullRequestComment = pullRequestCommentResource[entry]
                        postSubscribeMessage(
                            bot,
                            groupId,
                            entry,
                            image,
                            commits,
                            issue,
                            issueComment,
                            pullRequest,
                            pullRequestComment
                        )
                    }
                    Data.lastUpdatedTime[githubId] =
                        (feeds[githubId]?.maxOf { it.publishedDate.time } ?: Date.from(Instant.now()).time).also {
                            logger.debug("Update last updated time for user $githubId from ${Data.lastUpdatedTime[githubId]} to $it")
                        }
                }
            }
        }
        logger.info("Finished posting subscribe message, ${feeds.size} feeds posted.")

        // Cleanup
        imageResources.values.forEach { it.close() }
        commitsResource.values.forEach { it.close() }
        issueResource.values.forEach { it.close() }
        issueCommentsResource.values.forEach { it.close() }
        pullRequestResource.values.forEach { it.close() }
        pullRequestCommentResource.values.forEach { it.close() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun postSubscribeMessage(
        bot: Bot,
        groupId: Long,
        entry: SyndEntry,
        image: ExternalResource? = null,
        commits: ExternalResource? = null,
        issue: ExternalResource? = null,
        issueComment: ExternalResource? = null,
        pullRequest: ExternalResource? = null,
        pullRequestComment: ExternalResource? = null,
    ) {

        val imageResource = async {
            image?.use { bot.uploadImage(groupId, it) }
        }

        val commitsResource = useDeferredResource(commits) { resource ->
            resource.inputStream().use { Json.decodeFromStream<JsonObject>(it)["commits"]?.jsonArray }
        }

        val issueResource = useDeferredResource<JsonObject?>(issue)

        val issueCommentResource = useDeferredResource<JsonObject?>(issueComment)

        val pullRequestResource = useDeferredResource<JsonObject?>(pullRequest)

        val pullRequestCommentResource = useDeferredResource<JsonObject?>(pullRequestComment)

        val messageChain = MessageChainBuilder().apply {
            appendLine("GitHub Feed 订阅推送 \uD83D\uDE31\uD83D\uDE31\uD83D\uDE31\uD83D\uDE31")
            appendLine(entry.title)
            appendLine("时间：" + SimpleDateFormat.getDateTimeInstance().format(entry.publishedDate))
        }

        imageResource.await().let { if (it == null) messageChain.appendLine("无法加载图片") else messageChain.add(it) }
        messageChain.appendLine(entry.link)
        messageChain.appendLine("----------")

        commitsResource.await()?.let { commitJson ->
            buildMessageChain {
                appendLine("commit 信息：")
                for (json in commitJson) {
                    val sha = json.jsonObject["sha"]?.jsonPrimitive?.content ?: continue
                    val commit = json.jsonObject["commit"] ?: continue
                    val message = commit.jsonObject["message"]?.jsonPrimitive?.content ?: "无法获得 commit 消息"
                    val author = commit.jsonObject["author"] ?: continue
                    val date = author.jsonObject["date"]?.jsonPrimitive?.content ?: continue
                    val name = author.jsonObject["name"]?.jsonPrimitive?.content ?: "无法获取 commit 创建者"
                    appendLine(
                        "- commit#" + sha.substring(sha.length - 6, sha.length) + ": "
                                + message
                                + " (" + "on " + ZonedDateTime
                            .parse(date, DateTimeFormatter.ISO_DATE_TIME)
                            .withZoneSameInstant(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
                                + " by " + name + ")"
                    )
                }
                appendLine("----------")
            }
        }?.let { messageChain.add(it) }

        issueResource.await()?.let { issueJson ->
            val title = issueJson["title"]?.jsonPrimitive?.content.checkNull() ?: "无标题"
            val body = issueJson["body"]?.jsonPrimitive?.content.checkNull() ?: "无内容"
            val user = issueJson["user"]?.jsonObject ?: return@let buildMessageChain { }
            val login = user["login"]?.jsonPrimitive?.content.checkNull() ?: "无法获取 issue 创建者"
            val createdAt = issueJson["created_at"]?.jsonPrimitive?.content.checkNull() ?: "无法获取 issue 创建时间"
            buildMessageChain {
                appendLine("issue 信息：")
                appendLine("Title: $title")
                appendLine("Content: $body")
                append(
                    "（On " + ZonedDateTime
                        .parse(createdAt, DateTimeFormatter.ISO_DATE_TIME)
                        .withZoneSameInstant(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
                )
                appendLine(" by $login）")
                appendLine("----------")
            }
        }?.let { messageChain.add(it) }

        issueCommentResource.await()?.let { commentJson ->
            val body = commentJson["body"]?.jsonPrimitive?.content.checkNull() ?: "无内容"
            val user = commentJson["user"]?.jsonObject ?: return@let buildMessageChain { }
            val login = user["login"]?.jsonPrimitive?.content.checkNull() ?: "无法获取 comment 创建者"
            val createdAt = commentJson["created_at"]?.jsonPrimitive?.content.checkNull()?: "无法获取 comment 创建时间"
            buildMessageChain {
                appendLine("issue comment 信息：")
                appendLine("Content: $body")
                append(
                    "（On " + ZonedDateTime
                        .parse(createdAt, DateTimeFormatter.ISO_DATE_TIME)
                        .withZoneSameInstant(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
                )
                appendLine(" by $login）")
                appendLine("----------")
            }
        }?.let {
            messageChain.add(it)
        }

        pullRequestResource.await()?.let { pullRequestJson ->
            val title = pullRequestJson["title"]?.jsonPrimitive?.content.checkNull() ?: "无标题"
            val body = pullRequestJson["body"]?.jsonPrimitive?.content.checkNull() ?: "无内容"
            val user = pullRequestJson["user"]?.jsonObject ?: return@let buildMessageChain { }
            val login = user["login"]?.jsonPrimitive?.content.checkNull() ?: "无法获取 pull request 创建者"
            val createdAt = pullRequestJson["created_at"]?.jsonPrimitive?.content.checkNull() ?: "无法获取 pull request 创建时间"
            buildMessageChain {
                appendLine("pull request 信息：")
                appendLine("Title: $title")
                appendLine("Content: $body")
                append(
                    "（On " + ZonedDateTime
                        .parse(createdAt, DateTimeFormatter.ISO_DATE_TIME)
                        .withZoneSameInstant(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
                )
                appendLine(" by $login）")
                appendLine("----------")
            }
        }?.let { messageChain.add(it) }

        pullRequestCommentResource.await()?.let { commentJson ->
            val body = commentJson["body"]?.jsonPrimitive?.content.checkNull() ?: "无内容"
            val user = commentJson["user"]?.jsonObject ?: return@let buildMessageChain { }
            val login = user["login"]?.jsonPrimitive?.content.checkNull() ?: "无法获取 comment 创建者"
            val createdAt = commentJson["created_at"]?.jsonPrimitive?.content.checkNull() ?: "无法获取 comment 创建时间"
            buildMessageChain {
                appendLine("pull request comment 信息：")
                appendLine("Content: $body")
                append(
                    "（On " + ZonedDateTime
                        .parse(createdAt, DateTimeFormatter.ISO_DATE_TIME)
                        .withZoneSameInstant(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
                )
                appendLine(" by $login）")
                appendLine("----------")
            }
        }?.let {
            messageChain.add(it)
        }

        messageChain.append("from ScaryGitHubFeed")


        val message = messageChain.build()
        if (message.toString().length > 500) {
            bot.sendForwardMessage(groupId, message)
        } else {
            bot.sendMessage(groupId, message)
        }
    }

    private fun requireFeed(githubId: String): List<SyndEntry>? {
        logger.debug("Require feed from $githubId")
        return try {
            val feed = getFeed(githubId, proxy)
            val lastUpdatedTime = Data.lastUpdatedTime.getOrPut(githubId) { Date.from(Instant.now()).time }
            feed.entries.filter { it.publishedDate.time > lastUpdatedTime }
                .also { logger.debug("Got ${it.size} entries for $githubId") }
        } catch (e: Exception) {
            logger.error("Failed to get feed for $githubId", e)
            null
        }
    }

    private suspend fun Bot.sendForwardMessage(groupId: Long, message: Message) {
        val group = getGroup(groupId) ?: return
        message.toForwardMessage(
            sender = group.getMemberOrFail(bot.id),
            displayStrategy = object : ForwardMessage.DisplayStrategy {
                override fun generateTitle(forward: RawForwardMessage): String {
                    return "ScaryGitHubFeed 订阅推送"
                }

                override fun generateBrief(forward: RawForwardMessage): String {
                    return "[GitHub 订阅推送]"
                }

                override fun generateSource(forward: RawForwardMessage): String {
                    return "GitHub 订阅推送"
                }

                override fun generateSummary(forward: RawForwardMessage): String {
                    return "查看详细订阅推送信息"
                }
            }
        ).also { group.sendMessage(it) }
    }

    private fun String?.checkNull(): String? {
        return this?.takeIf { it != "null" }
    }

    private suspend fun Bot.sendMessage(groupId: Long, message: Message) {
        getGroup(groupId)?.sendMessage(message)
    }

    private suspend fun Bot.uploadImage(groupId: Long, resource: ExternalResource): Image? {
        return resource.use { getGroup(groupId)?.uploadImage(it) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> useDeferredResource(
        externalResource: ExternalResource?,
        crossinline use: (ExternalResource) -> T? = { resource ->
            resource.inputStream().use { Json.decodeFromStream(it) }
        }
    ): Deferred<T?> {
        return async {
            externalResource?.use { res ->
                use.invoke(res)
            }
        }
    }

    private suspend fun requireResource(url: URL): ExternalResource? {
        logger.debug("Require resource from $url")
        return try {
            withContext(Dispatchers.IO) {
                url.openConnection(proxy).getInputStream().use { it.toExternalResource() }.also {
                    logger.debug("Resource loaded from $url")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to load resource for $url", e)
            null
        }
    }

}