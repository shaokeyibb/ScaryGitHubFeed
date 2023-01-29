package io.hikarilan

import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.value

object Config : AutoSavePluginConfig("Config") {

    val postDelaySec: Long by value(10L)

    val postTimeoutSec: Long by value(5 * 60L)

}