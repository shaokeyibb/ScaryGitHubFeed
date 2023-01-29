package io.hikarilan

import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value

object Data : AutoSavePluginData("Data") {

    // Map<BotInstance, Map<QQGroup, List<GitHubID>>>
    val feedData: MutableMap<Long, MutableMap<Long, MutableList<String>>> by value()

    // Map<GitHubID, LastUpdatedTime>
    val lastUpdatedTime: MutableMap<String, Long> by value()

}