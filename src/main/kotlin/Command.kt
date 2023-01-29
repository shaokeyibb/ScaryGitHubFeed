package io.hikarilan

import kotlinx.coroutines.async
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand

object Command : CompositeCommand(
    owner = ScaryGitHubFeed,
    primaryName = "github-feed",
    secondaryNames = arrayOf("😱😱😱", "feed"),
    description = "Subscribe GitHub feed",
    parentPermission = commandPermission
) {

    @SubCommand
    suspend fun add(context: CommandSender, githubId: String) {
        ScaryGitHubFeed.logger.info("Received add command from ${context.name} for $githubId")
        context.subject ?: context.sendMessage("该指令只能在群内使用")
        if (context.bot?.let { Data.feedData[it.id]?.get(context.subject!!.id)?.contains(githubId) } == true) {
            context.sendMessage("已经订阅过 $githubId 的 GitHub Feed")
            return
        }
        if (ScaryGitHubFeed.async { checkFeedValid(githubId) }.await()) {
            ScaryGitHubFeed.logger.info("Subscribed $githubId for group ${context.subject!!.id} by bot ${context.bot!!.id}")
            context.subject?.sendMessage("已成功为 $githubId 添加 GitHub Feed 订阅")
            Data.feedData.getOrPut(context.bot!!.id) { mutableMapOf() }
                .getOrPut(context.subject!!.id) { mutableListOf() }
                .add(githubId)
        } else {
            ScaryGitHubFeed.logger.warning("Failed to subscribe $githubId for group ${context.subject!!.id} by bot ${context.bot!!.id} because of invalid feed")
            context.subject?.sendMessage("无法订阅 $githubId 的 GitHub Feed，因为指定 Feed 解析失败")
        }
    }

    @SubCommand
    suspend fun remove(context: CommandSender, githubId: String) {
        ScaryGitHubFeed.logger.info("Received remove command from ${context.name} for $githubId")
        context.subject ?: context.sendMessage("该指令只能在群内使用")
        if (context.bot?.let { Data.feedData[it.id]?.get(context.subject!!.id)?.contains(githubId) } == false) {
            context.sendMessage("还未订阅过 $githubId 的 GitHub Feed")
            return
        }
        if (context.bot?.let { Data.feedData[it.id]?.get(context.subject!!.id)?.contains(githubId) } == true) {
            ScaryGitHubFeed.logger.info("Unsubscribed $githubId for group ${context.subject!!.id} by bot ${context.bot!!.id}")
            context.subject?.sendMessage("已成功为 $githubId 移除 GitHub Feed 订阅")
            Data.feedData[context.bot!!.id]?.get(context.subject!!.id)?.remove(githubId)
        } else {
            context.subject?.sendMessage("无法移除 $githubId 的 GitHub Feed 订阅，因为指定 Feed 未被订阅")
        }
    }

    @SubCommand
    suspend fun list(context: CommandSender) {
        ScaryGitHubFeed.logger.info("Received list command from ${context.name}")
        context.subject ?: context.sendMessage("该指令只能在群内使用")
        context.subject?.sendMessage(
            "本群当前订阅的 GitHub Feed 有：\n${
                context.bot?.let {
                    Data.feedData[it.id]?.get(
                        context.subject!!.id
                    )
                }?.joinToString() ?: "无"
            }"
        )
    }
}