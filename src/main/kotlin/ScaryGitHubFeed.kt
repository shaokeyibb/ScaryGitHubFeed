package io.hikarilan

import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.command.CommandManager
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.toMessageChain
import net.mamoe.mirai.utils.ExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import java.net.URL
import java.text.SimpleDateFormat
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
                postAllSubscribeMessage()
                delay(Config.postDelaySec)
            }
        }

        logger.info("Enabled ScaryGitHubFeed")
    }

    override fun onDisable() {
        postJob.cancel()

        logger.info("Disabled ScaryGitHubFeed")
    }

    private suspend fun postAllSubscribeMessage() {
        Data.feedData.forEach { (groupId, feedList) ->
            feedList.forEach { id ->
                try {
                    postSubscribeMessage(groupId, id)
                } catch (e: Exception) {
                    sendMessage(
                        groupId,
                        PlainText("无法订阅 $id 的 GitHub Feed，因为指定 Feed 解析失败").toMessageChain()
                    )
                    logger.error("Failed to get feed of $id for group $groupId")
                    return
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun postSubscribeMessage(groupId: Long, githubId: String) {
        val feed = getFeed(githubId)
        if (feed.publishedDate.time > (Data.lastUpdatedTime[githubId] ?: Date.from(Instant.now()).time)) {
            feed.entries.filter {
                it.publishedDate.time > (Data.lastUpdatedTime[githubId] ?: Date.from(Instant.now()).time)
            }
                .forEach { entry ->
                    async {
                        sendMessage(
                            groupId, (PlainText(buildString {
                                appendLine("GitHub Feed 订阅推送 \uD83D\uDE31\uD83D\uDE31\uD83D\uDE31\uD83D\uDE31\uD83D\uDE31")
                                appendLine(entry.title)
                                appendLine("时间：" + SimpleDateFormat.getDateTimeInstance().format(entry.publishedDate))
                            }) + (uploadImage(
                                groupId,
                                async {
                                    withContext(Dispatchers.IO) {
                                        URL(githubGraphLinkPrefix + githubRepoRegex.find(entry.link)?.groupValues?.let { it[1] + "/" + it[2] }).openStream()
                                    }
                                }.await()
                                    .use { it.toExternalResource() })?.toMessageChain()
                                ?: PlainText("无法加载图片").toMessageChain()
                                    )).toMessageChain() + PlainText(buildString {
                                appendLine(entry.link)
                                async {
                                    println(entry.link)
                                    println(githubCompareRegex.find(entry.link)?.groupValues)
                                    withContext(Dispatchers.IO) {
                                        githubCompareRegex.find(entry.link)?.groupValues?.let { (_, owner, repo, from, to) ->
                                            URL("https://api.github.com/repos/$owner/$repo/compare/$from...$to").openStream()
                                        }
                                    }
                                }.await()?.use {
                                    appendLine("----------")
                                    println(it)
                                    for (json in Json.decodeFromStream<JsonObject>(it)["commits"]?.jsonArray
                                        ?: return@use) {
                                        val sha = json.jsonObject["sha"]?.jsonPrimitive?.content ?: continue
                                        val commit = json.jsonObject["commit"]?.jsonArray ?: continue
                                        val message = commit.jsonObject["message"]?.jsonPrimitive?.content ?: continue
                                        val author = commit.jsonObject["author"]?.jsonObject ?: continue
                                        val date = author.jsonObject["date"]?.jsonPrimitive?.content ?: continue
                                        val name = author.jsonObject["name"]?.jsonPrimitive?.content ?: continue
                                        appendLine(
                                            "- commit#" + sha.substring(sha.length - 6, sha.length) + ": "
                                                    + message
                                                    + " (" + "on " + LocalDateTime.from(DateTimeFormatter.ISO_INSTANT.parse(date)).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
                                                    + " by " + name + ")"
                                        )
                                    }
                                }
                                appendLine("----------")
                                append("from ScaryGitHubFeed")
                            })
                        )
                    }
                }
            Data.lastUpdatedTime[githubId] = feed.publishedDate.time
        }
    }

    private suspend fun sendMessage(groupId: Long, message: MessageChain) {
        Bot.instances[0].getGroup(groupId)?.sendMessage(message)
    }

    private suspend fun uploadImage(groupId: Long, resource: ExternalResource): Image? {
        return resource.use { Bot.instances[0].getGroup(groupId)?.uploadImage(it) }
    }

}