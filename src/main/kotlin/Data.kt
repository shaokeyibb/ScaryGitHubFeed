package io.hikarilan

import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value

object Data : AutoSavePluginData("Data") {

    val feedData: MutableMap<Long, MutableList<String>> by value()

    val lastUpdatedTime: MutableMap<String, Long> by value()

}