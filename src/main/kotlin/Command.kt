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
        context.subject ?: context.sendMessage("该指令只能在群内使用")
        if (Data.feedData[context.subject!!.id]?.contains(githubId) == true) {
            context.sendMessage("已经订阅过 $githubId 的 GitHub Feed")
            return
        }
        ScaryGitHubFeed.async {
            if (checkFeedValid(githubId)) {
                context.subject?.sendMessage("已成功为 $githubId 添加 GitHub Feed 订阅")
                Data.feedData[context.subject!!.id] =
                    (Data.feedData[context.subject!!.id]?.plus(githubId) ?: listOf(githubId)).toMutableList()
            } else {
                context.subject?.sendMessage("无法订阅 $githubId 的 GitHub Feed，因为指定 Feed 解析失败")
            }
        }
    }

    @SubCommand
    suspend fun remove(context: CommandSender, githubId: String) {
        context.subject ?: context.sendMessage("该指令只能在群内使用")
        if (Data.feedData[context.subject!!.id]?.contains(githubId) == false) {
            context.sendMessage("还未订阅过 $githubId 的 GitHub Feed")
            return
        }
        if (Data.feedData[context.subject!!.id]?.contains(githubId) == true) {
            context.subject?.sendMessage("已成功为 $githubId 移除 GitHub Feed 订阅")
            Data.feedData[context.subject!!.id] = Data.feedData[context.subject!!.id]!!.minus(githubId).toMutableList()
        } else {
            context.subject?.sendMessage("无法移除 $githubId 的 GitHub Feed 订阅，因为指定 Feed 未被订阅")
        }
    }

    @SubCommand
    suspend fun list(context: CommandSender) {
        context.subject ?: context.sendMessage("该指令只能在群内使用")
        context.subject?.sendMessage("本群当前订阅的 GitHub Feed 有：\n${Data.feedData[context.subject!!.id]?.joinToString() ?: "无"}")
    }
}