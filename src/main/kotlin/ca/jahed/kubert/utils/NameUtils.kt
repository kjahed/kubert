package ca.jahed.kubert.utils

import kotlin.random.Random

object NameUtils {
    private val charPool: List<Char> = ('a'..'z') + ('A'..'Z')
    private val random = Random(0)

    fun randomize(str:String, length: Int = 5): String {
        return "${str}_${randomString(length)}"
    }

    fun randomString(length: Int): String {
        return (1..length)
            .map { random.nextInt(0, charPool.size) }
            .map(charPool::get)
            .joinToString("")
    }

    fun toLegalK8SName(str: String): String {
        if (str.isEmpty()) return str
        val buffer = StringBuilder()

        if (!Character.isLetterOrDigit(str[0]))
            buffer.append("s")

        for (c in str.toCharArray())
            if (Character.isLetterOrDigit(c)) buffer.append(c) else buffer.append('-')

        if (!Character.isLetterOrDigit(buffer[buffer.length - 1]))
            buffer.deleteCharAt(buffer.length - 1)

        return buffer.toString().toLowerCase()
    }

    fun toLegalEnvVarName(str: String): String {
        return str.replace("-", "_").toUpperCase()
    }
}