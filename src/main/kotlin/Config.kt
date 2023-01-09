package io.hikarilan

import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value

object Config : AutoSavePluginData("Config") {

    val postDelaySec: Long by value(10 * 60L)

}